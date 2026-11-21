// Neo4j Browser: run statements separately. Set graphRelease from rag/data/graph-active.json.
// Current initial public release; update the parameter after each graph rebuild.
:param graphRelease => 'c3730754df73482f9ae14cf4c7015d09';

// Public conceptual claims only: subject -> source-supported claim -> object.
MATCH p=(s:Entity {graph_release:$graphRelease,domain:'display_reference'})
  -[:SUBJECT_OF]->(c:Claim {graph_release:$graphRelease,domain:'display_reference'})
  -[:OBJECT]->(o:Entity {graph_release:$graphRelease,domain:'display_reference'})
RETURN p LIMIT 50;

// Claim -> source chunk -> original document. PDF page and quote are properties.
MATCH p=(c:Claim {graph_release:$graphRelease,domain:'display_reference'})
  -[:SUPPORTED_BY]->(ch:Chunk {graph_release:$graphRelease,domain:'display_reference'})
  -[:PART_OF]->(d:Document {graph_release:$graphRelease,domain:'display_reference'})
RETURN p LIMIT 50;

// Explicitly SYNTHETIC order/inspection fixture; never real MES records.
// Snapshot ID must come from rag/data/business-graphs/900001.json after import.
:param demoSnapshot => '60bbddbab21c486b9b551a8d4b7c1a81';
MATCH p=(s:BusinessNode {graph_release:$demoSnapshot,scope_order_id:'900001',domain:'business_demo'})
  -[r {graph_release:$demoSnapshot,scope_order_id:'900001',domain:'business_demo'}]->
  (t:BusinessNode {graph_release:$demoSnapshot,scope_order_id:'900001',domain:'business_demo'})
RETURN p LIMIT 50;
