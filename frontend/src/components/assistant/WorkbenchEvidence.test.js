import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import * as helpers from '../../utils/workbenchEvidence.js'
import * as labels from '../../utils/workbenchLabels.js'
import * as agents from '../../utils/agentWorkbench.js'
import * as visionImages from '../../api/visionEvidence.js'

const user = vue.reactive({ token: 'current-session' })

const dependencies = {
  vue,
  '@element-plus/icons-vue': { Document: () => null, Link: () => null },
  '@/utils/workbenchEvidence': helpers,
  '@/utils/workbenchLabels': labels,
  '@/utils/agentWorkbench': agents,
  '@/api/visionEvidence': visionImages,
  '@/stores/user': { useUserStore: () => user },
}
function loadComponent(file) {
const source = readFileSync(new URL(file, import.meta.url), 'utf8')
const { descriptor, errors } = parse(source)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'evidence-test', inlineTemplate: true })
const code = compiled.content
  .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?\s*$/gm, (_, bindings, from) =>
    `const { ${bindings.replace(/\bas\b/g, ':')} } = dependencies[${JSON.stringify(from)}];`)
  .replace(/^import\s+(\w+)\s+from\s*['"]([^'"]+)['"];?\s*$/gm, (_, binding, from) =>
    `const ${binding} = dependencies[${JSON.stringify(from)}];`)
  .replace('export default', 'return')
return new Function('dependencies', code)(dependencies)
}
for (const name of ['KnowledgeEvidence', 'AnalysisEvidence', 'VisionImageEvidence']) {
  try { dependencies[`./${name}.vue`] = loadComponent(`./${name}.vue`) } catch (error) {
    if (error.code !== 'ENOENT') throw error
  }
}
const component = loadComponent('./WorkbenchEvidence.vue')
async function render(result) {
  const app = vue.createSSRApp(component, { result })
  app.component('el-icon', { setup: (_, { slots }) => () => vue.h('span', slots.default?.()) })
  app.component('el-dialog', { setup: (_, { slots }) => () => vue.h('section', slots.default?.()) })
  return renderToString(app)
}
function overview(domain, rows, extra = {}) {
  return { overview: {
    domain, rows, totalRecords: rows.length, returnedRecords: rows.length,
    truncated: false, limit: 100, period: 'LAST_7_DAYS', dateField: 'updated_at',
    startInclusive: '2026-11-03 00:00:00', endExclusive: '2026-11-10 00:00:00',
    timeZone: 'Asia/Shanghai', keyword: null, countBasis: 'MATCHING_DATABASE_RECORDS',
    quantityBasis: 'PER_RECORD_PER_MATERIAL_UNIT; NO_CROSS_MATERIAL_TOTAL; CURRENT_RECORDS_NOT_HISTORICAL_SNAPSHOTS',
    ...extra,
  } }
}

test('all 100 returned inventory rows render with Chinese columns, range and truncation', async () => {
  const rows = Array.from({ length: 100 }, (_, i) => ({
    record_id: String(i + 1), material_name: `物料-${i + 1}`, unit: '件',
    quantity_on_hand: '11.25', quantity_reserved: '2', quantity_available: '9.25',
    warehouse_code: 'W1', event_at: '2026-11-08 15:00:00', search_text: 'INTERNAL-SEARCH',
  }))
  rows[0].material_name = '<img src=x onerror=alert(1)>'
  const html = await render({ queryMode: 'NATURAL', facts: {
    inventory: overview('INVENTORY', rows, { totalRecords: 125, truncated: true }),
  } })
  assert.match(html, /匹配 125 条 · 本次取回 100 条/)
  assert.match(html, /全部 100 条已取回明细/)
  assert.match(html, /物料-100/)
  assert.equal((html.match(/<tbody>[\s\S]*?<\/tbody>/g)[0].match(/<tr>/g) || []).length, 100)
  assert.match(html, /现存数量/)
  assert.match(html, /可用数量/)
  assert.match(html, /按记录更新时间筛选/)
  assert.match(html, /2026-11-03 00:00:00（含）至 2026-11-10 00:00:00（不含）/)
  assert.match(html, /Asia\/Shanghai/)
  assert.match(html, /不同物料数量不合计/)
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/)
  assert.doesNotMatch(html, /<img|INTERNAL-SEARCH|search_text|<details[^>]*\sopen/)
})

