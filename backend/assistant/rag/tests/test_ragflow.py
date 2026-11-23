import copy
import hashlib
import json

import httpx
import pytest
from qdrant_client import QdrantClient

from mes_knowledge.ragflow.client import RagflowClient, RagflowConfig, RagflowError
from mes_knowledge.ragflow.drafts import normalize_document, make_draft, review_template, validate_review, stage_reviewed


def config(**kwargs):
    return RagflowConfig(api_key='private-test-token', dataset_id='dataset1', **kwargs)


@pytest.mark.parametrize('url', ['http://evil.test', 'http://127.0.0.1@evil.test',
                                    'http://127.0.0.1/path', 'http://169.254.169.254',
                                    'http://127.0.0.1?redirect=evil'])
def test_host_configuration_rejects_untrusted_or_ambiguous_urls(url):
    with pytest.raises(ValueError):
        config(base_url=url)


def test_pagination_configuration_respects_go_api_page_size_limit():
    with pytest.raises(ValueError):
        config(page_size=101)


def test_dataset_listing_is_scoped_and_only_returns_management_fields():
    def handler(request):
        assert request.url.path == '/api/v1/datasets'
        assert request.url.params['id'] == 'dataset1'
        return httpx.Response(200, json={'code':0, 'data':[{'id':'dataset1','chunk_count':4,'created_by':'internal'}]})
    with RagflowClient(config(), transport=httpx.MockTransport(handler)) as client:
        assert client.datasets() == [{'id':'dataset1','chunk_count':4}]
    with RagflowClient(config(), transport=httpx.MockTransport(lambda _: httpx.Response(
            200, json={'code':0, 'data':[{'id':'another'}]}))) as client:
        with pytest.raises(RagflowError):
            client.datasets()


def test_real_http_contract_upload_parse_poll_and_paginated_chunks(tmp_path):
    pdf = tmp_path / 'paper.pdf'
    pdf.write_bytes(b'%PDF-1.4 local only')
    calls = []
    def api(request):
        calls.append((request.method, request.url.path))
        assert request.headers['Authorization'] == 'Bearer private-test-token'
        if request.method == 'POST' and request.url.path.endswith('/documents'):
            assert b'%PDF-1.4 local only' in request.read()
            return httpx.Response(200, json={'code': 0, 'data': [{'id': 'doc1', 'dataset_id': 'dataset1'}]})
        if request.method == 'POST':
            assert json.loads(request.read()) == {'document_ids': ['doc1']}
            return httpx.Response(200, json={'code': 0})
        if request.url.path.endswith('/documents'):
            assert request.url.params['id'] == 'doc1'
            return httpx.Response(200, json={'code': 0, 'data': {'docs': [{'id': 'doc1', 'run': 'DONE'}], 'total': 1}})
        page = int(request.url.params['page'])
        chunk = {'id': f'chunk{page}', 'document_id': 'doc1', 'content': f'page{page}', 'positions': [[page, 0, 20, 0, 10]]}
        return httpx.Response(200, json={'code': 0, 'data': {'chunks': [chunk], 'total': 2}})
    with RagflowClient(config(page_size=1), transport=httpx.MockTransport(api)) as client:
        assert client.upload(pdf, hashlib.sha256(pdf.read_bytes()).hexdigest()) == 'doc1'
        client.parse('doc1')
        client.wait('doc1')
        assert [c['id'] for c in client.chunks('doc1')] == ['chunk1', 'chunk2']
    assert len(calls) == 5


@pytest.mark.parametrize('response', [httpx.Response(401, text='private-test-token'),
    httpx.Response(200, json={'code': 102, 'message': 'private-test-token'}),
    httpx.Response(302, headers={'Location': 'https://evil.test'}),
    httpx.Response(200, text='not JSON private-test-token')])
def test_api_errors_never_echo_credentials_or_follow_redirects(response):
    with RagflowClient(config(), transport=httpx.MockTransport(lambda _: response)) as client:
        with pytest.raises(RagflowError) as error:
            client.parse('doc1')
    assert 'private-test-token' not in str(error.value)


def test_response_size_and_duplicate_page_limits():
    with RagflowClient(config(max_response_bytes=100), transport=httpx.MockTransport(
            lambda _: httpx.Response(200, content=b'x' * 101))) as client:
        with pytest.raises(RagflowError, match='budget'):
            client.parse('doc1')
    data = {'code': 0, 'data': {'chunks': [{'id': 'same', 'document_id': 'doc1'}], 'total': 2}}
    with RagflowClient(config(page_size=1), transport=httpx.MockTransport(lambda _: httpx.Response(200, json=data))) as client:
        with pytest.raises(RagflowError, match='Duplicate'):
            client.chunks('doc1')


