/** Presentation state for observed events. Never infer that an expert has run. */
export const AGENTS = Object.freeze({
  SUP: {
    name: '总协调 Agent',
    short: '总',
    action: '拆解任务，协调业务专家并汇总证据',
    color: 'teal',
  },
  ORDER: {
    name: '订单交付 Agent',
    short: '订',
    action: '查询订单、交期与剩余交付数量',
    color: 'blue',
  },
  SUPPLY: {
    name: '物料供应 Agent',
    short: '料',
    action: '核对 BOM、领料与物料缺口',
    color: 'amber',
  },
  PRODUCTION: {
    name: '生产调度 Agent',
    short: '产',
    action: '核对工单、工序与生产进度',
    color: 'violet',
  },
  QUALITY: {
    name: '质量检验 Agent',
    short: '质',
    action: '分析检验记录与不合格品证据',
    color: 'teal',
  },
  EQUIPMENT: {
    name: '设备维护 Agent',
    short: '设',
    action: '检查关联设备、报警与维修记录',
    color: 'blue',
  },
  AFTERSALES: {
    name: '售后追溯 Agent',
    short: '溯',
    action: '关联售后案件与生产追溯路径',
    color: 'violet',
  },
})
export const PROFILES = Object.freeze([
  {
    id: 'DELIVERY',
    name: '订单交付',
    prompt:
      '帮我判断这张订单能否按期交付，列出剩余交付量、物料缺口和生产约束。',
    hint: '订单 · 物料 · 生产',
  },
  {
    id: 'QUALITY',
    name: '质量分析',
    prompt: '分析这张订单的检验结果与质量风险，区分实际证据和待确认的原因。',
    hint: '检验记录 · 不合格品',
  },
  {
    id: 'AFTERSALES',
    name: '售后追溯',
    prompt:
      '追溯这张订单的售后案件、生产和检验记录，列出已证实的关联与缺失证据。',
    hint: '售后案件 · 追溯路径',
  },
  {
    id: 'FULL_ASSESSMENT',
    name: '综合研判',
    prompt:
      '请结合六个业务领域，汇总这张订单的当前状态、交付风险与下一步建议。',
    hint: '六位业务专家协同',
  },
  {
    id: 'ORDER',
    name: '订单概况',
    prompt: '查询这张订单的交付状态和剩余数量。',
    hint: '订单与交付',
  },
  {
    id: 'SUPPLY',
    name: '物料供应',
    prompt: '检查这张订单的物料需求、库存与缺口。',
    hint: '订单与物料',
  },
  {
    id: 'PRODUCTION',
    name: '生产进度',
    prompt: '检查这张订单的工单、工序和生产进度。',
    hint: '物料与生产约束',
  },
  {
    id: 'EQUIPMENT',
    name: '设备状态',
    prompt: '检查这张订单关联设备的状态、报警和维修情况。',
    hint: '设备与维修',
  },
])
const STATES = new Set([
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
])
const TERMINAL = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'])
export const uniqueId = () =>
  globalThis.crypto?.randomUUID?.() ||
  `msg-${Date.now()}-${Math.random().toString(36).slice(2)}`
export const historyKey = (userId) =>
  userId != null && String(userId).match(/^\d+$/)
    ? `mes-agent-workbench-v1-${userId}`
    : null
const clip = (value, max = 6000) =>
  typeof value === 'string' ? value.slice(0, max) : ''
const workflowText = (value, max) => clip(value, max).replace(/[\x00-\x1f\x7f-\x9f]/g, '')
const businessAgent = (id) => id !== 'SUP' && Object.hasOwn(AGENTS, id)
const WORKFLOW_LIMIT = 128
const WORKFLOW_STATUSES = new Set(['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'])
const COORDINATION_KINDS = new Set(['PLAN_CONFIRMED', 'SUMMARIZING', 'CANCELLED'])
const TASK_KINDS = new Set(['AGENT_STARTED', 'STEP_STARTED', 'STEP_COMPLETED', 'HANDOFF', 'AGENT_COMPLETED'])

