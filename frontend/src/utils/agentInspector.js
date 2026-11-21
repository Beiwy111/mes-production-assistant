import { AGENTS, observedSteps } from './agentWorkbench.js'

export const agentStateText = (state) => ({ RUNNING: '执行中', COMPLETED: '已完成',
  FAILED: '未完成', CANCELLED: '已取消', INTERRUPTED: '状态待确认', WAITING: '待调用',
  UNCALLED: '未调用', SKIPPED: '未执行', INVALIDATED: '证据已失效', UNKNOWN: '未记录',
  RECORDED: '已记录', PENDING: '待处理' })[state] || '待确认'

const text = (value, limit = 1200) => typeof value === 'string' ? value.slice(0, limit) : ''
const isAgent = (id) => Object.hasOwn(AGENTS, id || '')
const scalar = (value) => ['string', 'number'].includes(typeof value) ? String(value).slice(0, 160) : ''
const count = (value) => ['string', 'number'].includes(typeof value) && /^\d+$/.test(String(value)) ? String(value) : ''
function queryPreview(item) {
  if (['RESOURCE_MATERIAL', 'RESOURCE_CAPACITY'].includes(item.kind)) {
    const rows = Array.isArray(item.data?.rows) ? item.data.rows : []
    return { label: item.kind === 'RESOURCE_MATERIAL' ? '共享物料核对' : '跨订单排程核对', kind: item.kind, keyword: '',
      result: `返回 ${rows.length} 条分析记录；${Array.isArray(item.data?.coverage) ? item.data.coverage.length : 0} 条依据待核实`,
      preview: rows.slice(0, 3).map(row => item.kind === 'RESOURCE_MATERIAL'
        ? `${scalar(row.order_no)} · ${scalar(row.material_name)} · 情景缺口 ${row.scenario_gap_qty == null ? '未知' : scalar(row.scenario_gap_qty)} ${scalar(row.unit)}`
        : `${scalar(row.left_order_no)} / ${scalar(row.right_order_no)} · ${scalar(row.resource_type)} ${scalar(row.resource_id)} · 重叠 ${scalar(row.overlap_minutes)} 分钟`),
      partial: true, partialNote: text(item.data?.scope) }
  }
  const overview = item.data?.overview
  const kind = text(item.kind, 60)
  const label = ({ OVERVIEW_INVENTORY: '库存查询', OVERVIEW_PRODUCTION: '生产工单查询', OVERVIEW_QUALITY: '检验记录查询',
    OVERVIEW_MAINTENANCE:'维修记录查询', OVERVIEW_ALARMS:'报警记录查询', OVERVIEW_STOCK_MOVEMENT:'库存流水查询', OVERVIEW_ORDER: '订单查询', OVERVIEW_EQUIPMENT: '设备查询', OVERVIEW_AFTERSALES: '售后查询',
    E1: '订单与计划', E2: '物料供需', E3: '生产进度', E4: '交付与库存', Q1: '检验与缺陷', D1: '设备记录', A1: '售后追溯' })[kind] || '数据查询'
  const total = count(overview?.totalRecords), returned = count(overview?.returnedRecords)
  const rows = Array.isArray(overview?.rows) ? overview.rows : []
  // Only the documented, non-personal business columns are projected. Never serialize arbitrary data/request objects.
  const preview = rows.slice(0, 3).filter((row) => row && typeof row === 'object').map((row) => {
    const name = [row.work_order_no, row.material_name ?? row.material_code, row.equipment_code].map(scalar).filter(Boolean).join(' · ')
    const values = [['quantity_available', '可用'], ['planned_quantity', '计划'], ['qualified_quantity', '合格']]
      .filter(([key]) => row[key] != null && scalar(row[key]) !== '')
      .map(([key, caption]) => `${caption} ${scalar(row[key])}${scalar(row.unit) ? ` ${scalar(row.unit)}` : ''}`)
    return [name, ...values, scalar(row.status)].filter(Boolean).join(' · ')
  }).filter(Boolean)
  return { label, kind, keyword: text(overview?.keyword, 160),
    result: [total !== '' ? `匹配 ${total} 条` : '', returned !== '' ? `返回 ${returned} 条` : ''].filter(Boolean).join('，'),
    preview, partial: item.status === 'PARTIAL' || overview?.truncated === true ||
      (total !== '' && returned !== '' && total !== returned),
    partialNote: overview?.truncated === true || (total !== '' && returned !== '' && total !== returned)
      ? '本次返回部分记录。' : item.status === 'PARTIAL' ? '服务端标记证据不完整，请结合缺失项核对。' : '',
  }
}