@pytest.mark.parametrize('state', ['FAIL', 'CANCEL', '4', '2'])
def test_failed_or_cancelled_parse_never_exports_chunks(state):
    data = {'code': 0, 'data': {'docs': [{'id': 'doc1', 'run': state}], 'total': 1}}
    with RagflowClient(config(), transport=httpx.MockTransport(lambda _: httpx.Response(200, json=data))) as client:
        with pytest.raises(RagflowError):
            client.wait('doc1')


def test_go_api_ingestion_status_overrides_legacy_run_and_waits_scheduled():
    states = iter(['SCHEDULED', 'RUNNING', 'COMPLETED'])
    def api(_):
        return httpx.Response(200, json={'code': 0, 'data': {'docs': [
            {'id': 'doc1', 'run': '0', 'ingestion_status': next(states)}], 'total': 1}})
    with RagflowClient(config(poll_seconds=.01), transport=httpx.MockTransport(api)) as client:
        assert client.wait('doc1')['ingestion_status'] == 'COMPLETED'


def test_api_poll_timeout_is_bounded(monkeypatch):
    ticks = iter([0, 0, 2, 2])
    monkeypatch.setattr('mes_knowledge.ragflow.client.time.monotonic', lambda: next(ticks))
    response = {'code': 0, 'data': {'docs': [{'id': 'doc1', 'ingestion_status': 'RUNNING'}]}}
    with RagflowClient(config(max_wait_seconds=1), transport=httpx.MockTransport(lambda _: httpx.Response(200, json=response))) as client:
        with pytest.raises(RagflowError, match='deadline'):
            client.wait('doc1')


def test_export_aggregate_budget_and_missing_pages_are_rejected():
    response = {'code': 0, 'data': {'chunks': [{'id': 'c1', 'document_id': 'doc1', 'content': 'a' * 200}], 'total': 1}}
    with RagflowClient(config(max_export_bytes=128), transport=httpx.MockTransport(lambda _: httpx.Response(200, json=response))) as client:
        with pytest.raises(RagflowError, match='budget'):
            client.chunks('doc1')


def document(tmp_path):
    pdf = tmp_path / 'original.pdf'
    pdf.write_bytes(b'%PDF-original')
    text = tmp_path / 'pages.txt'
    text.write_text('===== PDF PAGE 1 =====\noriginal text\n', encoding='utf-8')
    return {'id': 'P01', 'sha256': hashlib.sha256(pdf.read_bytes()).hexdigest(), 'pdf_path': pdf,
        'text_path': text, 'text_sha256': hashlib.sha256(text.read_bytes()).hexdigest(),
        'page_count': 1, 'pages': ['original text'], 'title': 'Paper', 'source_url': 'https://example.org/paper',
        'license': 'CC BY 4.0', 'source_type': 'PUBLIC_RESEARCH', 'review_status': 'REFERENCE_ALLOWED'}


def draft_for(doc, positions=None):
    chunk = {'id': 'c1', 'document_id': 'doc1', 'content': '<table><tr><td>A</td><td>42</td></tr></table>',
             'positions': [[1, 0, 10, 0, 10]] if positions is None else positions}
    return make_draft([normalize_document(doc, 'dataset1', 'doc1', [chunk])])


def test_tables_keep_raw_markup_text_and_source_identity_without_fabricated_pages(tmp_path):
    draft = draft_for(document(tmp_path), [])
    chunk = draft['documents'][0]['chunks'][0]
    assert chunk['text'] == 'A\t42'
    assert chunk['content'].startswith('<table>')
    assert chunk['page_start'] is None
    assert chunk['review_status'] == 'NEEDS_REVIEW'
    assert chunk['ragflow_chunk_id'] == 'c1'
    assert draft['documents'][0]['sha256'] == hashlib.sha256(b'%PDF-original').hexdigest()


def approved(draft):
    review = review_template(draft)
    review.update(status='APPROVED', reviewer='human-reviewer', reviewed_at='2026-11-09T12:00:00+08:00')
    review['documents'][0]['pages'] = ['A\t42']
    review['documents'][0]['approved_chunk_ids'] = ['c1']
    return review


