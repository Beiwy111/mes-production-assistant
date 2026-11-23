"""Reconcile the live public visualization with the active Qdrant points and Neo4j evidence links."""
import json
from contextlib import closing

import httpx
from qdrant_client import QdrantClient

from mes_knowledge.cli import settings_from_file
from mes_knowledge.config import RAG_ROOT
from mes_knowledge.graph.config import graph_settings, open_graph
from mes_knowledge.retrieval import KnowledgeStore


def verify():
    settings = settings_from_file(RAG_ROOT.parent / 'deploy/.env.knowledge.local')
    root = settings.data_dir
    manifest = json.loads((root / 'active.json').read_text('utf-8'))
    graph_release = json.loads((root / 'graph-active.json').read_text('utf-8'))
    release = manifest['release_id']
    assert graph_release['vector_release_id'] == release, 'Public graph awaits publication for the active index'
    with closing(QdrantClient(url=settings.qdrant_url, api_key=settings.qdrant_read_key.get_secret_value(),
                              trust_env=False, check_compatibility=False)) as client:
        store = KnowledgeStore(root, client, embedding=None)
        offset = None
        points = {}
        while True:
            rows, offset = client.scroll(manifest['collection'], scroll_filter=store._filter(manifest),
                                         offset=offset, limit=100, with_vectors=False, with_payload=True)
            for row in rows:
                hit = row.payload
                points[(hit['document_id'], hit['chunk_id'])] = hit['text']
            if offset is None:
                break
    allowed = sorted({doc for doc, _ in points})
    generation = graph_release['graph_release_id']
    with closing(open_graph(graph_settings())) as graph:
        assert graph.readonly(), 'Graph is in maintenance'
        with graph.driver.session(database='neo4j', default_access_mode='READ') as session:
            chunks = session.run(
                'MATCH (ch:Chunk {graph_release:$g,vector_release:$v,domain:"display_reference"})-[:PART_OF]->'
                '(d:Document {graph_release:$g,domain:"display_reference"}) WHERE d.document_id IN $docs '
                'RETURN d.document_id AS doc, ch.chunk_id AS chunk', g=generation, v=release, docs=allowed)
            assert {(r['doc'], r['chunk']) for r in chunks} == set(points), 'Neo4j and Qdrant chunk IDs differ'
            claims = session.run(
                'MATCH (s:Entity {graph_release:$g})-[:SUBJECT_OF]->(c:Claim {graph_release:$g,'
                'domain:"display_reference",review_status:"SOURCE_VERIFIED"})-[:OBJECT]->(o:Entity {graph_release:$g}) '
                'MATCH (c)-[:SUPPORTED_BY]->(ch:Chunk {graph_release:$g,vector_release:$v}) '
                'WHERE c.document_id IN $docs RETURN s.entity_id AS s,c.id AS id,o.entity_id AS o,'
                'ch.document_id AS doc,ch.chunk_id AS chunk', g=generation, v=release, docs=allowed)
            expected_links = {(r['s'], r['id'], r['o'], f"{release}:{r['doc']}:{r['chunk']}") for r in claims}

    headers = {'Authorization': 'Bearer ' + settings.api_key.get_secret_value()}
    offset, pages, shown, links = 0, 0, {}, set()
    with httpx.Client(base_url='http://127.0.0.1:18081', headers=headers, trust_env=False, timeout=30) as api:
        while offset is not None:
            response = api.get('/internal/knowledge/catalog', params={'offset':offset,'limit':100,'release_id':release})
            response.raise_for_status()
            page = response.json()
            assert page['release_id'] == release and page['graph_release_id'] == generation
            assert page['counts']['documents'] == len(allowed) and page['counts']['chunks'] == len(points)
            for hit in page['hits']:
                key = hit['document_id'], hit['chunk_id']
                assert key not in shown, 'Repeated slice on different pages'
                shown[key] = hit['text']
            for path in page['graph_paths']:
                links.update((path['subject_id'],path['claim_id'],path['object_id'],ref) for ref in path['evidence_refs'])
            following = page['next_offset']
            assert following is None or following > offset, 'Catalog cursor must advance'
            offset = following
            pages += 1
    assert shown == points, 'Visual catalog differs from Qdrant source text or IDs'
    assert links == expected_links, 'Visual catalog differs from Neo4j semantic relationships or their evidence'
    return {'release':release,'graph_release':generation,'pages':pages,'documents':len(allowed),'chunks':len(shown),
            'claims':len({row[1] for row in links}), 'evidence_links':len(links)}


if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
