import test from 'node:test'
import assert from 'node:assert/strict'
import { inventorySnapshot, inventoryQuantity, inventorySummary, isInventoryOnly } from './inventoryPresentation.js'

const rows = [{ record_id: '1', material_name: 'PCB主板', material_code: 'PCB', quantity_on_hand: '3988.0000', quantity_reserved: '100.0000', quantity_available: '3888.0000', unit: '块', warehouse_code: 'WH-01', batch_no: 'B1' },
  { record_id: '2', material_name: '背光模组', quantity_available: null, unit: '套' }]
const overview = { domain: 'INVENTORY', rows, totalRecords: 3, returnedRecords: 2, truncated: true, period: 'CURRENT' }
const pending = () => ({ status: 'RUNNING', agents: [{ agentId: 'SUPPLY', evidence: [{ kind: 'OVERVIEW_INVENTORY', queryRef: 'q1', data: { overview } }] }] })
test('inventory arrives from real supply evidence and final facts take precedence', () => {
  const message = pending()
  const snapshot = inventorySnapshot(message)
  assert.equal(snapshot.provisional, true)
  assert.deepEqual(snapshot.rows, rows)
  assert.equal(snapshot.total, 3)
  message.result = { facts: { inventory: { overview: { ...overview, rows: [], totalRecords: 0, returnedRecords: 0, truncated: false } } } }
  assert.deepEqual(inventorySnapshot(message).rows, [])
  assert.equal(inventorySnapshot(message).provisional, false)
})
test('invalid, omitted and stopped evidence never reappears as current inventory', () => {
  for (const marker of [{ evidenceInvalidated: true }, { workflow: { invalidated: true } }, { result: { verdict: 'DATA_CHANGED' } }, { result: { historyEvidenceOmitted: true } }, { status: 'FAILED' }, { status: 'CANCELLED' }, { status: 'INTERRUPTED' }]) {
    assert.equal(inventorySnapshot({ ...pending(), ...marker }), null)
  }
  assert.equal(inventorySnapshot({ ...pending(), result: { facts: {} } }), null)
})
test('quantity formatting retains precision, missing values and different units', () => {
  assert.equal(inventoryQuantity('12345678901234567890.120000'), '12,345,678,901,234,567,890.12')
  assert.equal(inventoryQuantity('0.0000'), '0')
  assert.equal(inventoryQuantity('-12.7500'), '-12.75')
  for (const value of [null, undefined, '', {}, 'not-a-number']) assert.equal(inventoryQuantity(value), '未提供')
  const summary = inventorySummary(inventorySnapshot(pending()))
  assert.match(summary, /3 条.*2 条/)
  assert.doesNotMatch(summary, /3888|3988|共.*块/)
})
test('malformed and incomplete snapshots remain explicitly partial; mixed business answers are preserved', () => {
  const result = { verdict: 'EVIDENCE_READY', plan: { intents: ['INVENTORY'] }, facts: { inventory: { overview: { ...overview, truncated: false, totalRecords: 2 } } } }
  assert.equal(isInventoryOnly({ status: 'COMPLETED', result }), true)
  assert.equal(isInventoryOnly({ result: { ...result, plan: { intents: ['INVENTORY', 'QUALITY'] } } }), false)
  assert.equal(isInventoryOnly({ result: { ...result, verdict: 'INSUFFICIENT_EVIDENCE' } }), false)
  assert.equal(isInventoryOnly({ result: { ...result, facts: { inventory: { overview } } } }), false)
  const message = pending()
  message.agents[0].evidence[0].data.overview = { ...overview, rows: [rows[0], null], returnedRecords: 2, truncated: false }
  assert.equal(inventorySnapshot(message).partial, true)
})
