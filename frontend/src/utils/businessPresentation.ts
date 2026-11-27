import { overviewEvidence, detailedDomainEvidence, deliveryEvidence, evidenceColumns, evidenceValue, evidenceWarning, analysisEvidence } from './workbenchEvidence.js'
import { limitationLabel } from './workbenchLabels.js'

export type BusinessRow = Record<string, unknown>
export interface BusinessColumn { key: string; label: string }
export interface BusinessTable {
  key: string; domain: string; label: string; rows: BusinessRow[]; columns: BusinessColumn[]
  total: string | null; returned: string | null; partial: boolean; notes: string[]
}
export interface BusinessSnapshot { tables: BusinessTable[]; notices: string[]; checkedAt: string; unavailable: boolean }
// This is a view of existing payloads, not a second API contract.
export interface BusinessMessage {
  evidenceInvalidated?: boolean
  workflow?: { invalidated?: boolean }
  result?: Record<string, any> | null
}
const rowsOf = (value: unknown): BusinessRow[] => Array.isArray(value)
  ? value.filter((row) => row && typeof row === 'object' && !Array.isArray(row)) : []
// MySQL DATETIME fields in trace snapshots share the same local clock, including saved responses.
function traceTimestamp(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?$/.test(value)) return ''
  const [seconds, fraction = ''] = value.replace('T', ' ').split('.')
  return seconds + '.' + fraction.padEnd(6, '0')
}
const strings = (value: unknown): string[] => Array.isArray(value) ? value.filter((s) => typeof s === 'string') : []
const productionColumns: BusinessColumn[] = [
  { key: 'work_order_no', label: '工单编号' }, { key: 'material_name', label: '产品名称' },
  { key: 'planned_quantity', label: '计划数量' }, { key: 'qualified_quantity', label: '合格数量' },
  { key: 'status', label: '运行状态' },
]
const extraLabels: Record<string, string> = {
  route_code: '路线编号', route_name: '工艺路线', version_no: '路线版本', step_code: '工序编码',
  quality_required: '需要检验', standard_equipment_type: '建议设备类型', standard_work_hours: '标准工时',
  inspection_item_id: '检验项 ID', item_code: '项目编号', item_name: '检验项目', standard_value: '检验标准',
  measured_value: '实测值', result: '检验结果', defect_level: '缺陷等级', defect_description: '缺陷描述',
  inspection_type: '检验类型', nonconforming_id: '不合格品 ID', nonconforming_no: '不合格品编号',
  before: '前期抽检', after: '后期抽检', difference_percentage_points: '差值（百分点）',
  step_key: '工序标识',
  sample_fail_percent: '抽检不合格率（%）', record_count: '缺陷记录数', inspectionCount: '检验记录数',
  validRecordCount: '有效抽检记录数', pendingCount: '待检记录数', conflictCount: '数据冲突记录数',
  concessionCount: '让步接收记录数', incompleteCount: '资料不完整记录数', acceptanceReviewCount: '需复核接收记录数',
  recheckRecordCount: '复检记录数', excludedDefectRegistrationCount: '因关联矛盾排除的缺陷登记数', previousComplete: '上期数据完整',
  comparable_batch_count: '可比批次数', lowest: '不合格率最低批次', highest: '不合格率最高批次',
  previous_sample_quantity: '上期有效抽检数量', current_sample_quantity: '本期有效抽检数量',
  previous_fail_percent: '上期抽检不合格率（%）', current_fail_percent: '本期抽检不合格率（%）',
  basis: '比较口径', previousStartInclusive: '上期开始（含）', previousEndExclusive: '上期结束（不含）',
  currentStartInclusive: '本期开始（含）', currentEndExclusive: '本期结束（不含）',
  link_basis: '关联依据', remark: '原记录备注', report_status: '报工状态',
  round_id: '检验版本 ID', event_type: '保存或判定动作', source_updated_at: '原记录更新时间',
  recorded_at: '留存时间', operator_source: '操作人来源', binding_id: '样机关联 ID',
  unit_code: '样机标识', sample_label: '样机', sample_origin: '样机标识来源', source_ref: '原始依据',
  measurement_id: '实测记录 ID', prior_measurement_id: '对应前测记录 ID', metric_name: '测量指标',
  before_value: '工序前读数', after_value: '工序后读数', before_measured_at: '前测时间', after_measured_at: '后测时间',
  inspector_id: '质检员 ID', inspector_name: '登记质检员', operator_name: '操作员',
  record_no: '原始单号', record_kind: '记录类别', source_file: '实训数据来源',
  mapping_status: '关联校验状态', plan_item_id: '计划行 ID', route_id: '工艺路线 ID', order_item_id: '订单行 ID',
  quantityBasis: '数量口径', scheduleSource: '计划来源', estimatedShipmentAt: '预计发货时间',
  orderItemPk: '订单行 ID', remainingDeliveryQty: '剩余交付数量', shippableQty: '确认可发货数量', candidateReadyQty: '库存分配情景数量',
  materialId: '物料 ID', materialName: '物料名称', scenarioGapQty: '当前 BOM 情景缺口', confirmedShortageNow: '已确认精确缺口',
  step_origin: '工序来源', trace_status: '核对结论', dispatch_count: '派工条数', inspection_id: '检验单 ID', order_item_id: '订单行 ID', plan_id: '生产计划 ID',
  step_no: '工序序号', step_name: '工序名称', completedQtyRecorded: '记录完成数量', dispatchIds: '派工 ID', reportIds: '报工 ID',
  schedule_id: '排程 ID', planned_start: '计划开始时间', planned_end: '计划结束时间', standard_hours: '标准工时',
  competing_order_nos: '竞争订单', scenario_demand_qty: '计划供料情景需求', scoped_total_demand_qty: '范围内总需求',
  public_available_qty: '公共可用库存', scenario_allocated_qty: '情景分配数量', scenario_gap_qty: '情景缺口',
  recorded_issued_qty: '记录已领料（未冲减）', inventory_ids: '库存依据 ID', plan_item_ids: '计划行依据 ID',
  bom_ids: 'BOM 依据 ID', transaction_ids: '流水依据 ID', bom_id: 'BOM ID', bom_version: 'BOM 版本',
  unfinished_plan_qty: '计划未完成数量', bom_quantity: 'BOM 单耗', loss_rate: '损耗率', progress_basis: '进度依据',
  resource_type: '资源类型', resource_id: '资源 ID', left_order_no: '订单一', right_order_no: '订单二',
  left_source_id: '排程/派工依据一', right_source_id: '排程/派工依据二', overlap_start: '重叠开始', overlap_end: '重叠结束', overlap_minutes: '重叠分钟',
}
function columnsFor(rows: BusinessRow[], production = false, dateField?: string): BusinessColumn[] {
  const first = production ? productionColumns : []
  const columns = [...first, ...evidenceColumns(rows, dateField), ...Object.entries(extraLabels)
    .filter(([key]) => rows.some((row) => Object.hasOwn(row, key))).map(([key, label]) => ({ key, label }))]
  const defined = new Map<string, BusinessColumn>()
  for (const column of columns) if (!defined.has(column.key)) defined.set(column.key, column)
  for (const row of rows) {
    for (const key of Object.keys(row)) if (!defined.has(key)) defined.set(key, { key, label: key })
  }
  return [...defined.values()]
}
function warningLabel(warning: string): string {
  const evidence = evidenceWarning(warning)
  if (evidence !== warning) return evidence
  const label = limitationLabel(warning)
  return label.startsWith('另有业务记录') ? warning : label
}

