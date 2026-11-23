import test from 'node:test'
import assert from 'node:assert/strict'
import { approvedSopSources, knowledgeSources, resultCsv } from './knowledgeSources.js'

test('approved SOP citations require a version and section and disappear when invalidated', () => {
  const hit = { evidence_ref:'sop:station:3', title:'装配作业规程', version:'3', section:'4.2', text:'内部段落' }
  const message = { result:{ approvedSopEvidence:[{ status:'MATCHES', hits:[hit, hit, {...hit, version:''}] }] } }
  assert.deepEqual(approvedSopSources(message), [{ ref:hit.evidence_ref, title:hit.title, section:'4.2', version:'3' }])
  assert.deepEqual(approvedSopSources({...message, evidenceInvalidated:true}), [])
})

test('sources preserve versioned references and reject unsafe URLs or invalidated evidence', () => {
  const hit = { evidence_ref:'r:p:c', title:'LCD paper', source_url:'https://example.org/paper', page_start:3, text:'source' }
  const message={ result:{ knowledgeEvidence:[{ hits:[hit,hit,{...hit,evidence_ref:'x',source_url:'javascript:alert(1)'}] }] } }
  assert.equal(knowledgeSources(message).length,1)
  assert.equal(knowledgeSources(message)[0].page,3)
  assert.deepEqual(knowledgeSources({...message,evidenceInvalidated:true}),[])
})
test('CSV preserves every field and row, escapes quotes and neutralizes spreadsheet formulas', () => {
  const csv=resultCsv({result:{facts:{order:{overview:{rows:[{name:'=SUM(A1)',memo:'a,"b"',quantity:12},{name:'普通',extra:'新字段'}]}}}}})
  assert.ok(csv.startsWith('\ufeff'))
  assert.ok(csv.includes("'=SUM(A1)"))
  assert.ok(csv.includes('a,""b""'))
  assert.ok(csv.includes('extra'))
  assert.ok(csv.includes('新字段'))
  assert.equal(resultCsv({evidenceInvalidated:true,result:{facts:{}}}),null)
})
