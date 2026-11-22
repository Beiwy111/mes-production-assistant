import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as workbench from '../../utils/agentWorkbench.js'
import * as inventory from '../../utils/inventoryPresentation.js'
import * as business from '../../utils/businessPresentation.ts'
import { prefersReadOnlyQuery } from '../../utils/workbenchRouting.js'
import * as modelModes from '../../utils/workbenchModes.js'

const filename = new URL('./AgentWorkbenchView.vue', import.meta.url)
const source = readFileSync(filename, 'utf8')
const { descriptor, errors } = parse(source)
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: 'natural-workbench-test' })

// Execute the actual compiled setup with network/storage/lifecycle boundaries stubbed.
// Vue refs and computed values stay real so send gating and session changes are exercised.
function setupView(overrides = {}, role = 'ADMIN') {
  const sent = []
  const user = { userInfo: { roleCode: role, userId: 8 }, token: 'test-token' }
  const deps = {
    ...vue,
    ...workbench,
    ...inventory,
    ...business,
    ...modelModes,
    modelChatStatus: async () => ({ apiConfigured: false, localConfigured: false, ragConfigured: false }),
    prefersReadOnlyQuery,
    useUserStore: () => user,
    watch: () => {},
    onBeforeUnmount: () => {},
    ElMessage: { info() {}, warning() {}, success() {} },
    workbenchStatus: async () => ({ configured: true, naturalLanguage: true }),
    streamWorkbench: async (_token, input, { onEvent }) => {
      sent.push(input)
      onEvent({ type: 'run', runId: `run-${sent.length}` })
      onEvent({ type: 'result', data: {
        reply: sent.length === 1 ? '请说明订单号' : '已查询生产进度',
        verdict: sent.length === 1 ? 'CLARIFY' : 'EVIDENCE_READY',
        queryMode: 'NATURAL', workflowProfile: 'AUTO',
      } })
    },
    workbenchRun: async () => ({}),
    orderStatus: async () => ({ available: true, canQuery: role === 'ADMIN' }),
    orderTurn: async () => ({ handled: false, steps: [] }),
    ...overrides,
  }
  const names = Object.keys(script.imports)
  const code = script.content
    .replace(/^import[\s\S]*?from\s*['"][^'"]+['"]\s*;?\s*$/gm, '')
    .replace('export default', 'return')
  const component = new Function(...names, code)(...names.map((name) => deps[name] || {}))
  return { view: component.setup({}, { expose() {} }), sent, user }
}

test('view compiles and presents natural examples without order or scenario selectors', () => {
  const template = compileTemplate({ source: descriptor.template.content, filename: filename.pathname, id: 'natural-workbench-test' })
  assert.deepEqual(template.errors, [])
  assert.equal(/<select\b/.test(descriptor.template.content), false)
  assert.equal(source.includes('resolveOrderContext'), false)
  const { view } = setupView()
  assert.deepEqual(view.introductory.value.map((example) => example.title), ['创建订单', '查库存', '看生产进度', '分析质量问题'])
})

test('clear read questions start the streaming Supervisor without order-intake latency', async () => {
  let intakes = 0
  const { view, sent } = setupView({ orderTurn: async () => { intakes++; throw new Error('not needed') } })
  await view.checkConnection();view.input.value = '帮我查询库存';await view.send()
  assert.equal(intakes, 0);assert.equal(sent.length, 1)
  assert.equal(view.messages.value.at(-2).orderMessage, false)
  assert.equal(view.messages.value.at(-1).orderFlow, undefined)
})

