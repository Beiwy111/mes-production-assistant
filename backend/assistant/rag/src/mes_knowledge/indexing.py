"""Single-writer, immutable-generation indexing with atomic publication."""
import json
import os
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path

from filelock import FileLock

from qdrant_client import models
from tokenizers import Tokenizer

from .corpus import chunk_document, sha256_file

COLLECTION = 'mes_public_e5small_614241f6_v1'


def atomic_json(path: Path, value):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temporary.open('w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def withdraw_document(data_dir: Path, document_id: str):
    # Lock the full read-modify-write across processes, not just the final file replace.
    with FileLock(str(data_dir / '.withdraw.lock'), timeout=10):
        active = json.loads((data_dir / 'active.json').read_text(encoding='utf-8'))
        if document_id not in {d['id'] for d in active['documents']}:
            raise ValueError('Document is not in the current release')
        path = data_dir / 'withdrawn.json'
        previous = json.loads(path.read_text(encoding='utf-8')) if path.exists() else []
        if not isinstance(previous, list) or not all(isinstance(v, str) for v in previous):
            raise ValueError('Invalid withdrawal state; refusing to overwrite it')
        atomic_json(path, sorted(set(previous) | {document_id}))


def archive_sources(document: dict, data_dir: Path):
    original = data_dir / 'sources' / document['sha256']
    original.mkdir(parents=True, exist_ok=True)
    refs = {}
    suffix = document['pdf_path'].suffix.lower()
    for source, name, expected, kind in (
        (document['pdf_path'], 'original' + suffix, document['sha256'], 'source_ref'),
        (document['text_path'], 'text-' + document['text_sha256'] + '.txt', document['text_sha256'], 'text_ref'),
    ):
        destination = original / name
        if not destination.exists():
            shutil.copyfile(source, destination)
        if sha256_file(destination) != expected:
            raise ValueError('Archived source does not match registered hash')
        refs[kind] = destination.relative_to(data_dir).as_posix()
    if suffix == '.pdf':
        refs['pdf_ref'] = refs['source_ref']
    return refs


def ingest(documents, client, embedding, data_dir: Path, tokenizer_path: Path, batch_size=8, *, expected_release=None):
    data_dir.mkdir(parents=True, exist_ok=True)
    lock = data_dir / '.ingest.lock'
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        raise RuntimeError('An ingest lock exists. Verify no writer is running before manual recovery.') from None
    os.close(descriptor)
    try:
        if expected_release is not None and json.loads((data_dir/'active.json').read_text('utf-8'))['release_id'] != expected_release:
            raise ValueError('Active release changed during preparation; retry')
        release = uuid.uuid4().hex
        tokenizer = Tokenizer.from_file(str(tokenizer_path))
        chunks = [chunk for document in documents for chunk in chunk_document(document, tokenizer)]
        if not chunks:
            raise ValueError('No chunks to publish')
        if not client.collection_exists(COLLECTION):
            client.create_collection(COLLECTION, vectors_config=models.VectorParams(
                size=embedding.dimension, distance=models.Distance.COSINE))
            for key in ('release_id', 'document_id', 'chunk_id', 'visibility', 'knowledge_domain', 'review_status'):
                client.create_payload_index(COLLECTION, key, models.PayloadSchemaType.KEYWORD, wait=True)
        config = client.get_collection(COLLECTION).config.params.vectors
        if config.size != embedding.dimension or config.distance != models.Distance.COSINE:
            raise ValueError('Existing collection vector configuration mismatch')
        source_metadata = []
        for document in documents:
            refs = archive_sources(document, data_dir)
            source_metadata.append({**{key: document[key] for key in
                                     ('id', 'title', 'sha256', 'text_sha256', 'source_url', 'license', 'page_count')},
                                    'source_type': document.get('source_type', 'PUBLIC_RESEARCH'),
                                    'keywords': document.get('keywords', []), **refs})
        for start in range(0, len(chunks), batch_size):
            batch = chunks[start:start + batch_size]
            # Metadata aids bilingual recall; evidence text remains the exact source substring.
            texts = [c['title'] + '\n' + ', '.join(c['keywords']) + '\n' + c['text'] for c in batch]
            vectors = embedding.documents(texts)
            points = [models.PointStruct(id=str(uuid.uuid5(uuid.NAMESPACE_URL, release + ':' + c['chunk_id'])),
                                          vector=vector, payload={**c, 'release_id': release})
                      for c, vector in zip(batch, vectors, strict=True)]
            client.upsert(COLLECTION, points, wait=True)
            print(f'Indexed {min(start + batch_size, len(chunks))}/{len(chunks)} chunks', flush=True)
        generation_filter = models.Filter(must=[models.FieldCondition(
            key='release_id', match=models.MatchValue(value=release))])
        count = client.count(COLLECTION, count_filter=generation_filter, exact=True).count
        if count != len(chunks):
            raise ValueError('Point count mismatch; active release was not changed')
        release_dir = data_dir / 'releases'
        release_dir.mkdir(exist_ok=True)
        manifest = {'release_id': release, 'collection': COLLECTION, 'embedding_signature': embedding.signature,
                    'documents': source_metadata, 'point_count': count,
                    'published_at': datetime.now(timezone.utc).isoformat(), 'scope': 'public-reference-only'}
        atomic_json(release_dir / f'{release}.json', manifest)
        atomic_json(data_dir / 'active.json', manifest)
        return manifest
    finally:
        lock.unlink(missing_ok=True)
