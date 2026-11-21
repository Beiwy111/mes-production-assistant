import test from 'node:test'
import assert from 'node:assert/strict'
import {
  createAssistantMessage,
  applyWorkbenchEvent,
  interruptMessage,
  restoreSessions,
  historyKey,
  recoverWorkbenchResult,
  buildNaturalRequest,
  canSubmitNaturalQuestion,
  legacyMessageContext,
  verdictLabel,
  serializeWorkbenchSessions,
} from './agentWorkbench.js'

test('actual tool steps remain live in chat and history; unknown tool IDs are discarded', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'run', runId: 'r1' })
  applyWorkbenchEvent(message, { type: 'agent', runId: 'r1', agentId: 'SUPPLY', status: 'RUNNING',
    steps: [{ id: 'OVERVIEW_INVENTORY', label: '查询库存', status: 'RUNNING', detail: '正在读取授权库存' },
      { id: 'arbitrary', label: '不存在的工具', status: 'COMPLETED' }] })
  assert.equal(message.agents[0].steps.length, 1)
  assert.equal(message.agents[0].steps[0].status, 'RUNNING')
  applyWorkbenchEvent(message, { type: 'agent', runId: 'r1', agentId: 'SUPPLY', status: 'RUNNING',
    steps: [{ id: 'OVERVIEW_INVENTORY', label: '查询库存', status: 'COMPLETED', detail: '已返回20条记录' }] })
  assert.equal(message.agents[0].steps[0].detail, '已返回20条记录')
  assert.equal(message.status, 'RUNNING')
})

test('verified snapshot and text deltas stream before final result, deduplicate and reject stale runs', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'run', runId: 'r1' })
  applyWorkbenchEvent(message, { type: 'snapshot', runId: 'r1', seq: 1, data: { runRef: 'r1', reply: '', presentation: 'compact_stream', facts: { equipment: { overview: { rows: [] } } } } })
  assert.equal(message.status, 'RUNNING')
  assert.ok(message.result.facts.equipment)
  for (const event of [{ seq: 2, text: '已查询' }, { seq: 2, text: '重复' }, { seq: 3, text: '设备。' }]) applyWorkbenchEvent(message, { type: 'delta', runId: 'r1', ...event })
  applyWorkbenchEvent(message, { type: 'delta', runId: 'foreign', seq: 4, text: '错误' })
  assert.equal(message.text, '已查询设备。')
  applyWorkbenchEvent(message, { type: 'result', data: { reply: '最终回答', presentation: 'compact_stream' } })
  applyWorkbenchEvent(message, { type: 'delta', runId: 'r1', seq: 5, text: '迟到' })
  assert.equal(message.text, '最终回答')
})

test('steps are observed only, bounded and invalidated with all citations on DATA_CHANGED', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'agent', agentId: 'QUALITY', status: 'RUNNING',
    steps: [{ id: 'BUSINESS_READ', label: '读取业务', status: 'COMPLETED', at: '2026-11-09' },
      { id: 'ANALYSIS', label: '未执行', status: 'PENDING' }, null] })
  assert.deepEqual(message.agents[0].steps.map((s) => s.id), ['BUSINESS_READ'])
  applyWorkbenchEvent(message, { type: 'result', data: { reply: '数据变化', verdict: 'DATA_CHANGED',
    facts: { quality: { inspectionCount: 1 } }, queryRefs: ['stale'],
    knowledgeEvidence: [{ hits: ['stale'] }], analyses: [{ summary: 'stale' }] } })
  assert.deepEqual(message.agents[0].steps, [])
  for (const key of ['queryRefs', 'knowledgeEvidence', 'analyses']) assert.deepEqual(message.result[key], [])
  assert.deepEqual(message.result.facts, {})
})

test('bounded persistence explicitly drops oversized evidence and never restores misleading totals', () => {
  const message = { ...createAssistantMessage(), status: 'COMPLETED', result: { reply: 'done',
    queryMode: 'NATURAL', facts: { quality: { inspections: [{ notes: 'x'.repeat(2_000_000) }] } },
    knowledgeEvidence: [{ hits: [{ text: 'large' }] }], analyses: [{ summary: 'old' }] } }
  const raw = serializeWorkbenchSessions([{ id: 's1', messages: [message] }])
  assert.ok(raw.length <= 1_800_000)
  const restored = restoreSessions(raw)[0].messages[0]
  assert.equal(restored.result.historyEvidenceOmitted, true)
  assert.deepEqual(restored.result.facts, {})
  assert.deepEqual(restored.result.knowledgeEvidence, [])
  assert.equal(message.result.facts.quality.inspections.length, 1)
})

