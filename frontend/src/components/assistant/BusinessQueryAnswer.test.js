import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import * as business from '../../utils/businessPresentation.ts'
import * as evidence from '../../utils/workbenchEvidence.js'

const dependencies = { vue, '@/utils/businessPresentation': business, '@/utils/workbenchEvidence': evidence }
const { descriptor, errors } = parse(readFileSync(new URL('./BusinessQueryAnswer.vue', import.meta.url), 'utf8'))
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'business-answer-test', inlineTemplate: true })
const code = stripTypeScriptTypes(compiled.content, { mode: 'strip' })
  .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?\s*$/gm, (_, bindings, from) =>
    `const { ${bindings.replace(/\bas\b/g, ':')} } = dependencies[${JSON.stringify(from)}];`)
  .replace('export default', 'return')
const component = new Function('dependencies', code)(dependencies)
const render = (message) => renderToString(vue.createSSRApp(component, { message }))

test('inspection shows pre-inspection work order alarms only, collapsed even for saved conversations', async () => {
  const trace = { inspection: [{ inspected_at: '2026-07-11 10:30:00' }], alarmRows: [
    { alarm_no: 'BEFORE', reported_at: '2026-07-10 10:30:00' },
    { alarm_no: 'EXACT', reported_at: '2026-07-11 10:30:00' },
    { alarm_no: 'FUTURE', reported_at: '2026-07-19 01:15:40' }, { alarm_no: 'UNDATED' }
  ] }
  const result = { facts: { inspection_trace: trace } }
  const html = await render({ result })
  assert.match(html, /<details[^>]*><summary[^>]*>[^<]*工单报警[^<]*2 条/)
  assert.match(html, /BEFORE/); assert.match(html, /EXACT/)
  assert.doesNotMatch(html, /FUTURE|UNDATED|<details[^>]* open/)
  trace.inspection = []
  assert.doesNotMatch(await render({ result }), /工单报警|FUTURE|BEFORE/)
})

test('all returned production rows, IDs, zero and caveats render with escaped cells', async () => {
  // Test-only records; the application renders only API evidence.
  const rows = Array.from({ length: 165 }, (_, i) => ({ record_id: `000${i}`, work_order_no: `TEST-WO-${i}`,
    material_name: i === 0 ? '<img src=x onerror=alert(1)>' : '测试产品', planned_quantity: '100.0000', qualified_quantity: i === 0 ? 0 : null, status: 'RUNNING' }))
  const html = await render({ result: { facts: { production: { overview: { domain: 'PRODUCTION', rows,
    totalRecords: 200, returnedRecords: 165, truncated: true, quantityBasis: 'NO_CROSS_MATERIAL_TOTAL' } } } } })
  assert.equal((html.match(/<tbody>[\s\S]*?<\/tbody>/)[0].match(/<tr>/g) || []).length, 165)
  for (const value of ['工单编号', '产品名称', '计划数量', '合格数量', '运行状态', '000164', 'TEST-WO-164', '100.0000', '未提供', '查询结果不完整', '&lt;img']) assert.ok(html.includes(value), value)
  assert.match(html, /class="numeric">0<\/td>/)
  assert.doesNotMatch(html, /<img|<script/)
})

test('changed data and omitted history show notice and no stale rows', async () => {
  for (const flags of [{ evidenceInvalidated: true }, { workflow: { invalidated: true } },
    { result: { verdict: 'DATA_CHANGED' } }, { result: { historyEvidenceOmitted: true } }]) {
    const result = { facts: { production: { overview: { rows: [{ work_order_no: 'STALE-WO' }] } } }, ...flags.result }
    const html = await render({ ...flags, result })
    assert.doesNotMatch(html, /STALE-WO|<table/)
    assert.match(html, /重新查询/)
  }
})
