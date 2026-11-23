import test from 'node:test'
import assert from 'node:assert/strict'
import { scopeGraphByDocument } from './scopeByDocument.js'

test('a document filter keeps shared entities, only its evidence, formats and edges', () => {
  const first = { evidence_ref: 'pdf:42', title: '同名资料' }
  const second = { evidence_ref: 'txt:42', title: '同名资料' }
  const graph = {
    nodes: [
      { id: 'pdf', type: 'document', evidence: [first], fileTypes: ['PDF'] },
      { id: 'txt', type: 'document', evidence: [second], fileTypes: ['TXT'] },
      { id: 'common', type: 'entity', evidence: [first, second], fileTypes: ['PDF', 'TXT'] },
    ],
    edges: [{ from: 'pdf', to: 'common' }, { from: 'txt', to: 'common' }],
  }
  const scoped = scopeGraphByDocument(graph, 'txt')
  assert.deepEqual(scoped.nodes.map(node => node.id), ['txt', 'common'])
  assert.deepEqual(scoped.nodes[1].evidence, [second])
  assert.deepEqual(scoped.nodes[1].fileTypes, ['TXT'])
  assert.deepEqual(scoped.edges, [graph.edges[1]])
  assert.equal(graph.nodes[2].evidence.length, 2)
  assert.equal(scopeGraphByDocument(graph, ''), graph)
  assert.deepEqual(scopeGraphByDocument(graph, 'deleted'), { nodes: [], edges: [] })
})