test('interruption and final result never leave observed steps pretending to be live', () => {
  for (const finish of [
    (message) => interruptMessage(message, 'CANCELLED', 'stopped'),
    (message) => applyWorkbenchEvent(message, { type: 'result', data: { reply: 'done' } }),
  ]) {
    const message = createAssistantMessage()
    applyWorkbenchEvent(message, { type: 'agent', agentId: 'QUALITY', status: 'RUNNING',
      steps: [{ id: 'KNOWLEDGE_RETRIEVAL', label: '检索', status: 'RUNNING' }] })
    finish(message)
    assert.notEqual(message.agents[0].steps[0].status, 'RUNNING')
    assert.notEqual(message.agents[0].steps[0].status, 'COMPLETED')
  }
})

test('legacy DATA_CHANGED history cannot revive stored steps, research or analyses', () => {
  const [session] = restoreSessions(JSON.stringify([{ id: 's', messages: [{ role: 'assistant', status: 'COMPLETED',
    result: { reply: 'changed', verdict: 'DATA_CHANGED', knowledgeEvidence: [{ hits: ['old'] }], analyses: [{ summary: 'old' }], facts: { old: true }, queryRefs: ['old'] },
    agents: [{ agentId: 'QUALITY', status: 'COMPLETED', steps: [{ id: 'ANALYSIS', label: 'old', status: 'COMPLETED' }] }] }] }]))
  assert.deepEqual(session.messages[0].agents[0].steps, [])
  for (const key of ['knowledgeEvidence', 'analyses', 'queryRefs']) assert.deepEqual(session.messages[0].result[key], [])
})

test('natural questions need text and a capable connection, with no order selection', () => {
  const state = { admin: true, connection: { configured: true, naturalLanguage: true }, busy: false }
  for (const text of ['查库存', '看生产进度', '分析质量问题'])
    assert.equal(canSubmitNaturalQuestion({ ...state, text }), true)
  for (const text of ['', '   ', null])
    assert.equal(canSubmitNaturalQuestion({ ...state, text }), false)
  assert.equal(canSubmitNaturalQuestion({ ...state, text: '查库存', busy: true }), false)
  assert.equal(canSubmitNaturalQuestion({ ...state, text: '查库存', admin: false }), false)
  assert.equal(canSubmitNaturalQuestion({ ...state, text: '查库存', connection: { configured: true } }), false)
})

test('continuations use only the latest completed answer in the selected session', () => {
  const completed = (runId) => ({ role: 'assistant', status: 'COMPLETED', runId, result: { reply: '回答', queryMode: 'NATURAL' } })
  const first = { id: 'one', orderPk: '100', profile: 'DELIVERY', messages: [completed('old'), completed('latest'), { ...completed('cancelled'), status: 'CANCELLED' }, { ...completed('running'), status: 'RUNNING' }, { role: 'user', runId: 'user-run', status: 'COMPLETED' }] }
  assert.deepEqual(buildNaturalRequest(first, '  查库存  ', 'request'), {
    text: '查库存', sessionId: 'one', requestId: 'request', previousRunId: 'latest',
  })
  assert.deepEqual(buildNaturalRequest({ id: 'two', messages: [] }, '看生产进度', 'request2'), {
    text: '看生产进度', sessionId: 'two', requestId: 'request2',
  })
})

test('old preview answers cannot continue natural runs or reveal an earlier natural scope', () => {
  const natural = { role: 'assistant', status: 'COMPLETED', runId: 'natural-run', result: { reply: '自然查询', workflowProfile: 'AUTO' } }
  const legacy = { role: 'assistant', status: 'COMPLETED', runId: 'legacy-run', result: { reply: '旧订单分析', workflowProfile: 'DELIVERY' } }
  const noRun = { ...natural, runId: '' }
  for (const messages of [[legacy], [natural, legacy], [natural, noRun]]) {
    const [restored] = restoreSessions(JSON.stringify([{ id: 'old-session', profile: 'DELIVERY', orderPk: '100', messages }]))
    const input = buildNaturalRequest(restored, '查库存', 'next')
    assert.deepEqual(input, { text: '查库存', sessionId: 'old-session', requestId: 'next' })
  }
  assert.equal(buildNaturalRequest({ id: 's', messages: [legacy, natural] }, '继续', 'next').previousRunId, 'natural-run')
})

