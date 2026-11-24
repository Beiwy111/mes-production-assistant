// From PowerShell at the project root, print the Browser command for the active graph:
// $g = (Get-Content -Raw backend/assistant/rag/data/graph-active.json | ConvertFrom-Json).graph_release_id
// ":param graphRelease => '$g'"
// Run the printed :param in Neo4j Browser, followed by one query at a time.

// Reconcile the same four public node types as the workbench. Historical releases are excluded.
MATCH (n:GraphNode {graph_release:$graphRelease, domain:'display_reference'})
RETURN count(CASE WHEN n:Document THEN 1 END) AS documents,
       count(CASE WHEN n:Chunk THEN 1 END) AS chunks,
       count(CASE WHEN n:Entity THEN 1 END) AS entities,
       count(CASE WHEN n:Claim THEN 1 END) AS claims;

// Every published chunk has a PART_OF link, including chunks without a reviewed semantic claim.
MATCH p=(ch:Chunk {graph_release:$graphRelease, domain:'display_reference'})
        -[:PART_OF]->(d:Document {graph_release:$graphRelease, domain:'display_reference'})
RETURN p;

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