def test_review_requires_digest_identity_pages_and_exact_offsets(tmp_path):
    doc = document(tmp_path)
    draft = draft_for(doc)
    review = approved(draft)
    chunks, _ = validate_review(draft, review, [doc])
    assert chunks[0]['char_start'] == 0
    assert chunks[0]['char_end'] == 4
    assert chunks[0]['text'] == 'A\t42'
    for mutate in [lambda r: r.update(status='DRAFT'), lambda r: r.update(draft_sha256='0'*64),
                   lambda r: r['documents'][0].update(pages=['wrong']),
                   lambda r: r['documents'][0].update(approved_chunk_ids=[]),
                   lambda r: r.update(reviewer='')]:
        bad = copy.deepcopy(review)
        mutate(bad)
        with pytest.raises(ValueError):
            validate_review(draft, bad, [doc])
    missing = draft_for(doc, [])
    with pytest.raises(ValueError, match='page'):
        validate_review(missing, approved(missing), [doc])


def test_staging_creates_distinct_collection_without_publishing_or_modifying_existing(tmp_path):
    doc = document(tmp_path)
    draft = draft_for(doc)
    data = tmp_path / 'data'
    data.mkdir()
    (data / 'active.json').write_text('{"release_id":"previous"}')
    class Embedding:
        dimension = 384
        signature = 'local-e5'
        def documents(self, texts):
            return [[1.0] + [0.0] * 383 for _ in texts]
    client = QdrantClient(':memory:')
    result = stage_reviewed(draft, approved(draft), [doc], client, Embedding(), data)
    assert result['status'] == 'STAGED_NOT_PUBLISHED'
    assert result['collection'].startswith('mes_ragflow_stage_')
    assert client.count(result['collection']).count == 1
    payload = client.scroll(result['collection'])[0][0].payload
    assert payload['document_version'] == doc['sha256']
    assert payload['page_start'] == 1 and payload['char_end'] == 4
    assert json.loads((data / 'active.json').read_text()) == {'release_id': 'previous'}
    assert not (data / 'graph-active.json').exists()


def test_review_rejects_page_whitespace_that_would_shift_archived_offsets(tmp_path):
    doc = document(tmp_path)
    draft = draft_for(doc)
    review = approved(draft)
    review['documents'][0]['pages'] = ['  A\t42\n']
    with pytest.raises(ValueError, match='page'):
        validate_review(draft, review, [doc])


def test_staging_failure_never_replaces_active_release(tmp_path):
    doc = document(tmp_path)
    draft = draft_for(doc)
    data = tmp_path / 'data'
    data.mkdir()
    (data / 'active.json').write_bytes(b'original-release')
    class FailedEmbedding:
        dimension = 384
        signature = 'local-e5'
        def documents(self, texts):
            raise ValueError('Embedding over budget')
    client = QdrantClient(':memory:')
    with pytest.raises(ValueError, match='budget'):
        stage_reviewed(draft, approved(draft), [doc], client, FailedEmbedding(), data)
    assert (data / 'active.json').read_bytes() == b'original-release'
    assert not list((data / 'ragflow' / 'staged').glob('*/manifest.json'))


@pytest.mark.parametrize('positions', [[[0, 0, 1, 0, 1]], [[2, 0, 1, 0, 1]], [[1, 5, 1, 0, 1]], ['1']])
def test_invalid_page_coordinates_remain_unresolved(tmp_path, positions):
    draft = draft_for(document(tmp_path), positions)
    chunk = draft['documents'][0]['chunks'][0]
    assert chunk['page_start'] is None
    assert 'MISSING_OR_INVALID_PAGE_POSITION' in chunk['issues']


def test_reviewed_splits_preserve_original_ragflow_chunk_identity(tmp_path):
    doc = document(tmp_path)
    draft = draft_for(doc)
    base = draft['documents'][0]['chunks'][0]
    draft['documents'][0]['chunks'] = [
        {**base, 'draft_chunk_id': 'c1-part1', 'content': 'A', 'text': 'A'},
        {**base, 'draft_chunk_id': 'c1-part2', 'content': '42', 'text': '42'}]
    review = approved(draft)
    review['documents'][0]['approved_chunk_ids'] = ['c1-part1', 'c1-part2']
    chunks, _ = validate_review(draft, review, [doc])
    assert [c['ragflow_chunk_id'] for c in chunks] == ['c1', 'c1']
    assert len({c['chunk_id'] for c in chunks}) == 2
    assert [c['char_start'] for c in chunks] == [0, 2]