test('switching session cancels its stream and late events cannot enter the new session', async () => {
  let publish, release, aborted=false
  const {view}=setupView({streamWorkbench:async(_token,_input,{onEvent,signal})=>{
    publish=onEvent;onEvent({type:'run',runId:'run-old'})
    signal.addEventListener('abort',()=>{aborted=true})
    await new Promise(resolve=>{release=resolve})
  }})
  await view.checkConnection();view.input.value='查询库存'
  const pending=view.send(), original=view.messages.value.at(-1)
  view.newSession()
  assert.equal(aborted,true);assert.equal(original.status,'CANCELLED')
  publish({type:'result',runId:'run-old',data:{reply:'late'}})
  assert.equal(view.messages.value.length,0);assert.equal(original.text,'')
  release();await pending;assert.equal(view.busy.value,false)
})

test('reading earlier messages prevents queued auto scroll from moving the viewport',async()=>{
  const {view}=setupView()
  const viewport={scrollTop:50,scrollHeight:1000,clientHeight:300}
  view.list.value=viewport;view.trackScroll();view.followScroll();await vue.nextTick()
  assert.equal(viewport.scrollTop,50)
  view.followScroll(true);await vue.nextTick();assert.equal(viewport.scrollTop,1000)
})

test('unacknowledged query failures restore text but acknowledged runs and cancellation do not', async () => {
  for (const kind of ['offline', 'acknowledged', 'cancelled']) {
    const { view } = setupView({ orderStatus: async () => ({ available: false }),
      streamWorkbench: async (_token, _input, { onEvent }) => {
        if (kind === 'acknowledged') onEvent({ type: 'run', runId: 'accepted-run' })
        const error = new Error('network interrupted')
        if (kind === 'cancelled') error.name = 'AbortError'
        throw error
      } })
    await view.checkConnection()
    view.input.value = '查询库存'
    await view.send()
    assert.equal(view.input.value, kind === 'offline' ? '查询库存' : '')
  }
})

test('session switch clears unsent text and compact composer does not expose redundant UI', () => {
  const { view } = setupView()
  view.sessions.value = [{ id: 'a', messages: [] }, { id: 'b', messages: [] }]
  view.activeId.value = 'a'; view.input.value = '本会话未发送'
  view.selectSession(view.sessions.value[1])
  assert.equal(view.input.value, '')
  assert.doesNotMatch(descriptor.template.content, /查看完整文字说明|WorkbenchEvidence|composer-footnote|composer-context|assistant-heading|expand-answer/)
})

test('streamed analytical text stays intact while tables precede the necessary conclusions', () => {
  const { view } = setupView()
  const message = { text: '已查询到以下质量信息：\n\n- 检验状态与数量不一致，需核对。', status: 'RUNNING',
    result: { presentation: 'compact_stream', verdict: 'EVIDENCE_READY', facts: {
      quality: { overview: { domain: 'QUALITY', rows: [{ inspection_no: 'QI-1', unqualified_quantity: 0 }], totalRecords: 1, returnedRecords: 1 } },
    } } }
  assert.equal(view.answerParts(message).intro, '已查询到以下质量信息：')
  assert.equal(view.answerParts(message).analysis, '- 检验状态与数量不一致，需核对。')
  assert.ok(descriptor.template.content.indexOf('<BusinessQueryAnswer') < descriptor.template.content.indexOf('class="answer-analysis"'))
})

test('text attachment adds editable content only and rejects oversized input without truncation', async () => {
  const warnings = []
  const { view } = setupView({ ElMessage: { warning: (value) => warnings.push(value) } })
  view.input.value = '分析这份记录'
  const target = { value: 'file', files: [{ name: 'records.csv', size: 24, text: async () => '工单,数量\nWO-1,0' }] }
  await view.attachTextFile({ target })
  assert.match(view.input.value, /records.csv\n工单,数量\nWO-1,0/)
  assert.equal(target.value, '')
  assert.match(view.attachmentNotice.value, /文件未上传/)
  const previous = view.input.value
  await view.attachTextFile({ target: { files: [{ name: 'long.txt', size: 3000, text: async () => 'x'.repeat(2001) }] } })
  assert.equal(view.input.value, previous)
  assert.equal(warnings.length, 1)
})

