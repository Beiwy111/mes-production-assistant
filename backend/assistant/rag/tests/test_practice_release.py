from mes_knowledge.config import RAG_ROOT
from mes_knowledge.corpus import chunk_document, sha256_file
from mes_knowledge.practice_release import merge_documents, practice_notes, wafer_example
import pytest
from tokenizers import Tokenizer
from tokenizers.models import WordLevel


def test_notes_are_repeatable_attributed_and_have_document_locators(tmp_path):
    notes = practice_notes(RAG_ROOT / 'corpus/practice', tmp_path)
    tok = Tokenizer(WordLevel({'[UNK]': 0}, unk_token='[UNK]'))
    for note in notes:
        assert sha256_file(note['pdf_path']) == note['sha256']
        assert note['source_url'] in note['pdf_path'].read_text('utf-8')
        hits = chunk_document(note, tok)
        assert hits and all(
            hit['authority_type'] == 'PUBLIC_MANUAL' and hit['location_type'] == 'document' for hit in hits
        )
    assert [doc['sha256'] for doc in notes] == [
        doc['sha256'] for doc in practice_notes(RAG_ROOT / 'corpus/practice', tmp_path)
    ]
    retained = {'id': 'OTHER_APPROVED_MANUAL'}
    assert merge_documents([retained, notes[0]], notes) == [retained, *notes]
    with pytest.raises(ValueError, match='Duplicate'):
        merge_documents([], [notes[0], notes[0]])


def test_worked_example_is_calculated_from_the_nist_data():
    example = wafer_example(RAG_ROOT / 'corpus/practice')
    for value in ('1500', '347', '23.13%', '41.02%', '5.24%', '第15片（44%）', '第23片（48%）'):
        assert value in example['text']
    assert example['source_url'].endswith('MONITOR-6_3_3_2.DAT')