test('natural verdicts complete the answer and CLARIFY can be continued without inventing agents', () => {
  for (const verdict of ['CLARIFY', 'UNSUPPORTED', 'NOT_FOUND', 'EVIDENCE_READY', 'INSUFFICIENT_EVIDENCE']) {
    const message = createAssistantMessage()
    applyWorkbenchEvent(message, { type: 'run', runId: `run-${verdict}` })
    applyWorkbenchEvent(message, { type: 'result', data: { reply: '请补充订单号', queryMode: 'NATURAL', workflowProfile: 'AUTO', verdict } })
    assert.equal(message.status, 'COMPLETED')
    assert.equal(message.text, '请补充订单号')
    assert.deepEqual(message.agents, [])
    assert.notEqual(verdictLabel(verdict), '本次分析')
    assert.equal(buildNaturalRequest({ id: 's', messages: [message] }, '订单 102', 'next').previousRunId, `run-${verdict}`)
  }
})

test('new AUTO and old order history both survive reload and only old messages show order labels', () => {
  const sessions = restoreSessions(JSON.stringify([
    { id: 'natural', profile: 'AUTO', messages: [{ role: 'user', text: '查库存', profile: 'AUTO' }] },
    { id: 'legacy', profile: 'DELIVERY', orderPk: '100', messages: [{ role: 'user', text: '交付', profile: 'DELIVERY', orderPk: '100' }] },
  ]))
  assert.equal(sessions[0].profile, 'AUTO')
  assert.equal(legacyMessageContext(sessions[0].messages[0]), '')
  assert.equal(sessions[1].profile, 'DELIVERY')
  assert.equal(sessions[1].orderPk, '100')
  assert.equal(legacyMessageContext(sessions[1].messages[0]), '订单交付 · 订单 ID 100')
  assert.equal(legacyMessageContext({}), '')
})

test('shows only observed agents, deduplicates polls, and never regresses completed tasks', () => {
  const message = createAssistantMessage()
  assert.equal(message.agents.length, 0)
  applyWorkbenchEvent(message, {
    type: 'agent',
    agentId: 'ORDER',
    status: 'RUNNING',
    summary: '正在查询',
  })
  applyWorkbenchEvent(message, {
    type: 'agent',
    agentId: 'ORDER',
    status: 'COMPLETED',
    summary: '已读取订单',
    evidence: [{ queryRef: 'e1' }],
  })
  applyWorkbenchEvent(message, {
    type: 'agent',
    agentId: 'ORDER',
    status: 'RUNNING',
  })
  assert.equal(message.agents.length, 1)
  assert.equal(message.agents[0].status, 'COMPLETED')
  assert.equal(message.agents[0].summary, '已读取订单')
  applyWorkbenchEvent(message, {
    type: 'agent',
    agentId: 'UNTRUSTED',
    status: 'RUNNING',
  })
  assert.equal(message.agents.length, 1)
})

test('disconnect does not turn unknown or running tasks into completed work', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, {
    type: 'agent',
    agentId: 'SUP',
    status: 'RUNNING',
  })
  interruptMessage(message, 'INTERRUPTED', '连接中断')
  assert.equal(message.agents[0].status, 'INTERRUPTED')
  assert.equal(message.status, 'INTERRUPTED')
  assert.equal(message.text, '')
})

test('data changes invalidate previously displayed task facts and explanations', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, {
    type: 'agent',
    agentId: 'ORDER',
    status: 'COMPLETED',
    summary: '库存 100',
    evidence: [{ quantity: 100 }],
  })
  applyWorkbenchEvent(message, {
    type: 'result',
    data: {
      reply: '数据变更，需要重新查询',
      verdict: 'DATA_CHANGED',
      facts: null,
    },
  })
  assert.equal(message.agents[0].evidence.length, 0)
  assert.equal(message.agents[0].summary.includes('100'), false)
  assert.equal(message.status, 'COMPLETED')
  assert.equal(message.result.verdict, 'DATA_CHANGED')
})

