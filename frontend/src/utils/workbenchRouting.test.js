import test from 'node:test'
import assert from 'node:assert/strict'
import { prefersReadOnlyQuery } from './workbenchRouting.js'
test('explicit reads enter streaming Supervisor without waiting on order extraction', () => {
  for (const text of ['WO202607003这个订单现在派工了吗','工单派给谁了','哪道工序没做完','同批有没有质检异常','帮我查询库存', '看看生产进度', '解释LCD面板缺陷', '查询2026-11-01至2026-11-15未完成工单', '汇总设备停机时长'])
    assert.equal(prefersReadOnlyQuery(text, { canQuery: true }), true, text)
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
