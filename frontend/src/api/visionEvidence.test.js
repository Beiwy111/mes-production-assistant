import test from 'node:test'
import assert from 'node:assert/strict'
import * as evidence from './visionEvidence.js'

const record = { inspectionId: '68', recordId: '5c0cc43c-90b8-4f6b-8c6e-1a986ac5d0d5' }
const response = () => new Response(new Uint8Array([137, 80, 78, 71]), { headers: { 'Content-Type': 'image/png' } })

test('image reads use a fixed authenticated endpoint and reject paths before sending credentials', async (t) => {
  let calls = 0
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls++
    assert.equal(url, '/api/quality/vision/evidence/68/5c0cc43c-90b8-4f6b-8c6e-1a986ac5d0d5/original')
    assert.equal(options.headers.Authorization, 'Bearer current-session')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.redirect, 'error')
    return response()
  })
  const blob = await evidence.fetchVisionImage({ ...record, url: 'https://evil.test' }, 'original', 'current-session')
  assert.equal(blob.type, 'image/png')
  for (const [data, variant, token] of [[record, 'original', ''], [{ ...record, inspectionId: '../1' }, 'original', 't'],
    [{ ...record, recordId: 'https://evil.test' }, 'original', 't'], [record, '../original', 't']]) {
    await assert.rejects(evidence.fetchVisionImage(data, variant, token))
  }
  assert.equal(calls, 1)
})

test('unauthorized, wrong MIME, empty and oversized responses never become images or expose server errors', async (t) => {
  const fixtures = [
    new Response('private stack', { status: 403 }),
    new Response('<svg/>', { headers: { 'Content-Type': 'image/svg+xml' } }),
    new Response('', { headers: { 'Content-Type': 'image/png' } }),
    new Response('x', { headers: { 'Content-Type': 'image/png', 'Content-Length': '10485761' } }),
    new Response(new Uint8Array(10485761), { headers: { 'Content-Type': 'image/png' } }),
  ]
  t.mock.method(globalThis, 'fetch', async () => fixtures.shift())
  for (let i = 0; i < 5; i++) {
    await assert.rejects(evidence.fetchVisionImage(record, 'input', 't'), (error) => {
      assert.doesNotMatch(error.message, /private stack|<svg/)
      return true
    })
  }
})

test('closing a preview revokes loaded URLs and aborts late responses before creating another URL', async (t) => {
  let resolveLate
  let lateSignal
  let count = 0
  const revoked = []
  t.mock.method(URL, 'createObjectURL', () => `blob:preview-${++count}`)
  t.mock.method(URL, 'revokeObjectURL', (url) => revoked.push(url))
  t.mock.method(globalThis, 'fetch', async (_, options) => {
    if (!count) return response()
    lateSignal = options.signal
    return new Promise((resolve) => { resolveLate = resolve })
  })
  const session = evidence.createVisionImageSession()
  assert.equal(await session.load(record, 'original', 't'), 'blob:preview-1')
  const late = session.load(record, 'annotated', 't')
  session.close()
  assert.equal(lateSignal.aborted, true)
  resolveLate(response())
  await assert.rejects(late, { name: 'AbortError' })
  assert.deepEqual(revoked, ['blob:preview-1'])
  assert.equal(count, 1)
  session.close()
  assert.equal(revoked.length, 1)
})

test('only supported original files within the upload limit are attached', () => {
  assert.equal(evidence.originalImageNotice({ type: 'image/png', size: 10485760 }), '')
  assert.match(evidence.originalImageNotice({ type: 'image/jpeg', size: 10485761 }), /原图未保存/)
  assert.match(evidence.originalImageNotice({ type: 'image/webp', size: 50 }), /原图未保存/)
})

test('a stalled image request times out and aborts the network operation', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  let requestSignal
  t.mock.method(globalThis, 'fetch', async (_, options) => {
    requestSignal = options.signal
    return new Promise((_, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true }))
  })
  const pending = evidence.fetchVisionImage(record, 'input', 't')
  const rejected = assert.rejects(pending, /读取超时/)
  t.mock.timers.tick(12000)
  await rejected
  assert.equal(requestSignal.aborted, true)
})