test('reading a text attachment never replaces newer edits or crosses an identity change', async () => {
  let resolve
  const { view, user } = setupView()
  view.input.value = '旧输入'
  const pending = view.attachTextFile({ target: { files: [{ name: 'a.txt', size: 10, text: () => new Promise((r) => { resolve = r }) }] } })
  view.input.value = '新输入'
  user.token = 'different-token'
  resolve('不应加入的内容')
  await pending
  assert.equal(view.input.value, '新输入')
})

test('actual send supports no-order text, CLARIFY continuation, and isolates new sessions', async () => {
  const { view, sent } = setupView()
  await view.checkConnection()
  view.input.value = '看生产进度'
  assert.equal(view.canSend.value, true)
  await view.send()
  assert.deepEqual(Object.keys(sent[0]).sort(), ['requestId', 'sessionId', 'text'])
  assert.equal(sent[0].text, '看生产进度')
  assert.equal(view.messages.value[1].result.verdict, 'CLARIFY')
  assert.deepEqual(view.messages.value[1].agents, [])
  assert.equal(workbench.legacyMessageContext(view.messages.value[0]), '')
  view.input.value = '订单号 CO-102'
  await view.send()
  assert.equal(sent[1].previousRunId, 'run-1')
  assert.equal(sent[1].sessionId, sent[0].sessionId)
  view.newSession()
  view.input.value = '查库存'
  await view.send()
  assert.notEqual(sent[2].sessionId, sent[0].sessionId)
  assert.equal(Object.hasOwn(sent[2], 'previousRunId'), false)
})

test('an older connected service blocks send and explicitly requests upgrade', async () => {
  const { view, sent } = setupView()
  view.connection.value = { configured: true, allowedOrderIds: ['100'] }
  view.input.value = '查库存'
  assert.equal(view.canSend.value, false)
  assert.equal(view.statusText.value, '服务待升级')
  assert.match(view.statusHint.value, /尚未升级为自然语言查询/)
  await view.send()
  assert.deepEqual(sent, [])
})

test('continuing an old history session starts natural querying without its preview run', async () => {
  const { view, sent } = setupView()
  await view.checkConnection()
  const [session] = workbench.restoreSessions(JSON.stringify([{
    id: 'legacy-session', profile: 'DELIVERY', orderPk: '100', messages: [
      { role: 'assistant', status: 'COMPLETED', runId: 'older-natural', result: { reply: '此前自然查询', queryMode: 'NATURAL' } },
      { role: 'assistant', status: 'COMPLETED', runId: 'legacy-run', result: { reply: '旧订单分析', workflowProfile: 'DELIVERY' } },
    ],
  }]))
  view.sessions.value = [session]
  view.selectSession(session)
  view.input.value = '查库存'
  await view.send()
  assert.equal(sent[0].sessionId, 'legacy-session')
  assert.equal(Object.hasOwn(sent[0], 'previousRunId'), false)
  assert.equal(view.messages.value.at(-2).profile, 'AUTO')
})

test('natural answers retain full replies including limitations', () => {
  const { view } = setupView()
  const text = '库存查询结果\n\n缺失/限制：当前库存不等于可供特定订单使用的数量。'
  assert.equal(view.displayAnswer({ text, result: { queryMode: 'NATURAL' } }), text)
})

const draftEnvelope = (version = 1, status = 'READY') => ({ handled: true, reply: '请核对订单后确认', steps: [{ agentId: 'ORDER', label: '校验草稿', status: 'COMPLETED' }], orderDraft: { id: 'draft-1', version, status, fields: { materialId: 3, quantity: 5 }, products: [], missing: [] } })

