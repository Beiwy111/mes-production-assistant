import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'

const { descriptor } = parse(readFileSync(new URL('./OrderFlowCard.vue', import.meta.url), 'utf8'))
function component(inlineTemplate) {
  const code = compileScript(descriptor, { id: 'order-flow-test', inlineTemplate }).content
    .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]vue['"];?\s*$/gm, (_, bindings) => `const { ${bindings.replace(/\bas\b/g, ':')} } = vue;`)
    .replace("import AgentAvatar from './AgentAvatar.vue'", '')
    .replace('export default', 'return')
  return new Function('vue', 'AgentAvatar', code)(vue, { props: ['agentId'], setup: props => () => vue.h('span', { 'data-role': props.agentId }) })
}
const flow = { steps: [{ label: '查找产品', status: 'COMPLETED', detail: '找到产品 <script>' }], orderDraft: {
  id: 'd', version: 1, status: 'READY', fields: { customerName: '客户甲', materialId: 3, quantity: 5 },
  products: [{ materialId: 3, materialCode: 'P3', materialName: '电脑<script>', unit: '台' }], missing: [],
} }
const render = (props) => renderToString(vue.createSSRApp(component(true), { flow, ...props }))

test('order card renders escaped real steps and locks CUSTOMER identity', async () => {
  const html = await render({ customer: true })
  assert.match(html, /查找产品/)
  assert.match(html, /电脑&lt;script&gt;/)
  assert.doesNotMatch(html, /<script>/)
  assert.match(html, /客户名称<input[^>]+disabled/)
  assert.match(html, /提交审核/)
  assert.match(html, /<select[^>]+required/)
})

test('restored or historical drafts cannot offer a confirmation until recovered', async () => {
  assert.doesNotMatch(await render({ flow: { ...flow, needsRecovery: true } }), /提交审核/)
  assert.doesNotMatch(await render({ historical: true }), /提交审核/)
  assert.doesNotMatch(await render({ flow: { ...flow, orderDraft: { ...flow.orderDraft, status: 'SUBMITTED' } } }), /提交审核/)
})

test('editing draft sends only changed fields and excludes customer identity', () => {
  const emitted = []
  const setup = component(false).setup({ flow, customer: true }, { expose() {}, emit: (...args) => emitted.push(args) })
  setup.form.customerName = '其他客户'
  setup.form.quantity = '8'
  setup.revise()
  assert.deepEqual(emitted, [['action', { action: 'revise', fields: { quantity: 8 } }]])
})

test('server pricing notice is visible and server identity lock prevents customer edits', async () => {
  const html = await render({ customer: false, flow: { ...flow, orderDraft: { ...flow.orderDraft, customerIdentityLocked: true, priceNotice: '金额需经审核，不作正式销售报价。' } } })
  assert.match(html, /金额需经审核，不作正式销售报价。/)
  assert.match(html, /客户名称<input[^>]+disabled/)
})

test('product selection is staged locally and direct submit includes all dirty fields', () => {
  const emitted = []
  const setup = component(false).setup({ flow, customer: true }, { expose() {}, emit: (...args) => emitted.push(args) })
  setup.form.quantity = '8'
  setup.form.receiverAddress = '新地址'
  setup.form.customerName = '不能修改'
  setup.form.materialId = 9
  assert.deepEqual(emitted, [])
  setup.submit()
  setup.form.quantity = 1
  assert.deepEqual(emitted, [['action', { action: 'confirm', fields: { quantity: 8, receiverAddress: '新地址', materialId: 9 } }]])
})

test('observed steps name the real actor and missing actors remain unassigned', async () => {
  const html = await render({ flow: { steps: [
    { agentId: 'SUP', label: '理解需求', status: 'COMPLETED' },
    { agentId: 'ORDER', label: '校验订单', status: 'COMPLETED' },
    { agentId: 'SERVICE', label: '创建业务记录', status: 'COMPLETED' },
    { label: '恢复状态', status: 'COMPLETED' },
  ] } })
  for (const label of ['总协调 Agent', '订单 Agent', '业务服务']) assert.match(html, new RegExp(label))
  const withoutActor = await render({ flow: { steps: [{ label: '恢复状态', status: 'COMPLETED' }] } })
  assert.doesNotMatch(withoutActor, /总协调 Agent|订单 Agent|业务服务/)
})

test('historical drafts show a compact selected-product summary without form or catalog', async () => {
  const html = await render({ historical: true, flow: { ...flow, orderDraft: { ...flow.orderDraft, products: [...flow.orderDraft.products, { materialId: 4, materialName: '不相关产品', unit: '台' }] } } })
  assert.match(html, /电脑&lt;script&gt;/)
  assert.match(html, /5 台/)
  assert.match(html, /待确认/)
  assert.match(html, /最新的订单草稿/)
  assert.doesNotMatch(html, /<form|<input|不相关产品|product-options|order-steps|刷新订单状态/)
})

test('terminal latest drafts retain selected product and result without a disabled catalog or form', async () => {
  for (const status of ['SUBMITTED', 'CANCELLED', 'EXPIRED']) {
    const html = await render({ flow: { ...flow, orderDraft: { ...flow.orderDraft, status, result: { orderNo: 'CO-123', message: '已完成' } } } })
    assert.match(html, /电脑&lt;script&gt;/)
    assert.match(html, /5 台/)
    assert.match(html, /CO-123/)
    assert.doesNotMatch(html, /<form|<input|product-options/)
  }
})

test('receiver name field follows the server length limit', async () => {
  assert.match(await render({}), /收货人<input[^>]+maxlength="80"/)
})

test('required form can submit while collecting and remarks remain optional', async () => {
  const html = await render({ flow: { ...flow, orderDraft: { ...flow.orderDraft, status: 'COLLECTING' } } })
  assert.match(html, /<button[^>]*type="submit"[^>]*>提交审核<\/button>/)
  assert.doesNotMatch(html.match(/<button[^>]*type="submit"[^>]*>/)?.[0] || '', /disabled/)
  for (const label of ['数量', '交付日期', '收货人', '联系电话', '收货地址']) {
    assert.match(html, new RegExp(`${label}<input[^>]+required`))
  }
  assert.match(html, /备注（选填）<input/)
  assert.doesNotMatch(html.match(/备注（选填）<input[^>]+>/)?.[0] || '', /required/)
  assert.doesNotMatch(html, /保存修改后/)
})

test('completed process is collapsed and active process shows pending details', async () => {
  const completed = await render({})
  assert.match(completed, /<details[^>]*class="order-process"[^>]*>/)
  assert.doesNotMatch(completed.match(/<details[^>]*>/)?.[0] || '', /open/)
  const pending = await render({ busy: true, flow: { ...flow, pending: '正在校验订单' } })
  assert.match(pending, /正在校验订单/)
  assert.match(pending, /<details[^>]*open/)
})
