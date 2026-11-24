// Presentation only. Quantities and counts remain server-provided strings/numbers.
const LABELS = {
  dispatch_no: '派工单号', operator_name: '操作员', operator_id: '操作员 ID', assigned_quantity: '派工数量', accepted_quantity: '接收数量', assigned_at: '派工时间', accepted_at: '接收时间', risk_status: '风险判断', risk_reason: '判断依据', overdue_days: '逾期天数', due_date: '要求交付日期', product_name: '产品名称',
  alarm_day:'报警上报日期', alarm_record_count:'报警记录数', returned_alarm_record_count:'返回记录数', invalid_alarm_record_count:'缺少编号或重复记录数', alarm_count_basis:'报警统计口径',
  inspection_day:'检验日期', inspection_record_count:'检验记录数', valid_sample_record_count:'有效样本记录数', invalid_sample_record_count:'待检或无效样本记录数', duplicate_record_count:'重复记录数',
  valid_sample_quantity:'有效抽检数量', valid_qualified_quantity:'有效合格样本数量', valid_unqualified_quantity:'有效不合格样本数量', sample_pass_percent:'抽检合格比例（%）', trend_status:'统计完整性', sample_basis:'样本统计口径',
  interval_union_minutes:'维修区间去重时长（分钟）', valid_interval_count:'有效时间区间数', missing_interval_count:'缺失或无效时间区间数', interval_basis:'区间统计口径',
  maintenance_record_count:'维修记录数', known_duration_count:'有效时长记录数', missing_duration_count:'缺失或无效时长记录数', recorded_downtime_minutes:'登记停机合计（分钟）', duration_basis:'统计口径',
  maintenance_no:'维修单号', fault_description:'故障描述', maintenance_content:'维修内容', alarm_description:'报警描述', transaction_no:'库存流水号', inventory_id:'库存记录ID', related_purchase_order_id:'关联采购单ID', related_work_order_id:'关联工单ID',
  customer_name: '客户名称', record_id: '记录 ID', material_id: '物料 ID', material_code: '物料编码',
  material_name: '物料名称', unit: '单位', warehouse_code: '仓库',
  location_code: '库位', batch_no: '批次', quantity_on_hand: '现存数量',
  quantity_reserved: '已预留数量', quantity_available: '可用数量',
  status: '状态', event_at: '业务时间', work_order_no: '工单号',
  planned_quantity: '计划数量', completed_quantity: '已完成数量',
  qualified_quantity: '合格数量', unqualified_quantity: '不合格数量',
  inspection_no: '检验单号', sample_quantity: '抽检数量',
  equipment_code: '设备编码', equipment_name: '设备名称',
  equipment_type: '设备类型', workshop: '车间', workstation: '工位',
  case_no: '售后案件号', order_id: '关联订单 ID', problem_type: '问题类型',
  case_level: '案件等级', order_no: '订单号', required_delivery_date: '要求交付日期',
  work_order_id: '工单 ID', executionStatus: '执行状态',
  bottleneckCompletedQty: '瓶颈工序完成量', finalStageCompletedQty: '末工序完成量',
  planned_start_time: '计划开始时间', planned_end_time: '计划结束时间',
  actual_start_time: '实际开始时间', actual_end_time: '实际结束时间',
  relation_status: '关联状态', relationStatus: '关联状态',
  inspection_id: '检验 ID', work_report_id: '报工 ID',
  inspection_category: '检验类别', inspection_type: '检验类型',
  inspection_result: '检验结果', inspection_status: '检验状态',
  inspected_at: '检验时间', updated_at: '记录更新时间',
  samplePassRate: '本次抽检合格比例', sampleRateStatus: '抽检比例口径',
  inspection_item_id: '检验项目 ID', item_code: '项目编码', item_name: '项目名称',
  standard_value: '标准值', measured_value: '实测值', result: '项目结果',
  defect_level: '缺陷等级', sort_order: '排序', nonconforming_id: '不合格品记录 ID',
  defect_type: '缺陷类型', quantity: '记录数量', severity: '严重程度',
  handle_method: '处理方式', handle_status: '处理状态',
  registered_at: '登记时间', handled_at: '处理时间',
  equipment_id: '设备 ID', last_maintenance_at: '最近维护时间',
  source_table: '来源表', source_id: '来源记录 ID', step_id: '工序 ID',
  observed_at: '记录时间', source_status: '来源状态',
  alarm_id: '报警 ID', alarm_no: '报警编号', dispatch_id: '派工 ID',
  alarm_type: '报警类型', alarm_level: '报警等级', alarm_status: '报警状态',
  reported_at: '上报时间', received_at: '接收时间', closed_at: '关闭时间',
  maintenance_id: '维护记录 ID', maintenance_type: '维护类型',
  start_time: '开始时间', end_time: '结束时间', downtime_minutes: '停机分钟数',
  maintenance_result: '维护结果', case_id: '售后案件 ID', delivery_id: '交付记录 ID',
  quality_inspection_id: '关联检验 ID', case_status: '案件状态', opened_at: '案件创建时间',
  resolved_at: '解决时间', task_id: '任务 ID', task_no: '任务编号', plan_id: '计划 ID',
  task_type: '任务类型', due_at: '截止时间', started_at: '开始时间', completed_at: '完成时间',
  closure_id: '结案记录 ID', recheck_passed: '复检通过记录', customer_confirmed: '客户确认记录',
  created_at: '创建时间', relation: '关联类型', sourceRecordRefs: '来源记录链路',
  conflictingDuplicate: '重复记录存在冲突',
}
const DOMAINS = [
  ['inventory', '库存'], ['production', '生产'], ['quality', '质量检验'],
  ['maintenance','维修记录'], ['alarms','报警记录'], ['stock_movement','库存流水'],
  ['order', '订单'], ['equipment', '设备'], ['aftersales', '售后'],
]
const DATE_LABELS = {
  updated_at: '记录更新时间', inspected_at: '检验时间',
  start_time:'维修开始时间', reported_at:'报警上报时间', handled_at:'库存流水发生时间',
  order_date: '订单日期', opened_at: '案件创建时间',
}
const PERIODS = { CURRENT: '当前记录', TODAY: '今天', LAST_7_DAYS: '最近七天', THIS_MONTH: '本月' }
const BASIS = {
  MATCHING_DATABASE_RECORDS: '符合查询条件的数据库记录数',
  PER_RECORD_PER_MATERIAL_UNIT: '数量按每条记录、各物料单位分别查看',
  NO_CROSS_MATERIAL_TOTAL: '不同物料数量不合计',
  CURRENT_RECORDS_NOT_HISTORICAL_SNAPSHOTS: '当前记录，不代表历史时点快照',
}
export const evidenceValue = (value) =>
  value == null ? '未提供'
    : Array.isArray(value) && value.every((item) => typeof item === 'string') ? value.join(' → ')
      : typeof value === 'object' ? '未提供' : String(value)
