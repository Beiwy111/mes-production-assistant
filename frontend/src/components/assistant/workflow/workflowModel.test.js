import assert from 'node:assert/strict'
import test from 'node:test'
import { projectDsl, workflowCatalog } from '../../../../build/workflowCatalog.js'
import { flowGraph, layoutNodes, overviewGraph } from './workflowModel.js'

const plugin = workflowCatalog()
const moduleText = plugin.load.call({ addWatchFile() {} }, plugin.resolveId('virtual:mes-workflows'))
const catalog = JSON.parse(moduleText.slice('export default '.length))
const supervisor = catalog.find(f => f.agentId === 'SUP')

test('overview resolves the supervisor’s six declared tools to real agent DSLs', () => {
  const graph = overviewGraph(catalog)
  assert.equal(graph.nodes.length, 7)
  assert.equal(graph.edges.length, 6)
  assert.ok(graph.edges.every(e => e.from === 'SUP' && graph.nodes.some(n => n.id === e.to)))
  assert.equal(catalog.filter(f => f.group === '知识流程').length, 2)
  assert.ok(graph.nodes.every(n => catalog.some(f => f.name === n.flowName && f.nodes.length)))
})

test('DSL projection includes safe configuration and parameter signatures only', () => {
  const data = { type: 'code', title: '校验', code_language: 'python3', code: 'private-code',
    headers: { Authorization: 'private-token' }, prompt_template: 'private-prompt',
    variables: [{ variable: 'receipt', value_selector: ['http', 'body'] }],
    outputs: { checked: { type: 'string' } } }
  const projected = projectDsl({ workflow: { graph: { nodes: [{ id: 5, data }], edges: [] } } })
  assert.deepEqual(projected.nodes[0].config, [['语言', 'python3']])
  assert.deepEqual(projected.nodes[0].inputs, [['receipt', 'http.body']])
  assert.deepEqual(projected.nodes[0].outputs, [['checked', 'string']])
  assert.doesNotMatch(JSON.stringify(projected), /private-/)
  assert.doesNotMatch(moduleText, /ROLE_API_KEY|Bearer |import json|prompt_template/)
})

test('iteration and main-route projections retain only real edges at their own level', () => {
  const all = flowGraph(supervisor, '', false)
  const main = flowGraph(supervisor, '', true)
  const wave = flowGraph(supervisor, 'wave1', false)
  assert.ok(wave.nodes.length > 1)
  assert.ok(wave.nodes.every(n => n.parent === 'wave1' && !all.nodes.includes(n)))
  assert.ok(all.nodes.length > main.nodes.length)
  assert.equal(main.edges.length, main.nodes.length - 1)
  assert.ok(main.edges.every((edge, i) => all.edges.includes(edge) && (!i || main.edges[i-1].to === edge.from)))
  assert.equal(main.edges[0].from, 'start_node')
  assert.equal(main.edges.at(-1).to, 'end_node')
})

test('both layout directions advance along every DSL edge without overlapping nodes', () => {
  for (const flow of catalog) {
    for (const parent of new Set(flow.nodes.map(n => n.parent))) {
      const graph = flowGraph(flow, parent, false)
      for (const direction of ['horizontal', 'vertical']) {
        const axis = direction === 'horizontal' ? 'x' : 'y'
        const positions = layoutNodes(graph, direction)
        assert.equal(new Set([...positions.values()].map(pos => JSON.stringify(pos))).size, graph.nodes.length)
        assert.ok(graph.edges.every(e => positions.get(e.to)[axis] > positions.get(e.from)[axis]))
      }
    }
  }
})
