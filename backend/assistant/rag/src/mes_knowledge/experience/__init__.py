"""Live-validated private historical case retrieval for the internal admin service."""
import threading
import math
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

from qdrant_client import models

from ..retrieval import Busy, Unavailable
from .graph import ExperienceGraph
from .index import COLLECTION, load_manifest, private_key, term_ids
from .source import MySQLHistory, RELATIONS, TABLES, opaque, record


def _path(row, live):
    a, b = live.get(row['source']), live.get(row['target'])
    if not a or not b or a.hash != row['source_hash'] or b.hash != row['target_hash']:
        return None
    first = a.entities.get(row['first'])
    last = b.entities.get(row['last'])
    if not first or not last:
        return None
    typ = first['type']
    if row['shape'] == 'shared' and a.key != b.key and row['first'] == row['last'] and typ != 'ACTION':
        relation, entity_type, label = 'SHARED_' + typ, typ, first['label']
    elif row['shape'] == 'record' and a.key == b.key and typ != 'ACTION' and last['type'] == 'ACTION':
        relation = RELATIONS[typ] + '→RECORDED_ACTION'
        entity_type, label = typ + '→ACTION', first['label'] + ' → ' + last['label']
    else:
        return None
    return {'source_ref': a.ref, 'target_ref': b.ref, 'relation': relation, 'entity_type': entity_type,
            'entity_label': label, 'hops': 2}


class ExperienceSearch:
    def __init__(self, root, client, embedding, source, graph):
        self.root, self.client, self.embedding = root, client, embedding
        self.source, self.graph = source, graph
        self.slot = threading.BoundedSemaphore(2)

    @classmethod
    def from_store(cls, store, graph_store=None):
        return cls(store.data_dir / 'private-experience', store.client, store.embedding, MySQLHistory(),
                   ExperienceGraph(graph_store) if graph_store is not None else None)

    def query(self, query: str, top_k: int = 5, kinds=None):
        allowed = sorted(TABLES if kinds is None else set(kinds))
        if (not isinstance(query, str) or not query.strip() or len(query) > 2000 or type(top_k) is not int
                or not 1 <= top_k <= 8 or not allowed or set(allowed) - TABLES.keys()):
            raise ValueError('Invalid history lookup')
        if not self.slot.acquire(blocking=False):
            raise Busy('Historical search at capacity')
        try:
            return self._search(query.strip(), top_k, allowed)
        except Exception as exc:
            raise Unavailable('Private history unavailable') from exc
        finally:
            self.slot.release()

    def _vector(self, query, top_k, kinds, secret, rows):
        points = self.client.query_points(COLLECTION, query=self.embedding.query(query),
            query_filter=models.Filter(must=[models.FieldCondition(key='partition',
                match=models.MatchAny(any=[opaque(secret, 'kind', kind) for kind in kinds]))]),
            limit=top_k * 8, with_payload=True, with_vectors=False).points
        scores = {}
        for point in points:
            payload = point.payload or {}
            key = payload.get('record')
            row = rows.get(key)
            if (row and row['kind'] in kinds and row['hash'] == payload.get('digest')
                    and str(point.id) in row['points']):
                scores.setdefault(key, float(point.score))
        return scores

    def _graph(self, query, top_k, kinds, secret, manifest):
        if self.graph is None:
            return [], [], 'UNAVAILABLE_OR_STALE'
        words = term_ids(secret, query)
        matches = {k: words.intersection(r['terms']) for k,r in manifest['records'].items() if r['kind'] in kinds}
        df = Counter(word for found in matches.values() for word in found)
        scores = {k: sum(math.log1p(len(matches) / df[word]) for word in sorted(found))
                  for k, found in matches.items() if found}
        seeds = sorted(scores, key=lambda k: (-scores[k], k))[:top_k]
        try:
            return seeds, self.graph.paths(manifest['generation'], seeds, kinds, top_k * 8), 'NO_MATCHES'
        except Exception:
            return [], [], 'UNAVAILABLE_OR_STALE'

    def _search(self, query, top_k, kinds):
        secret = private_key(self.root)
        manifest = load_manifest(self.root, self.embedding)
        rows = manifest['records']
        with ThreadPoolExecutor(max_workers=2) as pool:
            vector_task = pool.submit(self._vector, query, top_k, kinds, secret, rows)
            graph_task = pool.submit(self._graph, query, top_k, kinds, secret, manifest)
            seeds, graph_rows, graph_status = graph_task.result()
            try:
                scores = vector_task.result()
                vector_status = 'MATCHES' if scores else 'NO_MATCHES'
            except Exception:
                if graph_status == 'UNAVAILABLE_OR_STALE':
                    raise
                scores, vector_status = {}, 'UNAVAILABLE'

        candidates = set(scores)
        candidates.update(seeds)
        candidates.update(r['target'] for r in graph_rows if r['source'] in seeds)
        ids = {kind: [rows[k]['pk'] for k in candidates if k in rows and rows[k]['kind'] == kind]
               for kind in kinds}
        live = {}
        for kind, results in self.source.fetch(ids).items():
            for data in results:
                r = record(secret, kind, data)
                if r.key in candidates and r.hash == rows[r.key]['hash']:
                    live[r.key] = r
        stale_record_count = sum(len(values) for values in ids.values()) - len(live)

        if vector_status == 'MATCHES' and not scores.keys() & live.keys():
            vector_status = 'NO_MATCHES'
        paths, neighbors = [], {}
        graph_order = []
        for row in graph_rows:
            if row['source'] not in seeds:
                continue
            path = _path(row, live)
            if path:
                paths.append(path)
                graph_order.append(row['source'])
                if row['target'] != row['source']:
                    neighbors.setdefault(row['source'], []).append(row['target'])
        selected = {}
        graph_keys = sorted(set(graph_order), key=seeds.index)
        vector_keys = list(scores)
        for i in range(max(len(vector_keys), len(graph_keys))):
            if i < len(vector_keys) and vector_keys[i] in live:
                key = vector_keys[i]
                selected.setdefault(key, ('vector', scores[key]))
            if i < len(graph_keys):
                key = graph_keys[i]
                selected.setdefault(key, ('graph', scores.get(key)))
                for other in neighbors.get(key, [])[:1]:
                    selected.setdefault(other, ('graph', scores.get(other)))
        selected = dict(list(selected.items())[:top_k])
        refs = {live[k].ref for k in selected}
        paths = [p for p in paths if p['source_ref'] in refs and p['target_ref'] in refs][:top_k * 2]
        supported = {ref for p in paths for ref in (p['source_ref'], p['target_ref'])}
        hits = []
        for key, (source, score) in selected.items():
            r = live[key]
            if source == 'graph' and r.ref not in supported:
                if key not in scores:
                    continue
                source = 'vector'
            hits.append({'kind': r.kind, 'record_id': r.pk, 'record_no': r.number, 'evidence_ref': r.ref,
                         'title': r.title, 'text': r.text, 'score': score, 'retrieval_source': source,
                         'source_table': TABLES[r.kind], 'record_hash': r.hash})
        if load_manifest(self.root, self.embedding)['generation'] != manifest['generation']:
            raise ValueError('History publication changed')
        return {'status': 'MATCHES' if hits else 'NO_MATCHES', 'hits': hits, 'graph_paths': paths,
                'graph_status': 'MATCHES' if paths else graph_status, 'vector_status': vector_status,
                'stale_record_count': stale_record_count,
                'mode_used': 'graph_assisted' if paths else 'vector'}
