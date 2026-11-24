"""Graph publication binds an immutable Neo4j generation to an existing vector release."""
import uuid
from datetime import datetime, timezone

from filelock import FileLock
from qdrant_client import models

from ..indexing import atomic_json


def collect_chunks(vector, manifest):
    query_filter = models.Filter(must=[models.FieldCondition(key='release_id',
                                  match=models.MatchValue(value=manifest['release_id']))])
    chunks, cursor = [], None
    while True:
        rows, cursor = vector.client.scroll(manifest['collection'], scroll_filter=query_filter,
            limit=100, offset=cursor, with_payload=True, with_vectors=False)
        chunks.extend(r.payload for r in rows)
        if len(chunks) > 10000:
            raise ValueError('Public corpus exceeds this release budget')
        if cursor is None:
            break
    if vector.active()['release_id'] != manifest['release_id']:
        raise ValueError('Vector release changed during graph preparation')
    return chunks


def publish_graph(vector, graph, bundle, manifest, *, claims_hash=None):
    data_dir = vector.data_dir
    with FileLock(str(data_dir / '.graph-publish.lock'), timeout=1):
        if vector.active()['release_id'] != manifest['release_id']:
            raise ValueError('Vector release changed before graph publication')
        generation = uuid.uuid4().hex
        graph.write_release(generation, manifest['release_id'], bundle)
        counts = graph.verify_release(generation, manifest['release_id'])
        expected = {k: len(bundle[k]) for k in ('chunks', 'claims', 'entities', 'documents')}
        expected['evidence_links'] = sum(len(c['evidence_chunk_ids']) for c in bundle['claims'])
        if counts != expected:
            raise ValueError('Graph counts or source mappings differ; active graph preserved')
        if vector.active()['release_id'] != manifest['release_id']:
            raise ValueError('Vector release changed; new graph not published')
        receipt = {'schema_version': 1, 'graph_release_id': generation,
            'vector_release_id': manifest['release_id'], 'embedding_signature': manifest['embedding_signature'],
            'collection': manifest['collection'], 'domain': 'display_reference', 'status': 'READY',
            'counts': counts, 'entities': bundle['entities'], 'published_at': datetime.now(timezone.utc).isoformat()}
        if claims_hash:
            receipt['claims_sha256'] = claims_hash
        target = data_dir / 'graph-releases'
        target.mkdir(exist_ok=True)
        atomic_json(target / (generation + '.json'), receipt)
        atomic_json(data_dir / 'graph-active.json', receipt)
        return receipt
