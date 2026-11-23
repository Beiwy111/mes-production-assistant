import test from 'node:test'
import assert from 'node:assert/strict'
import { chunkLocation, displayDate, fileType, indexState } from './documentPresentation.js'
import { extensions, maxFileSize, validateFile } from './ingestionApi.js'

test('the document table distinguishes extraction from graph persistence', () => {
  const job = { status:'failed', stages:[
    {key:'embedding',status:'success'}, {key:'entities',status:'success'},
    {key:'relations',status:'success'}, {key:'graph',status:'failed'},
  ] }
  assert.equal(indexState(job, 'embedding'), '已向量化')
  assert.equal(indexState(job, 'graph'), '已抽取')
  assert.equal(indexState({ ...job, stages:[{key:'entities',status:'running'},{key:'relations',status:'pending'}], status:'running' }, 'graph'), '处理中')
  assert.equal(indexState({ status:'completed' }, 'graph'), '已抽取')
  assert.equal(indexState({ status:'failed', stages:[{key:'embedding',status:'pending'}] }, 'embedding'), '未执行')
  assert.equal(indexState({ status:'failed' }, 'embedding'), '未记录')
})

test('document formats and chunk positions preserve source information', () => {
  assert.equal(fileType({ filename:'旧版.PpT' }), 'PPT')
  assert.equal(fileType({ filename:'未知文件', metadata:{format:'pdf'} }), 'PDF')
  assert.equal(fileType({ filename:'未知文件' }), '未知格式')
  assert.equal(displayDate(null), '—')
  assert.equal(chunkLocation({location:'page:2/table:3'}), '第 2 页 · 第 3 个表格')
  assert.equal(chunkLocation({metadata:{sheet:'质量',rowStart:8,rowEnd:14}}), '质量 · 第 8–14 行')
  assert.equal(chunkLocation({location:'document:end'}), '文档末段')
  assert.equal(chunkLocation({location:'legacy:odd/source'}), 'legacy:odd · source')
})

test('upload validation accepts the service formats and rejects unsafe names and oversize files', () => {
  for (const suffix of extensions) assert.equal(validateFile({ name:'资料.' + suffix.toUpperCase(), size:maxFileSize }), '')
  for (const name of ['pdf', '.pdf', '../单据.pdf', 'a:b.txt', 'x.exe', 'a'.repeat(197) + '.pdf']) {
    assert.ok(validateFile({ name, size:1 }))
  }
  assert.ok(validateFile({ name:'a.txt', size:0 }))
  assert.ok(validateFile({ name:'a.txt', size:maxFileSize + 1 }))
})
