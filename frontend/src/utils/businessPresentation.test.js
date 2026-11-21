import test from 'node:test'
import assert from 'node:assert/strict'
import { businessSnapshot, hasBusinessTables, businessSummary, compactBusinessAnswer } from './businessPresentation.ts'

// Synthetic fixtures exercise server response shapes; never used by the application.
const message = (facts, extra = {}) => ({ result: { verdict: 'EVIDENCE_READY', plan: { intents: ['PRODUCTION'] }, facts, ...extra } })
const overview = (rows, extra = {}) => ({ overview: { rows, totalRecords: rows.length, returnedRecords: rows.length,
  domain: 'PRODUCTION', period: 'CURRENT', truncated: false, ...extra } })

test('quality trends keep server sample scope, unknown rates and every inspection detail', () => {
  const trend = [{ inspection_day: '2026-11-01', material_name: '显示器', unit: '台', inspection_record_count: 2,
    valid_sample_quantity: '100', valid_qualified_quantity: '98', sample_pass_percent: '98.00',
    invalid_sample_record_count: 0, sample_basis: '按返回记录的有效样本加权，不代表批次放行' }]
  const raw = [{ record_id: '1' }, { record_id: '2' }]
  const result = businessSnapshot(message({ quality: { ...overview(raw), qualityTrend: trend } }))
  assert.deepEqual(result.tables[0].rows, trend)
  assert.ok(result.tables[0].columns.some(c => c.key === 'sample_pass_percent' && c.label.includes('抽检')))
  assert.ok(result.tables.some(t => t.rows === raw || JSON.stringify(t.rows) === JSON.stringify(raw)))
  assert.equal(businessSnapshot(message({ quality: { qualityTrend: [{ sample_pass_percent: null }] } })).tables[0].rows[0].sample_pass_percent, null)
})

test('maintenance summary preserves unknown duration and its actual recorded-value basis', () => {
  const rows = [{ equipment_id: '1', equipment_name: '装配台', recorded_downtime_minutes: null,
    maintenance_record_count: 1, known_duration_count: 0, missing_duration_count: 1, duration_basis: '未合并重叠区间' }]
  const result = businessSnapshot(message({ maintenance: { maintenanceSummary: rows } }))
  assert.deepEqual(result.tables[0].rows, rows)
  assert.ok(result.tables[0].columns.some(c => c.key === 'recorded_downtime_minutes' && c.label.includes('登记')))
  assert.ok(result.tables[0].columns.some(c => c.key === 'duration_basis'))
})

test('maintenance interval summary puts equipment before durations and preserves unknowns and basis', () => {
  const rows = [{ equipment_id: '1', equipment_code: 'EQ-1', equipment_name: '装配台',
    recorded_downtime_minutes: '240', interval_union_minutes: '180', valid_interval_count: 2,
    missing_interval_count: 1, interval_basis: '仅本次返回维修区间，不代表真实净停机' }]
  const table = businessSnapshot(message({ maintenance: { maintenanceSummary: rows } })).tables[0]
  assert.deepEqual(table.columns.slice(0, 4).map(c => c.key), ['equipment_name', 'equipment_code', 'recorded_downtime_minutes', 'interval_union_minutes'])
  assert.deepEqual(table.rows, rows)
  assert.ok(table.columns.some(c => c.key === 'interval_basis'))
})

test('production overview preserves every row, precision, zero, missing fields and identity', () => {
  const rows = Array.from({ length: 165 }, (_, i) => ({ record_id: String(i), work_order_no: `TEST-WO-${i}`,
    material_name: '测试产品', planned_quantity: '9007199254740993.0000', qualified_quantity: i === 0 ? 0 : null, status: 'RUNNING' }))
  const input = message({ production: overview(rows) })
  const table = businessSnapshot(input).tables[0]
  assert.equal(table.rows.length, 165)
  assert.deepEqual(table.rows, rows)
  assert.deepEqual(table.columns.slice(0, 5).map(c => c.label), ['工单编号', '产品名称', '计划数量', '合格数量', '运行状态'])
  assert.ok(table.columns.some(c => c.key === 'record_id'))
  assert.equal(table.rows[0].qualified_quantity, 0)
  assert.equal(table.rows[1].qualified_quantity, null)
  assert.equal(table.rows[0].planned_quantity, '9007199254740993.0000')
  assert.match(businessSummary(input), /165/)
})

