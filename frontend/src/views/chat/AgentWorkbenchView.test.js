import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as workbench from '../../utils/agentWorkbench.js'
import * as inventory from '../../utils/inventoryPresentation.js'
import * as business from '../../utils/businessPresentation.ts'
import * as modelModes from '../../utils/workbenchModes.js'
import * as images from '../../utils/imageConversation.js'
import * as routing from '../../utils/workbenchRouting.js'

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
    ...images,
    ...routing,
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
  assert.deepEqual([...descriptor.template.content.matchAll(/<select\b[^>]*v-model="([^"]+)"/g)].map(m => m[1]), ['imageMode'])
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

test('polite planning requests reach production preview even when the entry classifier would route to orders', async () => {
  for (const role of ['PLANNER', 'MANAGER', 'ADMIN']) {
    for (const text of ['你能帮我去排产吗', '帮我去制定生产计划呢', '智能排产']) {
      let previews = 0, entries = 0, intakes = 0
      const { view, sent } = setupView({
        workbenchStatus: async () => ({ configured: true, naturalLanguage: true, directChat: true, productionActions: true }),
        chatEntry: async () => { entries++; return { route: 'ORDER' } },
        orderTurn: async () => { intakes++; throw Error('unexpected order intake') },
        previewProductionOperation: async (_token, request) => {
          previews++; assert.equal(request.text, text)
          return { status: 'NEEDS_INPUT', actionCode: 'PLAN_CREATE', message: '请选择待计划订单', missingFields: [{ name: 'orderId', label: '订单' }] }
        },
      }, role)
      await view.checkConnection(); view.input.value = text; await view.send()
      assert.equal(previews, 1, `${role}: ${text}`)
      assert.equal(entries, 0); assert.equal(intakes, 0); assert.equal(sent.length, 0)
      assert.equal(view.messages.value.at(-1).productionFlow.status, 'NEEDS_INPUT')
      assert.equal(view.messages.value.at(-2).orderMessage, false)
    }
  }
})

test('role shortcuts use the existing planning and production-manager task names', () => {
  const planner = setupView({}, 'PLANNER').view.productionExample.value
  const manager = setupView({}, 'MANAGER').view.productionExample.value
  assert.equal(planner.title, '智能制定生产计划')
  assert.match(planner.prompt, /制定生产计划/)
  assert.equal(manager.title, '智能排产')
  assert.match(manager.description, /工序.*设备.*操作员/)
})

test('general chat streams without creating business tasks and a topic switch enters quality lookup', async () => {
  const calls = []
  const { view, sent } = setupView({
    workbenchStatus: async () => ({ configured: true, naturalLanguage: true, directChat: true }),
    orderTurn: async () => { throw Error('unexpected order intake') },
    chatEntry: async (_token, request, { onDelta }) => {
      calls.push(request)
      if (request.text.includes('质检')) return { route: 'BUSINESS' }
      onDelta('你想查'); onDelta('哪个城市？')
      return { route: 'WEATHER' }
    },
  })
  await view.checkConnection()
  view.input.value = '今天天气怎么样'; await view.send()
  assert.equal(sent.length, 0)
  assert.equal(view.messages.value.at(-1).text, '你想查哪个城市？')
  assert.deepEqual(view.messages.value.at(-1).agents, [])
  view.input.value = '查询质检情况'; await view.send()
  assert.equal(calls[1].history.user, '今天天气怎么样')
  assert.deepEqual(sent.map(request => request.text), ['查询质检情况'])
  assert.equal(view.messages.value.at(-2).orderMessage, false)
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

test('regenerating a read reuses the original scope and never invokes order intake', async () => {
  let writes = 0
  const { view, sent } = setupView({ orderTurn: async () => { writes++; return { handled: false } } })
  await view.checkConnection()
  view.input.value = '查询当前库存'
  await view.send()
  const original = view.messages.value.at(-1)
  assert.ok(view.canRegenerate(original))
  await view.send(original)
  assert.equal(writes, 0)
  assert.equal(sent.length, 2)
  assert.equal(sent[1].previousRunId, sent[0].previousRunId)
  assert.equal(sent[1].text, sent[0].text)
  assert.notEqual(sent[1].requestId, sent[0].requestId)
  assert.equal(view.canRegenerate(original), false)
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

test('one continuous Markdown renderer precedes evidence for streamed, saved and recovered answers', () => {
  const template = descriptor.template.content
  assert.equal([...template.matchAll(/<WorkbenchMarkdown :content="message.text"/g)].length, 1)
  assert.ok(template.indexOf(':content="message.text"') < template.indexOf('<AnswerEvidence'))
  assert.ok(template.indexOf('<AgentCollaborationStream') < template.indexOf(':content="message.text"'))
  assert.match(template, /<AgentCollaborationStream v-if="!message.modelSource && !message.routing && !message.directChat"/)
  assert.doesNotMatch(source, /answerParts|compactBusinessAnswer|isProductionOnly/)
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

test('clipboard screenshots without extensions use image MIME and invalid pastes preserve the draft', async () => {
  const warnings = []
  const { view } = setupView({ ElMessage: { warning: (value) => warnings.push(value) } })
  view.input.value = '看看这张截图'
  const screenshot = new File(['image'], 'clipboard', { type: 'image/png' })
  await view.pasteFiles([screenshot])
  assert.equal(view.pendingImage.value, screenshot)
  await view.pasteFiles([screenshot, screenshot])
  await view.pasteFiles([new File(['x'], 'archive.zip')])
  assert.equal(warnings.length, 2)
  assert.equal(view.pendingImage.value, screenshot)
  assert.equal(view.input.value, '看看这张截图')
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
  const text = '库存查询结果\n\n缺失/限制：当前库存不等于可供特定订单使用的数量。'
  const message = workbench.createAssistantMessage()
  workbench.applyWorkbenchEvent(message, { type: 'result', data: { queryMode: 'NATURAL', reply: text } })
  assert.equal(message.text, text)
  assert.match(descriptor.template.content, /<WorkbenchMarkdown :content="message.text"/)
})

const draftEnvelope = (version = 1, status = 'READY') => ({ handled: true, reply: '请核对订单后确认', steps: [{ agentId: 'ORDER', label: '校验草稿', status: 'COMPLETED' }], orderDraft: { id: 'draft-1', version, status, fields: { materialId: 3, quantity: 5 }, products: [], missing: [] } })

const bindings = { configured:true, naturalLanguage:true, directChat:true, workflowModels:{API:true,LOCAL:true} }
for (const workflowModel of ['API','LOCAL']) {
  test(`workflow entry and supervised business query use the same ${workflowModel} binding`, async () => {
    const calls = []
    const {view} = setupView({
      workbenchStatus: async () => bindings,
      chatEntry: async (_token, body) => { calls.push(body.workflowModel); return {route:'BUSINESS',workflowModel:body.workflowModel} },
      streamWorkbench: async (_token, body, {onEvent}) => {
        calls.push(body.workflowModel)
        onEvent({type:'run',runId:'r1'})
        onEvent({type:'result',data:{reply:'授权工单清单',queryMode:'NATURAL',workflowModel:body.workflowModel}})
      },
    })
    await view.checkConnection()
    assert.equal(view.workflowModel.value,'API')
    view.setWorkflowModel(workflowModel)
    view.input.value='查询生产工单'; await view.send()
    assert.deepEqual(calls,[workflowModel,workflowModel])
    assert.equal(view.messages.value.at(-1).text,'授权工单清单')
    const [saved]=workbench.restoreSessions(workbench.serializeWorkbenchSessions(view.sessions.value))
    assert.equal(saved.workflowModel,workflowModel)
    assert.equal(saved.messages.at(-1).workflowModel,workflowModel)
  })
}
test('unavailable or mismatched v2 never becomes a successful API response',async()=>{
  const {view,sent}=setupView({
    workbenchStatus:async()=>({...bindings,workflowModels:{API:true,LOCAL:false}}),
    chatEntry:async()=>({route:'GENERAL',workflowModel:'API'}),
  })
  await view.checkConnection(); view.setWorkflowModel('LOCAL'); view.input.value='你好'
  assert.equal(view.canSend.value,false)
  view.connection.value=bindings; await view.send()
  assert.notEqual(view.messages.value.at(-1).status,'COMPLETED')
  assert.match(view.messages.value.at(-1).error,/模型选择/)
  assert.equal(view.workflowModel.value,'LOCAL'); assert.equal(sent.length,0)
})
test('a provider switch starts a separate workflow and preserves existing order drafts',async()=>{
  const {view}=setupView({workbenchStatus:async()=>bindings,chatEntry:async()=>({route:'ORDER',workflowModel:'LOCAL'}),
    orderTurn:async (_token,body)=>({...draftEnvelope(),workflowModel:body.workflowModel})},'ORDER')
  await view.checkConnection();view.setWorkflowModel('LOCAL');view.input.value='帮我创建订单';await view.send()
  assert.equal(view.messages.value.at(-1).orderFlow.orderDraft.id,'draft-1')
  view.input.value='未发送的文字';view.setWorkflowModel('API')
  assert.equal(view.activeId.value,null);assert.equal(view.input.value,'未发送的文字')
  assert.equal(view.sessions.value[0].workflowModel,'LOCAL')
  assert.equal(view.sessions.value[0].messages.at(-1).orderFlow.orderDraft.id,'draft-1')
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

const schedulePreview = {
  status: 'PENDING', actionCode: 'saveProductionPlan', message: '请核对排产明细',
  confirmation: { proposalId: 'proposal-plan-A', title: '保存生产排程草案',
    params: { orderId: 'CO801', schedules: [{ processStep: '装配', planQty: 4 }] } },
}
function productionView(overrides = {}) {
  const previews = [], decisions = []
  const result = setupView({
    workbenchStatus: async () => ({ configured: true, naturalLanguage: true, productionActions: true, capabilities: { domains: ['PRODUCTION'] } }),
    chatEntry: async () => ({ route: 'PRODUCTION_ACTION' }),
    previewProductionOperation: async (token, body) => { previews.push({ token, body }); return schedulePreview },
    decideProductionOperation: async (token, id, decision) => {
      decisions.push({ token, id, decision })
      return { proposalId: id, status: 'EXECUTED', reply: '已保存', result: { planId: 'PP901', orderId: 'CO801' } }
    },
    ...overrides,
  }, 'PLANNER')
  return { ...result, previews, decisions }
}
async function say(view, text) { view.input.value = text; await view.send() }

test('actual preview lifecycle records request start, safe response counts and failure without invented agents', async () => {
  let resolve
  const { view } = productionView({ previewProductionOperation: async () => new Promise(done => { resolve = done }) })
  await view.checkConnection()
  const pending = say(view, '帮我排产')
  const message = view.messages.value.at(-1)
  assert.equal(message.productionFlow.steps[0].status, 'RUNNING')
  assert.ok(message.productionFlow.steps[0].startedAt)
  resolve({ status: 'NEEDS_INPUT', schemes: [{}, {}, {}], options: { orderId: [{ value: 'secret' }] } })
  await pending
  assert.equal(message.productionFlow.steps[0].status, 'COMPLETED')
  assert.match(message.productionFlow.steps[0].detail, /1 个待计划订单.*3 种排产方案/)
  assert.doesNotMatch(message.productionFlow.steps[0].detail, /secret/)
  assert.deepEqual(message.agents, [])
  const failing = productionView({ previewProductionOperation: async () => { throw Error('private server detail') } }).view
  await failing.checkConnection(); await say(failing, '帮我排产')
  const step = failing.messages.value.at(-1).productionFlow.steps[0]
  assert.equal(step.status, 'FAILED'); assert.doesNotMatch(step.detail, /private/)
})

test('production chat previews under the caller and writes only on explicit approval of the current card', async () => {
  const { view, previews, decisions, sent } = productionView()
  await view.checkConnection(); await say(view, '给 CO801 排产')
  const card = view.messages.value.at(-1)
  assert.deepEqual(previews, [{ token: 'test-token', body: { text: '给 CO801 排产', sessionId: view.activeId.value, history: [] } }])
  assert.equal(card.productionFlow.status, 'PENDING')
  assert.equal(card.productionFlow.steps[0].agentId, 'SERVICE')
  assert.equal(card.productionFlow.steps[0].status, 'COMPLETED')
  assert.match(card.productionFlow.steps[0].detail, /待确认.*未写入/)
  assert.equal(view.canRegenerate(card), false)
  await say(view, '确定')
  assert.equal(previews.length, 1)
  assert.equal(decisions.length, 0)
  assert.deepEqual(sent, [])
  await view.productionDecision(card, 'APPROVE')
  assert.deepEqual(decisions, [{ token: 'test-token', id: 'proposal-plan-A', decision: 'APPROVE' }])
  assert.equal(card.productionFlow.result.planId, 'PP901')
  assert.equal(card.productionFlow.steps.at(-1).toolId, 'production.decision')
  assert.equal(card.productionFlow.steps.at(-1).status, 'COMPLETED')
  await view.productionDecision(card, 'APPROVE')
  assert.equal(decisions.length, 1)
  await say(view, '发布刚生成的计划')
  assert.deepEqual(previews[1].body.fields, { planId: 'PP901' })
})

test('clarification carries just this conversation’s fields and unrelated reads use the streaming query', async () => {
  const requests = [], contexts = []
  const { view, sent } = productionView({
    chatEntry: async (_token, body) => { contexts.push(body.context); return { route: body.text === '查库存' ? 'BUSINESS' : 'PRODUCTION_ACTION' } },
    previewProductionOperation: async (_token, body) => {
      requests.push(body)
      return body.text === '帮我排产' ? { status: 'NEEDS_INPUT', actionCode: 'saveProductionPlan', fields: { planStart: '2026-12-01' },
        missingFields: [{ name: 'orderId', label: '订单编号' }], message: '请补充订单编号' } : schedulePreview
    },
  })
  await view.checkConnection(); await say(view, '帮我排产'); await say(view, 'CO801')
  assert.deepEqual(requests[1], { text: 'CO801', sessionId: view.activeId.value, actionCode: 'saveProductionPlan', fields: { planStart: '2026-12-01' },
    history: [{ role: 'user', text: '帮我排产' }, { role: 'assistant', text: '请补充订单编号' }] })
  assert.deepEqual(contexts, []) // Scoped input goes straight to preview.
  const pending = view.messages.value.at(-1)
  await say(view, '查库存')
  assert.equal(contexts[0], 'PRODUCTION_ACTION')
  assert.equal(sent.length, 1)
  await view.productionDecision(pending, 'APPROVE')
  assert.equal(pending.productionFlow.status, 'PENDING')
  view.newSession(); await say(view, 'CO999')
  assert.equal(requests.at(-1).actionCode, undefined)
})

test('an uncertain decision can only retry the same decision and proposal, with no automatic retry', async () => {
  const attempts = []
  const { view } = productionView({ decideProductionOperation: async (_token, id, decision) => {
    attempts.push([id, decision])
    if (attempts.length === 1) throw new TypeError('网络中断')
    return { proposalId: id, status: 'EXECUTED', reply: '返回原结果', result: { planId: 'PP901' } }
  } })
  await view.checkConnection(); await say(view, '给 CO801 排产')
  const card = view.messages.value.at(-1)
  await view.productionDecision(card, 'APPROVE')
  assert.match(card.error, /核对|未确认/)
  assert.equal(card.productionFlow.steps.at(-1).status, 'INTERRUPTED')
  await view.productionDecision(card, 'SKIP')
  assert.equal(attempts.length, 1)
  await view.productionDecision(card, 'APPROVE')
  assert.deepEqual(attempts, [['proposal-plan-A', 'APPROVE'], ['proposal-plan-A', 'APPROVE']])
  assert.equal(card.productionFlow.status, 'EXECUTED')
})

test('a rejected or expired production action never appears executed and stored history cannot authorize it', async () => {
  const { view } = productionView({ decideProductionOperation: async () => { throw Object.assign(new Error('方案已过期'), { code: 409 }) } })
  await view.checkConnection(); await say(view, '给 CO801 排产')
  const card = view.messages.value.at(-1)
  const serialized = workbench.serializeWorkbenchSessions(view.sessions.value)
  assert.doesNotMatch(serialized, /proposal-plan-A/)
  const restored = workbench.restoreSessions(serialized)[0].messages.at(-1)
  assert.match(restored.text, /重新|历史/)
  await view.productionDecision(card, 'APPROVE')
  assert.match(card.error, /已过期/)
  assert.equal(card.productionFlow.status, 'FAILED')
  assert.equal(card.productionFlow.result, undefined)
})

test('new session and changed credentials cannot confirm an old production card or receive its late result', async () => {
  let release, decisions = 0
  const { view, user } = productionView({ decideProductionOperation: async (_token, id) => {
    decisions++
    await new Promise(resolve => { release = resolve })
    return { proposalId: id, status: 'EXECUTED', reply: '已保存', result: { planId: 'PP901' } }
  } })
  await view.checkConnection(); await say(view, '给 CO801 排产')
  const card = view.messages.value.at(-1), session = view.active.value
  view.newSession(); await view.productionDecision(card, 'APPROVE')
  assert.equal(decisions, 0)
  view.selectSession(session)
  const pending = view.productionDecision(card, 'APPROVE')
  await view.productionDecision(card, 'APPROVE')
  assert.equal(decisions, 1)
  user.token = 'other'; release(); await pending
  assert.notEqual(card.productionFlow.status, 'EXECUTED')
})

test('older gateway fallback recognizes explicit production requests and preserves status questions', async () => {
  const { view, previews, sent } = productionView({
    workbenchStatus: async () => ({ configured: true, naturalLanguage: true, capabilities: { domains: ['PRODUCTION'] } }),
  })
  await view.checkConnection(); await say(view, '给订单 CO801 排产')
  assert.equal(previews.length, 1)
  await say(view, '这个订单现在派工了吗？')
  assert.equal(sent.length, 1)
  assert.equal(previews.length, 1)
})

test('a clarification form sends only requested fields to the current proposal preview without classifying field values as a new intent', async () => {
  const previews = []
  const { view, decisions } = productionView({
    previewProductionOperation: async (_token, input) => {
      previews.push(input)
      return previews.length === 1 ? { status: 'NEEDS_INPUT', actionCode: 'submitReport', fields: { dispatchId: 'DT101' },
        missingFields: [{ name: 'reportQty', label: '本次完成数量' }], message: '请补充本次完成数量' } : schedulePreview
    },
  })
  await view.checkConnection(); await say(view, '我要给 DT101 报工')
  const card = view.messages.value.at(-1)
  await view.supplementProduction(card, { reportQty: '12', dispatchId: 'DT999' })
  assert.equal(previews[1].text, '')
  assert.deepEqual(previews[1].fields, { dispatchId: 'DT101', reportQty: '12' })
  assert.equal(previews[1].actionCode, 'submitReport')
  await view.supplementProduction(card, { reportQty: '50' })
  assert.equal(previews.length, 2)
  assert.equal(decisions.length, 0)
})

test('a targeted dispatch clarification preserves the requested operator and process when supplying a work order', async () => {
  const previews = []
  const { view, decisions } = productionView({ previewProductionOperation: async (_token, input) => {
    previews.push(input)
    return previews.length === 1
      ? { status: 'NEEDS_INPUT', actionCode: 'confirmSmartDispatch', fields: { operator: '王工', processStep: '装配' },
        missingFields: [{ name: 'workOrderNo', label: '工单编号' }], message: '请补充工单编号' } : schedulePreview
  } })
  await view.checkConnection()
  await say(view, '把装配派给王工'); await say(view, '按工单 WO615 安排智能派工')
  assert.deepEqual(previews[1].fields, { operator: '王工', processStep: '装配' })
  assert.equal(previews[1].actionCode, 'confirmSmartDispatch')
  assert.equal(decisions.length, 0)
})
