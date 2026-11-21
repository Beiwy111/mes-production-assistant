from fastapi.testclient import TestClient

from mes_knowledge.api import create_app
from mes_knowledge.retrieval import Unavailable


class Store:
    def query(self, query, top_k):
        if query == 'fail':
            raise Unavailable('secret-internal-error')
        return {'release_id': 'test', 'hits': []}

    def evidence(self, release, chunk):
        raise LookupError()


def test_document_parse_requires_service_auth_and_never_persists():
    import base64
    client=TestClient(create_app(Store(), 'local-service-key-not-a-model-key'))
    body={'filename':'sop.txt','content_base64':base64.b64encode(b'LCD checks').decode()}
    assert client.post('/internal/knowledge/documents/parse',json=body).status_code == 401
    response=client.post('/internal/knowledge/documents/parse',json=body,headers={'Authorization':'Bearer local-service-key-not-a-model-key'})
    assert response.status_code == 200
    assert response.json()['persisted'] is False
    assert response.json()['review_status'] == 'DRAFT'


def test_api_requires_key_rejects_scope_injection_and_bounds_inputs():
    client = TestClient(create_app(Store(), 'local-service-key-not-a-model-key'))
    url = '/internal/knowledge/query'
    assert client.post(url, json={'query': 'mura'}).status_code == 401
    headers = {'Authorization': 'Bearer local-service-key-not-a-model-key'}
    assert client.post(url, headers=headers, json={'query': 'mura'}).status_code == 200
    for body in ({'query': 'mura', 'tenant_id': 'other'}, {'query': 'mura', 'roles': ['ADMIN']},
                 {'query': 'x' * 2001}, {'query': 'mura', 'top_k': 100}, {'query': ' '}):
        assert client.post(url, headers=headers, json=body).status_code == 422
    response = client.post(url, headers=headers, json={'query': 'fail'})
    assert response.status_code == 503 and 'secret-internal-error' not in response.text
    assert client.get('/internal/knowledge/evidence/abc/def', headers=headers).status_code == 404


def test_graph_tool_rejects_arbitrary_templates_and_self_declared_scope():
    client = TestClient(create_app(Store(), 'local-service-key-not-a-model-key'))
    headers = {'Authorization': 'Bearer local-service-key-not-a-model-key'}
    path = '/internal/knowledge/graph/trace'
    assert client.post(path, json={'entity_id':'DEF_MURA'}).status_code == 401
    for payload in ({'template_id':'ORDER_CHAIN','entity_id':'Order:1'},
                    {'entity_id':'DEF_MURA','cypher':'MATCH(n) DETACH DELETE n'},
                    {'entity_id':'DEF_MURA','roles':['ADMIN']},
                    {'entity_id':'DEF_MURA','max_paths':100}):
        assert client.post(path, headers=headers, json=payload).status_code == 422
    assert client.post('/internal/knowledge/query', headers=headers,
                       json={'query':'Mura','mode':'arbitrary_sql'}).status_code == 422
