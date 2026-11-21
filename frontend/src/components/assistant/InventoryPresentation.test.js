import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import * as inventory from '../../utils/inventoryPresentation.js'

function component(name) {
  const { descriptor } = parse(readFileSync(new URL(`./${name}.vue`, import.meta.url), 'utf8'))
  const dependencies = { vue, '@/utils/inventoryPresentation': inventory }
  const code = compileScript(descriptor, { id: `inventory-${name}`, inlineTemplate: true }).content
    .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?\s*$/gm, (_, bindings, from) => `const { ${bindings.replace(/\bas\b/g, ':')} } = dependencies[${JSON.stringify(from)}];`)
    .replace('export default', 'return')
  return new Function('dependencies', code)(dependencies)
}
const row = { material_name: 'PCB主板<img src=x>', material_code: 'PCB', quantity_on_hand: '20.0000', quantity_reserved: '5.0000', quantity_available: '15.0000', unit: '块', warehouse_code: 'WH-01', batch_no: 'B1' }
const overview = { domain: 'INVENTORY', rows: [row], totalRecords: 1, returnedRecords: 1, period: 'CURRENT' }
const final = { status: 'COMPLETED', agents: [], result: { facts: { inventory: { overview } } } }
const render = (name, message) => renderToString(vue.createSSRApp(component(name), { message }))
test('final inventory is an accessible visible table, with separate original-unit quantities and escaped material names', async () => {
  const html = await render('InventoryAnswer', final)
  for (const text of ['<table>', '现存', '预留', '可用', '15', '20', 'WH-01', 'B1', 'PCB主板&lt;img']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(html, /<img|<details|库存一览|匹配记录|库存下一页|库存上一页|type="search"/)
  assert.doesNotMatch(await render('InventoryAnswer', { ...final, evidenceInvalidated: true }), /<section|<table|PCB/)
})
test('process shows received inventory as provisional data and stops displaying it after completion/cancel', async () => {
  const live = { status: 'RUNNING', agents: [{ agentId: 'SUPPLY', evidence: [{ kind: 'OVERVIEW_INVENTORY', queryRef: 'q', data: { overview } }] }] }
  const html = await render('InventoryFindings', live)
  for (const text of ['已读取库存明细', '等待总协调', '15', 'PCB主板&lt;img', 'WH-01', 'B1']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(await render('InventoryFindings', { ...live, status: 'CANCELLED' }), /<section|PCB/)
  assert.doesNotMatch(await render('InventoryFindings', final), /<section|PCB/)
})


test('all returned inventory rows render without frontend pagination or 100-row clipping', async () => {
  const rows = Array.from({ length: 165 }, (_, i) => ({ ...row, material_name: `PCB-${i}`, record_id: String(i) }))
  const html = await render('InventoryAnswer', { ...final, result: { facts: { inventory: { overview: { ...overview, rows, totalRecords: 165, returnedRecords: 165 } } } } })
  assert.equal((html.match(/<tbody>[\s\S]*?<\/tbody>/)[0].match(/<tr>/g) || []).length, 165)
  assert.ok(html.includes('PCB-164'))
})
