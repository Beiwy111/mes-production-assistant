import test from 'node:test'
import assert from 'node:assert/strict'
import { analyticalQuestion, analysisVisuals } from './analysisPresentation.js'
import { businessSnapshot } from './businessPresentation.ts'
import { resultCsv } from './knowledgeSources.js'

const group = { material_id: 7, material_name: '面板', inspection_type: '首检', inspection_category: '过程检验', unit: '件' }
const point = (day, value, extra = {}) => ({ ...group, inspection_day: '2026-06-' + day, sample_fail_percent: value, ...extra })
const quality = { overview: { domain: 'QUALITY', rows: [{ record_id: 22, extra_field: '原始字段' }], totalRecords: 1, returnedRecords: 1 },
  qualityTrend: [point('01', '0'), point('02', null), point('05', '8.5'), point('01', '95', { inspection_type: '复检' })],
  qualityStatistics: { inspectionCount: 12, validRecordCount: 9, pendingCount: 0, conflictCount: null,
    defectDistribution: Array.from({ length: 12 }, (_, i) => ({ defect_type: '分类-' + i, record_count: i + 1 })),
    sampleGroups: [], batchGroups: [], periodComparisons: [{ basis: 'MOM', previousStartInclusive: '2026-05-01',
      groups: [{ ...group, previous_fail_percent: '5', current_fail_percent: '0', difference_percentage_points: '-5' }] }] } }
const message = (question = '分析质量趋势') => ({ status: 'COMPLETED', result: { facts: { quality }, userQuery: question } })

test('quality trends keep units, inspection types, unknown samples and missing dates distinct', () => {
  const { metrics, charts } = analysisVisuals(message())
  assert.deepEqual(metrics.map(m => m.value), [12, 9, 0])
  const trend = charts.find(c => c.kind === 'line')
  assert.equal(trend.series.length, 1)
  assert.match(trend.series[0].label, /面板.*件.*过程检验.*首检/)
  assert.deepEqual(trend.series[0].points.map(([, value]) => value), [0, null, null, 8.5])
  const defects = charts.find(c => c.kind === 'bar')
  assert.match(defects.title, /前 8 项/)
  assert.equal(defects.unit, '条')
  assert.equal(defects.series[0].points.length, 8)
  assert.deepEqual(defects.series[0].points[0], ['分类-11', 12])
})

test('visual limits do not clip database fields, period definitions, defect records or CSV', () => {
  const input = message()
  const tables = businessSnapshot(input).tables
  assert.equal(tables.find(t => t.key === 'quality-stats-defects').rows.length, 12)
  assert.ok(tables.find(t => t.key === 'quality-overview').columns.some(c => c.key === 'extra_field'))
  const period = tables.find(t => t.key === 'quality-stats-period-0').rows[0]
  assert.deepEqual([period.previousStartInclusive, period.difference_percentage_points], ['2026-05-01', '-5'])
  const csv = resultCsv(input)
  for (const value of ['分类-0', '分类-11', '原始字段', 'previous_fail_percent', 'validRecordCount', 'totalRecords']) assert.ok(csv.includes(value))
})

test('a sample rate card identifies its denominator and disappears for mixed units or missing sample sizes', () => {
  const row = { ...group, valid_sample_quantity: '40', sample_fail_percent: '2.50' }
  const input = { result: { userQuery: '分析', facts: { quality: { qualityStatistics: { sampleGroups: [row] } } } } }
  const card = analysisVisuals(input).metrics.find(m => m.unit === '%')
  assert.equal(card.value, '2.50')
  assert.match(card.detail, /首检.*40件/)
  input.result.facts.quality.qualityStatistics.sampleGroups.push({ ...row, unit: '台' })
  assert.ok(!analysisVisuals(input).metrics.some(m => m.unit === '%'))
  input.result.facts.quality.qualityStatistics.sampleGroups = [{ ...row, valid_sample_quantity: null }]
  assert.ok(!analysisVisuals(input).metrics.some(m => m.unit === '%'))
})

test('list requests, invalidated evidence and absent measurements generate no dashboard', () => {
  for (const query of ['列出质检单', '列出有风险的订单明细']) assert.equal(analyticalQuestion(message(query).result), false)
  assert.equal(analyticalQuestion({ facts: { quality: { ...quality, presentationMode: 'LIST' } } }), false)
  assert.equal(analyticalQuestion({ facts: { quality: { presentationMode: 'ANALYSIS' } }, userQuery: '接着说' }), true)
  const invalid = message()
  for (const input of [message('列出质检单'), { ...invalid, evidenceInvalidated: true }, { ...invalid, workflow: { invalidated: true } },
    { result: { ...invalid.result, historyEvidenceOmitted: true } }, { result: { ...invalid.result, verdict: 'DATA_CHANGED' } },
    { result: { userQuery: '分析', facts: { quality: { qualityTrend: [point('01', null), point('02', '180')] } } } }])
    assert.deepEqual(analysisVisuals(input), { metrics: [], charts: [] })
})

test('production breakdown requires complete uniquely identified records and never adds material quantities', () => {
  const overview = { rows: [{ record_id: 1, status: '完成', quantity: 200 }, { record_id: 2, status: '运行', quantity: 300 }],
    totalRecords: 2, returnedRecords: 2 }
  const input = { result: { userQuery: '分析工单状态分布', facts: { production: { overview } } } }
  const { charts, metrics } = analysisVisuals(input)
  assert.deepEqual(charts[0].series[0].points, [['完成', 1], ['运行', 1]])
  assert.deepEqual(metrics[0], { label: '工单记录', value: 2, unit: '条' })
  overview.totalRecords = 3
  assert.deepEqual(analysisVisuals(input), { metrics: [], charts: [] })
})