const rowsOf = (value) => Array.isArray(value)
  ? value.filter((row) => row && typeof row === 'object' && !Array.isArray(row)) : []
export function evidenceColumns(rows, dateField) {
  return Object.entries(LABELS)
    .filter(([key]) => rows.some((row) => Object.hasOwn(row, key)))
    .map(([key, label]) => ({ key, label: key === 'event_at' ? DATE_LABELS[dateField] || label
      : key === 'status' ? ({ handled_at:'变动类型', start_time:'维修结果', reported_at:'报警状态' })[dateField] || label : label }))
}
const basisLabel = (value) => typeof value === 'string'
  ? value.split(';').map((part) => BASIS[part.trim()] || part.trim()).filter(Boolean).join('；') : ''
export function overviewEvidence(facts) {
  return DOMAINS.flatMap(([key, label]) => {
    const overview = facts?.[key]?.overview
    if (!overview || typeof overview !== 'object') return []
    const rows = rowsOf(overview.rows)
    return [{
      key, label, rows, columns: evidenceColumns(rows, overview.dateField),
      total: evidenceValue(overview.totalRecords), returned: evidenceValue(overview.returnedRecords),
      truncated: overview.truncated === true, limit: evidenceValue(overview.limit),
      period: PERIODS[overview.period] || '未提供时间范围',
      dateField: DATE_LABELS[overview.dateField] || '未提供时间字段',
      start: evidenceValue(overview.startInclusive), end: evidenceValue(overview.endExclusive),
      hasRange: overview.startInclusive != null || overview.endExclusive != null,
      timeZone: evidenceValue(overview.timeZone), keyword: overview.keyword,
      countBasis: basisLabel(overview.countBasis), quantityBasis: basisLabel(overview.quantityBasis),
    }]
  })
}

