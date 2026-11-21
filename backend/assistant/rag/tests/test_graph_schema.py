import copy
import json

import pytest

from mes_knowledge.graph.schema import prepare_graph


def fixture():
    manifest = {'release_id': 'a' * 32, 'documents': [{'id': 'P01', 'sha256': 'pdf-hash'}], 'point_count': 1}
    chunks = [{'chunk_id': 'c1', 'document_id': 'P01', 'document_version': 'pdf-hash',
               'release_id': 'a' * 32, 'page_start': 1, 'text': 'This method detects Mura defects in low contrast images.',
               'visibility': 'PUBLIC', 'knowledge_domain': 'display_reference', 'review_status': 'REFERENCE_ALLOWED'}]
    claims = {'schema_version': 1, 'entities': [
        {'id': 'DEF_MURA', 'kind': 'DefectConcept', 'name': 'Mura', 'aliases': ['mura', '不均匀']},
        {'id': 'METHOD_PGC', 'kind': 'Method', 'name': 'PGC', 'aliases': ['PGC']}],
        'claims': [{'id': 'C1', 'subject': 'METHOD_PGC', 'object': 'DEF_MURA', 'relation': 'DETECTS',
                    'document_id': 'P01', 'document_version': 'pdf-hash', 'page': 1,
                    'quote': 'This method detects Mura defects', 'statement': '研究提出方法检测Mura',
                    'review_status': 'SOURCE_VERIFIED', 'authority_type': 'PUBLIC_RESEARCH'}]}
    return manifest, chunks, claims


def test_claim_requires_exact_source_page_version_and_quote():
    manifest, chunks, claims = fixture()
    result = prepare_graph(claims, manifest, chunks)
    assert result['claims'][0]['evidence_chunk_ids'] == ['c1']
    for key, value in [('page', 9), ('document_version', 'other'), ('quote', 'The factory batch is defective')]:
        bad = copy.deepcopy(claims)
        bad['claims'][0][key] = value
        with pytest.raises(ValueError):
            prepare_graph(bad, manifest, chunks)


def test_graph_rejects_drafts_dangling_entities_and_unsafe_relations():
    manifest, chunks, claims = fixture()
    for key, value in [('review_status', 'DRAFT'), ('object', 'OTHER'), ('relation', 'CAUSES_THIS_BATCH_FAILURE')]:
        bad = copy.deepcopy(claims)
        bad['claims'][0][key] = value
        with pytest.raises(ValueError):
            prepare_graph(bad, manifest, chunks)


def test_graph_rejects_duplicate_chunks_and_cross_release_mapping():
    manifest, chunks, claims = fixture()
    with pytest.raises(ValueError):
        prepare_graph(claims, manifest, chunks + chunks)
    chunks[0]['release_id'] = 'b' * 32
    with pytest.raises(ValueError):
        prepare_graph(claims, manifest, chunks)


def test_unknown_or_ambiguous_alias_does_not_invent_an_entity():
    from mes_knowledge.graph.schema import resolve_entities
    _, _, claims = fixture()
    assert resolve_entities('Mura怎么检测？', claims['entities']) == ['DEF_MURA']
    assert resolve_entities('订单库存多少？', claims['entities']) == []
    assert resolve_entities('PGC用于Mura吗？', claims['entities']) == ['DEF_MURA', 'METHOD_PGC']
    assert resolve_entities('PGCXYZ', claims['entities']) == []
