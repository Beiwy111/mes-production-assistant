export const jobStates = { queued: '已排队', running: '处理中', completed: '已入库', failed: '失败' }
export const stageStates = { pending: '等待', running: '处理中', success: '完成', failed: '失败' }

export function fileType(job) {
  const suffix = job.filename?.match(/\.([^.]+)$/)?.[1]
  return String(job.metadata?.format || suffix || '未知格式').toUpperCase()
}

export function indexState(job, key) {
  const read = name => job.stages?.find(stage => stage.key === name)?.status
  let recorded = read(key)
  if (key === 'graph') {
    const entities = read('entities')
    const relations = read('relations')
    if (entities || relations) recorded = ['failed','running'].includes(entities) ? entities : relations || entities
  }
  // Older job summaries omit the stages. Only completed jobs have both index commits.
  const status = recorded ?? (job.status === 'completed' ? 'success' : '')
  if (status === 'success') return key === 'embedding' ? '已向量化' : '已抽取'
  if (status === 'pending' && job.status === 'failed') return '未执行'
  return stageStates[status] || '未记录'
}

export function displayDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return '—'
  return date.toLocaleString('sv-SE', { hour12: false }).slice(0, 16)
}

const locations = { page: '页', slide: '张幻灯片', table: '个表格', line: '行', paragraph: '段', image: '张图片' }

export function chunkLocation(chunk) {
  const metadata = chunk.metadata || {}
  if (metadata.sheet && metadata.rowStart != null && metadata.rowEnd != null)
    return metadata.sheet + ' · 第 ' + metadata.rowStart + '–' + metadata.rowEnd + ' 行'
  const location = chunk.location
  if (location === 'document:end') return '文档末段'
  if (location === 'document') return '文档正文'
  if (typeof location === 'string' && location) {
    return location.split('/').map(part => {
      const [name, number] = part.split(':')
      return locations[name] && /^\d+$/.test(number) ? '第 ' + number + ' ' + locations[name] : part
    }).join(' · ')
  }
  if (metadata.page != null) return '第 ' + metadata.page + ' 页'
  if (location && typeof location === 'object') return Object.values(location).filter(Boolean).join(' · ') || '位置未记录'
  return '位置未记录'
}
