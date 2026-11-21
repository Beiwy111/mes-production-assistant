import test from 'node:test'
import assert from 'node:assert/strict'
import { prefersReadOnlyQuery } from './workbenchRouting.js'
test('explicit reads enter streaming Supervisor without waiting on order extraction', () => {
  for (const text of ['帮我查询库存', '看看生产进度', '解释LCD面板缺陷', '查询2026-11-01至2026-11-15未完成工单', '汇总设备停机时长'])
    assert.equal(prefersReadOnlyQuery(text, { canQuery: true }), true, text)
})
test('write and mixed intents keep the established order intake and permissions', () => {
  for (const text of ['我想下单100台显示器', '查产品后下单100台', '修改订单地址', '查订单并取消', '安排张三生产订单100'])
    assert.equal(prefersReadOnlyQuery(text, { canQuery: true }), false, text)
  assert.equal(prefersReadOnlyQuery('查库存', { canQuery: false }), false)
  assert.equal(prefersReadOnlyQuery('100台', { canQuery: true }), false)
})
