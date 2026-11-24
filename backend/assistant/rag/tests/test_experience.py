import json
import re
import threading
from types import SimpleNamespace

import pytest
from qdrant_client import QdrantClient
from qdrant_client import models

from mes_knowledge.experience import ExperienceSearch
from mes_knowledge.experience.index import COLLECTION, synchronize
from mes_knowledge.experience.source import MySQLHistory


class Encoder:
    dimension = 3
    signature = 'test-model'
    counter = SimpleNamespace(encode=lambda text: SimpleNamespace(ids=list(text)))

    def __init__(self):
        self.calls = []

    def documents(self, texts):
        self.calls.extend(texts)
        return [[1., 0., 0.] for _ in texts]

    def query(self, text):
        return [1., 0., 0.]


class Source:
    def __init__(self):
        common = dict(equipment_id=12, equipment_code='EQ-12', equipment_name='贴合机', action='返工',
                      outcome='COMPLETED', updated_at='2026-01-01')
        self.data = {
            'MAINTENANCE': [dict(common, record_id=1, record_no='M-001', category='REPAIR', description='压力故障')],
            'QUALITY': [dict(common, record_id=1, record_no='Q-001', category='气泡', description='边缘气泡')]}

    def fetch(self, requested=None):
        return {kind: [r for r in rows if requested is None or r['record_id'] in requested.get(kind, [])]
                for kind, rows in self.data.items()}


class Graph:
    def reconcile(self, records, generation):
        self.records, self.generation = records, generation
        return {}

    def paths(self, generation, seeds, kinds, limit):
        assert generation == self.generation
        paths = []
        for key in seeds:
            a = self.records[key]
            for b in self.records.values():
                if b.kind not in kinds:
                    continue
                for e in a.entities.keys() & b.entities.keys():
                    if a.entities[e]['type'] != 'EQUIPMENT':
                        continue
                    common = dict(source=a.key, source_hash=a.hash, target=b.key, target_hash=b.hash, first=e)
                    if a.key != b.key:
                        paths.append(dict(common, last=e, shape='shared'))
                    else:
                        act = next(k for k,v in a.entities.items() if v['type'] == 'ACTION')
                        paths.append(dict(common, last=act, shape='record'))
        return paths[:limit]


@pytest.fixture
def components(tmp_path):
    client, embedding, src, graph = QdrantClient(':memory:'), Encoder(), Source(), Graph()
    return tmp_path, src, client, embedding, graph


def test_deterministic_incremental_remote_repair_and_deletion(components, monkeypatch):
    root, src, client, embed, graph = components
    first = synchronize(*components)
    assert first['records'] == first['embedded'] == 2
    second = synchronize(*components)
    assert second['embedded'] == 0 and first['generation'] == second['generation']
    points = client.scroll(COLLECTION, limit=10)[0]
    for point in points:
        assert set(point.payload) == {'record', 'partition', 'digest'}
        assert all(re.fullmatch(r'[0-9a-f-]{36}|[0-9a-f]{64}', value) for value in point.payload.values())
    assert '边缘气泡' not in (root / 'active.json').read_text()
    client.delete(COLLECTION, points_selector=models.PointIdsList(points=[points[0].id]))
    assert synchronize(*components)['embedded'] == 1
    src.data['MAINTENANCE'].clear()
    src.data['QUALITY'][0]['description'] = '重新检查' * 320
    saved = graph.reconcile
    def interrupt(*args):
        raise IOError('simulated graph failure')
    monkeypatch.setattr(graph, 'reconcile', interrupt)
    with pytest.raises(IOError):
        synchronize(*components)
    assert json.loads((root / 'active.json').read_text())['generation'] == first['generation']
    monkeypatch.setattr(graph, 'reconcile', saved)
    resumed = synchronize(*components)
    assert resumed['records'] == 1 and resumed['embedded'] == 0 and resumed['deleted_chunks'] == 2
    assert 2 < resumed['chunks'] == client.count(COLLECTION, exact=True).count
    assert all(len(text) + len('passage: ') <= 512 for text in embed.calls)


def test_live_revalidation_scope_and_retracting_paths(components, monkeypatch):
    root, src, client, embed, graph = components
    synchronize(*components)
    search = ExperienceSearch(root, client, embed, src, graph)
    result = search.query('贴合机')
    assert {h['kind'] for h in result['hits']} == {'MAINTENANCE','QUALITY'}
    assert result['stale_record_count'] == 0
    assert 'SHARED_EQUIPMENT' in {p['relation'] for p in result['graph_paths']}
    assert search.query('贴合机', kinds=['QUALITY'])['hits'][0]['source_table'] == 'nonconforming_product'
    assert len(search.query('贴合机', kinds=['QUALITY'])['hits']) == 1
    src.data['QUALITY'][0]['description'] = 'changed after publication'
    result = search.query('贴合机')
    assert [h['kind'] for h in result['hits']] == ['MAINTENANCE']
    assert result['stale_record_count'] == 1
    assert all(p['source_ref'] == p['target_ref'] for p in result['graph_paths'])
    monkeypatch.setattr(graph, 'paths', lambda *args: (_ for _ in ()).throw(IOError()))
    result = search.query('故障')
    assert result['graph_status'] == 'UNAVAILABLE_OR_STALE' and result['hits']
    src.data['MAINTENANCE'].clear()
    assert search.query('贴合机')['hits'] == []


def test_incomplete_snapshot_aborts_before_index_reconciliation(monkeypatch, tmp_path):
    import contextlib
    from mes_knowledge.experience import source
    cursor = SimpleNamespace(execute=lambda *args: None, fetchall=lambda: [{}] * 3)
    history = MySQLHistory()
    monkeypatch.setattr(history, 'transaction', lambda: contextlib.nullcontext(cursor))
    monkeypatch.setattr(source, 'LIMIT', 2)
    with pytest.raises(ValueError, match='exceeds'):
        history.fetch()


def test_vector_and_graph_overlap_and_each_can_supply_validated_evidence(components, monkeypatch):
    root, src, client, embed, graph = components
    synchronize(*components)
    search = ExperienceSearch(root, client, embed, src, graph)
    vector, paths = embed.query, graph.paths
    gate = threading.Barrier(2, timeout=3)
    def concurrent_vector(text):
        gate.wait()
        return vector(text)
    def concurrent_graph(*args):
        gate.wait()
        return paths(*args)
    monkeypatch.setattr(embed, 'query', concurrent_vector)
    monkeypatch.setattr(graph, 'paths', concurrent_graph)
    result = search.query('贴合机')
    assert result['vector_status'] == result['graph_status'] == 'MATCHES'

    monkeypatch.setattr(embed, 'query', lambda _: (_ for _ in ()).throw(OSError()))
    monkeypatch.setattr(graph, 'paths', paths)
    result = search.query('贴合机')
    assert result['vector_status'] == 'UNAVAILABLE' and result['graph_status'] == 'MATCHES'
    assert result['hits'] and all(h['retrieval_source'] == 'graph' for h in result['hits'])
    src.data['QUALITY'].clear()
    src.data['MAINTENANCE'].clear()
    assert search.query('贴合机')['graph_paths'] == []
