import test from 'node:test'
import assert from 'node:assert/strict'
import { comparePhoto, imageFollowup, precedingWorkRun } from './imageConversation.js'

const work = { role: 'assistant', status: 'COMPLETED', runId: 'work', result: { verdict: 'EVIDENCE_READY',
  scope: {confirmed: true, workOrderNo: 'WO1'}, facts: {production: {overview: {rows: [{work_order_no: 'WO1'}]}}}}}
const photo = { role: 'assistant', status: 'COMPLETED', imageRef: 'photo', imageQuestion: true }
const record = type => ({ verdict: 'EVIDENCE_READY', scope: {qualityWorkOrders: [3]}, facts: { quality: {
  overview: { rows: [{work_order_id: '3', work_order_no: 'WO1', inspection_no: 'QI1', recorded_defect_types: type}] }}} })

test('keeps work and photo references separate, respects replacement and new sessions', () => {
  assert.equal(precedingWorkRun([work, photo]), 'work')
  assert.equal(imageFollowup([work, photo], '划痕在哪个位置').action, 'location')
  assert.equal(imageFollowup([work, photo], '为什么会这样').action, 'describe')
  assert.equal(imageFollowup([work, photo], '怎么验证').action, 'describe')
  assert.equal(imageFollowup([work, photo], '这些斑点在哪里').action, 'describe')
  assert.equal(imageFollowup([work, photo], '这和刚才那张工单的质检记录一致吗').action, 'compare')
  assert.equal(imageFollowup([], '划痕在哪里'), null)
  assert.equal(imageFollowup([photo, {imageUpload: true, status:'FAILED'}], '划痕在哪里'), null)
  assert.equal(imageFollowup([photo], '帮我查库存'), null)
  assert.equal(imageFollowup([photo], '修改工单上的质检记录'), null)
})

test('distinguishes matching categories, missing evidence and unverified scope', () => {
  assert.match(comparePhoto(1,record('外观划伤')), /类别相符/)
  assert.match(comparePhoto(1,record('外观缺陷')), /不足以确认/)
  assert.match(comparePhoto(0,record('外观划伤')), /没有检出/)
  assert.match(comparePhoto(null,record('外观划伤')), /缺少可核验/)
  const changed = record('划痕')
  changed.scope.qualityWorkOrders=[8]
  assert.match(comparePhoto(1,changed), /无法判断/)
})
