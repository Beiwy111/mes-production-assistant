import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { configureSessionExpiry } from '../utils/sessionExpiry.js'

// Resolve only the Vite alias; execute the same API module used by the page.
const source = readFileSync(new URL('./agentWorkbench.js', import.meta.url), 'utf8')
  .replace("'@/utils/assistantStream'", JSON.stringify(new URL('../utils/assistantStream.js', import.meta.url).href))
  .replace("'../utils/sessionExpiry.js'", JSON.stringify(new URL('../utils/sessionExpiry.js', import.meta.url).href))
  .replace("'../utils/taskEventStream.js'", JSON.stringify(new URL('../utils/taskEventStream.js', import.meta.url).href))
const { streamWorkbench, previewProductionOperation, decideProductionOperation } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

test('dynamic production planning forwards real SSE events before completion and binds confirmation to its session', async (t) => {
  const observed = [], requests = []
  let controller
  const stream = new ReadableStream({ start(value) { controller = value } })
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, body: JSON.parse(options.body) })
    return url.endsWith('/stream') ? new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } })
      : new Response(JSON.stringify({ code: 200, data: { status: 'REJECTED' } }))
  })
  const push = (sequence, type, legacy) => controller.enqueue(new TextEncoder().encode('data: ' + JSON.stringify({
    event_id: String(sequence), task_id: 'task', conversation_id: 'session', sequence,
    timestamp: '2026-11-26T00:00:00Z', type, payload: { legacy },
  }) + '\n\n'))
  const pending = previewProductionOperation('token', { text: '安排生产', sessionId: 'session' }, { onEvent: e => observed.push(e) })
  push(1, 'task_started', { type: 'run' })
  push(2, 'tool_started', { type: 'workflow', events: [{ agentId: 'ORDER', kind: 'STEP_STARTED', toolId: 'TASK_ORDERS' }] })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(observed.length, 2)
  push(3, 'message_completed', { type: 'result', data: { status: 'PENDING', taskManaged: true } })
  push(4, 'task_completed', { type: 'done' })
  assert.equal((await pending).taskManaged, true)
  await decideProductionOperation('token', 'proposal', 'SKIP', { sessionId: 'session' })
  assert.deepEqual(requests.map(r => r.url), ['/api/assistant-v2/tasks/stream', '/api/assistant-v2/tasks/session/proposals/proposal/decision'])
  assert.deepEqual(requests[1].body, { decision: 'SKIP' })
})

test('production decisions post only the opaque proposal and decision with the current bearer token', async (t) => {
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push([url, options.headers.Authorization, JSON.parse(options.body)])
    return new Response(JSON.stringify({ code: 200, data: { status: 'PENDING' } }))
  })
  const preview = { text: '请排产', sessionId: 'session' }
  assert.equal((await previewProductionOperation('planner-token', preview)).status, 'PENDING')
  await decideProductionOperation('planner-token', 'opaque/id', 'SKIP')
  assert.deepEqual(requests, [
    ['/api/assistant-v2/operations/preview', 'Bearer planner-token', preview],
    ['/api/assistant-v2/operations/opaque%2Fid/decision', 'Bearer planner-token', { decision: 'SKIP' }],
  ])
})

test('a rejected continuation keeps its scope and reports the error without retrying', async (t) => {
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, body: JSON.parse(options.body) })
    return new Response(JSON.stringify({ code: 410, message: 'PREVIOUS_RUN_EXPIRED' }), {
      status: 410, headers: { 'Content-Type': 'application/json' },
    })
  })
  const input = { text: '继续看生产进度', sessionId: 'session', requestId: 'request', previousRunId: 'previous' }
  await assert.rejects(streamWorkbench('token', input), /上一轮查询已过期，请开始新对话/)
  assert.equal(requests.length, 1)
  assert.deepEqual(requests[0].body, input)
})

test('natural result and real progress events pass through without invented agent events', async (t) => {
  const events = [
    { type: 'run', runId: 'run-1' },
    { type: 'agent', agentId: 'SUP', status: 'RUNNING' },
    { type: 'result', data: { reply: '请提供订单号', queryMode: 'NATURAL', workflowProfile: 'AUTO', verdict: 'CLARIFY' } },
  ]
  t.mock.method(globalThis, 'fetch', async () => new Response(events.map((event) => JSON.stringify(event)).join('\n')))
  const observed = []
  const result = await streamWorkbench('token', { text: '查进度', sessionId: 's', requestId: 'r' }, { onEvent: (event) => observed.push(event) })
  assert.deepEqual(observed, events)
  assert.deepEqual(result, events.at(-1).data)
})

test('SSE authorization failure expires the same login without retrying the task',async(t)=>{
 let token='expired',cleared=false
 const dispose=configureSessionExpiry({getToken:()=>token,clearSession:()=>{token='';cleared=true},onExpired:()=>{}})
 const frames=[{event_id:'1',task_id:'run',conversation_id:'chat',sequence:1,timestamp:'2026-11-15T00:00:00Z',type:'task_started',payload:{legacy:{type:'run',runId:'run'}}},
 {event_id:'2',task_id:'run',conversation_id:'chat',sequence:2,timestamp:'2026-11-15T00:00:01Z',type:'task_failed',payload:{code:'UNAUTHORIZED',legacy:{type:'error',message:'UNAUTHORIZED'}}}]
 t.mock.method(globalThis,'fetch',async url=>String(url).endsWith('/cancel')?new Response('{}'):new Response(frames.map(e=>'data: '+JSON.stringify(e)+'\n\n').join(''),{headers:{'Content-Type':'text/event-stream'}}))
 await assert.rejects(streamWorkbench('expired',{sessionId:'chat',text:'查库存',requestId:'r'}),/登录已失效/)
 assert.equal(cleared,true)
 dispose()
})
