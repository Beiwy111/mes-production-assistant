"""Release-filtered public evidence retrieval. No business data or caller-defined ACLs."""
import json
import re
import threading
from pathlib import Path

from qdrant_client import models
from .hybrid import bm25_rank, reciprocal_rank_fusion


class Unavailable(RuntimeError):
    pass


class Busy(RuntimeError):
    pass


class KnowledgeStore:
    def __init__(self, data_dir: Path, client, embedding):
        self.data_dir = data_dir
        self.client = client
        self.embedding = embedding
        self._query_slot = threading.BoundedSemaphore(1)

    def active(self):
        try:
            manifest = json.loads((self.data_dir / 'active.json').read_text(encoding='utf-8'))
            if manifest['embedding_signature'] != self.embedding.signature:
                raise ValueError('Embedding signature mismatch')
            if not re.fullmatch(r'[a-f0-9]{32}', manifest['release_id']):
                raise ValueError('Invalid release identifier')
            if not manifest['documents'] or not isinstance(manifest['point_count'], int):
                raise ValueError('Invalid release manifest')
            return manifest
        except (OSError, ValueError, KeyError, TypeError) as exc:
            raise Unavailable('Knowledge release is unavailable') from exc

    def withdrawn(self) -> set[str]:
        path = self.data_dir / 'withdrawn.json'
        if not path.exists():
            return set()
        try:
            value = json.loads(path.read_text(encoding='utf-8'))
            if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
                raise ValueError('Invalid withdrawal list')
            return set(value)
        except (OSError, ValueError, TypeError) as exc:
            raise Unavailable('Withdrawal state is unavailable') from exc

    def _filter(self, manifest, chunk_id=None):
        allowed = [d['id'] for d in manifest['documents'] if d['id'] not in self.withdrawn()]
        must = [models.FieldCondition(key=k, match=models.MatchValue(value=v)) for k, v in {
            'release_id': manifest['release_id'], 'visibility': 'PUBLIC',
            'knowledge_domain': 'display_reference', 'review_status': 'REFERENCE_ALLOWED',
        }.items()]
        must.append(models.FieldCondition(key='document_id', match=models.MatchAny(any=allowed)))
        if chunk_id:
            must.append(models.FieldCondition(key='chunk_id', match=models.MatchValue(value=chunk_id)))
        return models.Filter(must=must)

    @staticmethod
    def _hit(payload, release_id, score=None):
        keys = ('document_id', 'document_version', 'chunk_id', 'title', 'text', 'page_start',
                'page_end', 'source_url', 'license', 'authority_type')
        hit = {key: payload[key] for key in keys}
        hit.update(release_id=release_id,
                   evidence_ref=f"{release_id}:{payload['document_id']}:{payload['chunk_id']}",
                   evidence_path=f"/internal/knowledge/evidence/{release_id}/{payload['chunk_id']}")
        if score is not None:
            hit['score'] = float(score)
        return hit

    def query(self, query: str, top_k: int, hybrid: bool = False):
        if not self._query_slot.acquire(blocking=False):
            raise Busy('CPU retrieval is busy')
        try:
            manifest = self.active()
            vector = self.embedding.query(query)
            points = self.client.query_points(collection_name=manifest['collection'], query=vector,
                                              query_filter=self._filter(manifest), limit=min(24, top_k * 3),
                                              with_payload=True, with_vectors=False).points
            if hybrid:
                # Page through the same ACL/release-filtered corpus. Fail explicitly at the
                # CPU budget rather than silently indexing only the first scroll page.
                documents, offset = [], None
                while True:
                    page, offset = self.client.scroll(manifest['collection'], scroll_filter=self._filter(manifest),
                        offset=offset, limit=256, with_payload=True, with_vectors=False)
                    documents.extend(point.payload for point in page)
                    if len(documents) > 10000:
                        raise Unavailable('Hybrid corpus exceeds local CPU budget')
                    if offset is None:
                        break
                payloads = {doc['chunk_id']: doc for doc in documents}
                vector_ids = [point.payload['chunk_id'] for point in points]
                lexical_ids = [identifier for identifier, _ in bm25_rank(query, documents, max(24, top_k*3))]
                ranked = reciprocal_rank_fusion(vector_ids, lexical_ids, max(24, top_k*3))
                from types import SimpleNamespace
                points = [SimpleNamespace(payload=payloads[identifier], score=None)
                          for rank, identifier in enumerate(ranked, 1) if identifier in payloads]
            withdrawn = self.withdrawn()  # Recheck revocation after the potentially slow embedding/query.
            if self.active()['release_id'] != manifest['release_id']:
                raise Unavailable('Release changed while retrieving; retry against the active release')
            hits = []
            seen = set()
            for point in points:
                payload = point.payload
                if payload['document_id'] in withdrawn:
                    continue
                identity = payload['document_id'], payload['chunk_id']
                if identity not in seen:
                    hits.append(self._hit(payload, manifest['release_id'], point.score))
                    seen.add(identity)
                if len(hits) == top_k:
                    break
            return {'release_id': manifest['release_id'], 'knowledge_domain': 'display_reference',
                    'status': 'MATCHES' if hits else 'NO_MATCHES', 'hits': hits,
                    'retrieval_method': 'bm25_vector_rrf' if hybrid else 'vector',
                    'limitations': ['Research references are not factory acceptance standards.',
                                    'Similarity scores are not calibrated correctness probabilities.',
                                    'PDF text extraction may omit layout, figures or formula meaning.']}
        except (Unavailable, Busy):
            raise
        except Exception as exc:
            raise Unavailable('Retrieval backend unavailable') from exc
        finally:
            self._query_slot.release()

    def hybrid_query(self, query: str, top_k: int):
        return self.query(query, top_k, hybrid=True)

    def evidence(self, release_id: str, chunk_id: str):
        manifest = self.active()
        if release_id != manifest['release_id']:
            raise LookupError('Only the active public release can be served')
        try:
            points, _ = self.client.scroll(manifest['collection'], scroll_filter=self._filter(manifest, chunk_id),
                                           limit=1, with_payload=True, with_vectors=False)
            if not points or points[0].payload['document_id'] in self.withdrawn():
                raise LookupError('Evidence not found or withdrawn')
            if self.active()['release_id'] != release_id:
                raise LookupError('Evidence release changed during retrieval')
            return self._hit(points[0].payload, release_id)
        except (LookupError, Unavailable):
            raise
        except Exception as exc:
            raise Unavailable('Evidence backend unavailable') from exc
