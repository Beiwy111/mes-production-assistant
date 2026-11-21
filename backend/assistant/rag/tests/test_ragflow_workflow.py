import json

import pytest

from mes_knowledge.ragflow.workflow import archived_corpus, comparison, ingest_drafts, export_drafts
from mes_knowledge.ragflow.client import RagflowClient, RagflowConfig, RagflowError
from mes_knowledge.ragflow.drafts import make_draft, normalize_document
from test_ragflow import document
import httpx


def test_archived_corpus_checks_originals_without_following_registry_urls(tmp_path):
    doc = document(tmp_path)
    source = tmp_path / 'data' / 'sources' / doc['sha256']
    source.mkdir(parents=True)
    (source / 'original.pdf').write_bytes(doc['pdf_path'].read_bytes())
    (source / ('text-' + doc['text_sha256'] + '.txt')).write_bytes(doc['text_path'].read_bytes())
    registry = tmp_path / 'papers.json'
    registry.write_text(json.dumps({'sources': [{k: v for k, v in doc.items() if k not in ('pdf_path', 'text_path', 'pages')}]}))
    loaded = archived_corpus(registry, tmp_path / 'data')
    assert loaded[0]['pages'] == ['original text']
    (source / 'original.pdf').write_bytes(b'changed')
    with pytest.raises(ValueError, match='checksum'):
        archived_corpus(registry, tmp_path / 'data')


def test_comparison_distinguishes_unparsed_baseline_and_page_coverage(tmp_path):
    doc = document(tmp_path)
    report = comparison([doc])
    assert report['ragflow_status'] == 'NOT_PARSED'
    assert report['documents'][0]['baseline_pages'] == 1
    assert report['documents'][0]['ragflow_chunks'] is None
    draft = make_draft([normalize_document(doc, 'ds1', 'doc1', [
        {'id': 'c1', 'document_id': 'doc1', 'content': 'original text', 'positions': [[1, 0, 10, 0, 10]]}])])
    report = comparison([doc], draft)
    assert report['documents'][0]['exact_baseline_matches'] == 1
    assert report['documents'][0]['pages_covered'] == [1]


def test_upload_receipt_survives_parse_failure_and_can_resume_without_reupload(tmp_path):
    doc = document(tmp_path)
    def api(request):
        if request.method == 'POST' and request.url.path.endswith('/documents'):
            return httpx.Response(200, json={'code': 0, 'data': [{'id': 'doc1', 'dataset_id': 'ds1'}]})
        return httpx.Response(503)
    config = RagflowConfig(api_key='test-private-key', dataset_id='ds1')
    with RagflowClient(config, transport=httpx.MockTransport(api)) as client:
        with pytest.raises(RagflowError):
            ingest_drafts([doc], client, tmp_path / 'out')
    receipt = json.loads((tmp_path / 'out' / 'upload-receipt.json').read_text())
    assert receipt['documents'][0]['ragflow_document_id'] == 'doc1'
    assert receipt['documents'][0]['sha256'] == doc['sha256']
    assert not (tmp_path / 'out' / 'draft.json').exists()
    def resumed(request):
        assert request.method == 'GET'
        if request.url.path.endswith('/documents'):
            return httpx.Response(200, json={'code': 0, 'data': {'docs': [{'id': 'doc1', 'run': 'DONE'}], 'total': 1}})
        return httpx.Response(200, json={'code': 0, 'data': {'chunks': [
            {'id': 'c1', 'document_id': 'doc1', 'content': 'evidence', 'positions': []}], 'total': 1}})
    with RagflowClient(config, transport=httpx.MockTransport(resumed)) as client:
        draft = export_drafts([doc], client, receipt, tmp_path / 'out')
    assert draft['documents'][0]['chunks'][0]['page_start'] is None