test('restored history is bounded and marks unfinished runs interrupted without resubmitting', () => {
  const raw = JSON.stringify(
    Array.from({ length: 30 }, (_, i) => ({
      id: String(i),
      title: '历史',
      messages: [{ ...createAssistantMessage(), runId: 'r1' }],
    })),
  )
  const sessions = restoreSessions(raw)
  assert.equal(sessions.length, 20)
  assert.equal(sessions[0].messages[0].status, 'INTERRUPTED')
  assert.deepEqual(restoreSessions('{broken'), [])
  assert.deepEqual(restoreSessions('{}'), [])
  assert.notEqual(historyKey(1), historyKey(2))
  assert.equal(historyKey(null), null)
})

test('a done marker alone cannot claim successful completion', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'done' })
  assert.equal(message.status, 'RUNNING')
  applyWorkbenchEvent(message, {
    type: 'result',
    data: { reply: '证据不足', verdict: 'INSUFFICIENT_EVIDENCE' },
  })
  assert.equal(message.text, '证据不足')
  assert.equal(message.status, 'COMPLETED')
})

test('malformed stored agents cannot crash the workflow view or revive invalidated evidence', () => {
  const saved = [
    {
      id: 's1',
      messages: [
        {
          role: 'assistant',
          status: 'COMPLETED',
          text: '数据已变',
          agents: [
            { agentId: 'ORDER' },
            { agentId: 'SUP', status: 'COMPLETED', evidence: 'bad' },
          ],
          result: { reply: '数据已变', verdict: 'DATA_CHANGED' },
        },
      ],
    },
  ]
  const [session] = restoreSessions(JSON.stringify(saved))
  assert.equal(session.messages[0].agents.length, 1)
  assert.deepEqual(session.messages[0].agents[0].evidence, [])
})

test('authoritative recovery replaces interrupted markers and clears obsolete errors', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, {
    type: 'agent',
    agentId: 'ORDER',
    status: 'RUNNING',
  })
  interruptMessage(message, 'INTERRUPTED', '连接中断')
  recoverWorkbenchResult(message, {
    agents: [{ agentId: 'ORDER', status: 'COMPLETED', summary: '已查询' }],
    result: { reply: '证据不足' },
  })
  assert.equal(message.agents[0].status, 'COMPLETED')
  assert.equal(message.agents[0].summary, '已查询')
  assert.equal(message.error, '')
  assert.equal(message.status, 'COMPLETED')
})

const workflowEvent = (seq, kind = 'AGENT_STARTED', fields = {}) => ({
  seq, kind, agentId: 'QUALITY', status: 'RUNNING', label: '读取', ...fields,
})
const workflowSnapshot = (events, extra = {}) => ({
  type: 'workflow', runId: 'r1', invalidated: false, plan: null, events, ...extra,
})

test('workflow snapshots reject foreign runs and regressions, deduplicate and bound fields', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'run', runId: 'r1' })
  const plan = { action: 'QUERY', requiredAgents: ['QUALITY', 'AFTERSALES', 'SUP', 'QUALITY'],
    dependencies: { AFTERSALES: ['QUALITY', 'ORDER'] } }
  const first = workflowSnapshot([workflowEvent(1, 'AGENT_STARTED', { detail: 'x'.repeat(500) })], { plan })
  applyWorkbenchEvent(message, first)
  assert.deepEqual(message.workflow.plan.requiredAgents, ['QUALITY', 'AFTERSALES'])
  assert.deepEqual(message.workflow.plan.dependencies.AFTERSALES, ['QUALITY'])
  assert.equal(message.workflow.events[0].detail.length, 200)
  const saved = message.workflow
  applyWorkbenchEvent(message, { ...first, events: [workflowEvent(2)], runId: 'foreign' })
  applyWorkbenchEvent(message, first)
  applyWorkbenchEvent(message, workflowSnapshot([]))
  assert.equal(message.workflow, saved)
  applyWorkbenchEvent(message, workflowSnapshot([workflowEvent(2), workflowEvent(1), workflowEvent(2)], { plan }))
  assert.deepEqual(message.workflow.events.map(({ seq }) => seq), [1, 2])
  assert.equal(message.agents.length, 0)
})