/** All non-inventory returned rows. No model-text parsing, aggregation, slicing, or inferred quantities. */
export function businessSnapshot(message: BusinessMessage | null | undefined): BusinessSnapshot {
  const result = message?.result
  const snapshot: BusinessSnapshot = { tables: [], notices: [], checkedAt: typeof result?.checkedAt === 'string' ? result.checkedAt : '', unavailable: false }
  if (message?.evidenceInvalidated || message?.workflow?.invalidated || result?.verdict === 'DATA_CHANGED') {
    return { ...snapshot, unavailable: true, notices: ['业务数据已变更，先前结果已失效；请重新查询。'] }
  }
  if (result?.historyEvidenceOmitted) {
    return { ...snapshot, unavailable: true, notices: ['历史证据因保存空间限制未保留，请重新查询以获取完整依据。'] }
  }
  if (!result) return snapshot
  const facts = result.facts || {}
  const traceTables = [
    ['inspection','检验单'], ['items','检验项目与实测值'], ['defects','已登记不合格品'],
    ['batchRecords','匹配的物料批次'], ['sameBatchInspections','同物料、批号与检验类别的记录'],
    ['workOrder','来源工单'], ['routeSteps','工单所属路线'], ['reportRows','关联生产报工'],
    ['trainingHistory','同工单实训历史（按原始单据补录）'],
    ['sampleBindings','缺陷与样机的关联依据'], ['sampleMeasurements','样机工序前后实测记录'],
    ['dispatchRows','工单派工'], ['equipmentRows','关联设备'], ['graphPaths','已核验图谱路径'],
  ]
  const appendRows = (key: string, domain: string, label: string, records: unknown) => {
    const rows = rowsOf(records)
    if (!rows.length) return
    const columns = columnsFor(rows)
    snapshot.tables.push({ key, domain, label, rows, columns, total: null, returned: null, partial: false, notes: [] })
  }
  appendRows('workshop-stages', 'production', '车间标准派工工序', facts.process_route?.productionStages)
  appendRows('process-route', 'production', '各产品的启用路线与工序', facts.process_route?.rows)
  appendRows('batch-comparison', 'quality', '同批分时段抽检比较', facts.quality?.batchComparison)
  for (const [key,label] of traceTables) appendRows('inspection-'+key, 'quality', label, facts.inspection_trace?.[key])
  const trace = facts.inspection_trace
  const rounds = rowsOf(trace?.inspectionEvents)
  appendRows('inspection-rounds', 'quality', '检验保存与判定历史', rounds.map(({ items, ...event }) => event))
  appendRows('inspection-round-items', 'quality', '各次保存的检验项快照', rounds.flatMap(event =>
    rowsOf(event.items).map(item => ({ ...item, round_id: event.round_id, event_type: event.event_type }))))
  const inspectedAt = traceTimestamp(trace?.inspection?.[0]?.inspected_at)
  if (inspectedAt) {
    const alarms = rowsOf(trace?.alarmRows).filter(row => {
      const reportedAt = traceTimestamp(row.reported_at)
      return reportedAt && reportedAt <= inspectedAt
    })
    appendRows('inspection-alarmRows', 'quality', '截至检验时的工单报警', alarms)
  }
  if (Array.isArray(facts.alarms?.alarmTrend)) {
    const rows = rowsOf(facts.alarms.alarmTrend)
    snapshot.tables.push({ key: 'alarm-daily-trend', domain: 'alarms', label: '按日报警记录趋势', rows,
      columns: columnsFor(rows).sort((a, b) => {
        const priority = ['alarm_day', 'equipment_name', 'alarm_record_count', 'alarm_type', 'alarm_level', 'status', 'trend_status']
        const rank = (key: string) => priority.includes(key) ? priority.indexOf(key) : priority.length
        return rank(a.key) - rank(b.key)
      }), total: null, returned: null, partial: facts.alarms.overview?.truncated === true, notes: [] })
  }
  if (Array.isArray(facts.quality?.qualityTrend)) {
    const rows = rowsOf(facts.quality.qualityTrend)
    snapshot.tables.push({ key: 'quality-daily-trend', domain: 'quality', label: '按日抽检趋势', rows,
      columns: columnsFor(rows).sort((a, b) => {
        const priority = ['inspection_day', 'material_name', 'unit', 'sample_pass_percent', 'valid_sample_quantity',
          'valid_unqualified_quantity', 'inspection_record_count', 'valid_qualified_quantity', 'trend_status']
        const rank = (key: string) => priority.includes(key) ? priority.indexOf(key) : priority.length
        return rank(a.key) - rank(b.key)
      }), total: null, returned: null, partial: facts.quality.overview?.truncated === true, notes: [] })
  }
  if (Array.isArray(facts.maintenance?.maintenanceSummary)) {
    const rows = rowsOf(facts.maintenance.maintenanceSummary)
    snapshot.tables.push({ key: 'maintenance-duration', domain: 'maintenance', label: '设备维修登记时长',
      rows, columns: columnsFor(rows).sort((a, b) => {
        const priority = ['equipment_name', 'equipment_code', 'recorded_downtime_minutes', 'interval_union_minutes',
          'maintenance_record_count', 'known_duration_count', 'missing_duration_count', 'valid_interval_count', 'missing_interval_count']
        const rank = (key: string) => priority.includes(key) ? priority.indexOf(key) : priority.length
        return rank(a.key) - rank(b.key)
      }), total: null, returned: null, partial: false, notes: [] })
  }
  snapshot.notices = strings(result.missing).map(warningLabel)
  for (const [key, domain, label, source] of [
    ['work-materials','supply','所选工单物料需求与账面缺口',facts.inventory?.materialRows],
    ['work-steps','production','工序完成情况',facts.production?.stepCoverage],
    ['work-graph','quality','已回源核对的订单追溯路径',facts.quality?.businessGraph?.paths],
  ] as const) {
    const rows = rowsOf(source)
    if (rows.length) snapshot.tables.push({key,domain,label,rows,columns:columnsFor(rows),total:null,returned:null,partial:false,notes:[]})
  }
  for (const conclusion of [facts.quality?.traceConclusion, facts.production?.traceConclusion, facts.inventory?.traceConclusion])
    if (typeof conclusion === 'string') snapshot.notices.push(conclusion)
  if (Array.isArray(facts.production?.dispatchRows)) {
    const rows = rowsOf(facts.production.dispatchRows)
    snapshot.tables.push({ key: 'work-order-dispatch', domain: 'production', label: '派工明细', rows,
      columns: columnsFor(rows), total: null, returned: null, partial: false, notes: [] })
    if (typeof facts.production.dispatchConclusion === 'string') snapshot.notices.push(facts.production.dispatchConclusion)
  }
  if (Array.isArray(facts.order?.crossOrderRisk)) {
    const rows = rowsOf(facts.order.crossOrderRisk)
    snapshot.tables.push({ key: 'cross-order-risk', domain: 'order', label: '订单交付风险', rows,
      columns: columnsFor(rows), total: null, returned: null, partial: false, notes: [] })
    snapshot.notices.push('按订单行核对当前发货记录与要求日期；不代表产能承诺或已分配共享库存。')
  }
  snapshot.notices.push(...strings(facts.order?.resourceLimitations))
  for (const [key, domain, label, data] of [
    ['shared-material', 'supply', '共享物料供料情景', facts.shared_material],
    ['capacity-conflicts', 'production', '跨订单排程冲突', facts.capacity_conflicts],
  ] as const) {
    if (!data || !Array.isArray(data.rows)) continue
    const rows = rowsOf(data.rows)
    const priority = key === 'shared-material'
      ? ['order_no','material_name','scenario_demand_qty','public_available_qty','scenario_allocated_qty','scenario_gap_qty','unit','risk_reason','competing_order_nos']
      : ['left_order_no','right_order_no','resource_type','resource_id','overlap_start','overlap_end','overlap_minutes','risk_reason']
    const rank = (column: BusinessColumn) => priority.includes(column.key) ? priority.indexOf(column.key) : priority.length
    const columns = columnsFor(rows).sort((a, b) => rank(a) - rank(b))
    snapshot.tables.push({ key, domain, label, rows, columns, total: null, returned: null, partial: false, notes: [] })
    for (const value of [data.basis, data.scope]) if (typeof value === 'string') snapshot.notices.push(value)
  }
  const coverage = [...rowsOf(facts.shared_material?.coverage), ...rowsOf(facts.capacity_conflicts?.coverage)]
  if (coverage.length) snapshot.tables.push({ key: 'resource-coverage', domain: 'order', label: '待核实的资源依据', rows: coverage,
    columns: columnsFor(coverage), total: null, returned: null, partial: false, notes: [] })
  if (Array.isArray(facts.shared_material?.demands)) {
    const rows = rowsOf(facts.shared_material.demands)
    snapshot.tables.push({ key: 'material-demand-basis', domain: 'supply', label: '计划与 BOM 计算依据', rows,
      columns: columnsFor(rows), total: null, returned: null, partial: false, notes: [] })
  }

  for (const overview of overviewEvidence(facts)) {
    if (overview.key === 'inventory') continue
    const raw = facts[overview.key].overview
    const countMismatch = !Array.isArray(raw.rows) || raw.rows.length !== overview.rows.length ||
      raw.returnedRecords == null || raw.totalRecords == null ||
      String(raw.returnedRecords) !== String(overview.rows.length) || String(raw.totalRecords) !== String(overview.rows.length)
    const notes = [overview.period, overview.countBasis, overview.quantityBasis]
    if (overview.hasRange) notes.push(`按${overview.dateField}筛选：${overview.start}（含）至 ${overview.end}（不含）；时区 ${overview.timeZone}`)
    if (overview.keyword) notes.push(`查询关键词：${evidenceValue(overview.keyword)}`)
    if (overview.truncated) notes.push(`服务端返回上限：${overview.limit} 条。`)
    snapshot.tables.push({ key: `${overview.key}-overview`, domain: overview.key,
      label: overview.key === 'production' ? '生产工单' : `${overview.label}明细`, rows: overview.rows,
      columns: columnsFor(overview.rows, overview.key === 'production', raw.dateField), total: overview.total,
      returned: overview.returned, partial: overview.truncated || countMismatch || raw.complete === false,
      notes: notes.filter(Boolean) })
    snapshot.notices.push(...strings(facts[overview.key].warnings).map(warningLabel))
  }
  for (const domain of detailedDomainEvidence(facts)) {
    snapshot.notices.push(...domain.warnings)
    if (domain.pathsIncomplete) snapshot.notices.push(`追溯路径尚不完整。${domain.pathLimit != null ? `本次路径上限 ${domain.pathLimit} 条。` : ''}`)
    for (const table of domain.tables) snapshot.tables.push({ ...table, columns: columnsFor(table.rows), key: `${domain.key}-${table.key}`, domain: domain.key,
      label: `${domain.label} · ${table.label}`, returned: null, partial: domain.partial || (table.key === 'tracePaths' && domain.pathsIncomplete), notes: [] })
  }
  const delivery = deliveryEvidence(facts)
  const workOrders = Array.isArray(facts.E3?.workOrders) ? delivery.workOrders : rowsOf(facts.productionWorkOrders)
  const workSource = Array.isArray(facts.E3?.workOrders) ? facts.E3 : facts
  const addTable = (key: string, domain: string, label: string, rows: BusinessRow[], notes: string[], partial = false, production = false) => {
    snapshot.tables.push({ key, domain, label, rows, columns: columnsFor(rows, production), total: null, returned: null, partial, notes })
  }
  if (Array.isArray(facts.E3?.workOrders) || Array.isArray(facts.productionWorkOrders)) {
    addTable('production-work-orders', 'production', '订单关联工单', workOrders,
      ['已保存的工单计划和执行记录，不代表交付承诺。数量按原单位逐条展示，不跨产品合计。'],
      workSource.recordCompleteness === 'PARTIAL' || workSource.complete === false, true)
    workOrders.forEach((work, i) => {
      if (Array.isArray(work.stages)) addTable(`production-stages-${i}`, 'production', `工单 ${evidenceValue(work.work_order_no)} · 工序记录`, rowsOf(work.stages), ['派工和报工记录可能涉及返工，不直接累加为产品产出。'])
    })
  }
  if (Array.isArray(facts.E3?.savedSchedules)) addTable('production-schedules', 'production', '已保存排程', rowsOf(facts.E3.savedSchedules), ['已保存排程不代表交付承诺。'])
  if (delivery.lines.length) addTable('delivery-lines', 'delivery', '交付数量', delivery.lines,
    ['库存分配情景尚未预留，不能直接作为可发货数量。未知数量不按 0 处理。'])
  if (delivery.materials.length) addTable('delivery-materials', 'supply', '物料缺口', delivery.materials,
    ['不同物料分别核算，情景结果受 BOM、领料与订单预留记录完整性影响。'])
  const stats = facts.quality?.qualityStatistics
  if (stats && typeof stats === 'object') {
    const counts = Object.fromEntries(Object.entries(stats).filter(([key, value]) =>
      key.endsWith('Count') && (value == null || ['number', 'string'].includes(typeof value))))
    if (Object.keys(counts).length) appendRows('quality-stats-counts', 'quality', '检验记录分类统计', [counts])
  }
  appendRows('quality-stats-samples', 'quality', '各检验口径抽检统计', stats?.sampleGroups)
  appendRows('quality-stats-batches', 'quality', '分批次抽检统计', stats?.batchGroups)
  appendRows('quality-stats-batch-differences', 'quality', '可比批次差异', stats?.batchDifferences)
  appendRows('quality-stats-defects', 'quality', '缺陷分类记录数', stats?.defectDistribution)
  for (const [index, period] of rowsOf(stats?.periodComparisons).entries()) {
    const { groups, ...range } = period
    const comparison = rowsOf(groups)
    appendRows('quality-stats-period-' + index, 'quality', '分期抽检对比',
      comparison.length ? comparison.map(row => ({ ...range, ...row })) : [range])
  }
  if (stats?.scopeNote) snapshot.notices.push(stats.scopeNote)
  snapshot.notices = [...new Set(snapshot.notices)]
  return snapshot
}

