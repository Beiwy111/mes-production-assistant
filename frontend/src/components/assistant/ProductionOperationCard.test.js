import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'

function compile(name) {
  const children = {}
  const filename = name + '.vue'
  const { descriptor } = parse(readFileSync(new URL(filename, import.meta.url), 'utf8'), { filename })
  let code = compileScript(descriptor, { id: name, inlineTemplate: true }).content
    .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?\s*$/gm, (_, bindings) => 'const { ' + bindings.replace(/\bas\b/g, ':') + ' } = vue;')

    .replace('export default', 'return')
  code = code.replace(/import (\w+) from ['"]\.\/(\w+)\.vue['"]/g, (_, key, file) => { children[key] = compile(file); return '' })
  return new Function('vue', ...Object.keys(children), code)(vue, ...Object.values(children))
}
const component = compile('ProductionOperationCard')
const preview = { status: 'PENDING', confirmation: { title: '智能派工', params: {
  planId: 'PP901', recommendations: [{ processStep: '装配', recommendedOperatorName: '操作员<甲>', equipmentCode: 'EQ-1' }],
} } }
const render = (flow, latest = true) => renderToString(vue.createSSRApp(component, { flow, latest }))

test('the production card renders actual dispatch rows and shows approval only for a current live proposal', async () => {
  const current = await render(preview)
  for (const text of ['PP901', '装配', '操作员&lt;甲&gt;', 'EQ-1', '确认执行']) assert.ok(current.includes(text), text)
  assert.doesNotMatch(await render(preview, false), /确认执行/)
  assert.doesNotMatch(await render({ ...preview, status: 'REJECTED' }), /确认执行/)
  const partial = await render({ ...preview, status: 'EXECUTED', result: { allDone: false, message: '有物料未领齐' } })
  assert.match(partial, /部分完成/)
  assert.match(partial, /有物料未领齐/)
  assert.doesNotMatch(partial, /确认执行|已执行/)
})

test('collecting a missing input only offers preview, and uncertainty locks the original decision', async () => {
  const collecting = await render({ status: 'NEEDS_INPUT', missingFields: [{ name: 'reportQty', label: '本次完成数量' }] })
  assert.match(collecting, /本次完成数量/)
  assert.match(collecting, /继续预览/)
  assert.doesNotMatch(collecting, /确认执行/)
  const uncertain = await render({ ...preview, uncertainDecision: 'APPROVE' })
  assert.match(uncertain, /核对并重试原确认/)
  assert.doesNotMatch(uncertain, />取消</)
})

test('eligible business targets appear as selectable options instead of requiring memorized identifiers', async () => {
  const html = await render({ status: 'NEEDS_INPUT', fields: {}, missingFields: [{ name: 'orderId', label: '待计划订单' }],
    options: { orderId: [{ value: 'CO801', label: 'CO801 · 面板<甲> · 50 台', description: '交期 2026-12-12' }] } })
  assert.match(html, /<select/)
  assert.match(html, /value="CO801"/)
  assert.match(html, /CO801 · 面板&lt;甲&gt; · 50 台/)
  assert.match(html, /交期 2026-12-12/)
  assert.doesNotMatch(html, /确认执行/)
})

test('planning compares actual schedules and conflicts before selecting a strategy for preview', async () => {
  const html = await render({ status: 'NEEDS_INPUT', dataNote: '利用率为算法估算', missingFields: [{ name: 'schedulingMode', label: '排产策略' }],
    schemes: [
      { key: 'DELIVERY', label: '交期优先', finishDate: '2026-12-10', equipmentUtilization: 76, delayDays: 0, canSubmit: true, summary: '优先保障交付', schedules: [{ stepName: '模组装配', equipmentCode: 'EQ101' }], conflicts: [] },
      { key: 'BALANCE', label: '负载均衡', finishDate: '2026-12-11', equipmentUtilization: 68, delayDays: 1, canSubmit: false, schedules: [], conflicts: [{ label: '设备冲突', detail: 'EQ102 已占用' }] },
    ] })
  for (const text of ['交期优先', '2026-12-10', '76', '优先保障交付', '模组装配', 'EQ101', '负载均衡', 'EQ102 已占用', '继续预览', '利用率为算法估算']) assert.ok(html.includes(text), text)
  assert.match(html, /type="radio"/)
  assert.match(html, /disabled/)
  assert.doesNotMatch(html, /确认执行/)
})

test('dispatch proposal exposes every assignment and queue evidence without opening details', async () => {
  const rows = Array.from({ length: 8 }, (_, i) => ({
    processStep: '工序' + i, recommendedOperatorName: '操作员' + i, activeLoad: i % 2,
    equipmentCode: 'EQ-' + i, planQty: 29, recommendReason: i % 2 ? '已有待接收任务' : '当前无在手任务',
    conflictSummary: i % 2 ? '设备使用中，等待释放' : '',
  }))
  const html = await render({ status: 'PENDING', confirmation: {
    title: '发布计划并下发工序派工', adjustmentHint: '可继续对话修改人员',
    params: { executionSteps: ['发布计划','提交主管','下发任务'], recommendations: rows },
  } })
  const table = html.match(/<table>[\s\S]*?<\/table>/)?.[0]
  assert.ok(table)
  for (let i=0;i<8;i++) assert.ok(table.includes('工序'+i))
  assert.match(table, /在手任务|已有待接收任务/)
  assert.match(table, /等待释放/)
  assert.match(html,/可继续对话修改人员/)
  assert.match(html,/<ol class="execution-steps">/)
})

test('production planning does not render automatic staff allocation even in old payloads', async () => {
  const html = await render({ status: 'NEEDS_INPUT', fields: { planEnd: '2026-12-07' },
    missingFields: [{ name: 'planEnd', label: '调整计划截止日期' }],
    staffingPreview: { note: '未锁定资源，未下发', recommendations: [{ processStep: '主板装配', recommendedOperatorName: '白操作', activeLoad: 1, equipmentName: '装配台', planQty: 100 }] } })
  assert.match(html, /继续预览/)
  assert.doesNotMatch(html, /人员与设备初步安排|白操作|派工明细/)
  assert.doesNotMatch(html, /确认执行/)
})

test('current dispatch has selectable eligible operators and avatars, history is read-only', async () => {
  const flow = { ...preview, actionCode: 'confirmSmartDispatch', confirmation: { ...preview.confirmation, operatorOptions: [
    { username:'first',name:'甲',processStep:'装配',activeLoad:0 },
    { username:'second',name:'乙',processStep:'装配',activeLoad:2,disabled:true,reason:'正在执行任务' },
  ] } }
  const html=await render(flow)
  assert.match(html, /更换装配操作员/)
  assert.match(html, /operator-avatar/)
  assert.match(html, /value="second" disabled/)
  assert.doesNotMatch(await render(flow,false), /更换装配操作员/)
  assert.doesNotMatch(await render({ ...flow,uncertainDecision:'APPROVE' }), /更换装配操作员/)
})