test('all six overview domains render real database fields without unknown legacy count cards', async () => {
  const facts = {
    inventory: overview('INVENTORY', [{ record_id: '1', quantity_on_hand: '0' }]),
    production: overview('PRODUCTION', [{ work_order_no: 'WO-1', planned_quantity: '50', completed_quantity: '10' }]),
    quality: overview('QUALITY', [{ inspection_no: 'QC-1', unqualified_quantity: '3', event_at: '2026-11-08' }], { dateField: 'inspected_at' }),
    order: overview('ORDER', [{ order_no: 'CO-1', required_delivery_date: '2026-11-15' }], { dateField: 'order_date' }),
    equipment: overview('EQUIPMENT', [{ equipment_name: '装配设备', workstation: 'A1' }]),
    aftersales: overview('AFTERSALES', [{ case_no: 'AS-1', problem_type: '屏幕异常', event_at: '2026-11-08' }], { dateField: 'opened_at' }),
  }
  const html = await render({ facts })
  for (const label of ['库存明细', '生产明细', '质量检验明细', '订单明细', '设备明细', '售后明细', '工单号', '检验单号', '订单号', '设备名称', '售后案件号', '检验时间', '案件创建时间'])
    assert.ok(html.includes(label), label)
  assert.doesNotMatch(html, /domain-stats|未知/)
  assert.match(html, /<td>0<\/td>/)
})

test('empty overview shows zero records; DATA_CHANGED hides stale tables', async () => {
  const facts = { quality: overview('QUALITY', [], { period: 'CURRENT', startInclusive: null, endExclusive: null }) }
  const html = await render({ facts })
  assert.match(html, /匹配 0 条 · 本次取回 0 条/)
  assert.match(html, /本次未返回匹配明细/)
  assert.doesNotMatch(html, /从 未提供/)
  assert.doesNotMatch(await render({ facts, verdict: 'DATA_CHANGED' }), /质量检验明细/)
})

test('natural delivery E4/E2/E3 facts and legacy evidence both remain visible', async () => {
  const lines = [{ orderItemPk: '12', remainingDeliveryQty: '80', shippableQty: null, candidateReadyQty: '20' }]
  const materials = [{ materialName: '屏幕', scenarioGapQty: '60', confirmedShortageNow: null }]
  const html = await render({ facts: { E4: { lines }, E2: { materials }, E3: { workOrders: [{ work_order_no: 'WO-DELIVERY', executionStatus: 'IN_PROGRESS', bottleneckCompletedQty: '20' }] } } })
  for (const label of ['交付数量', '物料缺口', '订单关联工单', 'WO-DELIVERY', '瓶颈工序完成量', '屏幕']) assert.ok(html.includes(label), label)
  const legacy = await render({ facts: { fulfillmentLines: lines, materials, quality: { inspectionCount: 5 } } })
  assert.match(legacy, /domain-stats/)
  assert.match(legacy, /5 <small>条/)
  assert.match(legacy, /屏幕/)
})

test('exact quality response renders inspections, all 165 inspection items and defects', async () => {
  // Fields and values follow the observed natural-live-exactquality response.
  const facts = { quality: {
    status: 'RECORDED', complete: true, inspectionCount: 1, inspectionItemCount: 165,
    nonconformingRecordCount: 1,
    warnings: ['REPEATED_INSPECTIONS_AND_DEFECT_RECORDS_MAY_REFER_TO_SAME_UNITS_DO_NOT_SUM_AS_BATCH_OUTPUT'],
    inspections: [{ inspection_id: '68', inspection_no: 'QI202607503', work_order_id: '20',
      work_report_id: '99', material_id: '9', batch_no: 'BATCH-WO202607003-1687',
      inspection_category: 'FINISHED_PRODUCT', inspection_type: 'FINAL', sample_quantity: '150.0000',
      qualified_quantity: '148.0000', unqualified_quantity: '2.0000', inspection_result: 'QUALIFIED',
      inspection_status: 'PASSED', inspected_at: '2026-07-19T01:27:46+08:00',
      relation_status: 'EXPLICIT', samplePassRate: '0.986667', sampleRateStatus: 'PER_INSPECTION_RECORDED_SAMPLE' }],
    inspectionItems: Array.from({ length: 165 }, (_, i) => ({ inspection_item_id: String(2281 + i),
      inspection_id: '68', item_code: `FP-${i + 1}`, item_name: i === 164 ? '最后一条检验项目' : '老化测试',
      standard_value: '12h无异常', unit: 'h', result: 'PENDING' })),
    nonconformingProducts: [{ nonconforming_id: '31', inspection_id: '68', work_order_id: '20',
      defect_type: '外观缺陷', quantity: '2.0000', handle_status: 'PENDING', relation_status: 'EXPLICIT' }],
  } }
  const html = await render({ queryMode: 'NATURAL', facts })
  for (const value of ['质量检验详细证据', '检验记录', '检验项目', '不合格品记录', '本次返回 165 条',
    'QI202607503', '最后一条检验项目', '外观缺陷', '关联状态', 'EXPLICIT', '0.986667', '不能合计为批次产出'])
    assert.ok(html.includes(value), value)
  assert.doesNotMatch(html, /<details[^>]*\sopen/)
})

