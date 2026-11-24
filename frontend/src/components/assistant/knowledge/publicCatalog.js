import { verifiedPublicRecords } from '../../../utils/workbenchEvidence.js'

const endpoint = '/api/assistant-v2/knowledge/catalog'
const pageSize = 100
const countKeys = ['documents', 'chunks', 'claims', 'entities', 'evidence_links']
const changed = '知识库版本已更新，请刷新目录。'

export async function readPublicCatalog(token, signal) {
  let snapshot, offset = 0
  const hits = [], paths = []
  do {
    const params = new URLSearchParams({ offset: String(offset), limit: String(pageSize) })
    if (snapshot) params.set('releaseId', snapshot.release_id)
    const response = await fetch(endpoint + '?' + params, { signal, headers: { Authorization: 'Bearer ' + token } })
    if (response.status === 401) throw new Error('登录已过期，请重新登录。')
    if (response.status === 409) throw new Error(changed)
    if (!response.ok) throw new Error('知识库目录读取失败（' + response.status + '）。')
    const result = await response.json()
    if (result.code === 409) throw new Error(changed)
    if (result.code !== 200 || !result.data) throw new Error(result.message || '知识库目录暂不可用。')
    const page = result.data
    if (!page.release_id || !Array.isArray(page.hits) || !Array.isArray(page.graph_paths) ||
        !Number.isSafeInteger(page.counts?.documents) || !Number.isSafeInteger(page.counts?.chunks)) {
      throw new Error('知识库目录缺少发布信息。')
    }
    if (!snapshot) {
      snapshot = { release_id: page.release_id, graph_release_id: page.graph_release_id,
        graph_status: page.graph_status, counts: page.counts }
    } else if (snapshot.release_id !== page.release_id || snapshot.graph_release_id !== page.graph_release_id ||
        snapshot.graph_status !== page.graph_status || countKeys.some(key => snapshot.counts[key] !== page.counts[key])) {
      throw new Error(changed)
    }
    hits.push(...page.hits)
    paths.push(...page.graph_paths)
    if (page.next_offset !== null && (!Number.isSafeInteger(page.next_offset) || page.next_offset <= offset ||
        (!page.hits.length && !page.graph_paths.length))) throw new Error('知识库目录的分页信息无效。')
    offset = page.next_offset
  } while (offset !== null)

  // A claim may cite a chunk on a later page of the same release.
  const records = verifiedPublicRecords({ ...snapshot, hits, graph_paths: paths }, true, snapshot.graph_status === 'READY')
  const chunks = new Set(records.hits.map(hit => hit.evidence_ref))
  const documents = new Set(records.hits.map(hit => hit.document_id))
  if (chunks.size !== snapshot.counts.chunks || documents.size !== snapshot.counts.documents ||
      chunks.size !== hits.length) throw new Error('目录片段未通过完整性核验，请刷新目录。')
  if (snapshot.graph_status === 'READY' && Number.isSafeInteger(snapshot.counts.claims) &&
      (paths.length !== snapshot.counts.claims || new Set(paths.map(path => path?.claim_id)).size !== paths.length ||
        paths.some(path => !path?.claim_id || !path.subject_id || !path.object_id))) {
    throw new Error('目录研究关系未通过完整性核验，请刷新目录。')
  }
  return { ...snapshot, ...records }
}