export const hasBusinessTables = (message: BusinessMessage | null | undefined): boolean => businessSnapshot(message).tables.length > 0

/** Replace list narration, not facts or validated analytical conclusions. Unknown response formats retain their text. */
export function compactBusinessAnswer(message: BusinessMessage | null | undefined): string {
  const snapshot = businessSnapshot(message)
  const result = message?.result
  if (!result || !snapshot.tables.length || snapshot.unavailable || !['EVIDENCE_READY', 'INSUFFICIENT_EVIDENCE'].includes(result.verdict)) return ''
  if (result.facts?.inspection_trace || result.facts?.process_route || result.facts?.quality?.traceConclusion) return ''
  const labels = [...new Set(snapshot.tables.map((table) => ({ production: '生产工单', order: '订单', quality: '质量', equipment: '设备', aftersales: '售后', delivery: '交付', supply: '物料' })[table.domain] || table.label))]
  if (result.facts?.inventory?.overview) labels.unshift('库存')
  const sections = [`已查询到以下${labels.join('、')}信息：`]
  if (result.facts?.shared_material) sections.push('共享库存按交期、订单 ID 顺序只分配一次；表中缺口是供料情景，不是已确认的实际缺料。')
  if (result.facts?.capacity_conflicts) sections.push(result.facts.capacity_conflicts.rows?.length
    ? '已发现跨订单排程重叠，相关订单、资源和时间见表；实际并发能力仍需核实。'
    : '在本次可核验的当前/未来排程中未发现跨订单重叠；缺失排程及范围外占用不在此结论内。')
  for (const analysis of analysisEvidence(result)) {
    for (const [key, label] of [['findings', '记录发现'], ['hypotheses', '待验证原因'], ['actions', '建议核查']]) {
      for (const item of analysis[key]) sections.push(`- ${label}：${item.text}${item.verification ? `；验证方法：${item.verification}` : ''}`)
    }
    if (analysis.missing.length) sections.push(`尚缺依据：${analysis.missing.join('；')}`)
  }
  return sections.join('\n\n')
}

/** A concise replacement is allowed only for a complete, standalone production overview. */
export function isProductionOnly(message: BusinessMessage | null | undefined): boolean {
  const result = message?.result
  const snapshot = businessSnapshot(message)
  return Boolean(result?.verdict === 'EVIDENCE_READY' && Array.isArray(result.plan?.intents) &&
    result.plan.intents.length === 1 && result.plan.intents[0] === 'PRODUCTION' &&
    Object.keys(result.facts || {}).every((key) => key === 'production') &&
    snapshot.tables.length === 1 && snapshot.tables[0].key === 'production-overview' &&
    !snapshot.tables[0].partial && !snapshot.unavailable && snapshot.notices.length === 0)
}
/** Empty means retain the complete model answer (especially mixed queries or limited evidence). */
export function businessSummary(message: BusinessMessage | null | undefined): string {
  if (!isProductionOnly(message)) return ''
  const table = businessSnapshot(message).tables[0]
  return `已查询生产工单：匹配 ${table.total} 条记录，本次展示 ${table.rows.length} 条。各工单计划数量、合格数量及运行状态见下表；数量按原单位展示。`
}
