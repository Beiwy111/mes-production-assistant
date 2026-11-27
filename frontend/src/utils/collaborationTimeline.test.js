import test from 'node:test'
import assert from 'node:assert/strict'
import { collaborationTimeline } from './collaborationTimeline.js'

test('parallel calls retain arrival order, inputs and results without invented roles', () => {
  const message = { status: 'RUNNING', agents: [], workflow: { events: [
    { seq: 1, agentId: 'SUP', kind: 'PLAN_CONFIRMED', status: 'COMPLETED', label: '任务规划', detail: '并行核对订单与设备' },
    { seq: 2, agentId: 'ORDER', taskId: 'a', toolCallId: 'a', kind: 'STEP_STARTED', status: 'RUNNING', label: '核对订单', detail: '正在查询 CO123' },
    { seq: 3, agentId: 'EQUIPMENT', taskId: 'b', toolCallId: 'b', kind: 'STEP_STARTED', status: 'RUNNING', label: '核对设备' },
    { seq: 4, agentId: 'ORDER', taskId: 'a', toolCallId: 'a', kind: 'STEP_COMPLETED', status: 'COMPLETED', label: '核对订单', detail: '共 100 台' },
  ] } }
  const model = collaborationTimeline(message)
  assert.deepEqual(model.rows.map(r => r.agentId), ['SUP', 'ORDER', 'EQUIPMENT'])
  assert.equal(model.rows[1].request, '正在查询 CO123')
  assert.equal(model.rows[1].result, '共 100 台')
  assert.equal(model.rows[2].status, 'RUNNING')
  assert.equal(model.live, true)
})
test('retry and handoff are separate observed records; cancellation never invents completion', () => {
  const message = { status: 'CANCELLED', agents: [], workflow: { events: [
    { seq: 1, agentId: 'PRODUCTION', toolCallId: 'old', kind: 'STEP_COMPLETED', status: 'FAILED', detail: '查询失败' },
    { seq: 2, agentId: 'PRODUCTION', toolCallId: 'new', kind: 'STEP_STARTED', status: 'RUNNING', detail: '重新核对' },
    { seq: 3, agentId: 'SUP', kind: 'HANDOFF', fromAgent: 'ORDER', toAgent: 'SUP', status: 'COMPLETED', detail: '订单结果已返回' },
  ] } }
  const model = collaborationTimeline(message)
  assert.equal(model.rows.length, 3)
  assert.deepEqual(model.rows.map(r => r.status), ['FAILED', 'CANCELLED', 'COMPLETED'])
  assert.match(model.rows[2].label, /订单交付.*总协调/)
})
test('general answers and invalidated evidence never display business execution', () => {
  assert.deepEqual(collaborationTimeline({ directChat: true, status: 'RUNNING' }).rows, [])
  assert.deepEqual(collaborationTimeline({ evidenceInvalidated: true, agents: [{ agentId: 'ORDER', steps: [{id:'a',status:'COMPLETED'}] }] }).rows, [])
})
test('final answer generation updates a single summary row without leaving a fake running node', () => {
  const message = { status:'RUNNING', agents:[], workflow:{events:[
    {seq:1,agentId:'SUP',kind:'SUMMARIZING',status:'RUNNING',label:'汇总结果',detail:'依据查询生成回答'},
    {seq:2,agentId:'SUP',kind:'SUMMARIZING',status:'COMPLETED',label:'汇总结果',detail:'回答已生成'},
  ]} }
  assert.equal(collaborationTimeline(message).rows.length,1)
  assert.equal(collaborationTimeline(message).rows[0].status,'COMPLETED')
})
test('saved legacy receipts remain accessible without inventing missing tools',()=>{
  const model=collaborationTimeline({status:'COMPLETED',agents:[{agentId:'QUALITY',taskId:'old',status:'COMPLETED',summary:'保留的质检摘要'}]})
  assert.equal(model.rows.length,1)
  assert.equal(model.rows[0].result,'保留的质检摘要')
  assert.equal(model.rows[0].toolId,undefined)
})
