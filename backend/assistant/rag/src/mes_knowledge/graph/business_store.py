from .store import GraphStore
from .business import EDGE_FIELDS, NODE_FIELDS


class BusinessGraphStore(GraphStore):
    def write_business(self, generation, order, data, domain):
        if domain not in {'business_demo', 'business_reference'}:
            raise ValueError('Business domain required')
        with self.driver.session(database='neo4j') as session:
            session.run('CREATE CONSTRAINT mes_graph_key IF NOT EXISTS FOR (n:GraphNode) REQUIRE n.key IS UNIQUE').consume()
            session.run('CREATE CONSTRAINT mes_graph_release IF NOT EXISTS FOR (n:GraphRelease) REQUIRE n.id IS UNIQUE').consume()
            with session.begin_transaction(timeout=20) as tx:
                tx.run('CREATE (:GraphRelease {id:$g, scope_order_id:$order, domain:$domain, status:"BUILDING"})',
                       g=generation, order=order, domain=domain).consume()
                for node in data['nodes']:
                    if node['kind'] not in NODE_FIELDS:
                        raise ValueError('Unsupported node')
                    props = {k:str(v) for k,v in node['properties'].items()}
                    tx.run('CREATE (n:GraphNode:BusinessNode:'+node['kind']+' {key:$key, graph_release:$g, '
                           'scope_order_id:$order, domain:$domain, source_id:$source_id, source_table:$source_table, '
                           'id:$id, kind:$kind}) SET n += $props',
                           key=generation+':'+order+':'+node['id'], g=generation, order=order, domain=domain,
                           source_id=node['source_id'], source_table=node['source_table'], id=node['id'],
                           kind=node['kind'], props=props).consume()
                for edge in data['edges']:
                    if edge['type'] not in EDGE_FIELDS:
                        raise ValueError('Unsupported relation')
                    tx.run('MATCH (s:BusinessNode {key:$s,scope_order_id:$order,domain:$domain}), '
                           '(t:BusinessNode {key:$t,scope_order_id:$order,domain:$domain}) '
                           'CREATE (s)-[e:'+edge['type']+' {graph_release:$g,scope_order_id:$order,domain:$domain, '
                           'id:$id,source_table:$source_table,source_id:$source_id,reliability:"CONFIRMED"}]->(t)',
                           s=generation+':'+order+':'+edge['source'], t=generation+':'+order+':'+edge['target'],
                           g=generation, order=order, domain=domain, id=edge['id'],
                           source_table=edge['source_table'],source_id=edge['source_id']).consume()
                tx.run('MATCH (g:GraphRelease {id:$g}) SET g.status="READY"',g=generation).consume()
                tx.commit()

    def business_counts(self, generation, order, domain):
        nodes=self._read('MATCH (n:BusinessNode {graph_release:$g,scope_order_id:$order,domain:$domain}) RETURN count(n) AS n',
                         g=generation,order=order,domain=domain)
        edges=self._read('MATCH (s:BusinessNode {graph_release:$g,scope_order_id:$order,domain:$domain})'
                         '-[e {graph_release:$g,scope_order_id:$order,domain:$domain}]->'
                         '(t:BusinessNode {graph_release:$g,scope_order_id:$order,domain:$domain}) RETURN count(e) AS n',
                         g=generation,order=order,domain=domain)
        return {'nodes':nodes[0]['n'],'edges':edges[0]['n']}

    def read_business(self, generation, order, domain, limit):
        if not self.readonly():
            raise ValueError('Graph is in maintenance')
        nodes=self._read('MATCH (n:BusinessNode {graph_release:$g,scope_order_id:$order,domain:$domain}) '
                         'RETURN properties(n) AS props ORDER BY n.id LIMIT $limit',
                         g=generation,order=order,domain=domain,limit=limit+1)
        edges=self._read('MATCH (s:BusinessNode {graph_release:$g,scope_order_id:$order,domain:$domain})'
                         '-[e {graph_release:$g,scope_order_id:$order,domain:$domain}]->'
                         '(t:BusinessNode {graph_release:$g,scope_order_id:$order,domain:$domain}) '
                         'RETURN e.id AS id, s.id AS source, t.id AS target,type(e) AS type,e.source_table AS source_table,'
                         'e.source_id AS source_id,e.reliability AS reliability ORDER BY e.id LIMIT $limit',
                         g=generation,order=order,domain=domain,limit=limit+1)
        if not self.readonly():
            raise ValueError('Graph maintenance began while reading')
        result=[]
        for row in nodes:
            p=row['props']
            result.append({**{k:p[k] for k in ['id','kind','source_table','source_id']},
                           'properties':{k:p[k] for k in NODE_FIELDS[p['kind']][1] if k in p}})
        return {'nodes':result,'edges':edges}
