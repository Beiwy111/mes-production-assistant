import json

import pytest
from qdrant_client import QdrantClient
from qdrant_client import models

from mes_knowledge.retrieval import KnowledgeStore, Unavailable


class FakeEmbedding:
    dimension = 3
    signature = 'test-model-revision'

    def query(self, text):
        return [1.0, 0.0, 0.0]


def make_store(tmp_path):
    client = QdrantClient(':memory:')
    client.create_collection('test', vectors_config=models.VectorParams(size=3, distance=models.Distance.COSINE))
    docs = [{'id': 'P01', 'title': 'Paper', 'sha256': 'hash'}]
    manifest = {'release_id': 'a' * 32, 'collection': 'test', 'embedding_signature': FakeEmbedding.signature,
                'documents': docs, 'point_count': 1}
    (tmp_path / 'active.json').write_text(json.dumps(manifest), encoding='utf-8')
    payload = dict(release_id='a' * 32, document_id='P01', document_version='hash', chunk_id='c1',
                   visibility='PUBLIC', knowledge_domain='display_reference', review_status='REFERENCE_ALLOWED',
                   text='Evidence', title='Paper', page_start=1, page_end=1,
                   source_url='https://example.org/paper', license='CC BY 4.0', authority_type='PUBLIC_RESEARCH')
    client.upsert('test', [models.PointStruct(id=1, vector=[1.0, 0.0, 0.0], payload=payload),
                           models.PointStruct(id=2, vector=[1.0, 0.0, 0.0], payload={**payload, 'visibility': 'PRIVATE'}),
                           models.PointStruct(id=3, vector=[1.0, 0.0, 0.0], payload={**payload, 'release_id': 'b' * 32})])
    return KnowledgeStore(tmp_path, client, FakeEmbedding()), client


def test_retrieval_filters_private_and_wrong_release_and_returns_source(tmp_path):
    store, _ = make_store(tmp_path)
    result = store.query('mura', 5)
    assert len(result['hits']) == 1
    hit = result['hits'][0]
    assert hit['document_id'] == 'P01' and hit['page_start'] == 1
    assert store.evidence('a' * 32, 'c1')['text'] == 'Evidence'
    with pytest.raises(LookupError):
        store.evidence('b' * 32, 'c1')


def test_withdrawal_is_immediate_for_retrieval_and_evidence(tmp_path):
    store, _ = make_store(tmp_path)
    (tmp_path / 'withdrawn.json').write_text(json.dumps(['P01']))
    assert store.query('mura', 5)['hits'] == []
    with pytest.raises(LookupError):
        store.evidence('a' * 32, 'c1')


def test_hybrid_uses_same_release_visibility_and_withdrawal_boundary(tmp_path):
    store, _ = make_store(tmp_path)
    result = store.hybrid_query('Evidence', 5)
    assert result['retrieval_method'] == 'bm25_vector_rrf'
    assert len(result['hits']) == 1
    assert result['hits'][0]['evidence_ref'] == 'a'*32 + ':P01:c1'
    (tmp_path / 'withdrawn.json').write_text(json.dumps(['P01']))
    assert store.hybrid_query('Evidence', 5)['hits'] == []


def test_model_mismatch_and_corrupt_manifest_fail_closed(tmp_path):
    store, _ = make_store(tmp_path)
    manifest = json.loads((tmp_path / 'active.json').read_text())
    manifest['embedding_signature'] = 'different-model-same-dimension'
    (tmp_path / 'active.json').write_text(json.dumps(manifest))
    with pytest.raises(Unavailable):
        store.query('mura', 5)


def test_revocation_during_embedding_is_applied_before_returning_results(tmp_path):
    store, _ = make_store(tmp_path)
    def revoke(query):
        (tmp_path / 'withdrawn.json').write_text(json.dumps(['P01']))
        return [1.0, 0.0, 0.0]
    store.embedding.query = revoke
    assert store.query('mura', 5)['hits'] == []


def test_release_switch_during_embedding_does_not_return_stale_references(tmp_path):
    store, _ = make_store(tmp_path)
    def switch(query):
        path = tmp_path / 'active.json'
        manifest = json.loads(path.read_text())
        manifest['release_id'] = 'b' * 32
        path.write_text(json.dumps(manifest))
        return [1.0, 0.0, 0.0]
    store.embedding.query = switch
    with pytest.raises(Unavailable):
        store.query('mura', 5)


def test_evidence_fails_if_release_changes_while_qdrant_is_reading(tmp_path, monkeypatch):
    store, client = make_store(tmp_path)
    original = client.scroll
    def change_release(*args, **kwargs):
        result = original(*args, **kwargs)
        path = tmp_path / 'active.json'
        manifest = json.loads(path.read_text())
        manifest['release_id'] = 'b' * 32
        path.write_text(json.dumps(manifest))
        return result
    monkeypatch.setattr(client, 'scroll', change_release)
    with pytest.raises(LookupError):
        store.evidence('a' * 32, 'c1')
    (tmp_path / 'active.json').write_text('broken-json')
    with pytest.raises(Unavailable):
        store.query('mura', 5)
