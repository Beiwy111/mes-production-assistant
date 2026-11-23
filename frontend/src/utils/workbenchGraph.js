const relations = { DETECTS: '检测', USES: '使用', CLASSIFIES: '分类' }
export const nodeTypes = {
  document: { label: '文档', color: '#2457E6', background: '#E9EFFF', symbol: 'roundRect' },
  chunk: { label: '索引片段', color: '#53657A', background: '#EFF3F7', symbol: 'rect' },
  vector: { label: '向量片段', color: '#556293', background: '#EEF0F8', symbol: 'diamond' },
  entity: { label: '实体', color: '#0F7779', background: '#E4F4F2', symbol: 'circle' },
  concept: { label: '概念', color: '#925038', background: '#F8EBE5', symbol: 'roundRect' },
  relation: { label: '关系', color: '#95621F', background: '#FAEFDB', symbol: 'diamond' },
  tag: { label: '标签', color: '#3F6E56', background: '#EAF2EC', symbol: 'triangle' },
}

function publicFileType(hit) {
  const extension = hit.url?.split(/[?#]/)[0].match(/\.(pdf|docx?|pptx?|xlsx?|csv|txt|md|jpe?g|png)$/i)?.[1]
  return extension?.toUpperCase() || (hit.url ? '网页 / 链接' : '未知格式')
}

function excerptLabel(text) {
  const characters = Array.from(text.replace(/\s+/g, ' ').trim())
  return characters.length > 28 ? characters.slice(0, 28).join('') + '…' : characters.join('')
}

export function knowledgeGraph(bundles) {
  const nodes = new Map(), edges = new Map()
  const add = (id, fields, evidence) => {
    if (!nodes.has(id)) nodes.set(id, { id, ...fields, evidence: new Map() })
    for (const hit of evidence) nodes.get(id).evidence.set(hit.evidence_ref, hit)
    return id
  }
  const link = (from, to, label) => edges.set(JSON.stringify([from,to,label]), { from,to,label })
  for (const bundle of bundles) {
    const refs = new Map()
    for (const hit of bundle.hits) {
      const metadata = { '资料编号': hit.document_id, '文档版本': hit.document_version, '发布版本': hit.release_id, '许可': hit.license }
      const doc = add('doc:' + hit.document_id + ':' + hit.document_version,
        { label: hit.title, kind: '资料', type: 'document', detail: hit.title, metadata, url: hit.url }, [hit])
      const location = hit.location_type === 'document' ? '' : '第 ' + hit.page_start + ' 页 · '
      const chunk = add('chunk:' + hit.evidence_ref, { label: location + excerptLabel(hit.text), kind: '索引片段',
        type: 'chunk', detail: hit.text, url: hit.url, metadata: { ...metadata, 'Chunk ID': hit.chunk_id,
          ...(Number.isFinite(hit.score) ? { '检索得分': hit.score } : {}) } }, [hit])
      refs.set(hit.evidence_ref, hit)
      link(doc, chunk, '包含')
    }
    for (const path of bundle.paths) {
      if (!path.evidence_refs.every(ref => refs.has(ref))) continue
      if (!path.claim_id || !path.subject_id || !path.object_id) continue
      const evidence = path.evidence_refs.map(ref => refs.get(ref))
      const metadata = { '图谱版本': path.graph_release_id }
      const entity = (id, name) => add('entity:' + path.graph_release_id + ':' + id, {
        label: name || id, kind: '图谱实体', type: 'entity', detail: name || id, metadata: { ...metadata, '实体编号': id } }, evidence)
      const claim = add('claim:' + path.graph_release_id + ':' + path.claim_id, { label: relations[path.relation], kind: '研究关系',
        type: 'relation', detail: [path.statement, path.quote].filter(Boolean).join('\n\n'), provenance:'公开来源已核验',
        metadata: { ...metadata, '关系编号': path.claim_id, '审核状态': '来源引文已核验' } }, evidence)
      link(entity(path.subject_id, path.subject_name), claim, '主语')
      link(claim, entity(path.object_id, path.object_name), '宾语')
      for (const ref of path.evidence_refs) link('chunk:' + ref, claim, '支持')
    }
  }
  return { nodes: [...nodes.values()].map(node => {
    const evidence = [...node.evidence.values()]
    return { ...node, fileTypes: [...new Set(evidence.map(publicFileType))], evidence }
  }), edges: [...edges.values()] }
}

export function filterGraph(graph, { mode = 'hybrid', type = '', file = '', hidden = [] } = {}) {
  const modeTypes = mode === 'rag' ? ['document', 'chunk', 'vector', 'tag'] :
    mode === 'graph' ? ['entity', 'relation', 'concept', 'tag'] : null
  const nodes = graph.nodes.filter(n => (!modeTypes || modeTypes.includes(n.type)) &&
    (!type || n.type === type) && (!file || n.fileTypes?.includes(file)))
  const ids = new Set(nodes.map(n => n.id))
  return { nodes, edges: graph.edges.filter(e => ids.has(e.from) && ids.has(e.to) && !hidden.includes(e.label)) }
}

// Uploaded evidence stays scoped to its job, even when two files have the same chunk/entity IDs.
export function uploadedKnowledgeGraph({ hits = [], entities = [], relations: links = [] } = {}) {
  const nodes = new Map(), edges = new Map(), chunks = new Map()
  const idOf = (...parts) => JSON.stringify(parts)
  function add(id, type, label, detail, metadata, evidence) {
    if (!nodes.has(id)) nodes.set(id, { id, type, label, detail, metadata, evidence: new Map() })
    const node = nodes.get(id)
    for (const hit of evidence) node.evidence.set(hit.evidence_ref, hit)
    return id
  }
  function link(from, to, label) { edges.set(idOf(from, to, label), { from, to, label }) }
  for (const hit of hits) {
    if (!hit?.jobId || !hit.chunkId || typeof hit.text !== 'string') continue
    const chunk = idOf('chunk', hit.jobId, hit.chunkId)
    const location = typeof hit.location === 'string' ? hit.location : ''
    const position = location || excerptLabel(hit.text)
    const entry = { ...hit, title: hit.filename, chunk_id: hit.chunkId, location, evidence_ref: chunk }
    chunks.set(chunk, entry)
    const metadata = { '任务编号': hit.jobId, '文件名': hit.filename }
    const doc = add(idOf('doc', hit.jobId), 'document', hit.filename, hit.filename, metadata, [entry])
    add(chunk, 'chunk', position, hit.text, { ...hit.metadata, ...metadata, 'Chunk ID': hit.chunkId, '检索得分': hit.score }, [entry])
    link(doc, chunk, '包含')
    const headings = Array.isArray(hit.metadata?.headings) ? hit.metadata.headings : []
    for (const heading of headings) {
      if (typeof heading === 'string' && heading.trim()) link(chunk,
        add(idOf('concept', hit.jobId, heading), 'concept', heading, heading, metadata, [entry]), '章节')
    }
    const tags = new Set(Array.isArray(hit.metadata?.tags) ? hit.metadata.tags : [])
    if (hit.metadata?.ocr) tags.add('OCR')
    if (hit.metadata?.table) tags.add('表格')
    for (const tag of tags) {
      if (typeof tag === 'string' && tag.trim()) link(chunk,
        add(idOf('tag', hit.jobId, tag), 'tag', tag, tag, metadata, [entry]), '标注')
    }
  }
  for (const entity of entities) {
    if (!entity?.id || !Array.isArray(entity.chunkIds)) continue
    const evidence = entity.chunkIds.map(id => chunks.get(idOf('chunk', entity.jobId, id))).filter(Boolean)
    if (!evidence.length) continue
    const id = add(idOf('entity', entity.jobId, entity.id), 'entity', entity.name || entity.id, entity.name || entity.id,
      { '实体编号': entity.id }, evidence)
    for (const hit of evidence) link(hit.evidence_ref, id, '提及')
  }
  for (const relation of links) {
    const hit = chunks.get(idOf('chunk', relation.jobId, relation.chunkId))
    const subject = idOf('entity', relation.jobId, relation.source), object = idOf('entity', relation.jobId, relation.target)
    if (!hit || !nodes.has(subject) || !nodes.has(object) || !relation.type || !relation.quote || !hit.text.includes(relation.quote)) continue
    const relationName = relation.type === 'CO_OCCURS' ? '共现' : relation.type
    const method = { 'rule-candidate':'规则抽取', 'co-occurrence':'同句共现' }[relation.extractionMethod]
    const review = relation.reviewStatus === 'UNREVIEWED' ? '待复核' : ''
    const id = add(idOf('relation', relation.jobId, relation.source, relation.type, relation.target, relation.chunkId),
      'relation', relationName, relation.quote, { '抽取关系': relation.type, '抽取方式':method, '审核状态':review }, [hit])
    nodes.get(id).provenance = [method === '规则抽取' ? '自动抽取' : method, review].filter(Boolean).join(' · ')
    link(subject, id, '主语')
    link(id, object, '宾语')
    link(hit.evidence_ref, id, '支持')
  }
  const chunkOrder = new Map([...chunks.keys()].map((key,index) => [key,index]))
  const ordered = [...nodes.values()].sort((a,b) =>
    chunkOrder.get(a.evidence.keys().next().value) - chunkOrder.get(b.evidence.keys().next().value))
  return { nodes: ordered.map(node => {
    const evidence = [...node.evidence.values()]
    return { ...node, evidence, fileTypes: [...new Set(evidence.map(hit => hit.filename?.match(/\.([^.]+)$/)?.[1]?.toUpperCase() || '未知格式'))] }
  }), edges: [...edges.values()] }
}

// A page keeps its own records plus connecting nodes across page boundaries.
// Focusing also brings in nearby nodes without rendering a whole large document at once.
export function graphPage(graph, page, limit, selected, depth, path) {
  if (graph.nodes.length <= limit) return graph
  const records = graph.nodes.slice(page * limit, (page+1) * limit)
  const visible = new Set(records.map(node => node.id))
  const adjacent = new Map(graph.nodes.map(node=>[node.id,[]]))
  for (const edge of graph.edges) {
    adjacent.get(edge.from)?.push(edge.to)
    adjacent.get(edge.to)?.push(edge.from)
  }
  for (const node of records) {
    const connected = adjacent.get(node.id)
    if (connected.length && !connected.some(id=>visible.has(id))) visible.add(connected[0])
  }
  if (selected && adjacent.has(selected)) {
    const nearby = new Set([selected])
    let layer = [selected]
    for (let hop=0; hop<depth && nearby.size<limit; hop++) {
      const next = []
      for (const id of layer) {
        for (const neighbor of adjacent.get(id)) {
          if (!nearby.has(neighbor)) { nearby.add(neighbor); next.push(neighbor) }
          if (nearby.size>=limit) break
        }
        if (nearby.size>=limit) break
      }
      layer=next
    }
    for (const id of nearby) visible.add(id)
  }
  for (const id of path) visible.add(id)
  return { nodes:graph.nodes.filter(node=>visible.has(node.id)), edges:graph.edges.filter(e=>visible.has(e.from) && visible.has(e.to)) }
}

export function neighbors(graph, id) {
  const byId = new Map(graph.nodes.map(node => [node.id, node]))
  return graph.edges.filter(edge => edge.from === id || edge.to === id).map(edge => {
    const outgoing = edge.from === id
    return { node: byId.get(outgoing ? edge.to : edge.from), outgoing, edge }
  }).filter(item => item.node)
}

export function shortestPath(graph, start, end) {
  const adjacent = new Map(graph.nodes.map(n => [n.id, []]))
  if (!adjacent.has(start) || !adjacent.has(end)) return []
  for (const edge of graph.edges) {
    adjacent.get(edge.from)?.push(edge.to)
    adjacent.get(edge.to)?.push(edge.from)
  }
  const previous = new Map([[start,null]]), queue = [start]
  for (let index=0; index<queue.length; index++) {
    const id = queue[index]
    if (id === end) {
      const path = []
      for (let step=end; step !== null; step=previous.get(step)) path.push(step)
      return path.reverse()
    }
    for (const next of adjacent.get(id)) {
      if (!previous.has(next)) { previous.set(next,id); queue.push(next) }
    }
  }
  return []
}

// Traverse undirected adjacency for exploring references in either direction.
export function neighborhood(graph, start, depth = 1) {
  const adjacent = new Map(graph.nodes.map(n => [n.id, []]))
  if (!adjacent.has(start)) return new Set()
  for (const edge of graph.edges) {
    adjacent.get(edge.from)?.push(edge.to)
    adjacent.get(edge.to)?.push(edge.from)
  }
  const seen = new Set([start])
  let layer = [start]
  for (let step = 0; step < depth; step++) {
    const next = []
    for (const id of layer) for (const neighbor of adjacent.get(id) || []) {
      if (!seen.has(neighbor)) { seen.add(neighbor); next.push(neighbor) }
    }
    layer = next
  }
  return seen
}

// Each layer follows the actual incoming edges. Shared destinations appear once.
export function layoutGraph(nodes, edges) {
  const positions = new Map(), remaining = new Set(nodes.map(n => n.id))
  const incoming = new Map(nodes.map(n => [n.id, []]))
  const links = edges.filter(e => incoming.has(e.from) && incoming.has(e.to))
  for (const e of links) incoming.get(e.to).push(e.from)
  const rows = []
  while (remaining.size) {
    const row = [...remaining].filter(id => incoming.get(id).every(from => !remaining.has(from)))
    if (!row.length) row.push(remaining.values().next().value)
    row.forEach(id => remaining.delete(id))
    rows.push(row)
  }
  const width = Math.max(1, ...rows.map(r => r.length)) * 204 + 32
  rows.forEach((row, level) => row.forEach((id, i) => positions.set(id, {
    x: (width - row.length * 204) / 2 + i * 204 + 10, y: 18 + level * 106,
  })))
  return { width, height: rows.length * 106 + 12, nodes: nodes.map(n => ({ ...n, ...positions.get(n.id) })), links: links.map(e => {
    const a = positions.get(e.from), b = positions.get(e.to)
    const x1 = a.x + 92, y1 = a.y + 60, x2 = b.x + 92, y2 = b.y
    const mid = (y1 + y2) / 2
    return { ...e, path: 'M '+x1+' '+y1+' C '+x1+' '+mid+', '+x2+' '+mid+', '+x2+' '+y2 }
  }) }
}
