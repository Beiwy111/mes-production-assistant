"""Incrementally reconcile the dedicated vector collection, graph and local ID manifest."""
import json
import os
import secrets

from filelock import FileLock
from qdrant_client import models

from ..indexing import atomic_json
from ..hybrid import terms
from .source import digest, opaque, record

COLLECTION = 'mes_private_history_e5small_v1'
MANIFEST = 'active.json'


def term_ids(key, text):
    return {digest(key, 'term', term)[:24] for term in set(terms(text))}


def private_key(root, create=False):
    path = root / 'identity.key'
    if create and not path.exists():
        if (root / MANIFEST).exists():
            raise RuntimeError('Restore the private history identity key')
        with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'wb') as stream:
            stream.write(secrets.token_bytes(32))
    key = path.read_bytes()
    if len(key) != 32:
        raise ValueError('Invalid history identity key')
    return key


def load_manifest(root, embedding):
    manifest = json.loads((root / MANIFEST).read_text(encoding='utf-8'))
    if manifest['embedding'] != embedding.signature or manifest['schema'] != 1:
        raise ValueError('History index version mismatch')
    return manifest


def chunks(r, embedding):
    body = r.text[len(r.title):].strip() or r.title
    start = 0
    while start < len(body):
        end = min(len(body), start + 340)
        while len(embedding.counter.encode('passage: ' + r.title + '\n' + body[start:end]).ids) > 512:
            size = (end - start) // 2
            if not size:
                raise ValueError('History title exceeds the embedding budget')
            end = start + size
        yield r.title + '\n' + body[start:end]
        if end == len(body):
            break
        start = end - min(40, (end - start) // 4)


def _inventory(client):
    found = {}
    offset = None
    while True:
        points, offset = client.scroll(COLLECTION, offset=offset, limit=256, with_payload=True,
                                       with_vectors=False)
        found.update((str(p.id), p.payload) for p in points)
        if offset is None:
            return found


def synchronize(root, source, client, embedding, graph):
    root.mkdir(parents=True, exist_ok=True)
    with FileLock(str(root / '.sync.lock'), timeout=0):
        key = private_key(root, create=True)
        records = {}
        for kind, rows in source.fetch().items():
            for row in rows:
                r = record(key, kind, row)
                if r.key in records:
                    raise ValueError('Ambiguous historical source identity')
                records[r.key] = r

        if not client.collection_exists(COLLECTION):
            client.create_collection(COLLECTION, vectors_config=models.VectorParams(
                size=embedding.dimension, distance=models.Distance.COSINE))
            client.create_payload_index(COLLECTION, 'partition', models.PayloadSchemaType.KEYWORD, wait=True)
        vectors = client.get_collection(COLLECTION).config.params.vectors
        if vectors.size != embedding.dimension or vectors.distance != models.Distance.COSINE:
            raise ValueError('Private vector collection configuration mismatch')
        previous = _inventory(client)
        wanted, manifest_rows, batch = set(), {}, []
        added = 0

        def flush():
            if batch:
                encoded = embedding.documents([text for _, _, text in batch])
                client.upsert(COLLECTION, points=[models.PointStruct(id=pid, payload=payload, vector=vector)
                    for (pid, payload, _), vector in zip(batch, encoded, strict=True)], wait=True)
                batch.clear()

        for r in records.values():
            points = []
            for i, text in enumerate(chunks(r, embedding)):
                pid = opaque(key, 'chunk', embedding.signature, r.key, r.hash, i, text)
                payload = {'record': r.key, 'digest': r.hash, 'partition': opaque(key, 'kind', r.kind)}
                wanted.add(pid)
                points.append(pid)
                if previous.get(pid) != payload:
                    batch.append((pid, payload, text))
                    added += 1
                    if len(batch) == 8:
                        flush()
            manifest_rows[r.key] = {'pk': r.pk, 'kind': r.kind, 'hash': r.hash, 'points': points,
                                    'terms': sorted(term_ids(key, r.text))}
        flush()
        generation = digest(key, 'generation', embedding.signature, manifest_rows)
        statistics = graph.reconcile(records, generation)
        atomic_json(root / MANIFEST, {'schema': 1, 'generation': generation, 'embedding': embedding.signature,
                                     'records': manifest_rows})
        # Readers accept only points and graph records present in the same active manifest.
        # A failed cleanup is retried against the actual remote inventory, even after publication.
        removed = sorted(previous.keys() - wanted)
        for i in range(0, len(removed), 128):
            client.delete(COLLECTION, points_selector=models.PointIdsList(points=removed[i:i+128]), wait=True)
        return {'records': len(records), 'chunks': len(wanted), 'embedded': added, 'deleted_chunks': len(removed),
                'generation': generation, **statistics}