/** Project existing observations only; the roster is capability metadata, not a dispatch. */
export function inspectAgentMessage(message) {
  const invalidated = Boolean(message?.evidenceInvalidated || message?.workflow?.invalidated || message?.result?.verdict === 'DATA_CHANGED')
  const omitted = message?.result?.historyEvidenceOmitted === true
  const available = !invalidated && !omitted
  const workflowEvents = available && Array.isArray(message?.workflow?.events)
    ? message.workflow.events.filter((event) => isAgent(event.agentId)) : []
  const orderFlow = message?.orderFlow
  const orderSteps = available && Array.isArray(orderFlow?.steps)
    ? orderFlow.steps.filter((step) => ['SUP', 'ORDER', 'SERVICE'].includes(step?.agentId))
      .map((step, index) => ({ seq: `order-${index}`, id: `order-${index}`, kind: 'ORDER_STEP',
        agentId: step.agentId, label: text(step.label, 120), detail: text(step.detail), at: text(step.at, 40),
        status: ['SUCCESS', 'SUCCEEDED'].includes(step.status) ? 'COMPLETED'
          : ['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'SKIPPED', 'WAITING', 'PENDING'].includes(step.status) ? step.status : 'UNKNOWN',
      })) : []
  const events = [...workflowEvents, ...orderSteps]
  const serviceActivity = available && !orderFlow?.needsRecovery ? text(orderFlow?.pending, 200) : ''
  const cancelled = events.some((event) => event.agentId === 'SUP' && event.kind === 'CANCELLED')
  const live = message?.status === 'RUNNING' && !cancelled && !invalidated && !omitted
  const statusOf = (status) => status === 'RUNNING' && !live
    ? cancelled || message?.status === 'CANCELLED' ? 'CANCELLED' : 'INTERRUPTED' : status
  const observed = new Map((message?.agents || []).filter((agent) => isAgent(agent.agentId)).map((agent) => [agent.agentId, agent]))
  const required = available ? message?.workflow?.plan?.requiredAgents || [] : []
  const calls = new Map()
  for (const event of events) {
    if (!['AGENT_STARTED', 'AGENT_COMPLETED'].includes(event.kind)) continue
    const previous = calls.get(event.agentId)
    const newAttempt = previous?.taskId && event.taskId && previous.taskId !== event.taskId
    if (newAttempt ? event.kind !== 'AGENT_STARTED' : previous?.kind === 'AGENT_COMPLETED') continue
    calls.set(event.agentId, event)
  }
  const roles = Object.entries(AGENTS).map(([agentId, metadata]) => {
    const call = calls.get(agentId)
    const stored = observed.get(agentId)
    const agent = call?.taskId && stored?.taskId && call.taskId !== stored.taskId ? undefined : stored
    const logs = events.filter((event) => event.agentId === agentId)
    const currentLogs = logs.filter((event) => !call?.taskId || event.taskId === call.taskId)
    let status = call?.kind === 'AGENT_COMPLETED' ? call.status : agent?.status || call?.status
    // Coordination events establish activity but never establish successful completion.
    const roleOrderSteps = orderSteps.filter((step) => step.agentId === agentId)
    if (!status && roleOrderSteps.length) {
      const lastStatus = roleOrderSteps.at(-1).status
      // These are step observations, not a complete agent lifecycle receipt.
      status = lastStatus === 'COMPLETED' ? 'RECORDED' : lastStatus
    }
    if (!status && agentId === 'SUP' && logs.length) status = cancelled ? 'CANCELLED' : live ? 'RUNNING' : 'RECORDED'
    status ||= live && required.includes(agentId) ? 'WAITING' : 'UNCALLED'
    if (invalidated) status = stored ? 'INVALIDATED' : 'UNKNOWN'
    const recordedSteps = observedSteps(agent?.steps)
    const streamedSteps = new Map()
    for (const event of currentLogs.filter((item) => ['STEP_STARTED', 'STEP_COMPLETED'].includes(item.kind))) {
      const key = event.toolCallId || event.label || `event-${event.seq}`
      streamedSteps.set(key, { id: key, toolId: event.toolId, label: event.label, detail: event.detail, status: event.status, at: event.at })
    }
    const combinedSteps = recordedSteps.map((step) => {
      const entry = [...streamedSteps.entries()].find(([, item]) => item.toolId === step.id || (!item.toolId && item.label === step.label))
      const streamed = entry?.[1]
      if (entry) streamedSteps.delete(entry[0])
      // A completed snapshot cannot be regressed by a delayed start event.
      return streamed && step.status === 'RUNNING' && streamed.status !== 'RUNNING'
        ? { ...streamed, id: step.id } : step
    })
    const steps = available ? [...combinedSteps, ...streamedSteps.values(), ...roleOrderSteps]
      .map((step) => ({ ...step, status: statusOf(step.status) })) : []
    const lastStep = currentLogs.findLast((event) => ['STEP_STARTED', 'STEP_COMPLETED'].includes(event.kind)) || steps.at(-1)
      || (agentId === 'SUP' && live ? currentLogs.at(-1) : undefined)
    const evidence = available && Array.isArray(agent?.evidence) ? agent.evidence.slice(0, 8).map((item) => ({
      ...queryPreview(item),
      reference: text(item.queryRef, 160), status: text(item.status, 60),
      at: text(item.checkedAt || item.queriedAt || item.observedAt, 40),
      missingCount: Array.isArray(item.missing) ? item.missing.length : 0,
    })) : []
    const knowledge = available && Array.isArray(message?.result?.knowledgeEvidence)
      ? message.result.knowledgeEvidence.filter((item) => item?.agentId === agentId).map((item) => ({
        status: text(item.status, 60), graphStatus: text(item.graph_status, 60),
        mode: text(item.mode_used, 40), hitCount: Array.isArray(item.hits) ? item.hits.length : 0,
      })) : []
    return { agentId, taskId: call?.taskId || agent?.taskId || '', ...metadata, status: statusOf(status), summary: available ? text(agent?.summary) : '',
      steps, lastStep, logs, evidence, knowledge,
      dependencies: available ? (message?.workflow?.plan?.dependencies?.[agentId] || []).filter(isAgent) : [],
    }
  })
  const requestStatus = orderFlow?.needsRecovery ? '状态待确认' : serviceActivity ? '处理中' : ({ RUNNING: '处理中', COMPLETED: '已收到响应',
    FAILED: '请求未完成', CANCELLED: '已取消', INTERRUPTED: '状态待确认' })[message?.status] || ''
  const draftStatus = orderFlow?.needsRecovery ? '待恢复核对' : ({ COLLECTING: '待补充', READY: '待确认',
    SUBMITTED: '已提交', CANCELLED: '已取消', EXPIRED: '已过期' })[orderFlow?.orderDraft?.status] || ''
  return { roles, events, live, invalidated, omitted, serviceActivity,
    serviceLogs: orderSteps.filter((step) => step.agentId === 'SERVICE').map((step) => ({ ...step, status: statusOf(step.status) })), requestStatus, draftStatus,
    truncated: message?.workflow?.truncated === true,
    notice: invalidated ? '查询期间数据发生变化，此前证据已失效，请重新查询。'
      : omitted ? '历史证据未保存，请重新查询。'
        : !message ? '发起任务后，实际 Agent 调用与步骤会显示在这里。' : '',
  }
}
