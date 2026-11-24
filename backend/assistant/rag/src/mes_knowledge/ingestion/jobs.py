"""Durable private upload queue. Publication requires successful vector and graph commits."""
import json
import logging
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone

from filelock import FileLock, Timeout

from .evidence import extract_entities, extract_relations, split_chunks
from .parsing import extract, validate_upload

STAGES = ('upload', 'parse', 'chunk', 'embedding', 'vector', 'entities', 'relations', 'graph', 'complete')
LOGGER = logging.getLogger(__name__)


class Conflict(ValueError):
    pass


def now():
    return datetime.now(timezone.utc).isoformat()


def initial(filename, size, job_id):
    created = now()
    return {'id': job_id, 'filename': filename, 'size': size, 'status': 'queued', 'error': None,
            'createdAt': created, 'updatedAt': created, 'counts': {'files': 1, 'chunks': 0, 'entities': 0, 'relations': 0},
            'stages': [{'key': key, 'status': 'success' if key == 'upload' else 'pending',
                        'durationMs': 0, 'error': None} for key in STAGES]}


class IngestionJobs:
    def __init__(self, directory, embedding, indexes):
        directory.mkdir(parents=True, exist_ok=True)
        self.path = directory / 'uploads.sqlite3'
        self.embedding = embedding
        self.indexes = indexes
        self.stopping = threading.Event()
        self.wakeup = threading.Event()
        self.thread = None
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, status TEXT NOT NULL,'
                       ' state TEXT NOT NULL, details TEXT NOT NULL, source BLOB NOT NULL,'
                       ' vector_dirty INTEGER NOT NULL DEFAULT 0, graph_dirty INTEGER NOT NULL DEFAULT 0)')
            db.execute('CREATE TABLE IF NOT EXISTS cleanup (id TEXT PRIMARY KEY, vector_dirty INTEGER NOT NULL,'
                       ' graph_dirty INTEGER NOT NULL, retry_after REAL NOT NULL DEFAULT 0)')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def save(db, state, details=None):
        state['updatedAt'] = now()
        updated = db.execute('UPDATE jobs SET status=?, state=? WHERE id=?',
                             (state['status'], json.dumps(state, ensure_ascii=False), state['id'])).rowcount
        if details is not None:
            db.execute('UPDATE jobs SET details=? WHERE id=?', (json.dumps(details, ensure_ascii=False), state['id']))
        return updated == 1

    @staticmethod
    def record(db, job_id, columns):
        # All callers supply fixed, internal column lists.
        row = db.execute(f'SELECT {columns} FROM jobs WHERE id=?', (job_id,)).fetchone()
        if row is None:
            raise LookupError('资料不存在')
        return row

    def enqueue(self, filename, data):
        validate_upload(filename, data)
        state = initial(filename, len(data), uuid.uuid4().hex)
        started = time.monotonic()
        with self.connect() as db:
            db.execute("INSERT INTO jobs(id,status,state,details,source) VALUES(?,? ,?,'{}',?)",
                       (state['id'], 'queued', json.dumps(state, ensure_ascii=False), data))
            state['stages'][0]['durationMs'] = round((time.monotonic() - started) * 1000)
            self.save(db, state)
        self.wakeup.set()
        return state

    def list(self):
        with self.connect() as db:
            return [json.loads(row[0]) for row in db.execute('SELECT state FROM jobs ORDER BY rowid DESC')]

    def get(self, job_id):
        with self.connect() as db:
            row = self.record(db, job_id, 'state,details')
            return {**json.loads(row['state']), **json.loads(row['details'])}

    def source(self, job_id):
        with self.connect() as db:
            row = self.record(db, job_id, 'source,state')
            return json.loads(row['state'])['filename'], row['source']

    @staticmethod
    def reset(db, state):
        next_state = initial(state['filename'], state['size'], state['id'])
        next_state['createdAt'] = state['createdAt']
        next_state['stages'][0] = state['stages'][0]
        IngestionJobs.save(db, next_state, {})
        return next_state

    def retry(self, job_id):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            state = json.loads(self.record(db, job_id, 'state')[0])
            if state['status'] not in ('failed', 'completed'):
                raise Conflict('该任务正在队列中或已开始处理')
            state = self.reset(db, state)
        self.wakeup.set()
        return state

    def delete(self, job_id, published=False):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self.record(db, job_id, 'state,vector_dirty,graph_dirty')
            state = json.loads(row['state'])
            if (state['status'] == 'completed') != published:
                raise Conflict('文档状态已变化，请刷新并核对要删除的资料')
            if published or row['graph_dirty'] or row['vector_dirty']:
                db.execute('INSERT INTO cleanup(id,vector_dirty,graph_dirty) VALUES (?,?,?)',
                           (job_id, published or row['vector_dirty'], published or row['graph_dirty']))
            db.execute('DELETE FROM jobs WHERE id=?', (job_id,))
        self.wakeup.set()
        return {'deleted': True}

    def cleanup_next(self):
        with self.connect() as db:
            row = db.execute('SELECT * FROM cleanup WHERE retry_after<=? ORDER BY retry_after LIMIT 1',
                             (time.time(),)).fetchone()
        if not row:
            return
        remaining = dict(row)
        for key, remove in (('vector_dirty', self.indexes.remove_vectors), ('graph_dirty', self.indexes.remove_graph)):
            if remaining[key]:
                try:
                    remove(row['id'])
                    remaining[key] = 0
                except Exception:
                    LOGGER.warning('Private index cleanup deferred for %s', key)
        with self.connect() as db:
            if remaining['graph_dirty'] or remaining['vector_dirty']:
                db.execute('UPDATE cleanup SET vector_dirty=?,graph_dirty=?,retry_after=? WHERE id=?',
                           (remaining['vector_dirty'], remaining['graph_dirty'], time.time()+30, row['id']))
            else:
                db.execute('DELETE FROM cleanup WHERE id=?', (row['id'],))

    def search(self, query, job_id, limit):
        with self.connect() as db:
            if job_id:
                status = self.record(db, job_id, 'status')[0]
                ids = [job_id] if status == 'completed' else []
            else:
                ids = [row[0] for row in db.execute("SELECT id FROM jobs WHERE status='completed'")]
        result = self.indexes.search(self.embedding, query, ids, limit)
        with self.connect() as db:
            published = {row[0] for row in db.execute("SELECT id FROM jobs WHERE status='completed'")}
        return {key: [item for item in values if item['jobId'] in published] for key, values in result.items()}

    def start(self):
        self.thread = threading.Thread(target=self.worker, name='private-knowledge-ingestion', daemon=True)
        self.thread.start()

    def close(self):
        self.stopping.set()
        self.wakeup.set()
        if self.thread:
            self.thread.join()
        self.indexes.close()

    def worker(self):
        lock = FileLock(str(self.path.with_suffix('.worker.lock')), timeout=1)
        while not self.stopping.is_set():
            try:
                with lock:
                    with self.connect() as db:
                        db.execute('BEGIN IMMEDIATE')
                        for row in db.execute("SELECT state FROM jobs WHERE status='running'").fetchall():
                            self.reset(db, json.loads(row[0]))
                    while not self.stopping.is_set():
                        self.cleanup_next()
                        if not self.process_next():
                            self.wakeup.wait(1)
                            self.wakeup.clear()
            except Timeout:
                continue
            except Exception:
                LOGGER.exception('Private ingestion worker interrupted')
                self.stopping.wait(1)

    def process_next(self):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT state,source FROM jobs WHERE status='queued' ORDER BY rowid LIMIT 1").fetchone()
            if not row:
                return False
            state, source = json.loads(row['state']), row['source']
            state['status'] = 'running'
            self.save(db, state)
        job_id, filename = state['id'], state['filename']
        details = {}
        for stage in state['stages'][1:]:
            key = stage['key']
            stage.update(status='running', startedAt=now())
            start = time.monotonic()
            with self.connect() as db:
                if not self.save(db, state):
                    # The administrator deleted this upload while its preceding stage ran.
                    return True
                if key in ('vector', 'graph'):
                    db.execute(f'UPDATE jobs SET {key}_dirty=1 WHERE id=?', (job_id,))
            try:
                if key == 'parse':
                    sections, details['metadata'] = extract(filename, source)
                elif key == 'chunk':
                    chunks = split_chunks(job_id, sections, self.embedding.counter)
                    state['counts']['chunks'] = len(chunks)
                    details['chunks'] = chunks
                elif key == 'embedding':
                    vectors = []
                    for start_index in range(0, len(chunks), 8):
                        vectors.extend(self.embedding.documents([c['text'] for c in chunks[start_index:start_index+8]]))
                elif key == 'vector':
                    self.indexes.vectorize(job_id, filename, chunks, vectors, self.embedding.dimension)
                elif key == 'entities':
                    entities, evidence = extract_entities(job_id, chunks)
                    state['counts']['entities'] = len(entities)
                    details['entities'] = entities
                elif key == 'relations':
                    relations = extract_relations(job_id, evidence)
                    state['counts']['relations'] = len(relations)
                    details['relations'] = relations
                elif key == 'graph':
                    self.indexes.write_graph(job_id, filename, chunks, entities, relations)
                else:
                    state['status'] = 'completed'
                stage['status'] = 'success'
            except Exception as exc:
                if isinstance(exc, (ValueError, RuntimeError)) and type(exc).__module__.startswith(
                        ('builtins', 'mes_knowledge')):
                    error = str(exc)[:240]
                else:
                    error = f'服务处理失败（{type(exc).__name__}），请检查对应服务后重试'
                state.update(status='failed', error=error)
                stage.update(status='failed', error=error)
            stage['durationMs'] = round((time.monotonic() - start) * 1000)
            stage['counts'] = state['counts'].copy()
            with self.connect() as db:
                self.save(db, state, details if key in ('parse', 'chunk', 'entities', 'relations') else None)
            if state['status'] == 'failed':
                break
        return True
