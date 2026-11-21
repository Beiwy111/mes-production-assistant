"""Fixed parameterized Neo4j operations. Public callers cannot submit Cypher."""
from neo4j import GraphDatabase, Query, READ_ACCESS


class GraphStore:
    def __init__(self, uri, user, password):
        self.driver = GraphDatabase.driver(uri, auth=(user, password), connection_timeout=3,
            connection_acquisition_timeout=3, max_connection_pool_size=4, max_transaction_retry_time=0)

    def close(self):
        self.driver.close()

    def _read(self, statement, **params):
        with self.driver.session(database='neo4j', default_access_mode=READ_ACCESS) as session:
            return session.run(Query(statement, timeout=5), **params).data()

    def readonly(self):
        rows = self._read('SHOW DATABASE neo4j YIELD access RETURN access')
        return len(rows) == 1 and rows[0]['access'] == 'read-only'

    def write_release(self, generation, vector_release, bundle):
        # Constraints are separate maintenance DDL, never run on API startup.
        with self.driver.session(database='neo4j') as session:
            session.run('CREATE CONSTRAINT mes_graph_key IF NOT EXISTS FOR (n:GraphNode) REQUIRE n.key IS UNIQUE').consume()
            session.run('CREATE CONSTRAINT mes_graph_release IF NOT EXISTS FOR (n:GraphRelease) REQUIRE n.id IS UNIQUE').consume()
            with session.begin_transaction(timeout=20) as tx:
                tx.run('CREATE (g:GraphRelease {id:$g, vector_release:$v, domain:"display_reference", status:"BUILDING"})',
                       g=generation, v=vector_release).consume()
                for doc in bundle['documents']:
                    props = {k: doc[k] for k in ('title', 'source_url', 'license', 'sha256')}
                    tx.run('CREATE (d:GraphNode:Document {key:$key, graph_release:$g, document_id:$id, domain:"display_reference"}) '
                           'SET d += $props WITH d MATCH (r:GraphRelease {id:$g}) CREATE (r)-[:CONTAINS]->(d)',
                           key=generation+':doc:'+doc['id'], g=generation, id=doc['id'], props=props).consume()
                for chunk in bundle['chunks']:
                    tx.run('MATCH (d:Document {key:$doc}) CREATE (c:GraphNode:Chunk {key:$key, graph_release:$g, '
                           'vector_release:$v, chunk_id:$id, document_id:$document, document_version:$version, '
                           'page:$page, domain:"display_reference"}) CREATE (c)-[:PART_OF]->(d)',
                           doc=generation+':doc:'+chunk['document_id'], key=generation+':chunk:'+chunk['chunk_id'],
                           g=generation, v=vector_release, id=chunk['chunk_id'], document=chunk['document_id'],
                           version=chunk['document_version'], page=chunk['page_start']).consume()
                for entity in bundle['entities']:
                    # Kind was validated, and is checked again at this dynamic-label boundary.
                    if entity['kind'] not in {'Method', 'DefectConcept'}:
                        raise ValueError('Unexpected graph entity kind')
                    tx.run('CREATE (e:GraphNode:Entity:'+entity['kind']+' {key:$key, graph_release:$g, '
                           'entity_id:$id, name:$name, kind:$kind, aliases:$aliases, domain:"display_reference"})',
                           key=generation+':entity:'+entity['id'], g=generation, **entity).consume()
                for claim in bundle['claims']:
                    props = {k: claim[k] for k in ('id', 'relation', 'statement', 'document_id', 'document_version',
                             'page', 'quote', 'review_status', 'authority_type', 'scope')}
                    tx.run('MATCH (s:Entity {key:$s}), (o:Entity {key:$o}) '
                           'CREATE (c:GraphNode:Claim {key:$key, graph_release:$g, vector_release:$v, domain:"display_reference"}) '
                           'SET c += $props CREATE (s)-[:SUBJECT_OF]->(c)-[:OBJECT]->(o) WITH c '
                           'UNWIND $chunks AS chunkKey MATCH (ch:Chunk {key:chunkKey}) CREATE (c)-[:SUPPORTED_BY]->(ch)',
                           s=generation+':entity:'+claim['subject'], o=generation+':entity:'+claim['object'],
                           key=generation+':claim:'+claim['id'], g=generation, v=vector_release, props=props,
                           chunks=[generation+':chunk:'+i for i in claim['evidence_chunk_ids']]).consume()
                tx.run('MATCH (g:GraphRelease {id:$g}) SET g.status="READY"', g=generation).consume()
                tx.commit()

    def verify_release(self, generation, vector_release):
        rows = self._read('MATCH (g:GraphRelease {id:$g, vector_release:$v, status:"READY", domain:"display_reference"}) '
            'OPTIONAL MATCH (n:GraphNode {graph_release:$g, domain:"display_reference"}) '
            'RETURN count(CASE WHEN n:Chunk THEN 1 END) AS chunks, '
            'count(CASE WHEN n:Claim THEN 1 END) AS claims, count(CASE WHEN n:Entity THEN 1 END) AS entities, '
            'count(CASE WHEN n:Document THEN 1 END) AS documents', g=generation, v=vector_release)
        links = self._read('MATCH (c:Claim {graph_release:$g, vector_release:$v, domain:"display_reference"})'
                          '-[r:SUPPORTED_BY]->(ch:Chunk {graph_release:$g, vector_release:$v, domain:"display_reference"}) '
                          'WHERE c.document_id=ch.document_id AND c.document_version=ch.document_version AND c.page=ch.page '
                          'RETURN count(r) AS n', g=generation, v=vector_release)
        return {**rows[0], 'evidence_links': links[0]['n']} if rows else {}

    def claims(self, generation, vector_release, roots, allowed_documents, max_paths=8):
        if not self.readonly():
            raise RuntimeError('Graph is in a maintenance window')
        # Two bounded logical hops, never an unbounded variable-length path.
        adjacent = self._read('MATCH (s:Entity)-[:SUBJECT_OF]->(c:Claim)-[:OBJECT]->(o:Entity) '
            'WHERE c.graph_release=$g AND c.vector_release=$v AND c.domain="display_reference" '
            'AND c.document_id IN $docs AND (s.entity_id IN $roots OR o.entity_id IN $roots) '
            'RETURN DISTINCT s.entity_id AS subject, o.entity_id AS object LIMIT 100',
            g=generation, v=vector_release, roots=roots, docs=allowed_documents)
        neighbors = sorted(set(roots) | {r[k] for r in adjacent for k in ('subject', 'object')})
        rows = self._read('MATCH (r:GraphRelease {id:$g, vector_release:$v, status:"READY", domain:"display_reference"}) '
            'MATCH (s:Entity {graph_release:$g})-[:SUBJECT_OF]->(c:Claim {graph_release:$g, vector_release:$v, '
            'domain:"display_reference", review_status:"SOURCE_VERIFIED"})-[:OBJECT]->(o:Entity {graph_release:$g}) '
            'WHERE c.document_id IN $docs AND (s.entity_id IN $near OR o.entity_id IN $near) '
            'MATCH (c)-[:SUPPORTED_BY]->(ch:Chunk {graph_release:$g, vector_release:$v, domain:"display_reference"}) '
            'WHERE ch.document_id=c.document_id AND ch.document_version=c.document_version AND ch.page=c.page '
            'WITH s,o,c,collect(ch.chunk_id) AS evidence_chunk_ids, '
            'CASE WHEN s.entity_id IN $roots OR o.entity_id IN $roots THEN 1 ELSE 2 END AS hops '
            'RETURN c.id AS claim_id, c.statement AS statement, c.relation AS relation, c.quote AS quote, '
            'c.document_id AS document_id, c.document_version AS document_version, c.page AS page, '
            's.entity_id AS subject_id, s.name AS subject_name, o.entity_id AS object_id, o.name AS object_name, '
            'evidence_chunk_ids,hops ORDER BY hops,claim_id LIMIT $limit',
            g=generation, v=vector_release, docs=allowed_documents, roots=roots, near=neighbors, limit=max_paths+1)
        if not self.readonly():
            raise RuntimeError('Graph maintenance began while reading')
        return rows
