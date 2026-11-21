import test from 'node:test'
import assert from 'node:assert/strict'
import { consumeAssistantStream } from './assistantStream.js'

const body = (parts) => new ReadableStream({ start(controller) {
  for (const part of parts) controller.enqueue(new TextEncoder().encode(part))
  controller.close()
} })

test('preserves legacy delta/result and accepts new run/status events across chunks', async () => {
  const deltas = [], events = []
  const result = await consumeAssistantStream(body([
    '{"type":"run","runId":"r1"}\n{"type":"delta","te',
    'xt":"你好"}\n{"type":"result","data":{"type":"answer","reply":"你好"}}\n{"type":"done"}\n'
  ]), { onDelta: text => deltas.push(text), onEvent: event => events.push(event.type) })
  assert.deepEqual(deltas, ['你好'])
  assert.equal(result.reply, '你好')
  assert.deepEqual(events, ['run', 'delta', 'result', 'done'])
})

test('rejects errors and incomplete final result', async () => {
  await assert.rejects(consumeAssistantStream(body(['{"type":"error","message":"不可用"}\n'])), /不可用/)
  await assert.rejects(consumeAssistantStream(body(['{"type":"delta","text":"partial"}\n'])), /最终结果/)
})

test('reports authentication errors returned as HTTP-200 Result envelopes', async () => {
  await assert.rejects(consumeAssistantStream(body(['{"code":401,"message":"登录失效","data":null}'])),
    error => error.code === 401 && error.message === '登录失效')
})

test('limits a malformed unbounded line and cancels its reader', async () => {
  let cancelled = false
  const stream = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('x'.repeat(2_097_153))) }, cancel() { cancelled = true } })
  await assert.rejects(consumeAssistantStream(stream), /过大/)
  assert.equal(cancelled, true)
})

test('complete bounded history results above one MiB are not silently truncated', async () => {
  const text='x'.repeat(1_100_000)
  const result=await consumeAssistantStream(body([JSON.stringify({type:'result',data:{text}})+'\n']))
  assert.equal(result.text.length,text.length)
})

test('abort cancels pending reads instead of leaving the promise running', async () => {
  const abort = new AbortController()
  let cancelled = false
  const stream = new ReadableStream({ cancel() { cancelled = true } })
  const promise = consumeAssistantStream(stream, { signal: abort.signal })
  abort.abort()
  await assert.rejects(promise, error => error.name === 'AbortError')
  assert.equal(cancelled, true)
})