test('backend E3 and legacy production work orders have tables without fabricated product names or totals', () => {
  const rows = [{ work_order_id: '0009', work_order_no: 'TEST-9', executionStatus: 'IN_PROGRESS', status: 'RUNNING', qualified_quantity: '0' }]
  for (const facts of [{ E3: { workOrders: rows, recordCompleteness: 'PARTIAL' } }, { productionWorkOrders: rows }]) {
    const snapshot = businessSnapshot(message(facts))
    assert.equal(snapshot.tables[0].rows[0].material_name, undefined)
    assert.equal(snapshot.tables[0].total, null)
    assert.ok(snapshot.tables[0].columns.some(c => c.key === 'work_order_id'))
    assert.ok(hasBusinessTables(message(facts)))
    assert.equal(businessSummary(message(facts)), '')
  }
})

test('alarm trend uses server counts and preserves every original alarm row', () => {
  const rows = [{ record_id: '1', alarm_no: 'AL-1' }, { record_id: '2', alarm_no: 'AL-2' }]
  const trend = [{ alarm_day: '2026-11-15', equipment_name: '点胶机', alarm_record_count: 2, alarm_count_basis: '当前状态' }]
  const tables = businessSnapshot(message({ alarms: { ...overview(rows), alarmTrend: trend } })).tables
  assert.equal(tables[0].key, 'alarm-daily-trend')
  assert.deepEqual(tables[0].rows, trend)
  assert.deepEqual(tables[1].rows, rows)
})

test('mixed queries retain full answer; inventory rows are never duplicated', () => {
  const input = message({ production: overview([]), inventory: overview([{ record_id: 'INV-1' }]) }, { plan: { intents: ['PRODUCTION', 'INVENTORY'] } })
  assert.equal(businessSnapshot(input).tables.length, 1)
  assert.equal(businessSummary(input), '')
  assert.equal(hasBusinessTables(message({ inventory: overview([]) })), false)
})

test('empty overview is explicit; unknown counts stay unknown and truncation/caveats stay visible', () => {
  const input = message({ production: overview([], { totalRecords: null, returnedRecords: null, truncated: true,
    quantityBasis: 'NO_CROSS_MATERIAL_TOTAL', startInclusive: '2026-01-01', endExclusive: '2026-01-02', timeZone: 'Asia/Shanghai' }) },
  { missing: ['SERVER_LIMITATION'] })
  const snapshot = businessSnapshot(input)
  assert.equal(snapshot.tables[0].rows.length, 0)
  assert.equal(snapshot.tables[0].total, '未提供')
  assert.ok(snapshot.tables[0].partial)
  assert.match(snapshot.tables[0].notes.join(' '), /不同物料数量不合计/)
  assert.ok(snapshot.notices.includes('SERVER_LIMITATION'))
  assert.equal(businessSummary(input), '')
})

test('invalidated and omitted history never return stale evidence', () => {
  const input = message({ production: overview([{ work_order_no: 'STALE' }]) })
  for (const changed of [ { ...input, evidenceInvalidated: true }, { ...input, workflow: { invalidated: true } },
    { result: { ...input.result, verdict: 'DATA_CHANGED' } }, { result: { ...input.result, historyEvidenceOmitted: true } }]) {
    const snapshot = businessSnapshot(changed)
    assert.equal(snapshot.tables.length, 0)
    assert.ok(snapshot.notices.length)
    assert.equal(hasBusinessTables(changed), false)
    assert.equal(businessSummary(changed), '')
  }
})

