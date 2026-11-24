"""Dedicated Neo4j labels, typed edges and fixed two-hop reads for MES history."""
from ..graph.store import GraphStore
from .source import RELATIONS


class ExperienceGraph:
    def __init__(self, graph: GraphStore):
        self.graph = graph

    def reconcile(self, records, generation):
        if self.graph.readonly():
            raise RuntimeError('History synchronization requires the managed graph maintenance window')
        with self.graph.driver.session(database='neo4j') as session:
            for label in ('ExperienceCase', 'ExperienceEntity', 'ExperienceIndex'):
                session.run(f'CREATE CONSTRAINT mes_{label}_key IF NOT EXISTS '
                            f'FOR (n:{label}) REQUIRE n.key IS UNIQUE').consume()
            with session.begin_transaction(timeout=90) as tx:
                existing = {r['key']: (r['hash'], r['kind'], sorted(r['edges'])) for r in tx.run(
                    'MATCH (c:ExperienceCase) OPTIONAL MATCH (c)-[rel]->(e:ExperienceEntity) '
                    'RETURN c.key AS key,c.hash AS hash,c.kind AS kind,'
                    'collect(e.key + ":" + type(rel) + ":" + e.type) AS edges')}
                changed = []
                for r in records.values():
                    edges = sorted(k + ':' + RELATIONS[v['type']] + ':' + v['type'] for k,v in r.entities.items())
                    if existing.get(r.key) != (r.hash, r.kind, edges):
                        changed.append(r)
                removed = sorted(existing.keys() - records.keys())
                tx.run('UNWIND $keys AS k MATCH (n:ExperienceCase {key:k}) DETACH DELETE n',
                       keys=removed).consume()
                tx.run('UNWIND $cases AS row MERGE (n:ExperienceCase {key:row.key}) '
                       'SET n.hash=row.hash,n.kind=row.kind WITH n OPTIONAL MATCH (n)-[old]->(:ExperienceEntity) '
                       'DELETE old', cases=[{'key': r.key, 'hash': r.hash, 'kind': r.kind} for r in changed]).consume()
                for typ, relationship in RELATIONS.items():
                    rows = [{'case': r.key, 'entity': k} for r in changed
                            for k, v in r.entities.items() if v['type'] == typ]
                    tx.run('UNWIND $rows AS row MATCH (n:ExperienceCase {key:row.case}) '
                           'MERGE (e:ExperienceEntity {key:row.entity}) SET e.type=$typ '
                           f'MERGE (n)-[:{relationship}]->(e)', rows=rows, typ=typ).consume()
                tx.run('MATCH (e:ExperienceEntity) WHERE NOT (e)--() DELETE e').consume()
                tx.run('MERGE (m:ExperienceIndex {key:"private-history-v1"}) SET m.generation=$g',
                       g=generation).consume()
                tx.commit()
        return {'graph_changed': len(changed), 'graph_removed': len(removed)}

    def paths(self, generation, seeds, kinds, limit):
        if not self.graph.readonly():
            raise RuntimeError('Graph maintenance in progress')
        receipt = self.graph._read('MATCH (m:ExperienceIndex {key:"private-history-v1"}) RETURN m.generation AS g')
        if receipt != [{'g': generation}]:
            raise ValueError('Stale experience graph')
        # Both arms require the same generation in the query transaction. A shared
        # action alone is insufficient evidence of similar equipment/work/process/defect.
        return self.graph._read("""
            MATCH (:ExperienceIndex {key:'private-history-v1',generation:$generation})
            UNWIND $seeds AS sk
            MATCH (a:ExperienceCase {key:sk}) WHERE a.kind IN $kinds
            CALL (a) {
              MATCH (a)-[r]->(e:ExperienceEntity)<-[s]-(b:ExperienceCase)
              WHERE a <> b AND b.kind IN $kinds AND type(r)=type(s) AND e.type <> 'ACTION'
              RETURN b,e.key AS first,e.key AS last,'shared' AS shape
              ORDER BY b.key,first LIMIT 6
              UNION ALL
              MATCH (e:ExperienceEntity)<-[]-(a)-[:RECORDED_ACTION]->(f:ExperienceEntity)
              WHERE e.type IN ['EQUIPMENT','WORK_ORDER','PROCESS','DEFECT','CAUSE']
              RETURN a AS b,e.key AS first,f.key AS last,'record' AS shape
              ORDER BY first LIMIT 4
            }
            RETURN a.key AS source,a.hash AS source_hash,b.key AS target,b.hash AS target_hash,
                   first,last,shape
            ORDER BY sk,shape DESC,b.key,first LIMIT $limit
            """, generation=generation, seeds=seeds, kinds=kinds, limit=limit)
