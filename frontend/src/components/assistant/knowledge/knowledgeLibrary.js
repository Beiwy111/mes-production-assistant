import { uploadedKnowledgeGraph } from '../../../utils/workbenchGraph.js'
import { ingestionRequest } from './ingestion/ingestionApi.js'

export function jobEvidence(job, publishedOnly = true) {
  if (publishedOnly && job.status !== 'completed') throw new Error('资料尚未完成入库。')
  const chunks = job.chunks || []
  if (!job.id || !Array.isArray(chunks) || chunks.some(c => !c?.id || typeof c.text !== 'string') ||
      new Set(chunks.map(c => c.id)).size !== chunks.length ||
      (publishedOnly && (!Number.isInteger(job.counts?.chunks) || job.counts.chunks !== chunks.length))) {
    throw new Error('文件的解析片段未通过完整性核验，请重新加载。')
  }
  const entities = job.entities || [], relations = job.relations || []
  if (!Array.isArray(entities) || !Array.isArray(relations) || (publishedOnly &&
      (entities.length !== job.counts.entities || relations.length !== job.counts.relations))) {
    throw new Error('文件的实体关系未通过完整性核验，请重新加载。')
  }
  return { hits:chunks.map(c => ({ ...c, jobId:job.id, filename:job.filename, chunkId:c.id })),
    entities:entities.map(e => ({ ...e, jobId:job.id })), relations:relations.map(r => ({ ...r, jobId:job.id })) }
}

export function mergeKnowledgeGraphs(...graphs) {
  const nodes = new Map(), edges = new Map()
  for (const graph of graphs) {
    for (const node of graph?.nodes || []) nodes.set(node.id, node)
    for (const edge of graph?.edges || []) edges.set(JSON.stringify([edge.from,edge.to,edge.label]),edge)
  }
  return { nodes:[...nodes.values()], edges:[...edges.values()].filter(e => nodes.has(e.from) && nodes.has(e.to)) }
}

// Use the existing admin-authorized endpoint; never fetch private records for other users.
export async function readUploadedLibrary(token, signal, request = ingestionRequest) {
  const { jobs } = await request(token, '/jobs', 'GET', signal)
  if (!Array.isArray(jobs)) throw new Error('入库目录格式无效。')
  const completed = jobs.filter(job => job.status === 'completed')
  const graphs = new Array(completed.length)
  let cursor = 0
  await Promise.all(Array.from({ length:Math.min(3, completed.length) }, async () => {
    while (cursor < completed.length) {
      if (signal?.aborted) throw new DOMException('已取消', 'AbortError')
      const index = cursor++, id = completed[index].id
      const job = await request(token, '/jobs/' + encodeURIComponent(id), 'GET', signal)
      if (job.id !== id) throw new Error('入库资料编号不一致，请刷新。')
      graphs[index] = uploadedKnowledgeGraph(jobEvidence(job))
    }
  }))
  return { graph:mergeKnowledgeGraphs(...graphs), jobs:completed }
}