export function deliveryEvidence(facts) {
  return {
    lines: rowsOf(facts?.fulfillmentLines ?? facts?.E4?.lines),
    materials: rowsOf(facts?.materials ?? facts?.E2?.materials),
    workOrders: rowsOf(facts?.E3?.workOrders),
  }
}

const DETAIL_TABLES = {
  quality: [
    ['inspections', '检验记录', 'inspectionCount'],
    ['inspectionItems', '检验项目', 'inspectionItemCount'],
    ['nonconformingProducts', '不合格品记录', 'nonconformingRecordCount'],
  ],
  equipment: [
    ['equipment', '设备台账', 'equipmentCount'],
    ['observedUsage', '设备使用关联记录', 'observedUsageCount'],
    ['alarms', '报警记录', 'alarmCount'],
    ['maintenanceRecords', '维护记录', 'maintenanceRecordCount'],
  ],
  aftersales: [
    ['cases', '售后案件', 'caseCount'], ['tasks', '售后任务', 'taskCount'],
    ['closures', '结案记录', 'closureCount'], ['tracePaths', '追溯路径'],
  ],
}
const WARNINGS = {
  CORRUPT_RECORD: '部分视觉归档记录损坏，未展示损坏内容。',
  ARCHIVE_UNAVAILABLE: '视觉归档暂不可读取。',
  RECORD_LIMIT_REACHED: '单个检验单视觉归档已达到读取上限。',
  TOTAL_RECORD_LIMIT_REACHED: '视觉归档总读取数量达到上限，证据不完整。',
  RECORD_SIZE_LIMIT: '部分视觉记录超出可读取大小，未展示。',
  MOCK_RECORDS_EXCLUDED_FROM_REAL_IMAGE_COUNTS: '模拟记录未计入真实图像记录数。',
  HISTORICAL_RAW_VISION_RESULTS_NOT_AVAILABLE_FOR_ALL_INSPECTIONS: '部分或全部检验单缺少历史原始视觉结果。',
  IMAGE_NOT_RETAINED: '归档没有保留原图，不能打开原图核对。',
  PARTIAL_IMAGES: '部分图片未保存或已不可读取，请查看各图片状态。',
  MODEL_LABEL_AND_ALGORITHM_VERSION_DO_NOT_PROVE_WEIGHT_VERSION: '模型名称与算法版本不能证明权重版本。',
  IMAGE_COUNT_IS_NOT_PRODUCT_SAMPLE_COUNT: '图像数不等于独立产品抽样数。',
  TRUNCATED_SNAPSHOT_TOTALS_UNAVAILABLE: '查询快照已截断，记录总数不可确定。',
  TRACE_PATH_LIMIT_REACHED: '追溯路径达到返回上限，尚有路径未展示。',
  REPEATED_INSPECTIONS_AND_DEFECT_RECORDS_MAY_REFER_TO_SAME_UNITS_DO_NOT_SUM_AS_BATCH_OUTPUT:
    '多次检验和缺陷记录可能对应同一实物，不能合计为批次产出。',
  'DISPATCH_ASSIGNMENT_IS_NOT_PROOF_OF_OPERATION; REPORTS_ARE_RECORDED_USAGE':
    '派工记录不能证明设备已运行；报工仅反映记录的使用情况。',
  EQUIPMENT_ONLY_ALARMS_AND_MAINTENANCE_DO_NOT_ESTABLISH_ORDER_CAUSALITY_OR_HISTORICAL_NORMALITY:
    '仅有关联设备的报警或维护记录，不能证明与订单的因果关系，也不能证明历史运行正常。',
  'TRACE_RELATIONS_ARE_NOT_ROOT_CAUSE_PROOF; BATCH_LABEL_MATCHES_ARE_CANDIDATES':
    '追溯关联不能证明根因；仅物料和批次标签匹配的路径属于候选关联。',
}
const WARNING_PREFIXES = {
  CONTRADICTORY_MAINTENANCE_ALARM: '维护与报警关联矛盾',
  CONTRADICTORY_CASE: '售后案件关联矛盾',
  UNVERIFIED_CASE_RELATION: '售后案件关联尚未核验',
  INVALID_DELIVERY_WORK_ORDER: '交付与工单关联无效',
  UNRESOLVED_CASE_DELIVERY: '售后案件的交付关联未解析',
  UNRESOLVED_CASE_INSPECTION: '售后案件的检验关联未解析',
  UNVERIFIED_ORDER_WORK_ORDER: '订单与工单关联尚未核验',
  UNRESOLVED_INBOUND_INVENTORY: '入库与库存关联未解析',
  UNRESOLVED_WORK_ORDER: '工单关联未解析',
  CONFLICTING_CONSUMPTION: '领料记录存在冲突',
  MISSING_RECORD_ID: '记录缺少 ID', CONFLICTING_DUPLICATE: '重复记录存在冲突',
  CONTRADICTORY_RELATION: '记录关联矛盾',
}
export function evidenceWarning(warning) {
  if (typeof warning !== 'string') return '存在待核验的业务记录。'
  if (Object.hasOwn(WARNINGS, warning)) return WARNINGS[warning]
  const [prefix, ...reference] = warning.split(':')
  return Object.hasOwn(WARNING_PREFIXES, prefix)
    ? `${WARNING_PREFIXES[prefix]}：${reference.join(':')}` : warning
}

