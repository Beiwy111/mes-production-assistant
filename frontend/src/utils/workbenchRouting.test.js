import test from 'node:test'
import assert from 'node:assert/strict'
import { prefersReadOnlyQuery, productionPreviewContext, productionPreviewIntent } from './workbenchRouting.js'

test('polite production requests open a preview while factual questions remain read only', () => {
  for (const text of ['你能帮我去排产吗', '帮我去制定生产计划呢', '可以帮我智能排产吗？', '请帮我生成生产计划', '帮我制定一下生产计划', '智能制定生产计划', '帮我做生产计划', '帮我给 PP901 排产吧', '能帮我智能派工吗？'])
    assert.equal(productionPreviewIntent(text), true, text)
  for (const text of ['排产情况怎么样？', '订单排产了吗', '如何制定生产计划？', '你能帮我查排产进度吗', '能看看生产计划吗', '为什么无法排产', '不要排产', '取消排产', '排产有没有冲突', '智能排产是什么'])
    assert.equal(productionPreviewIntent(text), false, text)
})

test('strategy replies continue only an open production clarification', () => {
  const previous = { productionFlow: { status: 'NEEDS_INPUT', actionCode: 'saveProductionPlan' } }
  for (const text of ['交期优先', '负载均衡', '成本优先', 'DELIVERY', 'BALANCE', 'COST']) {
    assert.equal(productionPreviewIntent(text, previous), true, text)
    assert.equal(productionPreviewIntent(text), false, text)
  }
})
test('dynamic task continuations retain the server task while unrelated reads and weather keep their own route', () => {
  const previous = { productionFlow: { taskManaged: true, status: 'EXECUTED' } }
  for (const text of ['继续', '回到刚才的计划，继续派工', '改成成本优先', '调整截止日期为2027-01-10'])
    assert.equal(productionPreviewIntent(text, previous), true, text)
  for (const text of ['继续查一下天气', '查看质检情况', '不要继续派工'])
    assert.equal(productionPreviewIntent(text, previous), false, text)
})
test('explicit reads enter streaming Supervisor without waiting on order extraction', () => {
  for (const text of ['WO202607003这个订单现在派工了吗','工单派给谁了','哪道工序没做完','同批有没有质检异常','帮我查询库存', '看看生产进度', '解释LCD面板缺陷', '查询2026-11-01至2026-11-15未完成工单', '汇总设备停机时长'])
    assert.equal(prefersReadOnlyQuery(text, { canQuery: true }), true, text)
})

test('a confirmed transition can reference its target, while a pending proposal or multiple dispatch tickets require clarification', () => {
  const completed = { productionFlow: { status: 'EXECUTED', result: true, confirmation: { params: { orderId: 'CO762' } } } }
  assert.deepEqual(productionPreviewContext(completed, '把这张订单提交给计划员'), { fields: { orderId: 'CO762' } })
  assert.deepEqual(productionPreviewContext(completed, '把这张订单 CO981 提交给计划员'), {})
  assert.deepEqual(productionPreviewContext({ productionFlow: { ...completed.productionFlow, status: 'PENDING' } }, '给这张订单排产'), {})
  assert.deepEqual(productionPreviewContext({ productionFlow: { status: 'EXECUTED', result: { dispatches: [{ id: 'DT001' }, { id: 'DT002' }] } } }, '催办这张派工单'), {})
  assert.deepEqual(productionPreviewContext({ productionFlow: { status: 'EXECUTED', result: { workOrderId: 'WO001' } } }, '把这张工单的装配派给王工'), { fields: { workOrderNo: 'WO001' } })
  for (const text of ['审核订单 CO801', '领齐物料', '给派工 DT001 报工']) assert.equal(productionPreviewIntent(text), true)
  for (const text of ['排产情况', '智能派工多少单', '提醒发给谁了']) assert.equal(productionPreviewIntent(text), false)
})
test('write and mixed intents keep the established order intake and permissions', () => {
  for (const text of ['我想下单100台显示器', '查产品后下单100台', '修改订单地址', '查订单并取消', '安排张三生产订单100'])
    assert.equal(prefersReadOnlyQuery(text, { canQuery: true }), false, text)
  assert.equal(prefersReadOnlyQuery('查库存', { canQuery: false }), false)
  assert.equal(prefersReadOnlyQuery('100台', { canQuery: true }), false)
})
test('open questions about stages, inspections and causes go to the streaming Supervisor', () => {
  for (const text of ['八道工序分别是什么？', 'QI202607021 存在什么问题？主要可能涉及哪个生产环节？', '为什么近期某个批次的不良率升高？', '抽样检验是什么？'])
    assert.equal(prefersReadOnlyQuery(text, { canQuery: true }), true, text)
  assert.equal(prefersReadOnlyQuery('解释完再下单', { canQuery: true }), false)
})

test('explicit new-production choice resumes the managed task without order-intake classification', () => {
  const previous = { productionFlow: { taskManaged: true, status: 'COMPLETED' } }
  assert.equal(productionPreviewIntent('新生产', previous), true)
  assert.equal(productionPreviewIntent('那就全部新生产吧', previous), true)
  assert.equal(productionPreviewIntent('不要新生产', previous), false)
  assert.equal(productionPreviewIntent('新生产', null), false)
})
