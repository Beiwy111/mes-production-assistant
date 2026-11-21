import test from 'node:test'
import assert from 'node:assert/strict'
import { serializeWorkbenchSessions, restoreSessions, buildNaturalRequest } from './agentWorkbench.js'

test('order history persists only recoverable identifiers and redacts personal data', () => {
  const encoded = serializeWorkbenchSessions([{ id: 's', title: '寄给张三 13800138000', messages: [
    { id: 'u', role: 'user', text: '寄给张三 13800138000', orderMessage: true },
    { id: 'a', role: 'assistant', text: '张三收货', orderFlow: { orderDraft: { id: 'd', version: 2, fields: { receiverPhone: '13800138000' } }, steps: [{ detail: '张三' }] } },
  ] }])
  assert.equal(encoded.includes('13800138000'), false)
  assert.equal(encoded.includes('张三'), false)
  const message = restoreSessions(encoded)[0].messages[1]
  assert.equal(message.orderFlow.orderDraft.id, 'd')
  assert.equal(message.orderFlow.needsRecovery, true)
  assert.equal(message.orderFlow.orderDraft.fields, undefined)
})

test('query continuation ignores an order message even if it carries a result', () => {
  const request = buildNaturalRequest({id: 's', messages: [
    { role: 'assistant', status: 'COMPLETED', runId: 'query', result: {reply: 'ok', queryMode: 'NATURAL'} },
    { role: 'assistant', status: 'COMPLETED', runId: 'order', orderFlow: {}, result: {reply: 'ok', queryMode: 'NATURAL'} },
  ]}, '库存', 'r')
  assert.equal(request.previousRunId, 'query')
})