for (const modelSource of ['API', 'LOCAL']) for (const ragEnabled of [false, true]) {
  test(`model turn: ${modelSource}, RAG=${ragEnabled} with the gateway and order tables offline`, async () => {
    const calls = []
    const { view, sent } = setupView({
      workbenchStatus: async () => { throw new Error('gateway offline') },
      orderStatus: async () => ({ available: false }),
      modelChatStatus: async () => ({ apiConfigured: true, localConfigured: true, ragConfigured: true }),
      modelChatTurn: async (_token, body) => { calls.push(body); return { ...body, reply: '已回答', queryMode: 'MODEL_CHAT' } },
    }, 'ORDER')
    await view.checkConnection()
    view.setMode(modelSource, ragEnabled)
    view.input.value = '显示器工艺是什么？'
    assert.equal(view.canSend.value, true)
    await view.send()
    assert.equal(calls.length, 1)
    assert.deepEqual(calls[0], { text: '显示器工艺是什么？', modelSource, ragEnabled, history: [] })
    assert.equal(view.messages.value.at(-1).status, 'COMPLETED')
    assert.equal(view.messages.value.at(-1).modelSource, modelSource)
    assert.equal(sent.length, 0)
  })
}

test('direct history stays within the same model source and RAG setting', async () => {
  const calls = []
  const { view } = setupView({
    modelChatStatus: async () => ({ apiConfigured: true, localConfigured: true, ragConfigured: true }),
    modelChatTurn: async (_token, body) => { calls.push(body); return { ...body, queryMode: 'MODEL_CHAT', reply: '第一轮回复' } },
  })
  await view.checkConnection()
  view.setMode('LOCAL', true)
  for (const question of ['一般工艺', '继续']) { view.input.value = question; await view.send() }
  assert.equal(calls[1].history.length, 2)
  view.setMode('LOCAL', false); view.input.value = '继续'; await view.send()
  view.setMode('API', false); view.input.value = '继续'; await view.send()
  assert.deepEqual(calls[2].history, [])
  assert.deepEqual(calls[3].history, [])
  const [saved] = workbench.restoreSessions(workbench.serializeWorkbenchSessions(view.sessions.value))
  assert.equal(saved.modelSource, 'API')
  assert.equal(saved.messages.at(-1).ragEnabled, false)
  assert.equal(saved.messages.at(-1).modelSource, 'API')
})

test('direct order turn forwards mode, never silently uses the gateway on failure', async () => {
  const orders = []
  const { view, sent } = setupView({
    modelChatStatus: async () => ({ localConfigured: true, ragConfigured: true }),
    orderTurn: async (_token, body) => { orders.push(body); throw Object.assign(new Error('本地模型不可用'), { code: 503 }) },
    modelChatTurn: async () => { throw new Error('unexpected fallback') },
  }, 'ORDER')
  await view.checkConnection()
  view.setMode('LOCAL', true)
  view.input.value = '我想订购5台显示器'
  await view.send()
  assert.equal(orders.length, 1); assert.equal(orders[0].modelSource, 'LOCAL'); assert.equal(orders[0].ragEnabled, true)
  assert.equal(sent.length, 0); assert.match(view.messages.value.at(-1).error, /本地模型不可用/)
})

test('unconfigured model / retrieval disables sending without disabling the selector', async () => {
  const { view } = setupView({ modelChatStatus: async () => ({ apiConfigured: true, localConfigured: false, ragConfigured: false }) })
  await view.checkConnection()
  view.input.value = '解释工序'; view.setMode('LOCAL', false)
  assert.equal(view.canSend.value, false)
  view.setMode('API', true); assert.equal(view.canSend.value, false)
  view.setMode('API', false); assert.equal(view.canSend.value, true)
})

