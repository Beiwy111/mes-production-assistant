import json

from fastapi.testclient import TestClient
from qdrant_client import models

from mes_knowledge.api import create_app
from test_retrieval import make_store


URL = '/internal/knowledge/catalog'
KEY = 'catalog-internal-test-credential'
HEADERS = {'Authorization': 'Bearer ' + KEY}
RELEASE = 'a' * 32
GRAPH = 'd' * 32


class Graph:
    def catalog_claims(self, generation, vector_release, allowed_documents):
        assert generation == GRAPH and vector_release == RELEASE
        return [{
            'claim_id': 'CLAIM_1', 'statement': 'Verified claim', 'relation': 'DETECTS', 'quote': 'Evidence',
            'document_id': 'P01', 'document_version': 'hash', 'page': 1, 'subject_id': 'METHOD',
            'subject_name': 'Method', 'object_id': 'DEFECT', 'object_name': 'Defect',
            'evidence_chunk_ids': ['c2', 'c1'],
        }] if 'P01' in allowed_documents else []


def setup_catalog(folder):
    store, qdrant = make_store(folder)
    payload = qdrant.retrieve('test', [1])[0].payload
    qdrant.upsert('test', [
        models.PointStruct(id=i, vector=[1., 0., 0.], payload={**payload, 'chunk_id': f'c{i}', **changes})
        for i, changes in [
            (4, {'review_status': 'DRAFT'}), (5, {'document_version': 'old'}),
            (6, {'document_id': 'NOT_PUBLISHED'}), (7, {'authority_type': 'PRIVATE'}),
            (8, {'chunk_id': 'c2', 'char_start': 5}),
        ]
    ])
    (folder / 'graph-active.json').write_text(json.dumps({
        'graph_release_id': GRAPH, 'vector_release_id': RELEASE, 'domain': 'display_reference',
        'status': 'READY', 'embedding_signature': store.embedding.signature, 'counts': {'claims': 1},
    }))
    graph = Graph()
    return store, graph, TestClient(create_app(store, KEY, graph))


def test_catalog_auth_validation_and_pin(tmp_path):
    _, _, client = setup_catalog(tmp_path)
    assert client.get(URL).status_code == 401
    assert client.get(URL, headers={'Authorization': 'Bearer experience-key'}).status_code == 401
    for params in ({'offset': -1}, {'limit': 201}, {'limit': 0}, {'release_id': '../active.json'}):
        assert client.get(URL, headers=HEADERS, params=params).status_code == 422
    assert client.get(URL, headers=HEADERS, params={'release_id': 'f' * 32}).status_code == 409


def test_catalog_every_published_chunk_and_graph_support_over_pages(tmp_path):
    _, _, client = setup_catalog(tmp_path)
    first = client.get(URL, params={'limit': 1}, headers=HEADERS).json()
    assert first['release_id'] == RELEASE and first['graph_release_id'] == GRAPH
    assert first['counts'] == {'documents': 1, 'chunks': 2, 'entities': 2, 'claims': 1, 'evidence_links': 2}
    assert first['graph_status'] == 'READY' and first['next_offset'] == 1
    second = client.get(URL, headers=HEADERS, params={'offset': 1, 'limit': 1, 'release_id': RELEASE}).json()
    assert second['next_offset'] is None and second['graph_paths'] == []
    hits = first['hits'] + second['hits']
    assert [h['chunk_id'] for h in hits] == ['c1', 'c2']
    assert first['graph_paths'][0]['evidence_refs'] == [h['evidence_ref'] for h in hits]
    for hit in hits:
        assert hit['text'] == 'Evidence' and 'score' not in hit
        assert client.get(hit['evidence_path'], headers=HEADERS).json() == hit


def test_catalog_withdrawn_and_stale_source(tmp_path):
    _, graph, client = setup_catalog(tmp_path)
    original = graph.catalog_claims
    graph.catalog_claims = lambda *args: [{**row, 'quote': 'Invented'} for row in original(*args)]
    stale = client.get(URL, headers=HEADERS).json()
    assert stale['graph_status'] == 'UNAVAILABLE_OR_STALE' and stale['graph_release_id'] is None
    assert not stale['graph_paths'] and len(stale['hits']) == 2
    (tmp_path / 'withdrawn.json').write_text('["P01"]')
    empty = client.get(URL, headers=HEADERS).json()
    assert not empty['hits'] and not empty['graph_paths'] and all(v == 0 for v in empty['counts'].values())


def test_catalog_rechecks_release_and_withdrawals_during_read(tmp_path, monkeypatch):
    store, _, client = setup_catalog(tmp_path)
    scroll = store.client.scroll

    def revoke(*args, **kwargs):
        result = scroll(*args, **kwargs)
        (tmp_path / 'withdrawn.json').write_text('["P01"]')
        return result

    monkeypatch.setattr(store.client, 'scroll', revoke)
    assert client.get(URL, headers=HEADERS, params={'release_id': RELEASE}).status_code == 409
