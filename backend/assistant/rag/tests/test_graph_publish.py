import json
from pathlib import Path

import pytest
from mes_knowledge.graph.publish import publish_graph


class Vector:
    data_dir: Path
    def __init__(self, path):
        self.data_dir = path
        self.manifest = {'release_id': 'a'*32, 'embedding_signature': 'sig', 'collection': 'public', 'point_count': 1}
    def active(self):
        return self.manifest.copy()


class Graph:
    def write_release(self, generation, vector_release, data):
        return {'chunks': 1, 'claims': 1, 'entities': 2, 'documents': 1}
    def verify_release(self, generation, vector_release):
        return {'chunks': 1, 'claims': 1, 'entities': 2, 'documents': 1, 'evidence_links': 1}


def bundle():
    return {'chunks': [{}], 'claims': [{'evidence_chunk_ids': ['c1']}], 'entities': [{}, {}], 'documents': [{}]}


def test_graph_only_published_after_successful_count_verification(tmp_path):
    vector, graph = Vector(tmp_path), Graph()
    result = publish_graph(vector, graph, bundle(), vector.active())
    assert json.loads((tmp_path/'graph-active.json').read_text())['graph_release_id'] == result['graph_release_id']
    assert result['vector_release_id'] == 'a'*32
    old = (tmp_path/'graph-active.json').read_bytes()
    graph.verify_release = lambda *a: {'chunks': 0}
    with pytest.raises(ValueError):
        publish_graph(vector, graph, bundle(), vector.active())
    assert (tmp_path/'graph-active.json').read_bytes() == old


def test_vector_switch_during_graph_build_does_not_publish(tmp_path):
    vector, graph = Vector(tmp_path), Graph()
    def change(*args):
        vector.manifest['release_id'] = 'b'*32
    graph.write_release = change
    with pytest.raises(ValueError):
        publish_graph(vector, graph, bundle(), {'release_id':'a'*32})
    assert not (tmp_path/'graph-active.json').exists()