test('exact equipment includes usage, contradictory alarms, maintenance relations and warnings', async () => {
  const facts = { equipment: {
    complete: false, equipmentCount: null,
    warnings: ['TRUNCATED_SNAPSHOT_TOTALS_UNAVAILABLE', 'CONTRADICTORY_MAINTENANCE_ALARM:7',
      'EQUIPMENT_ONLY_ALARMS_AND_MAINTENANCE_DO_NOT_ESTABLISH_ORDER_CAUSALITY_OR_HISTORICAL_NORMALITY'],
    equipment: [{ equipment_id: '3', equipment_name: '设备甲', status: 'RUNNING' }],
    observedUsage: [{ source_table: 'work_report', source_id: '99', work_order_id: '20', equipment_id: '3' }],
    alarms: [{ alarm_id: '6', alarm_no: 'ALARM-6', equipment_id: '3', work_order_id: '20',
      alarm_type: '<script>unsafe()</script>', relation_status: 'CONTRADICTORY' }],
    maintenanceRecords: [{ maintenance_id: '7', equipment_id: '4', alarm_id: '6',
      downtime_minutes: '25', relationStatus: 'CONTRADICTORY', maintenance_result: '待核验' }],
  } }
  const html = await render({ facts })
  for (const value of ['设备详细证据', '设备台账', '设备使用关联记录', '报警记录', '维护记录',
    'ALARM-6', '维护与报警关联矛盾：7', '关联状态', 'CONTRADICTORY', '不能证明与订单的因果关系',
    '以下仅展示本次返回的全部明细', '记录总数不可确定', '&lt;script&gt;unsafe()&lt;/script&gt;'])
    assert.ok(html.includes(value), value)
  assert.doesNotMatch(html, /<script>|domain-stats/)
})

test('exact aftersales preserves foreign order IDs, task/closure records, trace references and path limits', async () => {
  const facts = { aftersales: {
    complete: true, caseCount: 1, taskCount: 1, closureCount: 1,
    tracePathsComplete: false, tracePathLimit: 100,
    warnings: ['TRACE_PATH_LIMIT_REACHED', 'CONTRADICTORY_CASE:after_sales_case:9',
      'TRACE_RELATIONS_ARE_NOT_ROOT_CAUSE_PROOF; BATCH_LABEL_MATCHES_ARE_CANDIDATES'],
    cases: [{ case_id: '9', case_no: 'CASE-9', order_id: '999', delivery_id: '14',
      quality_inspection_id: '68', problem_type: '屏幕异常', relation_status: 'CONTRADICTORY' }],
    tasks: [{ task_id: '5', task_no: 'TASK-5', case_no: 'CASE-9', task_type: 'RECHECK', status: 'RUNNING' }],
    closures: [{ closure_id: '2', case_no: 'CASE-9', recheck_passed: '1', customer_confirmed: '0' }],
    tracePaths: [{ relationStatus: 'CANDIDATE', relation: 'MATERIAL_AND_BATCH_LABEL_MATCH_ONLY',
      sourceRecordRefs: ['after_sales_case:9', 'material_batch:22'] }],
  } }
  const html = await render({ facts })
  for (const value of ['售后详细证据', '售后案件', '售后任务', '结案记录', '追溯路径', '关联订单 ID',
    '<td>999</td>', '关联状态', 'CONTRADICTORY', 'TASK-5', '客户确认记录', '来源记录链路',
    'after_sales_case:9 → material_batch:22', 'CANDIDATE', '本次路径上限 100 条',
    '追溯路径达到返回上限', '售后案件关联矛盾：after_sales_case:9'])
    assert.ok(html.includes(value), value)
})