export function detailedDomainEvidence(facts) {
  return DOMAINS.flatMap(([key, label]) => {
    const data = facts?.[key]
    if (!DETAIL_TABLES[key] || !data || data.overview) return []
    const warnings = Array.isArray(data.warnings) ? data.warnings.filter((item) => typeof item === 'string') : []
    const tables = DETAIL_TABLES[key].flatMap(([tableKey, tableLabel, countKey]) => {
      if (!Array.isArray(data[tableKey])) return []
      const rows = rowsOf(data[tableKey])
      return [{ key: tableKey, label: tableLabel, rows, columns: evidenceColumns(rows),
        total: countKey && data[countKey] != null ? evidenceValue(data[countKey]) : null }]
    })
    if (!tables.length && !warnings.length) return []
    return [{ key, label, tables, warnings: [...new Set(warnings)].map(evidenceWarning),
      partial: data.complete === false || warnings.includes('TRUNCATED_SNAPSHOT_TOTALS_UNAVAILABLE'),
      pathsIncomplete: key === 'aftersales' && data.tracePathsComplete === false,
      pathLimit: data.tracePathLimit == null ? null : evidenceValue(data.tracePathLimit),
    }]
  })
}

// URLs are never interpreted as HTML, and source links cannot navigate to executable schemes.
export function safeSourceUrl(value) {
  if (typeof value !== 'string' || !/^https?:\/\//i.test(value) || /[\u0000-\u0020\u007f]/.test(value)) return ''
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : ''
  } catch { return '' }
}
const textValue = (value) => typeof value === 'string' ? value : ''
const strings = (value) => Array.isArray(value) ? value.filter((item) => typeof item === 'string') : []
const normalizedQuote = (value) => textValue(value).replace(/\s+/g, ' ').trim()
const knownAgent = (id) => ['SUP', 'ORDER', 'SUPPLY', 'PRODUCTION', 'QUALITY', 'EQUIPMENT', 'AFTERSALES'].includes(id)

export function knowledgeEvidence(result) {
  if (result?.verdict === 'DATA_CHANGED' || result?.historyEvidenceOmitted) return []
  return rowsOf(result?.knowledgeEvidence).filter((bundle) => knownAgent(bundle.agentId)).map(verifiedPublicBundle)
}

export function verifiedPublicRecords(bundle, includeHits, includePaths) {
  const originalHits = rowsOf(bundle.hits)
  const hits = includeHits ? originalHits.filter((hit) =>
    ['PUBLIC_RESEARCH','PUBLIC_MANUAL','PUBLIC_STANDARD'].includes(hit.authority_type) && typeof bundle.release_id === 'string' && !!bundle.release_id &&
    hit.release_id === bundle.release_id && typeof hit.document_id === 'string' && !!hit.document_id &&
    typeof hit.chunk_id === 'string' && !!hit.chunk_id && typeof hit.document_version === 'string' && !!hit.document_version &&
    hit.evidence_ref === `${bundle.release_id}:${hit.document_id}:${hit.chunk_id}` &&
    Number.isInteger(hit.page_start) && hit.page_start > 0 && Number.isInteger(hit.page_end) && hit.page_end >= hit.page_start &&
    !!textValue(hit.text).trim() && !!textValue(hit.title).trim(),
  ).map((hit) => ({ ...hit, url: safeSourceUrl(hit.source_url) })) : []
  const refs = new Map(hits.map((hit) => [hit.evidence_ref, hit]))
  const originalPaths = rowsOf(bundle.graph_paths)
  const paths = includePaths
    ? originalPaths.filter((path) => path.scope === 'RESEARCH_ONLY' && path.authority_type === 'PUBLIC_RESEARCH' &&
      path.review_status === 'SOURCE_VERIFIED' && !!bundle.graph_release_id && path.graph_release_id === bundle.graph_release_id &&
      ['DETECTS', 'USES', 'CLASSIFIES'].includes(path.relation) && !!normalizedQuote(path.quote) &&
      Array.isArray(path.evidence_refs) && path.evidence_refs.length > 0 && path.evidence_refs.every((ref) => {
        const hit = refs.get(ref)
        return hit?.authority_type === 'PUBLIC_RESEARCH' && path.document_id === hit.document_id && path.document_version === hit.document_version &&
          path.page === hit.page_start && normalizedQuote(hit.text).includes(normalizedQuote(path.quote))
      })) : []
  return { hits, paths, omitted: originalHits.length - hits.length + originalPaths.length - paths.length }
}

