import assert from 'node:assert/strict'
import test from 'node:test'
import { nodeDiameter, spatialLayout } from './graphLayout.js'

test('a document with many chunks gets repeatable, separated positions across the viewport', () => {
  const nodes = Array.from({ length: 24 }, (_, i) => ({ id: String(i) }))
  const edges = nodes.slice(1).map(node => ({ from: '0', to: node.id }))
  const points = spatialLayout(nodes, edges, 620, 430)
  assert.deepEqual(points, spatialLayout(nodes, edges, 620, 430))
  assert.deepEqual(new Set(points.keys()), new Set(nodes.map(n => n.id)))
  const coords = [...points.values()]
  const distances = coords.flatMap(([x, y], i) =>
    coords.slice(i + 1).map(([nx, ny]) => Math.hypot(x - nx, y - ny)))
  assert.ok(Math.min(...distances) > 52)
  assert.ok(coords.every(([x, y]) => x >= 0 && x <= 620 && y >= 0 && y <= 430))
})

test('disconnected nodes, cycles, self-links and dangling links retain one position per real node', () => {
  const nodes = ['a', 'b', 'c', 'd', 'e', 'f'].map(id => ({ id }))
  const edges = [['a', 'b'], ['b', 'c'], ['c', 'a'], ['d', 'e'], ['f', 'f'], ['f', 'absent']]
    .map(([from, to]) => ({ from, to }))
  const points = spatialLayout(nodes, edges, 500, 320)
  assert.equal(points.size, 6)
  assert.equal(new Set([...points.values()].map(p => p.join(','))).size, 6)
  assert.deepEqual([...spatialLayout([{ id: 'alone' }], [], 500, 320).values()], [[250, 160]])
  assert.equal(spatialLayout([], [], 500, 320).size, 0)
})

test('node size reflects semantic type, connection count and selection', () => {
  assert.ok(nodeDiameter('document', 1) > nodeDiameter('chunk', 1))
  assert.ok(nodeDiameter('entity', 6) > nodeDiameter('entity', 1))
  assert.ok(nodeDiameter('entity', 6, true) > nodeDiameter('entity', 6))
  assert.ok(nodeDiameter('document', 300) <= 52)
})

test('large PDF overview preserves every node with bounded layout work',()=>{
  const nodes=Array.from({length:2400},(_,i)=>({id:String(i)}))
  const edges=nodes.slice(1).map(n=>({from:'0',to:n.id}))
  const positions=spatialLayout(nodes,edges,1200,800)
  assert.equal(positions.size,2400)
  assert.ok([...positions.values()].every(([x,y])=>Number.isFinite(x)&&Number.isFinite(y)))
})
