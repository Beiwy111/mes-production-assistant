import test from 'node:test'
import assert from 'node:assert/strict'
import { inspectAgentMessage } from './agentInspector.js'

test('live Supervisor shows its latest real coordination phase before any text arrives',()=>{
 const message={status:'RUNNING',agents:[{agentId:'SUP',status:'RUNNING'}],workflow:{events:[
  {seq:1,agentId:'SUP',kind:'PLAN_CONFIRMED',status:'COMPLETED',label:'确认执行计划',detail:'已确认需要物料Agent'},
  {seq:2,agentId:'SUP',kind:'SUMMARIZING',status:'RUNNING',label:'汇总业务证据',detail:'正在复核并汇总本次结果'},
 ]}}
 const supervisor=inspectAgentMessage(message).roles[0]
 assert.equal(supervisor.lastStep.label,'汇总业务证据')
 assert.equal(supervisor.lastStep.detail,'正在复核并汇总本次结果')
 assert.equal(supervisor.status,'RUNNING')
})

test('interleaved agents and simultaneous identically labelled tools retain separate call states', () => {
  const events = [
    {seq:1,agentId:'QUALITY',kind:'AGENT_STARTED',status:'RUNNING',taskId:'quality'},
    {seq:2,agentId:'EQUIPMENT',kind:'AGENT_STARTED',status:'RUNNING',taskId:'equipment'},
    {seq:3,agentId:'QUALITY',kind:'STEP_STARTED',status:'RUNNING',taskId:'quality',toolCallId:'call-a',toolId:'Q1',label:'读取数据'},
    {seq:4,agentId:'QUALITY',kind:'STEP_STARTED',status:'RUNNING',taskId:'quality',toolCallId:'call-b',toolId:'Q1',label:'读取数据'},
    {seq:5,agentId:'EQUIPMENT',kind:'STEP_STARTED',status:'RUNNING',taskId:'equipment',toolCallId:'call-c',toolId:'D1',label:'读取数据'},
    {seq:6,agentId:'QUALITY',kind:'STEP_COMPLETED',status:'FAILED',taskId:'quality',toolCallId:'call-a',toolId:'Q1',label:'读取数据'},
  ]
  const model=inspectAgentMessage({status:'RUNNING',workflow:{events}})
  assert.deepEqual(model.roles.find(r=>r.agentId==='QUALITY').steps.map(s=>[s.id,s.status]),[['call-a','FAILED'],['call-b','RUNNING']])
  assert.equal(model.roles.find(r=>r.agentId==='EQUIPMENT').steps[0].status,'RUNNING')
})

test('roster names seven real agents and only confirmed live planned roles wait', () => {
  const empty = inspectAgentMessage(null)
  assert.deepEqual(empty.roles.map((role) => role.agentId), ['SUP', 'ORDER', 'SUPPLY', 'PRODUCTION', 'QUALITY', 'EQUIPMENT', 'AFTERSALES'])
  assert.ok(empty.roles.every((role) => role.status === 'UNCALLED'))
  const message = { status: 'RUNNING', workflow: { plan: { requiredAgents: ['QUALITY'] }, events: [] } }
  assert.equal(inspectAgentMessage(message).roles.find((role) => role.agentId === 'QUALITY').status, 'WAITING')
  message.status = 'INTERRUPTED'
  assert.equal(inspectAgentMessage(message).roles.find((role) => role.agentId === 'QUALITY').status, 'UNCALLED')
})

test('retry excludes stale task evidence while preserving actual failure log', () => {
  const model = inspectAgentMessage({ status: 'RUNNING', agents: [{ agentId: 'QUALITY', taskId: 'old',
    status: 'FAILED', summary: 'stale summary', evidence: [{ queryRef: 'stale' }] }], workflow: { events: [
    { seq: 1, kind: 'AGENT_COMPLETED', agentId: 'QUALITY', taskId: 'old', status: 'FAILED' },
    { seq: 2, kind: 'AGENT_STARTED', agentId: 'QUALITY', taskId: 'new', status: 'RUNNING' },
    { seq: 3, kind: 'STEP_STARTED', agentId: 'QUALITY', taskId: 'new', label: '重新查询', status: 'RUNNING' },
  ] } })
  const quality = model.roles.find((role) => role.agentId === 'QUALITY')
  assert.equal(quality.status, 'RUNNING')
  assert.equal(quality.summary, '')
  assert.deepEqual(quality.evidence, [])
  assert.equal(quality.lastStep.label, '重新查询')
  assert.equal(quality.logs[0].status, 'FAILED')
})