export function verifiedPublicBundle(bundle) {
  const records = verifiedPublicRecords(bundle, bundle.status === 'MATCHES',
    bundle.mode_used === 'graph_assisted' && bundle.graph_status === 'MATCHES')
  return { ...bundle, ...records,
    limitations: strings(bundle.limitations), missing: strings(bundle.missing_information),
    modeLabel: bundle.mode_used === 'graph_assisted' && records.paths.length ? '图谱辅助检索' : bundle.mode_used === 'vector' ? '向量检索' : '未确认检索模式',
    graphLabel: ({ NOT_REQUESTED: '本次查询使用向量索引', MATCHES: '图谱有匹配', NO_MATCHES: '图谱未命中', NO_ENTITY_MATCH: '图谱未匹配到概念',
      UNAVAILABLE_OR_STALE: '图谱不可用或已过期，已降级' })[bundle.graph_status] || '未提供图谱状态',
    notice: bundle.status === 'NO_MATCHES' ? '公开资料未命中，不能据此判断不存在相关研究。'
      : bundle.status !== 'MATCHES' ? '知识检索不可用，本次没有可引用的公开资料。'
        : !records.hits.length ? '没有通过引用核验的公开资料。' : '',
  }
}

export function analysisEvidence(result) {
  if (result?.verdict === 'DATA_CHANGED' || result?.historyEvidenceOmitted) return []
  const knowledge = knowledgeEvidence(result)
  const businessRefs = new Set(strings(result?.queryRefs))
  return rowsOf(result?.analyses).filter((analysis) => knownAgent(analysis.agentId)).map((analysis) => {
    const allowed = new Set([...businessRefs, ...knowledge.filter((bundle) => bundle.agentId === analysis.agentId)
      .flatMap((bundle) => bundle.hits.map((hit) => hit.evidence_ref))])
    for (const bundle of rowsOf(result?.experienceEvidence).filter(b => b.agentId === analysis.agentId && b.status === 'MATCHES')) {
      for (const h of rowsOf(bundle.hits)) if (/^experience:[a-f0-9-]{36}:[a-f0-9]{64}$/.test(h.evidence_ref) &&
          h.evidence_ref.endsWith(':' + h.record_hash) && /^[a-f0-9]{64}$/.test(h.record_hash)) allowed.add(h.evidence_ref)
    }
    let omitted = 0
    const validated = (items) => rowsOf(items).filter((item) => {
      const valid = !!textValue(item.text).trim() && Array.isArray(item.evidenceRefs) && item.evidenceRefs.length > 0 &&
        item.evidenceRefs.every((ref) => typeof ref === 'string' && allowed.has(ref))
      if (!valid) omitted++
      return valid
    }).map((item) => ({ text: item.text, evidenceRefs: item.evidenceRefs, verification: textValue(item.verification) }))
    const findings = validated(analysis.findings)
    const hypotheses = validated(analysis.hypotheses)
    const actions = validated(analysis.actions)
    return { agentId: analysis.agentId, status: textValue(analysis.status), summary: textValue(analysis.summary),
      findings, hypotheses, actions, omitted, missing: strings(analysis.missing) }
  })
}

