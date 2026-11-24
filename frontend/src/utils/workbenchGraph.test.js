import test from 'node:test'
import assert from 'node:assert/strict'
import { filterGraph, graphPage, neighbors, neighborhood, shortestPath, uploadedKnowledgeGraph } from './workbenchGraph.js'

function fixture() {
  const hits = ['job1','job2'].map((jobId,i) => ({
    jobId, chunkId:'part', filename:i ? 'second.pdf' : 'guide.docx',
    text:'PANEL requires GLASS.', location:'page:1', metadata:{ headings:['Assembly'], tags:['QA'] },
  }))
  const entities = hits.flatMap(({ jobId }) => ['a','b'].map((id,i) => ({ id, jobId, name:i ? 'GLASS':'PANEL', chunkIds:['part'] })))
  const relations = hits.map(({ jobId, text }) => ({ source:'a',target:'b',type:'requires',chunkId:'part',quote:text,jobId,
    reviewStatus:'UNREVIEWED',extractionMethod:'rule-candidate' }))
  return uploadedKnowledgeGraph({ hits, entities, relations:[...relations,{ ...relations[0], quote:'Absent evidence', type:'other' }] })
}
test('upload graph preserves provenance, node types and job isolation for shared IDs', () => {
  const graph=fixture()
  assert.equal(new Set(graph.nodes.map(n=>n.type)).size,6)
  assert.equal(graph.nodes.filter(n=>n.type==='chunk').length,2)
  assert.equal(graph.nodes.filter(n=>n.type==='vector').length,0)
  assert.equal(graph.nodes.filter(n=>n.type==='relation').length,2)
  assert.ok(graph.nodes.filter(n=>n.type==='relation').every(n=>n.provenance==='自动抽取 · 待复核'))
  for (const node of graph.nodes) assert.equal(new Set(node.evidence.map(hit=>hit.jobId)).size,1)
  const first=filterGraph(graph,{file:'DOCX'})
  assert.equal(first.nodes.length,7)
  assert.ok(first.nodes.every(n=>n.evidence.every(hit=>hit.filename==='guide.docx')))
  const docs=graph.nodes.filter(n=>n.type==='document')
  assert.deepEqual(shortestPath(graph,docs[0].id,docs[1].id),[])
})
test('pages keep off-page connecting evidence and focus can reveal neighbors on another page', () => {
  const graph={ nodes:['a','b','c','d','e','f'].map(id=>({id})),
    edges:[{from:'a',to:'c'},{from:'a',to:'d'},{from:'b',to:'e'},{from:'b',to:'f'}] }
  const page=graphPage(graph,1,2,'',1,[])
  assert.deepEqual(page.nodes.map(n=>n.id),['a','c','d'])
  const focused=graphPage(graph,1,2,'b',1,[])
  assert.ok(focused.nodes.some(n=>n.id==='e'))
  assert.ok(focused.edges.some(e=>e.from==='b' && e.to==='e'))
})
test('modes, hidden relationships and neighbor direction stay consistent', () => {
  const graph=filterGraph(fixture(),{file:'DOCX'})
  const chunk=graph.nodes.find(n=>n.type==='chunk'), doc=graph.nodes.find(n=>n.type==='document')
  const concept=graph.nodes.find(n=>n.type==='concept')
  const rag=filterGraph(graph,{mode:'rag',hidden:['包含']})
  assert.ok(rag.nodes.every(n=>!['relation','entity'].includes(n.type)))
  assert.deepEqual(shortestPath(rag,doc.id,chunk.id),[])
  assert.deepEqual(shortestPath(graph,doc.id,concept.id),[doc.id,chunk.id,concept.id])
  assert.ok(!neighborhood(graph,doc.id,1).has(concept.id))
  assert.ok(neighborhood(graph,doc.id,2).has(concept.id))
  assert.equal(neighbors(graph,chunk.id).find(item=>item.node.id===doc.id).outgoing,false)
  const graphOnly=filterGraph(graph,{mode:'graph'})
  assert.ok(graphOnly.edges.every(e=>graphOnly.nodes.some(n=>n.id===e.from) && graphOnly.nodes.some(n=>n.id===e.to)))
})