function workflowPlan(plan) {
  if (!['QUERY', 'CLARIFY', 'UNSUPPORTED'].includes(plan?.action) ||
      !Array.isArray(plan.requiredAgents)) return null
  const requiredAgents = [...new Set(plan.requiredAgents.slice(0, 6).filter(businessAgent))]
  const dependencies = Object.fromEntries(requiredAgents.map((role) => {
    const upstream = plan.dependencies?.[role]
    return [role, Array.isArray(upstream)
      ? [...new Set(upstream.slice(0, 6).filter((id) => id !== role && requiredAgents.includes(id)))]
      : []]
  }))
  return { action: plan.action, requiredAgents, dependencies, confirmedAt: workflowText(plan.confirmedAt, 40) }
}

function normalizeWorkflow(value, runId) {
  if (!value || !Array.isArray(value.events) || typeof value.invalidated !== 'boolean' ||
      (value.runId != null && (typeof value.runId !== 'string' || value.runId !== runId))) return null
  if (value.invalidated) return { runId, invalidated: true, plan: null, events: [] }
  const events = new Map()
  for (const item of value.events.slice(0, WORKFLOW_LIMIT)) {
    if (!Number.isSafeInteger(item?.seq) || item.seq <= 0 || events.has(item.seq) ||
        !WORKFLOW_STATUSES.has(item.status)) continue
    const { seq, kind, agentId, status } = item
    if (COORDINATION_KINDS.has(kind) ? agentId !== 'SUP' : !TASK_KINDS.has(kind) || !businessAgent(agentId)) continue
    const event = { seq, kind, agentId, status, label: workflowText(item.label, 80),
      detail: workflowText(item.detail, 200), at: workflowText(item.at, 40) }
    for (const field of ['eventId', 'runRef', 'toolCallId']) {
      if (typeof item[field] === 'string' && /^[a-f0-9-]{36}$/.test(item[field])) event[field] = item[field]
    }
    if (typeof item.toolId === 'string' && /^[A-Z0-9_]{1,40}$/.test(item.toolId)) event.toolId = item.toolId
    if (businessAgent(agentId) && typeof item.taskId === 'string') event.taskId = workflowText(item.taskId, 36)
    if (kind === 'HANDOFF') {
      if (!businessAgent(item.fromAgent) || item.toAgent !== agentId || item.fromAgent === agentId || status !== 'COMPLETED') continue
      event.fromAgent = item.fromAgent
      event.toAgent = item.toAgent
    }
    events.set(seq, event)
  }
  return { runId, invalidated: false, plan: workflowPlan(value.plan),
    events: [...events.values()].sort((a, b) => a.seq - b.seq),
    truncated: value.truncated === true || value.events.length > WORKFLOW_LIMIT }
}

function mergeWorkflow(message, value) {
  const next = normalizeWorkflow(value, message.runId)
  const previous = message.workflow
  if (!next || previous?.invalidated) return
  if (next.invalidated) {
    clearWorkbenchEvidence(message, true)
    return
  }
  if (message.evidenceInvalidated) return
  if (!previous) {
    message.workflow = next
    return
  }
  const last = previous.events.at(-1)?.seq ?? 0
  const additions = next.events.filter(({ seq }) => seq > last)
  if (!additions.length && !(next.truncated && !previous.truncated && next.events.at(-1)?.seq === last)) return
  const events = [...previous.events, ...additions]
  message.workflow = { ...next, plan: previous.plan || next.plan,
    events: events.slice(0, WORKFLOW_LIMIT),
    truncated: previous.truncated || next.truncated || events.length > WORKFLOW_LIMIT }
}