export function qualityResearchEvidence(facts) {
  const quality = facts?.quality
  if (!quality?.qualityMetrics && !quality?.visionEvidence) return null
  const metrics = quality.qualityMetrics
  const vision = quality.visionEvidence
  const fields = (data, labels) => Object.entries(labels).filter(([key]) => data && Object.hasOwn(data, key))
    .map(([key, label]) => ({ label, value: evidenceValue(data[key]) }))
  return { metrics, vision,
    visionFields: fields(vision, { realImageRecordCount: '真实图像归档记录数', mockRecordCount: '模拟归档记录数',
      realDefectImageRecordCount: '含缺陷的真实图像记录数', missingInspectionArchiveCount: '缺少归档的检验单数' }),
    metricFields: fields(metrics, { inspectionRecordCount: '检验记录数', validSampleRecordCount: '可计算抽检比例的记录数',
      failedInspectionRecordCount: '不合格检验记录数', failedItemRecordCount: '不合格项目记录数', nonconformingRecordCount: '不合格品记录数' }),
    defectTypes: rowsOf(metrics?.defectTypes), sampleRates: rowsOf(metrics?.sampleRates),
    visionLabel: ({ NO_INSPECTIONS_IN_SCOPE: '本次范围内无检验单', SOURCE_RECORD_MISSING: '原始记录缺失', RECORDED_SUMMARIES_ONLY: '仅历史文字摘要',
      RECORDED: '有归档记录', PARTIAL: '归档记录不完整' })[vision?.status] || '状态未确认',
    visionNotice: ({ NO_INSPECTIONS_IN_SCOPE: '没有可核对视觉归档的检验单。', SOURCE_RECORD_MISSING: '未保存原始视觉记录，无法核验原图、检测框或模型版本。',
      RECORDED_SUMMARIES_ONLY: '历史记录仅有文字摘要，无法提供原始框或模型版本。',
      RECORDED: '以下仅展示已绑定检验单的归档检测结果。',
      PARTIAL: '部分归档记录缺失或不可读取，以下证据不完整。' })[vision?.status] || '未提供可确认的视觉来源状态。',
    records: ['RECORDED', 'PARTIAL'].includes(vision?.status) ? rowsOf(vision.records).map((record) => ({ ...record,
      imageEntries: record.schemaVersion === 2 ? ['original', 'input', 'annotated'].map((variant) => {
        const metadata = record.images?.[variant] || {}
        const label = ({ original: '原图', input: '推理输入图', annotated: '标注图' })[variant]
        const status = ['AVAILABLE', 'MISSING', 'INVALID'].includes(metadata.status) ? metadata.status : 'MISSING'
        return { variant, label, status, width: metadata.width, height: metadata.height,
          notice: status === 'AVAILABLE' ? '已保存，点击后核验读取' : status === 'INVALID' ? `${label}无效或不可读取` : `${label}未保存` }
      }) : [],
      fields: fields({ ...record, ...(record.imageRetention ? { imageRetention: ({ IMAGES_RETAINED: '图片已保留', PARTIAL_IMAGES: '图片部分保留', IMAGE_NOT_RETAINED: '历史图片未保留' })[record.imageRetention] || '图片保留状态未确认' } : {}) },
        { inspectionNo: '检验单号', batchNo: '批次', workOrderId: '工单 ID', materialId: '物料 ID', bindingSource: '检验绑定来源', imageRetention: '图片保留状态',
          recordedAt: '服务器归档时间', source: '归档来源', imageSha256: '图像 SHA-256', imageHashBasis: '图像哈希口径', model: '模型声明',
        algorithmVersion: '算法版本', modelProvenance: '模型来源', weightsVersion: '权重版本', summary: '检测摘要', defect: '是否检测出缺陷', count: '返回检测数量', maxConfidence: '最高置信度' }),
      boxes: rowsOf(record.detections).map((box) => ({ ...box, coordinates: Array.isArray(box.box) && box.box.length === 4 && box.box.every((n) => typeof n === 'number' && Number.isFinite(n))
        ? box.box.join(', ') : '未提供有效坐标' })),
    })) : [],
    summaries: rowsOf(vision?.summaries), limitations: strings(vision?.limitations).map(evidenceWarning),
  }
}

export function inspectionImageRecords(message) {
  const result = message?.result
  if (!result || message.evidenceInvalidated || message.workflow?.invalidated || result.historyEvidenceOmitted ||
      !['EVIDENCE_READY', 'INSUFFICIENT_EVIDENCE'].includes(result.verdict)) return []
  const trace = result.facts?.inspection_trace
  if (!trace?.boundImages?.length) return []
  const visionEvidence = { status: trace.visionStatus, records: trace.boundImages }
  return qualityResearchEvidence({ quality: { visionEvidence } }).records.filter(record =>
    record.imageEntries.some(entry => entry.status === 'AVAILABLE'))
}