test('empty detailed tables stay explicit and changed data hides detailed records and warnings', async () => {
  const facts = { quality: { complete: true, inspections: [], inspectionItems: [], nonconformingProducts: [], warnings: ['CONTRADICTORY_RELATION:quality_inspection:5'] } }
  const html = await render({ facts })
  assert.match(html, /本次返回 0 条/)
  assert.equal((html.match(/本次未返回此类记录/g) || []).length, 3)
  const changed = await render({ facts, verdict: 'DATA_CHANGED', queryRefs: ['reference'] })
  assert.doesNotMatch(changed, /质量检验详细证据|quality_inspection:5/)
})

test('research and analysis panels escape text, validate citations and reveal unavailable knowledge', async () => {
  const hit = { evidence_ref: 'release:P01:c1', release_id: 'release', document_id: 'P01', document_version: 'hash',
    chunk_id: 'c1', title: '<img src=x onerror=alert(1)>', text: 'Source excerpt', page_start: 2, page_end: 3,
    source_url: 'javascript:alert(1)', authority_type: 'PUBLIC_RESEARCH' }
  const result = { queryRefs: ['q1'], knowledgeEvidence: [{ agentId: 'QUALITY', status: 'MATCHES',
    release_id: 'release', hits: [hit], mode_used: 'vector', graph_status: 'UNAVAILABLE_OR_STALE' }],
    analyses: [{ agentId: 'QUALITY', status: 'COMPLETED', summary: '<script>unsafe()</script>',
      findings: [{ text: '业务观察', evidenceRefs: ['q1'] }, { text: '伪造结论', evidenceRefs: ['fake'] }],
      hypotheses: [{ text: '待验证原因', evidenceRefs: [hit.evidence_ref], verification: '复查样品' }],
      actions: [], missing: ['缺少独立样本'] }] }
  const html = await render(result)
  for (const value of ['公开资料', '向量检索', '图谱不可用', '第 2–3 页', 'Source excerpt', '业务观察',
    '待验证原因', '复查样品', '论文观点不等于业务事实', '缺少独立样本', '&lt;img', '&lt;script&gt;']) assert.ok(html.includes(value), value)
  assert.doesNotMatch(html, /伪造结论|href="javascript:|<script>|<img|<details[^>]*\sopen/)
  assert.match(await render({ knowledgeEvidence: [{ agentId: 'QUALITY', status: 'NO_MATCHES' }] }), /公开资料未命中/)
  assert.match(await render({ knowledgeEvidence: [{ agentId: 'QUALITY', status: 'UNAVAILABLE' }] }), /知识检索不可用/)
  assert.doesNotMatch(await render({ ...result, verdict: 'DATA_CHANGED' }), /业务观察|待验证原因|Source excerpt|q1/)
})

test('quality metrics and vision preserve real sample basis and summaries without invented raw evidence', async () => {
  const metrics = { basis: 'PER_INSPECTION_RECORDS_NOT_UNIQUE_PRODUCTS', inspectionRecordCount: 2,
    validSampleRecordCount: 1, failedInspectionRecordCount: 1, failedItemRecordCount: 3,
    nonconformingRecordCount: 2, defectTypes: [{ defectType: 'Mura', recordCount: 2 }],
    sampleRates: [{ inspectionId: '68', samplePassRate: '0.986667', status: 'PER_INSPECTION_RECORDED_SAMPLE' }], batchPassRate: null }
  const vision = { status: 'RECORDED_SUMMARIES_ONLY', records: [],
    summaries: [{ inspectionId: '68', itemCode: 'YOLO', text: '仅保存检测摘要', source: 'RECORDED_TEXT_NOT_RAW_VISION' }], limitations: [] }
  const html = await render({ facts: { quality: { qualityMetrics: metrics, visionEvidence: vision } } })
  for (const value of ['质量统计口径', '按检验记录统计', '不能合计为批次产出', '0.986667', '仅保存检测摘要',
    '历史记录仅有文字摘要', '无法提供原始框或模型版本']) assert.ok(html.includes(value), value)
  assert.doesNotMatch(html, /检测框 0|模型版本：未知/)
  const recorded = await render({ facts: { quality: { visionEvidence: { ...vision, status: 'RECORDED',
    records: [{ recordId: 'record-1', inspectionId: '68', recordedAt: '2026-11-09T09:00:00', imageSha256: 'hash-1',
      model: 'YOLO', algorithmVersion: 'v1', modelProvenance: 'DEMO', mock: true, count: 1,
      detections: [{ className: '<img>', confidence: 0.9, box: [1, 2, 3, 4] }] }] } } } })
  for (const value of ['record-1', 'hash-1', '模拟记录', '不计为真实缺陷', '图像数不等于产品数', '&lt;img&gt;', '1, 2, 3, 4']) assert.ok(recorded.includes(value), value)
  assert.match(await render({ result: null, facts: { quality: { visionEvidence: { status: 'SOURCE_RECORD_MISSING' } } } }), /未保存原始视觉记录/)
  const noInspections = await render({ result: null, facts: { quality: { visionEvidence: { status: 'NO_INSPECTIONS_IN_SCOPE' } } } })
  assert.match(noInspections, /本次范围内无检验单/)
  assert.doesNotMatch(noInspections, /原始记录缺失|未保存原始视觉记录/)
})

test('history evidence omission is visible without pretending saved rows are complete', async () => {
  const html = await render({ historyEvidenceOmitted: true, facts: {} })
  assert.match(html, /历史证据因保存空间限制未保留/)
  assert.doesNotMatch(html, /全部明细/)
})

const imageRecord = () => ({ inspectionId: '68', recordId: '5c0cc43c-90b8-4f6b-8c6e-1a986ac5d0d5',
  schemaVersion: 2, inspectionNo: 'QI-68', batchNo: 'BATCH-7', mock: false, recordedAt: '2026-11-09T09:00:00',
  imageRetention: 'PARTIAL_IMAGES', images: { original: { status: 'AVAILABLE', width: 2400, height: 1800 },
    input: { status: 'AVAILABLE', width: 1280, height: 960 }, annotated: { status: 'MISSING', reason: 'NO_RESULT_IMAGE' } } })
const imageResult = () => ({ facts: { quality: { visionEvidence: { status: 'RECORDED', records: [imageRecord()] } } } })

test('scoped inspection images are available only while verified evidence is present', () => {
  const message = { result: { verdict: 'EVIDENCE_READY', facts: {
    inspection_trace: { visionStatus: 'RECORDED', boundImages: [imageRecord()] }
  } } }
  const [image] = helpers.inspectionImageRecords(message)
  assert.equal(image.recordId, imageRecord().recordId)
  assert.equal(image.imageEntries.find(entry => entry.variant === 'original').status, 'AVAILABLE')
  assert.deepEqual(helpers.inspectionImageRecords({ ...message, evidenceInvalidated: true }), [])
  message.result.historyEvidenceOmitted = true
  assert.deepEqual(helpers.inspectionImageRecords(message), [])
})

test('image evidence exposes saved variants and missing states while old/changed history cannot open images', async () => {
  const current = await render(imageResult())
  for (const label of ['QI-68', 'BATCH-7', '原图 / 标注图对照', '查看推理输入图', '标注图未保存', '推理输入图尺寸']) assert.ok(current.includes(label), label)
  assert.doesNotMatch(current, /原图未保留|<img|blob:/)
  const history = imageResult()
  history.facts.quality.visionEvidence.records = [{ inspectionId: '68', recordId: 'old', schemaVersion: 1 }]
  const old = await render(history)
  assert.match(old, /历史记录未保留图片/)
  assert.doesNotMatch(old, /原图 \/ 标注图对照|查看推理输入图/)
  assert.doesNotMatch(await render({ ...imageResult(), verdict: 'DATA_CHANGED' }), /原图 \/ 标注图对照|BATCH-7/)
})

// A minimal Vue host exercises component lifecycle and events without a browser or DOM package.
function mountEvidence(result) {
  const node = (type, text = '') => ({ type, text, children: [], props: {}, parent: null })
  const renderer = vue.createRenderer({
    createElement: (type) => node(type), createText: (text) => node('text', text), createComment: () => node('comment'),
    setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text },
    patchProp: (n, key, _, value) => { n.props[key] = value },
    insert: (n, parent, anchor) => { n.parent = parent; const at = parent.children.indexOf(anchor); if (at < 0) parent.children.push(n); else parent.children.splice(at, 0, n) },
    remove: (n) => { const list = n.parent?.children; if (list) list.splice(list.indexOf(n), 1) },
    parentNode: (n) => n.parent, nextSibling: (n) => n.parent?.children[n.parent.children.indexOf(n) + 1],
  })
  const state = vue.ref(result)
  const root = node('root')
  const app = renderer.createApp({ setup: () => () => vue.h(component, { result: state.value }) })
  app.component('el-icon', { setup: (_, { slots }) => () => vue.h('span', slots.default?.()) })
  app.component('el-dialog', { setup: (_, { slots }) => () => vue.h('section', slots.default?.()) })
  app.mount(root)
  const all = () => { const found = []; const visit = (n) => { found.push(n); n.children.forEach(visit) }; visit(root); return found }
  return { app, state, all, click: (label) => { const button = all().find((n) => n.type === 'button' && n.text.includes(label)); assert.ok(button, label); return button.props.onClick() } }
}