test('missing or malformed row arrays cannot be described as a complete empty result', () => {
  for (const rows of [undefined, [null, 7]]) {
    const input = message({ production: overview([], { rows, totalRecords: 0, returnedRecords: 0 }) })
    assert.equal(businessSnapshot(input).tables[0].partial, true)
    assert.equal(businessSummary(input), '')
  }
})

test('detailed quality/equipment/aftersales preserve returned rows and warnings', () => {
  const input = message({ quality: { inspections: [{ inspection_id: '1', sample_quantity: '0' }], inspectionItems: [], complete: false,
    warnings: ['TRUNCATED_SNAPSHOT_TOTALS_UNAVAILABLE'] },
  aftersales: { cases: [{ case_id: '2', order_id: '999' }], tracePaths: [{ sourceRecordRefs: ['a:2', 'b:3'] }], tracePathsComplete: false, tracePathLimit: 100 } })
  const snapshot = businessSnapshot(input)
  assert.equal(snapshot.tables.length, 4)
  assert.ok(snapshot.notices.some(n => n.includes('记录总数不可确定')))
  assert.ok(snapshot.notices.some(n => n.includes('100')))
  assert.equal(businessSummary(input), '')
})


test('all overview domains use compact narration without duplicating rows; invalid evidence retains the original response', () => {
  for (const domain of ['production', 'order', 'quality', 'equipment']) {
    const input = message({ [domain]: overview([{ record_id: 1, material_name: 'Do not duplicate this row' }], { domain: domain.toUpperCase() }) })
    assert.match(compactBusinessAnswer(input), /已查询到以下/)
    assert.doesNotMatch(compactBusinessAnswer(input), /Do not duplicate|匹配|本次展示/)
    assert.equal(compactBusinessAnswer({ evidenceInvalidated: true, ...input }), '')
  }
})


test('authorized dispatch and cross-order risks retain every server row and conclusion', () => {
  const dispatch = [{ dispatch_no: 'D1', operator_name: '测试人员', step_name: '装配', assigned_quantity: '8' }]
  const risks = [{ order_no: 'SO1', remainingDeliveryQty: null, risk_status: 'UNKNOWN', risk_reason: '归属待核实' }]
  const snapshot = businessSnapshot(message({ production: { dispatchRows: dispatch, dispatchConclusion: '本人派工范围' }, order: { crossOrderRisk: risks } }))
  assert.deepEqual(snapshot.tables.find(t => t.key === 'work-order-dispatch').rows, dispatch)
  assert.deepEqual(snapshot.tables.find(t => t.key === 'cross-order-risk').rows, risks)
  assert.ok(snapshot.notices.includes('本人派工范围'))
})


test('shared-resource tables keep server gaps, missing coverage, and scope limitations intact', () => {
  const rows = [{ order_no: 'SO2', material_name: 'PCB', scenario_gap_qty: '40', risk_status: 'SHARED_POOL_SCENARIO_GAP' }]
  const coverage = [{ order_no: 'SO3', risk_reason: 'BOM 版本待核实' }]
  const conflicts = [{ left_order_no: 'SO1', right_order_no: 'SO2', resource_type: 'EQUIPMENT', overlap_minutes: '60' }]
  const snapshot = businessSnapshot(message({ shared_material: { rows, demands: [], coverage, basis: '供料情景', scope: '当前范围' }, capacity_conflicts: { rows: conflicts, coverage: [] }, order: { resourceLimitations: ['没有额外资源权限'] } }))
  assert.deepEqual(snapshot.tables.find(t => t.key === 'shared-material').rows, rows)
  assert.deepEqual(snapshot.tables.find(t => t.key === 'capacity-conflicts').rows, conflicts)
  assert.deepEqual(snapshot.tables.find(t => t.key === 'resource-coverage').rows, coverage)
  assert.ok(snapshot.notices.includes('供料情景'))
  assert.ok(snapshot.notices.includes('没有额外资源权限'))
})