test('switching providers with an order draft leaves it in its original conversation', async () => {
  const { view } = setupView({
    modelChatStatus: async () => ({ localConfigured: true, apiConfigured: true, ragConfigured: true }),
    orderTurn: async (_token, body) => ({ ...draftEnvelope(), modelSource: body.modelSource, ragEnabled: body.ragEnabled }),
  }, 'ORDER')
  await view.checkConnection()
  view.setMode('LOCAL', false)
  view.input.value = '订购 8 台显示器'; await view.send()
  assert.equal(view.messages.value.at(-1).orderFlow.orderDraft.id, 'draft-1')
  view.setMode('API', false)
  assert.equal(view.activeId.value, null)
  assert.equal(view.sessions.value[0].modelSource, 'LOCAL')
  assert.equal(view.sessions.value[0].messages.at(-1).orderFlow.orderDraft.id, 'draft-1')
})

test('same-mode follow-ups keep the previous business question without sending previous MES records', () => {
  const mode = { modelSource: 'LOCAL', ragEnabled: false }
  const history = modelModes.modelHistory([
    { ...mode, role: 'user', text: '查订单 DEMO-12' },
    { ...mode, role: 'assistant', status: 'COMPLETED', text: '私有测试记录', result: { queryMode: 'NATURAL' } },
  ], mode)
  assert.equal(history[0].content, '查订单 DEMO-12')
  assert.doesNotMatch(JSON.stringify(history), /私有测试记录/)
})

async function orderView(overrides = {}) {
  const { view } = setupView({ orderTurn: async () => draftEnvelope(), ...overrides })
  await view.checkConnection()
  view.input.value = '买5件产品'
  await view.send()
  return { view, message: view.messages.value.at(-1) }
}

test('direct submission saves changed fields once then confirms the returned version once', async () => {
  const calls = []
  const { view, message } = await orderView({
    getOrderDraft: async () => { calls.push('get'); return draftEnvelope(1, 'COLLECTING') },
    reviseOrderDraft: async (_token, _id, body) => { calls.push(['revise', body]); return draftEnvelope(2) },
    confirmOrderDraft: async (_token, _id, body) => { calls.push(['confirm', body]); return draftEnvelope(3, 'SUBMITTED') },
  })
  await view.orderAction(message, { action: 'confirm', fields: { quantity: 8, receiverName: '客户甲' } })
  assert.equal(calls.length, 3)
  assert.equal(calls[0], 'get')
  assert.equal(calls[1][0], 'revise')
  assert.equal(calls[1][1].version, 1)
  assert.deepEqual(calls[1][1].fields, { quantity: 8, receiverName: '客户甲' })
  assert.equal(calls[2][0], 'confirm')
  assert.equal(calls[2][1].version, 2)
  assert.deepEqual(Object.keys(calls[2][1]).sort(), ['requestId', 'version'])
  assert.notEqual(calls[1][1].requestId, calls[2][1].requestId)
  assert.equal(message.orderFlow.orderDraft.status, 'SUBMITTED')
})

test('failed or uncertain revision only recovers by GET and never confirms or retries', async () => {
  let reads = 0, revisions = 0, confirms = 0
  const { view, message } = await orderView({
    getOrderDraft: async () => draftEnvelope(++reads),
    reviseOrderDraft: async () => { revisions++; throw new Error('response lost') },
    confirmOrderDraft: async () => { confirms++; return draftEnvelope(3, 'SUBMITTED') },
  })
  await view.orderAction(message, { action: 'confirm', fields: { quantity: 8 } })
  assert.equal(reads, 2)
  assert.equal(revisions, 1)
  assert.equal(confirms, 0)
  assert.equal(message.orderFlow.orderDraft.version, 2)
})

test('revision must return READY and the next version before direct confirmation', async () => {
  for (const revised of [draftEnvelope(2, 'COLLECTING'), draftEnvelope(1), draftEnvelope(3), { ...draftEnvelope(2), orderDraft: { ...draftEnvelope(2).orderDraft, id: 'other-draft' } }]) {
    let confirms = 0
    const { view, message } = await orderView({ getOrderDraft: async () => draftEnvelope(), reviseOrderDraft: async () => revised,
      confirmOrderDraft: async () => { confirms++; return draftEnvelope(3, 'SUBMITTED') } })
    await view.orderAction(message, { action: 'confirm', fields: { quantity: 8 } })
    assert.equal(confirms, 0)
  }
})

