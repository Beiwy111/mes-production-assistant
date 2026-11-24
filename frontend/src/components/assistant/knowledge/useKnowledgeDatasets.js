import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { knowledgeEvidence, verifiedPublicBundle } from '@/utils/workbenchEvidence'
import { knowledgeGraph, uploadedKnowledgeGraph } from '@/utils/workbenchGraph'
import { ingestionRequest } from './ingestion/ingestionApi'
import { readPublicCatalog } from './publicCatalog'

const base = '/api/assistant-v2/knowledge'
const emptyGraph = () => ({ nodes:[], edges:[] })
const complete = job => String(job.status).toLowerCase() === 'completed'
async function post(token, path, data, signal) {
  const response = await fetch(base + path, { method:'POST', signal,
    headers:{ Authorization:'Bearer ' + token, 'Content-Type':'application/json' }, body:JSON.stringify(data) })
  if (response.status === 401) throw new Error('登录已过期，请重新登录。')
  if (!response.ok) throw new Error('知识服务请求失败（' + response.status + '）。')
  const result = await response.json()
  if (result.code !== 200 || !result.data) throw new Error(result.message || '知识服务暂不可用，请重试。')
  return result.data
}

export function useKnowledgeDatasets(props) {
  const turn = computed(() => props.message?.evidenceInvalidated || props.message?.workflow?.invalidated ? [] : knowledgeEvidence(props.message?.result))
  const source = ref(props.canIngest && props.jobId ? 'uploads' : 'catalog')
  const publicQuery = ref(''), uploadQuery = ref(''), jobId = ref(props.canIngest ? props.jobId || '' : '')
  const query = computed({ get:() => source.value === 'uploads' ? uploadQuery.value : publicQuery.value,
    set:value => { if (source.value === 'uploads') uploadQuery.value = value; else publicQuery.value = value } })
  const publicResult = shallowRef(null), uploadGraph = shallowRef(emptyGraph()), jobs = shallowRef([])
  const catalog = shallowRef(null), documentId = ref('')
  const loading = ref(false), error = ref(''), jobsError = ref(''), lastQuery = ref('')
  let active, listing
  const documents = computed(() => {
    const docs = new Map()
    for (const hit of catalog.value?.hits || []) {
      if (!docs.has(hit.document_id)) docs.set(hit.document_id, { id:hit.document_id, title:hit.title, chunks:0 })
      docs.get(hit.document_id).chunks++
    }
    return [...docs.values()]
  })
  const graph = computed(() => {
    if (source.value === 'uploads') return uploadGraph.value
    if (source.value === 'turn') return knowledgeGraph(turn.value)
    if (source.value === 'search') return knowledgeGraph(publicResult.value ? [publicResult.value] : [])
    if (!catalog.value) return emptyGraph()
    const { hits, paths } = catalog.value
    const data = documentId.value ? { hits:hits.filter(hit => hit.document_id === documentId.value),
      paths:paths.filter(path => path.document_id === documentId.value) } : { hits, paths }
    const result = knowledgeGraph([data])
    // Page through an evidence chain together, so the first view contains readable, connected groups.
    const order = new Map()
    function place(id) { if (!order.has(id)) order.set(id, order.size) }
    for (const path of data.paths) {
      place('doc:' + path.document_id + ':' + path.document_version)
      for (const ref of path.evidence_refs) place('chunk:' + ref)
      place('entity:' + path.graph_release_id + ':' + path.subject_id)
      place('claim:' + path.graph_release_id + ':' + path.claim_id)
      place('entity:' + path.graph_release_id + ':' + path.object_id)
    }
    for (const node of result.nodes) place(node.id)
    result.nodes.sort((left, right) => order.get(left.id) - order.get(right.id))
    return result
  })
  const notice = computed(() => {
    if (source.value === 'catalog') {
      if (!catalog.value) return ''
      const data = catalog.value
      return ['公开库版本 ' + data.release_id,
        data.graph_status === 'READY' ? '已核验研究关系 ' + new Set(data.paths.map(path => path.claim_id)).size + ' 条' : '实体关系索引暂不可用',
        data.omitted ? '已排除 ' + data.omitted + ' 项无效引用' : ''].filter(Boolean).join(' · ')
    }
    if (source.value === 'uploads') return lastQuery.value ? lastQuery.value : ''
    const bundles = source.value === 'turn' ? turn.value : publicResult.value ? [publicResult.value] : []
    return [...new Set(bundles.map(b => [b.query, b.notice || b.graphLabel, b.omitted ? '已排除 '+b.omitted+' 项无效引用' : ''].filter(Boolean).join(' · ')))].join(' / ')
  })
  async function listJobs() {
    listing?.abort()
    const request = listing = new AbortController()
    jobsError.value = ''
    try {
      const data = await ingestionRequest(props.token, '/jobs', 'GET', request.signal)
      if (!request.signal.aborted) jobs.value = data.jobs.filter(complete)
    } catch (e) { if (!request.signal.aborted) jobsError.value = e.message }
  }
  async function search() {
    active?.abort()
    const request = active = new AbortController(), dataset = source.value, q = query.value.trim()
    error.value = ''; loading.value = false; lastQuery.value = ''
    if (dataset === 'turn') return
    if (dataset === 'catalog') { catalog.value = null; documentId.value = '' }
    else if (dataset === 'uploads') uploadGraph.value = emptyGraph()
    else publicResult.value = null
    if (dataset !== 'catalog' && !q && !(dataset === 'uploads' && jobId.value)) return
    loading.value = true
    try {
      if (dataset === 'catalog') {
        const data = await readPublicCatalog(props.token, request.signal)
        if (!request.signal.aborted) catalog.value = data
      } else if (dataset === 'uploads') {
        if (!props.canIngest) return
        let data
        if (q) {
          data = await post(props.token, '/ingestion/search', { query:q, top_k:8, ...(jobId.value ? { jobId:jobId.value } : {}) }, request.signal)
        } else {
          const job = await ingestionRequest(props.token, '/jobs/' + encodeURIComponent(jobId.value), 'GET', request.signal)
          if (!complete(job)) throw new Error('资料尚未完成入库。')
          if (!Array.isArray(job.chunks) || job.chunks.some(chunk => !chunk?.id || typeof chunk.text !== 'string') ||
              new Set(job.chunks.map(chunk => chunk.id)).size !== job.chunks.length ||
              (Number.isInteger(job.counts?.chunks) && job.counts.chunks !== job.chunks.length)) {
            throw new Error('文件的解析片段未通过完整性核验，请重新加载。')
          }
          data = { hits:job.chunks.map(chunk => ({ ...chunk, jobId:job.id, filename:job.filename, chunkId:chunk.id })),
            entities:job.entities.map(e => ({ ...e, jobId:job.id })), relations:job.relations.map(r => ({ ...r, jobId:job.id })) }
        }
        if (!request.signal.aborted) {
          uploadGraph.value = uploadedKnowledgeGraph(data)
          lastQuery.value = q ? '入库资料检索 · ' + q : '已入库文件 · ' + (data.hits[0]?.filename || jobId.value)
        }
      } else {
        const data = await post(props.token, '/explore', { query:q }, request.signal)
        if (!request.signal.aborted) publicResult.value = verifiedPublicBundle(data)
      }
    } catch (e) {
      if (!request.signal.aborted) error.value = e instanceof SyntaxError || e instanceof TypeError ? '知识服务暂不可用，请重试。' : e.message
    } finally { if (active === request) loading.value = false }
  }
  function chooseJob(id) {
    uploadQuery.value = ''
    if (id === jobId.value) search()
    else jobId.value = id
  }
  watch([() => props.canIngest, () => props.jobId], ([allowed,id]) => {
    if (!allowed) {
      if (source.value === 'uploads') source.value = 'catalog'
      jobId.value = ''; jobs.value = []; uploadGraph.value = emptyGraph()
    } else if (id) { jobId.value = id; uploadQuery.value = ''; source.value = 'uploads' }
  })
  watch([source, jobId, () => props.token], () => {
    listing?.abort(); jobsError.value = ''
    if (source.value === 'uploads' && props.canIngest) listJobs()
    search()
  }, { immediate:true })
  onBeforeUnmount(() => { active?.abort(); listing?.abort() })
  return { source, jobId, query, jobs, graph, catalog, documents, documentId, notice, loading, error, jobsError, lastQuery, search, chooseJob, listJobs }
}
