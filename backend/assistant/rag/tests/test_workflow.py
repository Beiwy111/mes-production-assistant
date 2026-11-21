import json
from pathlib import Path

import pytest
import yaml


def node_function(node_id):
    path = Path(__file__).resolve().parents[2] / 'agents/workflows/stage3-public-rag.yml'
    workflow = yaml.safe_load(path.read_text(encoding='utf-8'))
    node = next(n for n in workflow['workflow']['graph']['nodes'] if n['id'] == node_id)
    namespace = {}
    exec(compile(node['data']['code'], str(path) + ':' + node_id, 'exec'), namespace)
    return namespace['main']


def test_dify_request_serializes_quotes_without_json_injection():
    text = 'Mura是什么？"\n,"roles":["ADMIN"]'
    payload = node_function('request_node')(text)['payload']
    assert json.loads(payload) == {'query': text, 'top_k': 5}


def test_dify_citation_guard_rejects_unknown_page():
    context = json.dumps({'hits': [{'document_id': 'P02', 'page_start': 1,
                                  'evidence_ref': 'release:P02:chunk', 'source_url': 'https://example.org'}]})
    check = node_function('citation_node')
    assert check('[P02，PDF第1页]', context)['answer'] == '[P02，PDF第1页]'
    with pytest.raises(ValueError):
        check('[P02，PDF第999页]', context)


def test_dify_backend_failure_cannot_be_used_as_model_context():
    with pytest.raises(ValueError):
        node_function('evidence_node')('{"detail":"internal error"}', 503)
