import test from 'node:test'
import assert from 'node:assert/strict'
import { readPublicCatalog } from './publicCatalog.js'

const page = { release_id:'r', graph_release_id:null, graph_status:'UNAVAILABLE_OR_STALE',
  counts:{ documents:1, chunks:2 }, hits:[], graph_paths:[], next_offset:null }
const hit = { release_id:'r', document_id:'P', document_version:'v', title:'Manual', text:'Source text.',
  page_start:1, page_end:1, authority_type:'PUBLIC_MANUAL', chunk_id:'c1', evidence_ref:'r:P:c1' }
const first = { ...page, hits:[hit], next_offset:1 }
const second = { ...page, hits:[{ ...hit, chunk_id:'c2', evidence_ref:'r:P:c2' }] }
function mockPages(t, pages) {
  const requests = []
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    requests.push({ url, options })
    const entry = pages.shift()
    assert.ok(entry, 'unexpected request')
    return entry instanceof Response ? entry : Response.json({ code:200, data:entry })
  })
  return requests
}

test('catalog reads every chunk when the entity graph is unavailable and pins continuation requests', async t => {
  const requests = mockPages(t, [first, second])
  const controller = new AbortController()
  const result = await readPublicCatalog('session', controller.signal)
  assert.deepEqual(result.hits.map(entry => entry.chunk_id), ['c1', 'c2'])
  assert.deepEqual(result.paths, [])
  assert.match(requests[1].url, /offset=1&limit=100&releaseId=r$/)
  assert.ok(requests.every(({ options }) => options.signal === controller.signal && options.headers.Authorization === 'Bearer session'))
})

test('refresh is required when a release, graph revision, or total changes between pages', async t => {
  for (const change of [{ release_id:'other' }, { graph_release_id:'other' }, { counts:{ ...page.counts, documents:2 } }]) {
    await t.test(JSON.stringify(change), async sub => {
      mockPages(sub, [first, { ...second, ...change }])
      await assert.rejects(readPublicCatalog('session'), /版本已更新/)
    })
  }
  await t.test('HTTP conflict', async sub => {
    mockPages(sub, [first, new Response(null, { status:409 })])
    await assert.rejects(readPublicCatalog('session'), /版本已更新/)
  })
})

test('duplicates, missing chunks and stuck pagination cannot become a complete catalog', async t => {
  for (const [name, pages, error] of [
    ['duplicate', [first, { ...second, hits:[hit] }], /完整性核验/],
    ['missing', [{ ...first, next_offset:null }], /完整性核验/],
    ['foreign reference', [first, { ...second, hits:[{ ...hit, evidence_ref:'other' }] }], /完整性核验/],
    ['repeated offset', [first, { ...second, next_offset:1 }], /分页信息/],
  ]) await t.test(name, async sub => {
    mockPages(sub, pages)
    await assert.rejects(readPublicCatalog('session'), error)
  })
})
