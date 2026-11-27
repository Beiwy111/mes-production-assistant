import { evidenceColumns } from './workbenchEvidence.js'

// Display only: no inventory arithmetic, inference of shortages, or cross-material totals.
const inventoryFields = new Set(['material_name', 'material_code', 'material_type', 'quantity_on_hand', 'quantity_reserved',
  'quantity_available', 'unit', 'warehouse_code', 'location_code', 'batch_no'])
const count = (value) => ['number', 'string'].includes(typeof value) && String(value).trim() !== '' &&
  Number.isSafeInteger(Number(value)) && Number(value) >= 0 ? Number(value) : null
export function inventoryQuantity(value) {
  if (!['string', 'number'].includes(typeof value)) return '未提供'
  const match = String(value).trim().match(/^(-?)(\d+)(?:\.(\d+))?$/)
  if (!match) return '未提供'
  const fraction = (match[3] || '').replace(/0+$/, '')
  return match[1] + match[2].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction ? `.${fraction}` : '')
}
export function inventorySnapshot(message) {
  if (!message || message.evidenceInvalidated || message.workflow?.invalidated ||
      message.result?.verdict === 'DATA_CHANGED' || message.result?.historyEvidenceOmitted) return null
  let overview, provisional = false
  if (message.result) overview = message.result.facts?.inventory?.overview
  else if (message.status === 'RUNNING') {
    overview = message.agents?.find((agent) => agent.agentId === 'SUPPLY')?.evidence
      ?.find((item) => item.kind === 'OVERVIEW_INVENTORY' && item.queryRef)?.data?.overview
    provisional = true
  }
  if (!overview || overview.domain !== 'INVENTORY' || !Array.isArray(overview.rows)) return null
  const rows = overview.rows.filter((row) => row && typeof row === 'object' && !Array.isArray(row))
  const total = count(overview.totalRecords), returned = count(overview.returnedRecords)
  const labels = new Map(evidenceColumns(rows, overview.dateField).map(column => [column.key, column.label]))
  const extraColumns = [...new Set(rows.flatMap(row => Object.keys(row)))].filter(key => !inventoryFields.has(key))
    .map(key => ({ key, label: labels.get(key) || key }))
  return { rows, total, returned, provisional, extraColumns,
    category: typeof overview.inventoryCategory === 'string' ? overview.inventoryCategory : '',
    partial: overview.truncated === true || overview.rows.length !== rows.length || total === null || returned === null || returned !== rows.length || total !== rows.length,
    keyword: typeof overview.keyword === 'string' ? overview.keyword.slice(0, 160) : '',
    period: ({ CURRENT: '当前记录', TODAY: '今天更新', LAST_7_DAYS: '近七天更新', THIS_MONTH: '本月更新' })[overview.period] || '指定范围',
    checkedAt: message.result?.checkedAt || '',
  }
}
export const inventorySummary = (snapshot) => snapshot
  ? `已查询${snapshot.category || ''}库存：匹配 ${snapshot.total ?? '数量未知的'} 条记录，本次展示 ${snapshot.rows.length} 条。${snapshot.partial ? '结果不完整，请结合范围查看或缩小查询条件。' : '各物料的库存数量见下表。'}` : ''
export const inventoryTypeLabel = value => ({ RAW: '原材料', SEMI: '半成品', FINISHED: '成品', PACK: '包装材料', SPARE: '备件' })[value] || value || '未提供'
export function isInventoryOnly(message) {
  const intents = message.result?.plan?.intents
  const snapshot = inventorySnapshot(message)
  return Boolean(snapshot && !snapshot.partial && message.result?.verdict === 'EVIDENCE_READY' &&
    Array.isArray(intents) && intents.length === 1 && intents[0] === 'INVENTORY' &&
    Object.keys(message.result.facts || {}).every((key) => key === 'inventory'))
}