test('stale direct submission never saves local changes or confirms', async () => {
  let writes = 0
  const { view, message } = await orderView({ getOrderDraft: async () => draftEnvelope(2),
    reviseOrderDraft: async () => { writes++ }, confirmOrderDraft: async () => { writes++ } })
  await view.orderAction(message, { action: 'confirm', fields: { quantity: 8 } })
  assert.equal(writes, 0)
})

test('stopping while saving prevents the following confirmation even if save resolves', async () => {
  let resolveRevision, confirms = 0
  const { view, message } = await orderView({ getOrderDraft: async () => draftEnvelope(),
    reviseOrderDraft: async () => new Promise((resolve) => { resolveRevision = resolve }),
    confirmOrderDraft: async () => { confirms++; return draftEnvelope(3, 'SUBMITTED') } })
  const action = view.orderAction(message, { action: 'confirm', fields: { quantity: 8 } })
  await Promise.resolve()
  assert.equal(typeof resolveRevision, 'function')
  view.stop()
  resolveRevision(draftEnvelope(2))
  await action
  assert.equal(confirms, 0)
})

test('unchanged direct submission skips revision and uses the checked version', async () => {
  let revisions = 0, confirms = 0
  const { view, message } = await orderView({ getOrderDraft: async () => draftEnvelope(),
    reviseOrderDraft: async () => { revisions++ }, confirmOrderDraft: async (_token, _id, body) => {
      confirms++
      assert.equal(body.version, 1)
      return draftEnvelope(2, 'SUBMITTED')
    } })
  await view.orderAction(message, { action: 'confirm', fields: {} })
  assert.equal(revisions, 0)
  assert.equal(confirms, 1)
})

test('latest server identity lock is honored when submitting unsaved fields', async () => {
  let saved
  const { view, message } = await orderView({ getOrderDraft: async () => ({ ...draftEnvelope(), orderDraft: { ...draftEnvelope().orderDraft, customerIdentityLocked: true } }),
    reviseOrderDraft: async (_token, _id, body) => { saved = body.fields; return draftEnvelope(2) },
    confirmOrderDraft: async () => draftEnvelope(3, 'SUBMITTED') })
  await view.orderAction(message, { action: 'confirm', fields: { customerName: '不可修改的客户', quantity: 8 } })
  assert.deepEqual(saved, { quantity: 8 })
})

test('identity change during saving prevents confirmation and discards the former owner response', async () => {
  let resolveRevision, confirms = 0
  const { view, user } = setupView({ orderTurn: async () => draftEnvelope(), getOrderDraft: async () => draftEnvelope(),
    reviseOrderDraft: async () => new Promise((resolve) => { resolveRevision = resolve }),
    confirmOrderDraft: async () => { confirms++; return draftEnvelope(3, 'SUBMITTED') } })
  await view.checkConnection()
  view.input.value = '买5件产品'
  await view.send()
  const message = view.messages.value.at(-1)
  const action = view.orderAction(message, { action: 'confirm', fields: { quantity: 8 } })
  await Promise.resolve()
  user.token = 'other-token'
  resolveRevision(draftEnvelope(2))
  await action
  assert.equal(confirms, 0)
  assert.equal(message.orderFlow.orderDraft.version, 1)
})

test('order roles send through intake and never fall through to global query', async () => {
  for (const role of ['CUSTOMER', 'ORDER']) {
    const { view, sent } = setupView({}, role)
    await view.checkConnection()
    view.input.value = '看看全厂库存'
    assert.equal(view.canSend.value, true)
    await view.send()
    assert.equal(sent.length, 0)
    assert.match(view.messages.value.at(-1).text, /订单/)
  }
})

