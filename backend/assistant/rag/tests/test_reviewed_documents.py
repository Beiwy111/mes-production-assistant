import zipfile

import pytest
from mes_knowledge.reviewed_documents import reviewed_source, restore_active, append_reviewed
from mes_knowledge.corpus import sha256_file
from mes_knowledge.indexing import ingest
from test_indexing import Embedding
from qdrant_client import QdrantClient
from tokenizers import Tokenizer
from tokenizers.models import WordLevel


def test_review_and_preserve_paper(tmp_path):
    source = tmp_path / 'manual.docx'
    with zipfile.ZipFile(source, 'w') as archive:
        archive.writestr('word/document.xml', '<w:document xmlns:w="urn:w"><w:p><w:t>inspect the panel</w:t></w:p></w:document>')
    meta = dict(id='M01', title='Panel manual', source_url='https://example.org/manual',
                source_type='PUBLIC_MANUAL', license='Public distribution approved',
                sha256=sha256_file(source), visibility='PUBLIC', review_status='REFERENCE_ALLOWED')
    manual = reviewed_source(source, meta, tmp_path)
    assert manual['pages'] == ['inspect the panel']
    with pytest.raises(ValueError):
        reviewed_source(source, {**meta, 'visibility':'PRIVATE'}, tmp_path)
    with pytest.raises(ValueError):
        reviewed_source(source, {**meta, 'sha256':'stale'}, tmp_path)
    path = tmp_path / 'tokenizer.json'
    Tokenizer(WordLevel({'[UNK]':0}, unk_token='[UNK]')).save(str(path))
    store = QdrantClient(':memory:')
    data = tmp_path/'index'
    paper = {**manual, 'id':'P01', 'source_type':'PUBLIC_RESEARCH', 'title':'paper', 'pdf_path':tmp_path/'paper.pdf'}
    paper['pdf_path'].write_bytes(b'paper')
    paper['sha256'] = sha256_file(paper['pdf_path'])
    first = ingest([paper], store, Embedding(), data, path)
    second = append_reviewed(manual, store, Embedding(), data, path)
    assert [d['id'] for d in second['documents']] == ['P01','M01']
    assert restore_active(data, second)[1]['pages'] == manual['pages']
    assert append_reviewed(manual, store, Embedding(), data, path)['release_id'] == second['release_id']
    assert second['documents'][1]['source_ref'].endswith('.docx')
    from mes_knowledge.retrieval import KnowledgeStore
    points, _ = store.scroll(second['collection'], limit=16, with_payload=True)
    matches = [p.payload for p in points if p.payload['document_id']=='M01' and p.payload['release_id']==second['release_id']]
    assert matches and all(KnowledgeStore._hit(p,second['release_id'])['location_type']=='document' for p in matches)
    with pytest.raises(ValueError, match='changed'):
        ingest([manual], store, Embedding(), data, path, expected_release=first['release_id'])