test('terminal response never turns observed running agent or step into completed', () => {
  const model = inspectAgentMessage({ status: 'COMPLETED', agents: [{ agentId: 'QUALITY', status: 'RUNNING',
    steps: [{ id: 'KNOWLEDGE_RETRIEVAL', status: 'RUNNING', label: '公开检索' }] }],
  workflow: { events: [{ agentId: 'SUP', kind: 'SUMMARIZING', status: 'RUNNING' }] } })
  assert.equal(model.roles[0].status, 'RECORDED')
  const quality = model.roles.find((role) => role.agentId === 'QUALITY')
  assert.equal(quality.status, 'INTERRUPTED')
  assert.equal(quality.steps[0].status, 'INTERRUPTED')
})

test('current streamed steps remain visible beside stored steps and completion replaces its start', () => {
  const message = { status: 'RUNNING', agents: [{ agentId: 'SUPPLY', taskId: 't1', status: 'RUNNING',
    steps: [{ id: 'BUSINESS_READ', label: '读取业务证据', status: 'COMPLETED', detail: '已读取授权数据' }] }], workflow: { events: [
      { seq: 1, agentId: 'SUPPLY', taskId: 't1', kind: 'AGENT_STARTED', status: 'RUNNING' },
      { seq: 2, agentId: 'SUPPLY', taskId: 't1', kind: 'STEP_STARTED', label: '形成分析', status: 'RUNNING', detail: '正在结合业务证据' },
    ] } }
  const supply = () => inspectAgentMessage(message).roles.find((role) => role.agentId === 'SUPPLY')
  assert.equal(supply().steps.length, 2)
  assert.equal(supply().steps[1].status, 'RUNNING')
  message.workflow.events.push({ seq: 3, agentId: 'SUPPLY', taskId: 't1', kind: 'STEP_COMPLETED', label: '形成分析', status: 'COMPLETED', detail: '已校验引用' })
  assert.equal(supply().steps.length, 2)
  assert.equal(supply().steps[1].status, 'COMPLETED')
  assert.equal(supply().steps[1].detail, '已校验引用')
  assert.equal(supply().logs.length, 3, 'original event history remains intact')
})

test('RAG and GraphRAG observations belong to their actual business agent', () => {
  const model = inspectAgentMessage({ status: 'COMPLETED', agents: [{ agentId: 'QUALITY', status: 'COMPLETED',
    evidence: [{ queryRef: 'q1', status: 'PARTIAL', data: { private: 'never display' } }] }], result: {
    knowledgeEvidence: [{ agentId: 'QUALITY', status: 'MATCHES', graph_status: 'UNAVAILABLE_OR_STALE',
      mode_used: 'vector', hits: [{ text: 'private unused text' }] }, { agentId: 'RAG', status: 'MATCHES', hits: [] }] } })
  const quality = model.roles.find((role) => role.agentId === 'QUALITY')
  assert.deepEqual(quality.knowledge, [{ status: 'MATCHES', graphStatus: 'UNAVAILABLE_OR_STALE', mode: 'vector', hitCount: 1 }])
  assert.equal(quality.evidence[0].reference, 'q1')
  assert.equal(model.roles.length, 7)
  assert.ok(model.roles.filter((role) => role.agentId !== 'QUALITY').every((role) => !role.knowledge.length))
  assert.doesNotMatch(JSON.stringify(model), /private/)
})

test('invalidation and omitted history suppress old steps, evidence, summaries and logs', () => {
  for (const guard of [{ evidenceInvalidated: true }, { result: { historyEvidenceOmitted: true } }]) {
    const model = inspectAgentMessage({ status: 'COMPLETED', agents: [{ agentId: 'QUALITY', status: 'COMPLETED',
      summary: 'obsolete', steps: [{ id: 'ANALYSIS', status: 'COMPLETED', label: 'obsolete' }], evidence: [{ queryRef: 'obsolete' }] }],
    workflow: { events: [{ agentId: 'QUALITY', detail: 'obsolete' }] }, ...guard })
    assert.equal(model.events.length, 0)
    assert.doesNotMatch(JSON.stringify(model), /obsolete/)
    assert.ok(model.notice)
  }
})

test('inspector never reconstructs request bodies or copies arbitrary raw fields', () => {
  const model = inspectAgentMessage({ status: 'COMPLETED', text: 'customer private',
    agents: [{ agentId: 'ORDER', status: 'COMPLETED', request: { token: 'secret' },
      evidence: [{ queryRef: 'q1', request: { customerName: 'private' }, data: { password: 'secret' } }] }] })
  assert.doesNotMatch(JSON.stringify(model), /secret|private|customerName|password|"request":/)
})