test('handled order produces its own execution record and never invokes query', async () => {
  const { view, sent } = setupView({ orderTurn: async () => draftEnvelope() })
  await view.checkConnection()
  view.input.value = '买5件产品'
  await view.send()
  const message = view.messages.value.at(-1)
  assert.equal(message.orderFlow.orderDraft.id, 'draft-1')
  assert.equal(message.orderFlow.steps[0].label, '校验草稿')
  assert.equal(message.workflow, undefined)
  assert.equal(sent.length, 0)
})

test('stale confirmation refreshes the draft without creating an order', async () => {
  let confirms = 0
  const { view } = setupView({ orderTurn: async () => draftEnvelope(), getOrderDraft: async () => draftEnvelope(2), confirmOrderDraft: async () => { confirms++; return draftEnvelope(2, 'SUBMITTED') } })
  await view.checkConnection()
  view.input.value = '买5件产品'
  await view.send()
  const message = view.messages.value.at(-1)
  await view.orderAction(message, { action: 'confirm' })
  assert.equal(confirms, 0)
  assert.equal(message.orderFlow.orderDraft.version, 2)
  assert.match(message.error, /变化|更新/)
})

test('lost confirmation response recovers with GET and does not resubmit', async () => {
  let confirms = 0, reads = 0
  const { view } = setupView({ orderTurn: async () => draftEnvelope(), getOrderDraft: async () => draftEnvelope(1, ++reads > 1 ? 'SUBMITTED' : 'READY'), confirmOrderDraft: async (_token, _id, body) => {
    confirms++
    assert.deepEqual(Object.keys(body).sort(), ['requestId', 'version'])
    throw new Error('network lost')
  } })
  await view.checkConnection()
  view.input.value = '买5件产品'
  await view.send()
  const message = view.messages.value.at(-1)
  await view.orderAction(message, { action: 'confirm' })
  assert.equal(confirms, 1)
  assert.equal(reads, 2)
  assert.equal(message.orderFlow.orderDraft.status, 'SUBMITTED')
})

test('admin query remains available when the order backend is not loaded', async () => {
  const { view, sent } = setupView({ orderStatus: async () => { throw new Error('404') } })
  await view.checkConnection()
  view.input.value = '查库存'
  await view.send()
  assert.equal(sent.length, 1)
})

test('stopping an order request marks it interrupted rather than claiming server cancellation', async () => {
  let release
  const { view } = setupView({ orderTurn: (_token, _data, { signal }) => new Promise((resolve, reject) => {
    release = resolve
    signal.addEventListener('abort', () => reject(Object.assign(new Error('stopped'), { name: 'AbortError' })))
  }) })
  await view.checkConnection()
  view.input.value = '买5件产品'
  const sending = view.send()
  view.stop()
  await sending
  const message = view.messages.value.at(-1)
  assert.equal(message.status, 'INTERRUPTED')
  assert.equal(message.orderFlow.needsRecovery, true)
  assert.equal(message.orderFlow.pending, '')
})

test('confirm blocks a second click while the first confirmation is waiting', async () => {
  let release, confirms = 0
  const { view } = setupView({ orderTurn: async () => draftEnvelope(), getOrderDraft: async () => draftEnvelope(), confirmOrderDraft: () => {
    confirms++
    return new Promise((resolve) => { release = resolve })
  } })
  await view.checkConnection()
  view.input.value = '买5件产品'
  await view.send()
  const message = view.messages.value.at(-1)
  const first = view.orderAction(message, { action: 'confirm' })
  await Promise.resolve()
  await view.orderAction(message, { action: 'confirm' })
  assert.equal(confirms, 1)
  release(draftEnvelope(2, 'SUBMITTED'))
  await first
  assert.equal(message.orderFlow.orderDraft.status, 'SUBMITTED')
})