test('workflow invalidation clears completed evidence immediately and stays sticky in recovery and history', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'run', runId: 'r1' })
  const stale = workflowSnapshot([workflowEvent(1)])
  const agent = { agentId: 'QUALITY', status: 'COMPLETED', summary: 'stale', evidence: [{ queryRef: 'stale' }] }
  applyWorkbenchEvent(message, stale)
  applyWorkbenchEvent(message, { type: 'agent', ...agent })
  applyWorkbenchEvent(message, workflowSnapshot([], { invalidated: true }))
  assert.deepEqual(message.agents[0].evidence, [])
  assert.doesNotMatch(message.agents[0].summary, /stale/)
  applyWorkbenchEvent(message, stale)
  const [session] = restoreSessions(serializeWorkbenchSessions([{ id: 's', messages: [message] }]))
  const restored = session.messages[0]
  assert.equal(restored.status, 'INTERRUPTED')
  assert.equal(recoverWorkbenchResult(restored, { runId: 'r1', workflow: stale, agents: [agent],
    result: { reply: 'changed', queryRefs: ['stale'] } }), true)
  assert.equal(restored.workflow.invalidated, true)
  assert.deepEqual(restored.workflow.events, [])
  assert.deepEqual(restored.agents[0].evidence, [])
  assert.deepEqual(restored.result.queryRefs, [])
})

test('recovery retains sanitized workflow history for the same run', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'run', runId: 'r1' })
  interruptMessage(message)
  assert.equal(recoverWorkbenchResult(message, { runId: 'foreign', result: { reply: 'foreign' } }), false)
  assert.equal(recoverWorkbenchResult(message, { runId: 'r1', workflow: workflowSnapshot([workflowEvent(1)]),
    result: { reply: 'done' } }), true)
  const [session] = restoreSessions(serializeWorkbenchSessions([{ id: 's', messages: [message] }]))
  assert.equal(session.messages[0].workflow.events[0].seq, 1)
  assert.equal(session.messages[0].status, 'COMPLETED')
})

test('live workflow envelopes require the current run ID while recovery accepts a raw observation', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'run', runId: 'r1' })
  const { runId, ...observation } = workflowSnapshot([workflowEvent(1)])
  applyWorkbenchEvent(message, observation)
  assert.equal(message.workflow, undefined)
  applyWorkbenchEvent(message, { ...observation, invalidated: true })
  assert.equal(message.workflow, undefined)
  assert.equal(recoverWorkbenchResult(message, { runId, workflow: observation, result: { reply: 'done' } }), true)
  assert.equal(message.workflow.events.length, 1)
})

test('legacy invalidation stays sticky without creating a workflow panel', () => {
  const message = createAssistantMessage()
  const agent = { type: 'agent', agentId: 'QUALITY', status: 'COMPLETED', evidence: [{ queryRef: 'stale' }] }
  applyWorkbenchEvent(message, agent)
  applyWorkbenchEvent(message, { type: 'result', data: { reply: 'changed', verdict: 'DATA_CHANGED' } })
  assert.equal(message.workflow, undefined)
  const [session] = restoreSessions(serializeWorkbenchSessions([{ id: 's', messages: [message] }]))
  const restored = session.messages[0]
  assert.equal(restored.workflow, undefined)
  assert.equal(recoverWorkbenchResult(restored, { agents: [agent], result: { reply: 'old' } }), true)
  assert.equal(restored.workflow, undefined)
  assert.deepEqual(restored.agents[0].evidence, [])
})

test('confirmed retries replace terminal agent details and reject older task snapshots', () => {
  const message = createAssistantMessage()
  applyWorkbenchEvent(message, { type: 'run', runId: 'r1' })
  const first = workflowEvent(1, 'AGENT_STARTED', { taskId: 'first' })
  applyWorkbenchEvent(message, workflowSnapshot([first]))
  const agent = { type: 'agent', agentId: 'QUALITY', taskId: 'first', status: 'FAILED', summary: 'old' }
  applyWorkbenchEvent(message, agent)
  applyWorkbenchEvent(message, workflowSnapshot([first, workflowEvent(2, 'AGENT_STARTED', { taskId: 'second' })]))
  applyWorkbenchEvent(message, { ...agent, taskId: 'second', status: 'COMPLETED', summary: 'new' })
  applyWorkbenchEvent(message, agent)
  assert.equal(message.agents.length, 1)
  assert.equal(message.agents[0].taskId, 'second')
  assert.equal(message.agents[0].summary, 'new')
})
