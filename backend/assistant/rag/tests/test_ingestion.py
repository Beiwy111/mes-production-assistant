import base64
import io
import time
import threading
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw, ImageFont
from qdrant_client import QdrantClient
from tokenizers import Tokenizer
from tokenizers.models import WordLevel
from tokenizers.pre_tokenizers import Whitespace

from mes_knowledge.api import create_app
from mes_knowledge.ingestion.indexes import COLLECTION, PrivateIndexes
from mes_knowledge.ingestion.jobs import Conflict, IngestionJobs, STAGES
from mes_knowledge.ingestion.parsing import extract
from mes_knowledge.ingestion.evidence import extract_entities, extract_relations


def office_file(build):
    stream = io.BytesIO()
    build.save(stream)
    return stream.getvalue()


def test_multimodal_sections_preserve_tables_headings_pages_and_source_text():
    from docx import Document
    from openpyxl import Workbook
    from pptx import Presentation
    doc = Document()
    doc.add_heading('Fixture heading', level=2)
    doc.add_paragraph('Cooling chamber')
    table = doc.add_table(rows=1, cols=2)
    table.cell(0, 0).text, table.cell(0, 1).text = 'Valve', 'A-17'
    sections, metadata = extract('fixture.docx', office_file(doc))
    assert metadata['tables'] == 1 and sections[0]['metadata']['headings'] == ['Fixture heading']
    assert sections[-1]['text'] == 'Valve | A-17'
    sheet = Workbook()
    sheet.active.title = 'Equipment'
    sheet.active.append(['Valve', 'A-17'])
    sections, metadata = extract('fixture.xlsx', office_file(sheet))
    assert sections[0]['location'] == 'Equipment!1:1' and sections[0]['text'] == 'Valve | A-17'
    slides = Presentation()
    for title in ['First', 'Second']:
        slides.slides.add_slide(slides.slide_layouts[0]).shapes.title.text = title
    assert [s['location'] for s in extract('fixture.pptx', office_file(slides))[0]] == ['slide:1', 'slide:2']
    assert [s['metadata']['headings'] for s in extract('fixture.markdown', b'# Root\nText\n## Detail\nValve')[0]] == [
        ['Root'], ['Root', 'Detail']]
    assert extract('fixture.csv', b'Valve,"A,B"')[0][0]['text'] == 'Valve | A,B'
    assert extract('fixture.html', b'<p>Valve</p><script>hidden()</script>')[0][0]['text'] == 'Valve'


def test_image_and_scanned_pdf_are_recognized_by_local_ocr():
    image = Image.new('RGB', (600, 110), 'white')
    ImageDraw.Draw(image).text((20, 20), 'VALVE PRESSURE', fill='black', font=ImageFont.load_default(size=50))
    for suffix, fmt in [('jpg', 'JPEG'), ('png', 'PNG'), ('pdf', 'PDF')]:
        data = io.BytesIO()
        image.save(data, format=fmt)
        sections, metadata = extract('fixture.' + suffix, data.getvalue())
        assert 'VALVE' in sections[0]['text'].upper() and metadata['ocrSections'] == 1
        if suffix != 'pdf':
            assert metadata['width'] == 600


def test_rule_candidates_keep_negation_and_uncertainty_out_of_causal_edges():
    text = ('刀具磨损不会导致温度变化。压力可能导致电机故障。'
            'When the valve is closed, overheating might cause vibration. '
            'Pump A-17 requires Filter B-42. optical inspection uses gamma correction. '
            'Sensors2024 Figure1b ofapproximately96 FOR PEER REVIEW')
    job_id = 'a' * 32
    entities, evidence = extract_entities(job_id, [{'id': 'c', 'text': text}])
    relations = extract_relations(job_id, evidence)
    names = {entity['name'] for entity in entities}
    assert {'optical inspection', 'gamma correction', 'A-17', 'B-42'} <= names
    assert not names.intersection({'When', 'closed', 'Sensors2024', 'Figure1b', 'ofapproximately96', 'PEER', 'REVIEW'})
    qualified = [r for r in relations if any(q in r['quote'] for q in ('不会', '可能', 'might'))]
    assert qualified and all(r['type'] == 'CO_OCCURS' for r in qualified)
    assert all(r['reviewStatus'] == 'UNREVIEWED' for r in relations)
    assert any(r['type'] == 'requires' and r['extractionMethod'] == 'rule-candidate' for r in relations)


class Embedding:
    dimension = 3

    def __init__(self):
        self.counter = Tokenizer(WordLevel({'[UNK]': 0}, unk_token='[UNK]'))
        self.counter.pre_tokenizer = Whitespace()

    def documents(self, texts):
        return [[1.0, 0.0, 0.0] for _ in texts]