test('switching identity during confirmation never displays the previous owner result', async () => {
  let release
  const { view, user } = setupView({ orderTurn: async () => draftEnvelope(), getOrderDraft: async () => draftEnvelope(), confirmOrderDraft: () => new Promise((resolve) => { release = resolve }) })
  await view.checkConnection()
  view.input.value = '买5件产品'
  await view.send()
  const message = view.messages.value.at(-1)
  const pending = view.orderAction(message, { action: 'confirm' })
  await Promise.resolve()
  user.token = 'other-token'
  release(draftEnvelope(2, 'SUBMITTED'))
  await pending
  assert.equal(message.orderFlow.orderDraft.status, 'READY')
})

test('ADMIN intake network or 503 failures continue read-only query with a notice and redact history', async () => {
  for (const error of [new TypeError('Failed to fetch'), Object.assign(new Error('Unavailable'), { code: 503 })]) {
    let intakes = 0
    const { view, sent } = setupView({ orderTurn: async () => { intakes++; throw error } })
    await view.checkConnection()
    view.input.value = '请查库存，收货电话 13800138000'
    await view.send()
    const answer = view.messages.value.at(-1)
    assert.equal(sent.length, 1)
    assert.equal(intakes, 1)
    assert.equal(answer.status, 'COMPLETED')
    assert.match(answer.orderIntakeNotice, /暂不可用.*只读查询/)
    assert.equal(workbench.serializeWorkbenchSessions(view.sessions.value).includes('13800138000'), false)
  }
})

test('validation, authorization, abort, and non-admin intake failures never fall back to query', async () => {
  for (const [role, error] of [
    ['ADMIN', Object.assign(new Error('invalid'), { code: 400 })],
    ['ADMIN', Object.assign(new Error('conflict'), { code: 409 })],
    ['ADMIN', Object.assign(new Error('login expired'), { code: 401 })],
    ['ADMIN', Object.assign(new Error('forbidden'), { code: 403 })],
    ['ADMIN', Object.assign(new Error('stopped'), { name: 'AbortError' })],
    ['CUSTOMER', new TypeError('Failed to fetch')],
    ['ORDER', Object.assign(new Error('Unavailable'), { code: 503 })],
  ]) {
    const { view, sent } = setupView({ orderTurn: async () => { throw error } }, role)
    await view.checkConnection()
    view.input.value = '查库存并修改订单'
    await view.send()
    assert.equal(sent.length, 0)
    assert.equal(view.messages.value.at(-1).orderIntakeNotice, undefined)
  }
})


test('non-admin query access follows server capabilities rather than granting global admin access', async () => {
  const { view, sent } = setupView({ workbenchStatus: async () => ({ configured: true, naturalLanguage: true, capabilities: { domains: ['QUALITY', 'KNOWLEDGE'] } }) }, 'QC')
  await view.checkConnection()
  assert.equal(view.queryReady.value, true)
  assert.deepEqual(view.introductory.value.map(x => x.title), ['分析质量问题'])
  view.input.value = '查看质量记录'
  await view.send()
  assert.equal(sent.length, 1)
})


test('new authorization stamp removes cached business messages before continuing', async () => {
  const original = globalThis.localStorage
  const storage = new Map()
  globalThis.localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k,v) => storage.set(k,v), removeItem: k => storage.delete(k) }
  try {
    let stamp = 'policy-one'
    const { view } = setupView({ workbenchStatus: async () => ({ configured: true, naturalLanguage: true, authorizationStamp: stamp, capabilities: { domains: ['ORDER'] } }) })
    await view.checkConnection()
    view.sessions.value = [{ id: 'old', title: '旧客户订单', messages: [{ role: 'assistant', text: '旧客户数据' }] }]
    view.activeId.value = 'old'
    stamp = 'policy-two'
    await view.checkConnection()
    assert.equal(view.sessions.value.length, 0)
    assert.equal(view.activeId.value, null)
  } finally {globalThis.localStorage = original}
})
