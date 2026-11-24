export function scopeGraphByDocument(graph, documentId) {
  if (!documentId) return graph
  const document = graph.nodes.find(node => node.type === 'document' && node.id === documentId)
  if (!document) return { nodes: [], edges: [] }

  const references = new Set(document.evidence.map(hit => hit.evidence_ref))
  const nodes = []
  for (const node of graph.nodes) {
    const evidence = node.evidence.filter(hit => references.has(hit.evidence_ref))
    if (evidence.length) nodes.push({ ...node, evidence, fileTypes: document.fileTypes })
  }
  const ids = new Set(nodes.map(node => node.id))
  return { nodes, edges: graph.edges.filter(edge => ids.has(edge.from) && ids.has(edge.to)) }
}