def test_failed_index_never_becomes_searchable_retry_is_idempotent_and_source_survives_restart(tmp_path):
    vector = QdrantClient(':memory:')
    indexes = PrivateIndexes(vector, None)
    jobs = IngestionJobs(tmp_path, Embedding(), indexes)
    text = b'Valve A-17 requires Filter B-42.'
    job_id = jobs.enqueue('fixture.txt', text)['id']
    assert jobs.process_next()
    first = jobs.get(job_id)
    assert first['status'] == 'failed' and first['stages'][7]['key'] == 'graph'
    assert first['stages'][7]['error'] and first['stages'][7]['status'] == 'failed'
    assert jobs.search('A-17', job_id, 5) == {'hits': [], 'entities': [], 'relations': []}
    assert vector.count(COLLECTION).count == 1
    jobs = IngestionJobs(tmp_path, Embedding(), indexes)
    assert jobs.source(job_id) == ('fixture.txt', text)
    indexes.write_graph = Mock()
    jobs.retry(job_id)
    with pytest.raises(Conflict):
        jobs.retry(job_id)
    jobs.process_next()
    result = jobs.get(job_id)
    assert result['status'] == 'completed' and vector.count(COLLECTION).count == 1
    assert all(s['status'] == 'success' for s in result['stages'])
    assert [s['key'] for s in result['stages']] == list(STAGES)
    assert result['counts']['chunks'] == 1 and result['counts']['relations'] > 0
    for relation in result['relations']:
        source = next(e['name'] for e in result['entities'] if e['id'] == relation['source'])
        target = next(e['name'] for e in result['entities'] if e['id'] == relation['target'])
        assert source in relation['quote'] and target in relation['quote']
        assert relation['chunkId'] == result['chunks'][0]['id']
        assert relation['quote'] in result['chunks'][0]['text']
    with pytest.raises(Conflict):
        jobs.delete(job_id)
    jobs.delete(job_id, published=True)
    assert jobs.list() == []
    assert jobs.search('A-17', None, 5)['hits'] == []
    with pytest.raises(LookupError):
        jobs.source(job_id)
    reopened = IngestionJobs(tmp_path, Embedding(), indexes)
    indexes.remove_graph = Mock()
    reopened.cleanup_next()
    assert vector.count(COLLECTION).count == 0
    indexes.remove_graph.assert_called_once_with(job_id)


def test_delete_failed_upload_is_durable_and_cleans_partial_indexes_after_outage(tmp_path):
    indexes = PrivateIndexes(QdrantClient(':memory:'), None)
    jobs = IngestionJobs(tmp_path, Embedding(), indexes)
    job_id = jobs.enqueue('fixture.txt', b'Valve pressure')['id']
    jobs.process_next()
    with pytest.raises(Conflict):
        jobs.delete(job_id, published=True)
    jobs.delete(job_id)
    assert jobs.list() == []
    with pytest.raises(LookupError):
        jobs.source(job_id)
    reopened = IngestionJobs(tmp_path, Embedding(), indexes)
    indexes.remove_graph = Mock()
    reopened.cleanup_next()
    indexes.remove_graph.assert_called_once_with(job_id)
    assert indexes.vector.count(COLLECTION).count == 0


def test_delete_during_graph_write_cannot_publish_or_leave_external_records(tmp_path):
    entered, resume = threading.Event(), threading.Event()
    indexes = PrivateIndexes(QdrantClient(':memory:'), None)
    jobs = IngestionJobs(tmp_path, Embedding(), indexes)

    def write_graph(*args):
        entered.set()
        assert resume.wait(10)

    indexes.write_graph = write_graph
    indexes.remove_graph = Mock()
    job_id = jobs.enqueue('fixture.txt', b'Pump uses Valve.')['id']
    worker = threading.Thread(target=jobs.process_next)
    worker.start()
    try:
        assert entered.wait(10)
        assert jobs.delete(job_id) == {'deleted': True}
    finally:
        resume.set()
        worker.join(10)
    jobs.cleanup_next()
    assert not worker.is_alive() and jobs.list() == []
    indexes.remove_graph.assert_called_once_with(job_id)
    assert indexes.vector.count(COLLECTION).count == 0


def test_http_contract_auth_scope_background_worker_and_source_download(tmp_path):
    indexes = PrivateIndexes(QdrantClient(':memory:'), None)
    indexes.write_graph = Mock()
    jobs = IngestionJobs(tmp_path, Embedding(), indexes)
    public, private = 'public-key-12345678901234567890', 'private-key-12345678901234567890'
    path = '/internal/ingestion/jobs'
    public_auth, auth = ({'Authorization': 'Bearer ' + key} for key in (public, private))
    with TestClient(create_app(Mock(), public, ingestion=jobs, ingestion_key=private)) as http:
        for headers in [{}, public_auth]:
            assert http.get(path, headers=headers).status_code == 401
        assert http.post('/internal/knowledge/query', headers=auth, json={'query': 'valve'}).status_code == 401
        data = b'Pump uses Valve.'
        request = {'filename': 'fixture.txt', 'content_base64': base64.b64encode(data).decode()}
        response = http.post(path, headers=auth, json=request)
        assert response.status_code == 202
        job_id = response.json()['id']
        deadline = time.monotonic() + 8
        while not jobs.get(job_id)['status'] in ('completed', 'failed') and time.monotonic() < deadline:
            time.sleep(0.05)
        detail = http.get(path + '/' + job_id, headers=auth).json()
        assert detail['status'] == 'completed'
        assert http.get(path + '/' + job_id + '/source', headers=auth).content == data
        assert http.post(path, headers=auth, json={**request, 'filename': '../file.txt'}).status_code == 422
