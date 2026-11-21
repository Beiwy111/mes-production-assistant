from mes_knowledge.hybrid import bm25_rank, reciprocal_rank_fusion


def test_bm25_uses_terms_and_chinese_bigrams_not_substrings():
    docs = [{'chunk_id': 'a', 'text': 'LCD mura defect'}, {'chunk_id': 'b', 'text': '背光模组装配及漏光检查'}]
    assert bm25_rank('背光漏光', docs)[0][0] == 'b'
    assert bm25_rank('mura', docs)[0][0] == 'a'
    assert bm25_rank('no_matching_term', docs) == []


def test_fusion_keeps_both_sources_deduplicates_and_is_deterministic():
    assert reciprocal_rank_fusion(['a','b'],['c','b'],3) == ['b','a','c']
    assert reciprocal_rank_fusion(['a','a'],['b'],5) == ['a','b']
