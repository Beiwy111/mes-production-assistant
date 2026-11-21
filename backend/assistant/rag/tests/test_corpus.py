import hashlib
import json

import pytest
from tokenizers import Tokenizer
from tokenizers.models import WordLevel
from tokenizers.pre_tokenizers import Whitespace

from mes_knowledge.corpus import load_corpus, split_pages, chunk_document


def test_rejects_missing_repeated_and_out_of_order_pages():
    for text in ('hello', '===== PDF PAGE 2 =====\nx',
                 '===== PDF PAGE 1 =====\nx\n===== PDF PAGE 1 =====\ny'):
        with pytest.raises(ValueError):
            split_pages(text, 2)


def fixture_source(tmp_path, **overrides):
    pdf = b'%PDF-test'
    text = '===== PDF PAGE 1 =====\n' + ' '.join(['evidence'] * 700)
    (tmp_path / 'paper.pdf').write_bytes(pdf)
    (tmp_path / 'pages.txt').write_text(text, encoding='utf-8')
    doc = dict(id='P01', title='Example', file='paper.pdf', text_file='pages.txt',
               sha256=hashlib.sha256(pdf).hexdigest(), text_sha256=hashlib.sha256((tmp_path / 'pages.txt').read_bytes()).hexdigest(),
               page_count=1, source_url='https://example.org/paper', license='CC BY 4.0',
               source_type='PUBLIC_RESEARCH', review_status='REFERENCE_ALLOWED', keywords=['LCD'])
    doc.update(overrides)
    path = tmp_path / 'registry.json'
    path.write_text(json.dumps({'sources': [doc]}), encoding='utf-8')
    return path


def test_hash_mismatch_and_nonpublic_draft_fail_before_ingestion(tmp_path):
    registry = fixture_source(tmp_path, sha256='0' * 64)
    with pytest.raises(ValueError):
        load_corpus(registry, tmp_path)
    registry = fixture_source(tmp_path, review_status='DRAFT')
    with pytest.raises(ValueError):
        load_corpus(registry, tmp_path)
    registry = fixture_source(tmp_path, file='../outside.pdf')
    with pytest.raises(ValueError):
        load_corpus(registry, tmp_path)


def test_token_windows_preserve_exact_page_text_and_stable_ids(tmp_path):
    registry = fixture_source(tmp_path)
    document = load_corpus(registry, tmp_path)[0]
    tokenizer = Tokenizer(WordLevel({'[UNK]': 0, 'evidence': 1}, unk_token='[UNK]'))
    tokenizer.pre_tokenizer = Whitespace()
    chunks = chunk_document(document, tokenizer, max_tokens=320, overlap=48)
    assert len(chunks) == 3
    assert chunks == chunk_document(document, tokenizer, max_tokens=320, overlap=48)
    assert len({c['chunk_id'] for c in chunks}) == 3
    for c in chunks:
        assert c['page_start'] == c['page_end'] == 1
        assert len(tokenizer.encode(c['text']).ids) <= 320
        assert document['pages'][0][c['char_start']:c['char_end']] == c['text']
        assert c['authority_type'] == 'PUBLIC_RESEARCH'


def test_bibliography_does_not_displace_research_evidence(tmp_path):
    document = load_corpus(fixture_source(tmp_path), tmp_path)[0]
    document['pages'] = ['evidence\nReferences\nother paper title', 'more bibliography']
    tokenizer = Tokenizer(WordLevel({'[UNK]': 0, 'evidence': 1}, unk_token='[UNK]'))
    tokenizer.pre_tokenizer = Whitespace()
    chunks = chunk_document(document, tokenizer)
    assert len(chunks) == 1 and chunks[0]['text'] == 'evidence'