export function observedSteps(value) {
  if (!Array.isArray(value)) return []
  const ids = new Set(['BUSINESS_READ', 'VISION_REVIEW', 'KNOWLEDGE_RETRIEVAL', 'ANALYSIS',
    'E1', 'E2', 'E3', 'E4', 'Q1', 'D1', 'A1',
    'RESOURCE_MATERIAL', 'RESOURCE_CAPACITY', 'EXPERIENCE_RETRIEVAL',
    'PROCESS_ROUTE', 'INSPECTION_TRACE', 'INSPECTION_HEADER', 'INSPECTION_DETAILS', 'INSPECTION_BATCH',
    'INSPECTION_PRODUCTION', 'INSPECTION_EQUIPMENT', 'INSPECTION_GRAPH', 'INSPECTION_VISION', 'INSPECTION_HISTORY', 'SOP_LOOKUP',
    ...['INVENTORY', 'PRODUCTION', 'QUALITY', 'ORDER', 'EQUIPMENT', 'AFTERSALES', 'MAINTENANCE', 'ALARMS', 'STOCK_MOVEMENT'].map(domain => `OVERVIEW_${domain}`)])
  const seen = new Set()
  return value.filter((step) => step && ids.has(step.id) && !seen.has(step.id) &&
    (STATES.has(step.status) || step.status === 'SKIPPED') && seen.add(step.id))
    .map((step) => ({ id: step.id, label: clip(step.label, 120), status: step.status,
      detail: clip(step.detail, 1200), at: clip(step.at, 40) }))
}

function invalidateEvidence(result) {
  return { ...result, facts: {}, queryRefs: [], knowledgeEvidence: [], experienceEvidence: [], approvedSopEvidence: [], analyses: [] }
}
function clearWorkbenchEvidence(message, hasWorkflow = Boolean(message.workflow)) {
  message.evidenceInvalidated = true
  delete message.photoComparison
  if (hasWorkflow) message.workflow = { runId: message.runId, invalidated: true, plan: null, events: [] }
  if (message.result) {
    message.result = invalidateEvidence(message.result)
    message.result.verdict = 'DATA_CHANGED'
    message.result.reply = '查询期间数据发生变化，此前证据已失效，请重新查询。'
    message.text = message.result.reply
  }
  for (const agent of message.agents) {
    agent.evidence = []
    agent.steps = []
    agent.summary = '查询期间数据发生变化，此前证据已失效，请重新查询。'
  }
}
function stopLiveSteps(agent, status = 'INTERRUPTED') {
  for (const step of agent.steps || []) if (step.status === 'RUNNING') step.status = status
}

export const canSubmitNaturalQuestion = ({ admin, connection, busy, text }) =>
  admin === true && connection?.configured === true &&
  connection?.naturalLanguage === true && !busy &&
  typeof text === 'string' && text.trim().length > 0

// This is only a continuation reference. The server validates its owner and session.
export function buildNaturalRequest(session, text, requestId) {
  const previous = [...(session.messages || [])].reverse().find((message) =>
    message.role === 'assistant' && (message.imageQuestion || (!message.orderFlow && message.status === 'COMPLETED' &&
    typeof message.result?.reply === 'string')),
  )
  const canContinue = previous && (!previous.imageQuestion || previous.photoComparison) && !previous.evidenceInvalidated &&
    !previous.workflow?.invalidated && previous.result?.verdict !== 'DATA_CHANGED' &&
    (previous.result.queryMode === 'NATURAL' || previous.result.workflowProfile === 'AUTO') &&
    typeof previous.runId === 'string' && previous.runId.trim()
  return {
    text: text.trim(),
    sessionId: session.id,
    requestId,
    ...(canContinue ? { previousRunId: previous.runId } : {}),
  }
}

export const verdictLabel = (verdict) => ({
  CLARIFY: '请补充查询信息',
  UNSUPPORTED: '当前暂不支持此操作',
  NOT_FOUND: '未找到匹配的业务记录',
  INSUFFICIENT_EVIDENCE: '证据不足，需补充确认',
  EVIDENCE_READY: '已完成证据查询',
  DATA_CHANGED: '数据已变化，请重新查询',
  INCOMPLETE: '任务尚未全部完成',
})[verdict] || '本次分析'

export function legacyMessageContext(message) {
  if (!message.orderPk || message.profile === 'AUTO') return ''
  const name = PROFILES.find((p) => p.id === message.profile)?.name
  return `${name ? `${name} · ` : ''}订单 ID ${message.orderPk}`
}