test('preview releases images on close, record change, logout, DATA_CHANGED and unmount', async (t) => {
  let count = 0
  const revoked = []
  t.mock.method(URL, 'createObjectURL', () => `blob:test-${++count}`)
  t.mock.method(URL, 'revokeObjectURL', (url) => revoked.push(url))
  t.mock.method(globalThis, 'fetch', async () => new Response('image', { headers: { 'Content-Type': 'image/png' } }))
  const flush = async () => { await new Promise((resolve) => setImmediate(resolve)); await vue.nextTick() }
  const mounted = mountEvidence(imageResult())
  for (const reason of ['close', 'record', 'logout', 'changed', 'unmount']) {
    user.token = 'current-session'
    mounted.state.value = imageResult()
    await flush()
    await mounted.click('原图 / 标注图对照')
    await flush()
    assert.ok(mounted.all().some((n) => n.type === 'img' && n.props.src?.startsWith('blob:')))
    if (reason === 'close') await mounted.click('关闭图片')
    if (reason === 'record') mounted.state.value.facts.quality.visionEvidence.records[0].recordId = '30ea7e0b-84d9-4fb4-80eb-082b50503da0'
    if (reason === 'logout') user.token = ''
    if (reason === 'changed') mounted.state.value.verdict = 'DATA_CHANGED'
    if (reason === 'unmount') mounted.app.unmount()
    await flush()
    assert.equal(revoked.length, count, reason)
    assert.ok(!mounted.all().some((n) => n.type === 'img' && n.props.src?.startsWith('blob:')), reason)
  }
})

