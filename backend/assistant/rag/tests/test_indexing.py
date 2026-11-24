import hashlib
import json

import pytest
from qdrant_client import QdrantClient
from tokenizers import Tokenizer
from tokenizers.models import WordLevel
from tokenizers.pre_tokenizers import Whitespace

from mes_knowledge.indexing import ingest, COLLECTION


class Embedding:
    dimension = 3
    signature = 'test-signature'

    def documents(self, texts):
        return [[1.0, 0.0, 0.0] for _ in texts]


def test_rebuild_retains_previous_generation_and_failed_write_does_not_publish(tmp_path, monkeypatch):
    tokenizer = Tokenizer(WordLevel({'[UNK]': 0, 'evidence': 1}, unk_token='[UNK]'))
    tokenizer.pre_tokenizer = Whitespace()
    tokenizer_path = tmp_path / 'tokenizer.json'
    tokenizer.save(str(tokenizer_path))
    pdf = tmp_path / 'p.pdf'
    pdf.write_bytes(b'%PDF-reference')
    text = tmp_path / 'p.txt'
    text.write_bytes(b'evidence')
    document = {'id': 'P01', 'title': 'Paper', 'sha256': hashlib.sha256(pdf.read_bytes()).hexdigest(),
                'text_sha256': hashlib.sha256(text.read_bytes()).hexdigest(), 'pdf_path': pdf, 'text_path': text,
                'page_count': 1, 'pages': ['evidence'], 'source_url': 'https://example.org', 'license': 'CC BY 4.0'}
    client = QdrantClient(':memory:')
    data = tmp_path / 'data'
    first = ingest([document], client, Embedding(), data, tokenizer_path)
    second = ingest([document], client, Embedding(), data, tokenizer_path)
    assert first['release_id'] != second['release_id']
    assert client.count(COLLECTION, exact=True).count == 2
    assert (data / 'releases' / (first['release_id'] + '.json')).exists()
    def fail(*args, **kwargs):
        raise RuntimeError('simulated index failure')
    monkeypatch.setattr(client, 'upsert', fail)
    with pytest.raises(RuntimeError):
        ingest([document], client, Embedding(), data, tokenizer_path)
    assert json.loads((data / 'active.json').read_text())['release_id'] == second['release_id']
    assert not (data / '.ingest.lock').exists()


def test_multiple_withdrawals_are_monotonic(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    from mes_knowledge.indexing import withdraw_document
    (tmp_path / 'active.json').write_text(json.dumps({'documents': [{'id': 'P01'}, {'id': 'P02'}]}))
    with ThreadPoolExecutor(max_workers=2) as executor:
        list(executor.map(lambda doc: withdraw_document(tmp_path, doc), ['P01', 'P02']))
    assert set(json.loads((tmp_path / 'withdrawn.json').read_text())) == {'P01', 'P02'}


def test_extraction_fix_preserves_both_text_versions(tmp_path):
    from mes_knowledge.indexing import archive_sources
    pdf = tmp_path / 'paper.pdf'
    text = tmp_path / 'pages.txt'
    pdf.write_bytes(b'%PDF-fixed')
    text.write_bytes(b'old extraction')
    doc = {'pdf_path': pdf, 'text_path': text, 'sha256': hashlib.sha256(pdf.read_bytes()).hexdigest(),
           'text_sha256': hashlib.sha256(text.read_bytes()).hexdigest()}
    first = archive_sources(doc, tmp_path / 'data')
    text.write_bytes(b'corrected extraction')
    doc['text_sha256'] = hashlib.sha256(text.read_bytes()).hexdigest()
    second = archive_sources(doc, tmp_path / 'data')
    assert first['text_ref'] != second['text_ref']
    assert (tmp_path / 'data' / first['text_ref']).read_bytes() == b'old extraction'
    assert (tmp_path / 'data' / second['text_ref']).read_bytes() == b'corrected extraction'


def test_single_writer_lock_is_preserved_when_another_writer_is_running(tmp_path):
    (tmp_path / '.ingest.lock').touch()
    with pytest.raises(RuntimeError, match='ingest lock'):
        ingest([], None, None, tmp_path, tmp_path / 'unused')
    assert (tmp_path / '.ingest.lock').exists()


def test_fresh_publisher_requires_empty_pointer_under_writer_lock(tmp_path):
    from filelock import FileLock

    pointer = tmp_path / 'active.json'
    pointer.write_text(json.dumps({'release_id': 'another-writer'}))
    with FileLock(tmp_path / '.ingest-writer.lock'):
        with pytest.raises(RuntimeError, match='held by another publisher'):
            ingest([], None, None, tmp_path, tmp_path / 'unused', expected_release='')
    with pytest.raises(ValueError, match='Active release changed'):
        ingest([], None, None, tmp_path, tmp_path / 'unused', expected_release='')
    assert json.loads(pointer.read_text()) == {'release_id': 'another-writer'}