export function createAssistantMessage() {
  return {
    id: uniqueId(),
    role: 'assistant',
    text: '',
    status: 'RUNNING',
    agents: [],
    runId: null,
    result: null,
    error: '',
    createdAt: Date.now(),
  }
}
export function applyWorkbenchEvent(message, event) {
  if (message.conversationId && event?.conversationId && message.conversationId !== event.conversationId) return
  if (!event || (event.type !== 'run' && event.runId != null && event.runId !== message.runId)) return
  if (event.type === 'workflow' && (message.status === 'RUNNING' || event.invalidated === true)) {
    if (typeof event.runId !== 'string' || !event.runId || event.runId !== message.runId) return
    mergeWorkflow(message, event)
    return
  }
  if (message.status !== 'RUNNING') return
  if (event.type === 'run' && !message.runId) message.runId = clip(event.runId, 160)
  if (['snapshot', 'delta'].includes(event.type)) {
    if (message.evidenceInvalidated || !message.runId || event.runId !== message.runId ||
        !Number.isSafeInteger(event.seq) || event.seq <= (message.streamSeq || 0)) return
    message.streamSeq = event.seq
    if (event.type === 'snapshot' && event.data?.runRef === message.runId && event.data?.presentation === 'compact_stream') {
      message.result = { ...event.data, reply: '' }
      message.text = ''
    } else if (event.type === 'delta' && message.result?.presentation === 'compact_stream' && typeof event.text === 'string') {
      message.text = clip(message.text + event.text, 64000)
    }
    return
  }
  if (
    event.type === 'agent' &&
    Object.hasOwn(AGENTS, event.agentId) &&
    STATES.has(event.status)
  ) {
    const index = message.agents.findIndex((item) => item.agentId === event.agentId)
    let agent = message.agents[index]
    const latestCall = message.workflow?.events.findLast((item) =>
      item.agentId === event.agentId && item.kind === 'AGENT_STARTED')
    const newAttempt = agent?.taskId && event.taskId && agent.taskId !== event.taskId
    if (newAttempt && latestCall?.taskId !== event.taskId) return
    if (newAttempt) {
      agent = { agentId: event.agentId, evidence: [] }
      message.agents[index] = agent
    }
    if (agent && TERMINAL.has(agent.status)) return
    if (!agent) {
      agent = { agentId: event.agentId, evidence: [] }
      message.agents.push(agent)
    }
    agent.status = event.status
    if (event.taskId) agent.taskId = workflowText(event.taskId, 36)
    if (message.evidenceInvalidated) return
    if (Array.isArray(event.steps)) agent.steps = observedSteps(event.steps)
    if (event.summary) agent.summary = clip(event.summary)
    if (Array.isArray(event.evidence))
      agent.evidence = event.evidence.slice(0, 8)
    if (event.startedAt) agent.startedAt = clip(event.startedAt, 40)
    if (event.completedAt) agent.completedAt = clip(event.completedAt, 40)
  }
  if (event.type === 'result' && typeof event.data?.reply === 'string') {
    message.result = event.data
    if (message.text !== event.data.reply) message.text = clip(event.data.reply, 64000)
    message.status = 'COMPLETED'
    // Running markers are observations, never assume completion from final text alone.
    for (const agent of message.agents) {
      if (agent.status === 'RUNNING') agent.status = 'INTERRUPTED'
      stopLiveSteps(agent)
    }
    if (event.data.verdict === 'DATA_CHANGED' || message.evidenceInvalidated) {
      const reply = event.data.verdict === 'DATA_CHANGED' ? message.text : null
      clearWorkbenchEvidence(message)
      if (reply !== null) message.result.reply = message.text = reply
    }
  }
}
export function interruptMessage(message, status = 'INTERRUPTED', reason = '') {
  if (message.status !== 'RUNNING') return
  message.status = ['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(status)
    ? status
    : 'INTERRUPTED'
  message.error = clip(reason, 500)
  for (const agent of message.agents) {
    if (agent.status === 'RUNNING') agent.status = message.status
    stopLiveSteps(agent, message.status)
  }
}
export function recoverWorkbenchResult(message, run) {
  if (!run || (run.runId && message.runId && run.runId !== message.runId)) return false
  if (message.conversationId && run.sessionId && message.conversationId !== run.sessionId) return false
  if (!message.runId && run.runId) message.runId = clip(run.runId, 160)
  mergeWorkflow(message, run.workflow)
  if (typeof run.result?.reply !== 'string') {
    if (typeof run.partialText === 'string' && !message.evidenceInvalidated && run.partialText.length >= message.text.length) message.text = clip(run.partialText, 64000)
    if (['FAILED', 'CANCELLED'].includes(run.status)) {
      message.status = 'RUNNING'
      interruptMessage(message, run.status, run.status === 'CANCELLED' ? '服务端已取消任务。' : '任务未能完成，已保留收到的内容。')
    }
    return false
  }
  message.status = 'RUNNING'
  message.error = ''
  message.agents = []
  if (Array.isArray(run.agents))
    for (const agent of run.agents)
      applyWorkbenchEvent(message, { ...agent, type: 'agent' })
  applyWorkbenchEvent(message, { type: 'result', data: run.result })
  return true
}
export function restoreSessions(raw) {
  try {
    if (!raw || raw.length > 2_000_000) return []
    const sessions = JSON.parse(raw)
    if (!Array.isArray(sessions)) return []
    return sessions
      .filter((s) => s && typeof s.id === 'string' && Array.isArray(s.messages))
      .slice(0, 20)
      .map((s) => ({
        id: clip(s.id, 160),
        title: clip(s.title, 80),
        renamed: s.renamed === true,
        updatedAt: Number(s.updatedAt) || 0,
        orderPk: clip(s.orderPk, 20),
        profile: s.profile === 'AUTO' || PROFILES.some((p) => p.id === s.profile)
          ? s.profile
          : s.orderPk ? 'DELIVERY' : 'AUTO',
        messages: s.messages
          .filter((m) => m && ['user', 'assistant'].includes(m.role))
          .slice(-40)
          .map((m, index, messages) => {
            const agents = Array.isArray(m.agents)
              ? m.agents
                  .filter(
                    (a) =>
                      a &&
                      Object.hasOwn(AGENTS, a.agentId) &&
                      STATES.has(a.status),
                  )
                  .slice(0, 7)
                  .map((a) => ({
                    agentId: a.agentId,
                    taskId: workflowText(a.taskId, 36),
                    status: a.status,
                    summary: clip(a.summary),
                    startedAt: clip(a.startedAt, 40),
                    completedAt: clip(a.completedAt, 40),
                    steps: observedSteps(a.steps),
                    evidence: Array.isArray(a.evidence)
                      ? a.evidence
                          .filter((e) => e && typeof e === 'object')
                          .slice(0, 8)
                      : [],
                  }))
              : []
            const item = {
              id: clip(m.id, 160) || uniqueId(),
              role: m.role,
              text: m.role === 'user' && messages[index + 1]?.imageUpload
                ? clip(m.text, 64000).replace(/\n📎 [^\r\n]+\.(?:jpe?g|png)$/i, '') : clip(m.text, 64000),
              status: STATES.has(m.status) ? m.status : 'INTERRUPTED',
              agents,
              runId: clip(m.runId, 160),
              error: clip(m.error, 500),
              orderPk: clip(m.orderPk, 20),
              profile: clip(m.profile, 32),
              result:
                m.result &&
                typeof m.result === 'object' &&
                typeof m.result.reply === 'string'
                  ? m.result
                  : null,
            }
            if (m.imageUpload === true) {
              item.imageUpload = true
              if (Number.isSafeInteger(m.frameCount) && m.frameCount >= 0) item.frameCount = m.frameCount
            }
            if (m.directChat === true) item.directChat = true
            if (typeof m.imageRef === 'string') item.imageRef = clip(m.imageRef, 80)
            if (m.photoObservation) item.photoObservation = clip(m.photoObservation, 2000)
            if (m.photoComparison && !m.evidenceInvalidated && !m.workflow?.invalidated) item.photoComparison = clip(m.photoComparison, 8000)
            if (m.imageQuestion === true) item.imageQuestion = true
            if (m.orderMessage === true) item.orderMessage = true
            if (m.orderFlow && typeof m.orderFlow === 'object') {
              item.orderFlow = { needsRecovery: true, steps: [],
                ...(m.orderFlow.orderDraft?.id ? { orderDraft: { id: clip(String(m.orderFlow.orderDraft.id), 160) } } : {}) }
              item.text = '订单会话已保存，请恢复草稿查看当前状态。'
              item.error = ''
            }
            const workflow = normalizeWorkflow(m.workflow, item.runId)
            if (workflow) item.workflow = workflow
            if (item.workflow?.invalidated || m.evidenceInvalidated === true || item.result?.verdict === 'DATA_CHANGED') {
              const reply = item.result?.verdict === 'DATA_CHANGED' ? item.text : null
              clearWorkbenchEvidence(item)
              if (reply !== null) item.result.reply = item.text = reply
            } else if (item.result?.historyEvidenceOmitted) {
              item.result = invalidateEvidence(item.result)
              for (const agent of agents) {
                agent.evidence = []
                agent.steps = []
                agent.summary = '历史证据未保存，请重新查询。'
              }
            }
            if (item.status === 'RUNNING')
              interruptMessage(
                item,
                'INTERRUPTED',
                '页面已关闭或刷新，未自动重新执行。',
              )
            else for (const agent of agents) stopLiveSteps(agent)
            return item
          }),
      }))
  } catch {
    return []
  }
}

// Keep complete evidence or omit the whole evidence bundle with a visible reason. Never
// truncate rows while retaining totals or analysis that appear to describe a full snapshot.
export function serializeWorkbenchSessions(sessions) {
  const budget = 1_800_000
  const saved = sessions.slice(0, 20).map((session) => ({ ...session,
    title: !session.renamed && session.messages.some((m) => m.orderMessage || m.orderFlow) ? '订单会话' : session.title,
    messages: session.messages.slice(-40).map((message) => message.orderMessage || message.orderFlow ? {
      id: message.id, role: message.role, status: message.status, profile: 'AUTO',
      text: message.role === 'user' ? '订单信息（仅在当前页面显示）' : '订单会话已保存，请恢复草稿查看当前状态。',
      orderMessage: true,
      ...(message.orderFlow ? { orderFlow: { needsRecovery: true,
        ...(message.orderFlow.orderDraft?.id ? { orderDraft: { id: message.orderFlow.orderDraft.id } } : {}) } } : {}),
    } : ({ ...message,
      text: clip(message.text, 64000), result: message.result ? { ...message.result, reply: clip(message.result.reply, 64000) } : null,
    })),
  }))
  let encoded = JSON.stringify(saved)
  while (encoded.length > budget && saved.length > 1) {
    saved.pop()
    encoded = JSON.stringify(saved)
  }
  if (encoded.length <= budget) return encoded
  const messages = saved[0]?.messages || []
  for (let i = 0; i < messages.length && encoded.length > budget; i++) {
    const message = messages[i]
    if (message.role !== 'assistant') continue
    message.agents = (message.agents || []).map((agent) => ({ agentId: agent.agentId, status: agent.status,
      summary: '历史证据未保存，请重新查询。', evidence: [], steps: [] }))
    if (message.result) message.result = { reply: message.result.reply, verdict: message.result.verdict,
      queryMode: message.result.queryMode, workflowProfile: message.result.workflowProfile,
      facts: {}, queryRefs: [], knowledgeEvidence: [], experienceEvidence: [], approvedSopEvidence: [], analyses: [], historyEvidenceOmitted: true }
    encoded = JSON.stringify(saved)
  }
  while (encoded.length > budget && messages.length > 1) {
    messages.shift()
    encoded = JSON.stringify(saved)
  }
  return encoded.length <= budget ? encoded : '[]'
}