test('partial quality snapshots and real archive counts describe images, never product totals', async () => {
  const html = await render({ facts: { quality: { qualityMetrics: { complete: false, inspectionRecordCount: null },
    visionEvidence: { status: 'PARTIAL', realImageRecordCount: 2, mockRecordCount: 1, realDefectImageRecordCount: 1,
      missingInspectionArchiveCount: 3, records: [], limitations: ['CORRUPT_RECORD', 'MODEL_LABEL_AND_ALGORITHM_VERSION_DO_NOT_PROVE_WEIGHT_VERSION'] } } } })
  for (const value of ['质量统计不完整', '真实图像归档记录数', '模拟归档记录数', '缺少归档的检验单数', '归档记录损坏', '不能证明权重版本']) assert.ok(html.includes(value), value)
})

test('validated graph paths show original quotation, page, relation and a safe public link', async () => {
  const hit = { evidence_ref: 'release:P01:c1', release_id: 'release', document_id: 'P01', document_version: 'hash',
    chunk_id: 'c1', title: 'Mura paper', text: 'Original quoted evidence.', page_start: 2, page_end: 2,
    source_url: 'https://example.org/paper.pdf', authority_type: 'PUBLIC_RESEARCH' }
  const path = { claim_id: 'C1', scope: 'RESEARCH_ONLY', authority_type: 'PUBLIC_RESEARCH', review_status: 'SOURCE_VERIFIED',
    graph_release_id: 'graph', document_id: 'P01', document_version: 'hash', page: 2, evidence_refs: [hit.evidence_ref],
    relation: 'DETECTS', subject_name: 'Optical method', object_name: 'Mura', quote: 'quoted evidence', statement: 'Research statement' }
  const html = await render({ knowledgeEvidence: [{ agentId: 'QUALITY', status: 'MATCHES', release_id: 'release', graph_release_id: 'graph',
    mode_used: 'graph_assisted', graph_status: 'MATCHES', hits: [hit], graph_paths: [path, { ...path, quote: 'Forged quotation' }] }] })
  for (const value of ['图谱辅助检索', 'Optical method → 检测 → Mura', 'quoted evidence', '第 2 页', 'href="https://example.org/paper.pdf"', 'rel="noopener noreferrer"']) assert.ok(html.includes(value), value)
  assert.doesNotMatch(html, /Forged quotation/)
})
