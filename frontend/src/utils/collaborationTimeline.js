import { inspectAgentMessage } from './agentInspector.js'
import { AGENTS } from './agentWorkbench.js'

// Both views project the same normalized event store; this function never schedules work.
export function collaborationTimeline(message) {
  const model = inspectAgentMessage(message)
  if (message?.directChat || model.invalidated || model.omitted) return { ...model, rows: [] }
  const rows = new Map()
  let summaryKey = ''
  const state = status => status === 'RUNNING' && !model.live
    ? message?.status === 'CANCELLED' ? 'CANCELLED' : 'INTERRUPTED' : status
  const name = id => AGENTS[id]?.name.replace(' Agent', '') || '业务服务'
  for (const event of model.events) {
    const step = ['STEP_STARTED', 'STEP_COMPLETED', 'ORDER_STEP', 'PRODUCTION_STEP'].includes(event.kind)
    if (!step && !['PLAN_CONFIRMED', 'HANDOFF', 'SUMMARIZING', 'CANCELLED'].includes(event.kind)) continue
    // A service request wraps these actual server calls; do not duplicate its start/finish.
    if (event.kind === 'PRODUCTION_STEP' && event.toolId === 'production.preview' && message.workflow?.events?.length) continue
    if (event.kind === 'PLAN_CONFIRMED') {
      const planning = [...rows.values()].findLast(row => row.toolId === 'TASK_TASK_PLAN')
      if (planning && planning.status === 'COMPLETED') { planning.result = event.detail || planning.result; continue }
    }
    if (event.kind === 'SUMMARIZING' && (event.status === 'RUNNING' || !summaryKey)) summaryKey = `summary:${event.seq}`
    const key = event.kind === 'SUMMARIZING' ? summaryKey : step ? `${event.agentId}:${event.toolCallId || event.id || `${event.taskId || ''}:${event.toolId || event.label}`}` : `event:${event.seq}`
    const old = rows.get(key)
    if (old && old.status !== 'RUNNING' && event.kind === 'STEP_STARTED') continue
    const status = state(event.status)
    rows.set(key, { id: key, agentId: event.agentId, name: name(event.agentId),
      label: event.kind === 'HANDOFF' ? `${name(event.fromAgent)} → ${name(event.toAgent)}` : event.label || '执行记录',
      kind: event.kind, toolId: event.toolId || '', status,
      request: old?.request || (event.kind === 'STEP_STARTED' ? event.detail || '' : ''),
      result: event.kind === 'STEP_STARTED' ? '' : event.detail || '',
      at: event.at, startedAt: old?.startedAt || event.startedAt || event.at })
  }
  for (const role of model.roles) {
    // Legacy agent snapshots are real observations too, but never expand a planned roster into executions.
    if ([...rows.values()].some(r => r.agentId === role.agentId)) continue
    for (const step of role.steps) {
      const id = `snapshot:${role.agentId}:${step.id}`
      rows.set(id, { id, agentId: role.agentId, name: name(role.agentId), label: step.label || '执行记录',
        status: state(step.status), toolId: step.toolId || step.id, request: step.status === 'RUNNING' ? step.detail : '',
        result: step.status !== 'RUNNING' ? step.detail : '', at: step.at })
    }
    const recorded = (message?.agents || []).find(agent => agent.agentId === role.agentId && agent.taskId === role.taskId)
    if (!role.steps.length && recorded && ['RUNNING','COMPLETED','FAILED','CANCELLED','INTERRUPTED'].includes(role.status)) {
      const id = `receipt:${role.agentId}:${role.taskId}`
      rows.set(id, { id, agentId:role.agentId, name:name(role.agentId), label:'角色任务记录', status:role.status,
        request:'', result:role.summary || '该记录没有保存工具明细。' })
    }
  }
  // Lifecycle starts can arrive before the first tool event. Show that observed dispatch only.
  for (const role of model.roles.filter(r => r.status === 'RUNNING' && r.logs.some(e => e.kind === 'AGENT_STARTED'))) {
    if ([...rows.values()].some(r => r.agentId === role.agentId && r.status === 'RUNNING')) continue
    const start = role.logs.findLast(e => e.kind === 'AGENT_STARTED')
    const hasStep = role.logs.some(e => e.taskId === start.taskId && ['STEP_STARTED','STEP_COMPLETED'].includes(e.kind))
    if (!hasStep) rows.set(`dispatch:${role.agentId}:${start.taskId}`, { id: `dispatch:${role.agentId}:${start.taskId}`,
      agentId: role.agentId, name: name(role.agentId), label: start.label || '已接收任务', request: start.detail, result: '', status: 'RUNNING' })
  }
  return { ...model, rows: [...rows.values()] }
}
