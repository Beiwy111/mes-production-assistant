import json
from pathlib import Path

import pytest

from mes_knowledge.graph.search import GraphSearch
from mes_knowledge.retrieval import Unavailable


class Vector:
    def __init__(self, path):
        self.data_dir = path
        self.revoked = set()
        self.release = 'a'*32
        self.hits = {i: {'chunk_id': i, 'document_id': 'P01', 'document_version': 'hash', 'page_start': 1,
            'text': 'Evidence for Mura method.', 'authority_type': 'PUBLIC_RESEARCH',
            'release_id': self.release, 'evidence_ref': self.release+':P01:'+i} for i in ['v1', 'g1']}
    def active(self):
        return {'release_id': self.release, 'embedding_signature': 'sig', 'documents': [{'id': 'P01'}]}
    def withdrawn(self):
        return self.revoked
    def query(self, query, top_k):
        return {'release_id': self.release, 'hits': [self.hits['v1']], 'limitations': []}
    def evidence(self, release, chunk):
        if release != self.release or 'P01' in self.revoked:
            raise LookupError()
        return self.hits[chunk]


class Graph:
    def __init__(self):
        self.rows = [{'claim_id': 'CL1', 'subject_id': 'METHOD', 'object_id': 'DEF_MURA', 'document_id': 'P01',
            'document_version': 'hash', 'page': 1, 'evidence_chunk_ids': ['g1'], 'quote': 'Evidence for Mura method.'}]
    def claims(self, *args, **kwargs):
        return self.rows


def setup(tmp_path):
    vector, graph = Vector(tmp_path), Graph()
    manifest = {'graph_release_id': 'b'*32, 'vector_release_id': 'a'*32, 'domain': 'display_reference',
        'embedding_signature':'sig', 'status':'READY', 'entities': [
            {'id':'DEF_MURA','aliases':['Mura'],'kind':'DefectConcept','name':'Mura'}]}
    (tmp_path/'graph-active.json').write_text(json.dumps(manifest))
    return vector, graph, GraphSearch(vector, graph)


def test_graph_adds_verified_evidence_which_vector_search_did_not_return(tmp_path):
    vector, graph, search = setup(tmp_path)
    result = search.query('Mura', 5)
    assert result['mode_used'] == 'graph_assisted'
    assert {h['chunk_id'] for h in result['hits']} == {'v1', 'g1'}
    assert result['graph_paths'][0]['evidence_refs'] == ['a'*32+':P01:g1']


def test_unavailable_or_mismatched_graph_falls_back_without_fabricating_paths(tmp_path):
    vector, graph, search = setup(tmp_path)
    def fail(*args, **kwargs):
        raise RuntimeError('secret-password-error')
    graph.claims = fail
    result = search.query('Mura', 5)
    assert result['mode_used'] == 'vector' and not result['graph_paths']
    assert 'secret-password' not in json.dumps(result)
    vector.release = 'c'*32
    assert search.query('Mura', 5)['graph_status'] == 'UNAVAILABLE_OR_STALE'


def test_withdrawal_and_release_switch_during_graph_call_cannot_restore_evidence(tmp_path):
    vector, graph, search = setup(tmp_path)
    def revoke(*args, **kwargs):
        vector.revoked = {'P01'}
        return graph.rows
    graph.claims = revoke
    assert search.query('Mura', 5)['hits'] == []
    vector.revoked.clear()
    def switch(*args, **kwargs):
        vector.release = 'd'*32
        return graph.rows
    graph.claims = switch
    with pytest.raises(Unavailable):
        search.query('Mura', 5)


def test_unknown_entity_and_small_budget_return_honest_coverage(tmp_path):
    vector, graph, search = setup(tmp_path)
    assert search.query('库存多少', 5)['graph_status'] == 'NO_ENTITY_MATCH'
    result = search.query('Mura', 1)
    assert len(result['hits']) == 1
    assert all(ref in {h['evidence_ref'] for h in result['hits']}
               for p in result['graph_paths'] for ref in p['evidence_refs'])
    assert result['complete'] is False


def test_trace_rejects_unknown_roots_and_graph_generation_switch(tmp_path):
    vector, graph, search = setup(tmp_path)
    with pytest.raises(ValueError):
        search.trace('Order:1', 5)
    def switch(*args, **kwargs):
        path=tmp_path/'graph-active.json'
        manifest=json.loads(path.read_text());manifest['graph_release_id']='e'*32
        path.write_text(json.dumps(manifest))
        return graph.rows
    graph.claims=switch
    assert search.query('Mura', 5)['graph_status'] == 'UNAVAILABLE_OR_STALE'


def test_missing_bridge_evidence_cannot_leave_disconnected_second_hop(tmp_path):
    vector,graph,search=setup(tmp_path)
    graph.rows=[
        {**graph.rows[0],'claim_id':'BRIDGE','subject_id':'METHOD','object_id':'DEF_MURA','evidence_chunk_ids':['missing'],'hops':1},
        {**graph.rows[0],'claim_id':'ORPHAN','subject_id':'METHOD','object_id':'OTHER','hops':2}]
    original=vector.evidence
    def evidence(release,chunk):
        if chunk=='missing':raise LookupError()
        return original(release,chunk)
    vector.evidence=evidence
    result=search.trace('DEF_MURA',8)
    assert result['graph_paths']==[] and result['hits']==[]
    assert result['complete'] is False
    answer=search.query('Mura',5)
    assert answer['graph_paths']==[]
    assert [h['chunk_id'] for h in answer['hits']]==['v1']
