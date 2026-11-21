import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveOrderContext } from './workbenchContext.js'

test('requires a selected order or an explicit order ID, never treating inventory numbers as IDs', () => {
  assert.equal(
    resolveOrderContext('', '库存还有 100 台吗', ['100']).error,
    'MISSING_ORDER',
  )
  assert.equal(
    resolveOrderContext('', '订单 ID 100 能交付吗', ['100']).orderPk,
    '100',
  )
  assert.equal(
    resolveOrderContext('101', '还能交付多少', ['100', '101']).orderPk,
    '101',
  )
})
test('asks for clarification on conflicting or multiple orders and respects the allowed list', () => {
  assert.equal(
    resolveOrderContext('100', '订单101怎么样', ['100', '101']).error,
    'ORDER_CONFLICT',
  )
  assert.equal(
    resolveOrderContext('', '订单100和订单101', ['100', '101']).error,
    'MULTIPLE_ORDERS',
  )
  assert.equal(
    resolveOrderContext('', '订单999能交付吗', ['100']).error,
    'ORDER_NOT_ALLOWED',
  )
  assert.equal(
    resolveOrderContext('100', '你好', []).error,
    'ORDER_NOT_ALLOWED',
  )
})
test('keeps primary keys as strings and does not interpret business order numbers as numeric IDs', () => {
  const id = '9223372036854775807'
  assert.equal(resolveOrderContext('', `订单 ID ${id}`, [id]).orderPk, id)
  assert.equal(
    resolveOrderContext('', '订单 SO20261109001', ['100']).error,
    'MISSING_ORDER',
  )
  assert.equal(
    resolveOrderContext('', '订单号20261109', ['100']).error,
    'MISSING_ORDER',
  )
})
test('never silently chooses an order from quantities, decimals or a shorthand list', () => {
  for (const text of [
    '这张订单100台能按时交付吗',
    '订单 ID 100.5',
    '订单100和101哪个更紧急',
    '订单 ID 100和101哪个好',
  ]) {
    assert.ok(resolveOrderContext('', text, ['100', '101']).error, text)
  }
})