test('query preview uses actual counts and allowlisted result fields, preserving zero and partial flags', () => {
  const model = inspectAgentMessage({ status: 'RUNNING', agents: [{ agentId: 'SUPPLY', status: 'RUNNING', evidence: [{
    kind: 'OVERVIEW_INVENTORY', status: 'PARTIAL', queryRef: 'q-stock', data: { overview: {
      keyword: 'PCB', period: 'CURRENT', totalRecords: 10, returnedRecords: 1, truncated: true,
      rows: [{ material_name: 'PCB板', quantity_available: '0.0000', unit: '块', password: 'secret', receiverPhone: 'private' }],
    } },
  }] }] })
  const preview = model.roles.find((role) => role.agentId === 'SUPPLY').evidence[0]
  assert.equal(preview.label, '库存查询')
  assert.equal(preview.keyword, 'PCB')
  assert.match(preview.result, /返回 1 条/)
  assert.equal(preview.partial, true)
  assert.match(preview.preview[0], /PCB板.*0.0000.*块/)
  assert.doesNotMatch(JSON.stringify(preview), /secret|private|password|receiverPhone/)
})

test('missing counts and tool names remain absent rather than fabricated from evidence presence', () => {
  const model = inspectAgentMessage({ status: 'RUNNING', agents: [{ agentId: 'QUALITY', status: 'RUNNING',
    evidence: [{ kind: 'UNKNOWN_NEW_KIND', queryRef: 'q-unknown', data: { privateRows: ['secret'] } }] }] })
  const preview = model.roles.find((role) => role.agentId === 'QUALITY').evidence[0]
  assert.equal(preview.result, '')
  assert.deepEqual(preview.preview, [])
  assert.doesNotMatch(JSON.stringify(preview), /0 条|SQL|secret/)
})

test('natural order steps are attributed to their real roles while service is a separate log', () => {
  const model = inspectAgentMessage({ status: 'COMPLETED', orderFlow: { steps: [
    { agentId: 'SUP', label: '识别下单需求', detail: '完成意图判断', status: 'COMPLETED' },
    { agentId: 'ORDER', label: '整理订单需求', detail: '整理本轮信息', status: 'COMPLETED' },
    { agentId: 'SERVICE', label: '校验并保存草稿', detail: '尚未创建订单', status: 'COMPLETED' },
  ], orderDraft: { status: 'READY', fields: { receiverPhone: 'private' } } } })
  assert.deepEqual(model.roles.slice(0, 2).map((role) => role.status), ['RECORDED', 'RECORDED'])
  assert.equal(model.roles[1].steps[0].detail, '整理本轮信息')
  assert.equal(model.roles[1].steps[0].status, 'COMPLETED')
  assert.ok(model.roles.slice(2).every((role) => role.status === 'UNCALLED'))
  assert.equal(model.roles.length, 7)
  assert.equal(model.events.length, 3)
  assert.equal(model.serviceLogs[0].label, '校验并保存草稿')
  assert.equal(model.draftStatus, '待确认')
  assert.equal(model.requestStatus, '已收到响应')
  assert.doesNotMatch(JSON.stringify(model), /private|receiverPhone/)
})

test('pending order service activity never invents a new agent call or completion', () => {
  const message = { status: 'COMPLETED', orderFlow: { pending: '正在提交订单审核', steps: [
    { agentId: 'SERVICE', label: '读取草稿', status: 'COMPLETED' },
  ], orderDraft: { status: 'READY' } } }
  const model = inspectAgentMessage(message)
  assert.equal(model.requestStatus, '处理中')
  assert.equal(model.serviceActivity, '正在提交订单审核')
  assert.equal(model.events.length, 1)
  assert.ok(model.roles.every((role) => role.status === 'UNCALLED'))
  message.orderFlow.needsRecovery = true
  const recovery = inspectAgentMessage(message)
  assert.equal(recovery.serviceActivity, '')
  assert.equal(recovery.draftStatus, '待恢复核对')
  assert.equal(recovery.requestStatus, '状态待确认')
})

test('coordination-only logs establish a record without asserting a terminal agent state', () => {
  for (const status of ['COMPLETED', 'INTERRUPTED', 'FAILED']) {
    const model = inspectAgentMessage({ status, workflow: { events: [
      { agentId: 'SUP', kind: 'PLAN_CONFIRMED', status: 'COMPLETED' },
    ] } })
    assert.equal(model.roles[0].status, 'RECORDED')
  }
})

test('resource tools retain real steps and only project safe computed evidence',()=>{
 const model=inspectAgentMessage({status:'COMPLETED',agents:[{agentId:'SUPPLY',status:'COMPLETED',steps:[{id:'RESOURCE_MATERIAL',label:'核对物料',status:'COMPLETED'}],evidence:[{kind:'RESOURCE_MATERIAL',status:'PARTIAL',data:{rows:[{order_no:'SO1',material_name:'PCB',scenario_gap_qty:'40',unit:'块',secret:'DO_NOT_RENDER'}],coverage:[{}],scope:'当前授权范围',sourceEvidence:{password:'DO_NOT_RENDER'}}}]}]})
 const role=model.roles.find(r=>r.agentId==='SUPPLY')
 assert.equal(role.steps.length,1);assert.match(role.evidence[0].preview[0],/SO1.*PCB.*40/)
 assert.doesNotMatch(JSON.stringify(role.evidence),/DO_NOT_RENDER/)
})
