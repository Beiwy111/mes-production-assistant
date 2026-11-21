<script setup>
import { computed, ref, watch } from 'vue'
import { ChevronRight } from 'lucide-vue-next'
import { AGENTS, observedSteps } from '@/utils/agentWorkbench'
import ChatBubble from '@/components/chat/ChatBubble.vue'
import AgentStatusIndicator from './AgentStatusIndicator.vue'

const props = defineProps({ message: { type: Object, required: true } })
const workflow = computed(() => props.message.workflow)
const events = computed(() => workflow.value?.events || [])
const coordination = computed(() => events.value.findLast((event) => event.agentId === 'SUP'))
const live = computed(() => props.message.status === 'RUNNING' &&
  coordination.value?.kind !== 'CANCELLED' && !workflow.value?.invalidated)
const expanded = ref(live.value)
watch(live, (value) => { expanded.value = value })
const agentName = (id) => AGENTS[id].name.replace(' Agent', '')
const displayStatus = (status) => {
  if (status !== 'RUNNING' || live.value) return status
  if (coordination.value?.kind === 'CANCELLED' || props.message.status === 'CANCELLED') return 'CANCELLED'
  return 'INTERRUPTED'
}
const agents = computed(() => {
  const observed = new Map(props.message.agents.map((agent) => [agent.agentId, agent]))
  const calls = new Map()
  for (const event of events.value) {
    if (event.kind !== 'AGENT_STARTED' && event.kind !== 'AGENT_COMPLETED') continue
    const previous = calls.get(event.agentId)
    const differentTask = previous?.taskId && event.taskId && previous.taskId !== event.taskId
    if (differentTask ? event.kind !== 'AGENT_STARTED' : previous?.kind === 'AGENT_COMPLETED') continue
    calls.set(event.agentId, event)
  }
  const currentAgent = (id) => {
    const agent = observed.get(id)
    const taskId = calls.get(id)?.taskId
    return taskId && agent?.taskId && agent.taskId !== taskId ? undefined : agent
  }
  const currentStatus = (id) => {
    const call = calls.get(id)
    return call?.kind === 'AGENT_COMPLETED' ? call.status : currentAgent(id)?.status || call?.status
  }
  const required = workflow.value?.plan?.requiredAgents || []
  const roles = [...new Set([...required, ...observed.keys(), ...calls.keys()])]
  return roles.map((agentId) => {
    const agent = currentAgent(agentId)
    const call = calls.get(agentId)
    const status = currentStatus(agentId) || (live.value ? 'WAITING' : 'UNCALLED')
    const step = events.value.findLast((event) => event.agentId === agentId &&
      (!call?.taskId || event.taskId === call.taskId) &&
      (event.kind === 'STEP_STARTED' || event.kind === 'STEP_COMPLETED'))
    const dependencies = workflow.value?.plan?.dependencies?.[agentId] || []
    const waitingFor = dependencies.filter((id) => currentStatus(id) !== 'COMPLETED')
    return { ...agent, agentId, status: displayStatus(status), step, dependencies, waitingFor,
      steps: observedSteps(agent?.steps).map((item) => ({ ...item, status: displayStatus(item.status) })) }
  })
})
const taskNodes = computed(() => workflow.value?.plan || events.value.length
  ? agents.value.filter((agent) => agent.agentId !== 'SUP')
  : agents.value)
const completed = computed(
  () => taskNodes.value.filter((a) => a.status === 'COMPLETED').length,
)
const activeNames = computed(() =>
  taskNodes.value
    .filter((a) => a.status === 'RUNNING')
    .map((a) => agentName(a.agentId))
    .join('、'),
)
const supervisor = computed(() => agents.value.find((agent) => agent.agentId === 'SUP'))
const phase = computed(() => {
  if (workflow.value?.invalidated) return '证据已失效'
  if (coordination.value?.kind === 'CANCELLED' || props.message.status === 'CANCELLED') return '协作已取消'
  if (props.message.status === 'COMPLETED') return '协作记录'
  if (!live.value) return '协作已中止'
  if (coordination.value?.kind === 'SUMMARIZING') return '正在汇总证据'
  if (workflow.value?.plan) return '已确认执行计划'
  return '等待确认执行计划'
})
const latestStep = (agent) => agent.step || agent.steps.at(-1)
const timelineStatus = (event) => event.status === 'RUNNING' ? '已开始' : stateText(event.status)
const stateText = (state) =>
  ({
    RUNNING: '执行中',
    COMPLETED: '已完成',
    FAILED: '未完成',
    CANCELLED: '已停止等待',
    INTERRUPTED: '状态待确认',
    WAITING: '待调用',
    UNCALLED: '未调用',
    SKIPPED: '未执行',
  })[state] || '待确认'
const evidenceName = (evidence) =>
  ({
    E1: '订单与计划',
    E2: '物料供需',
    E3: '生产进度',
    E4: '交付与库存',
    Q1: '检验与缺陷',
    D1: '设备与维护',
    A1: '售后追溯',
  })[evidence.kind || evidence.evidenceKind] || '业务查询证据'
const time = (value) =>
  value && !Number.isNaN(Date.parse(value))
    ? new Date(value).toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      })
    : ''
</script>

<template>
  <span v-if="workflow" class="flow-status-announcement" role="status" aria-live="polite">{{ phase }}<template v-if="live && activeNames">，{{ activeNames }}正在处理</template></span>
  <details
    v-if="workflow || agents.length"
    class="agent-flow"
    :class="{ 'agent-flow--live': live }"
    aria-label="Agent 协作过程"
    :open="expanded"
  >
    <summary class="flow-heading" @click.prevent="expanded = !expanded">
      <AgentStatusIndicator v-if="live" status="RUNNING" />
      <ChevronRight v-else class="flow-expand" :size="14" />
      <span class="flow-heading__name">{{ live ? (activeNames ? `${activeNames}正在处理` : phase) : '查看协作过程' }}</span>
      <span v-if="(live || expanded) && taskNodes.length && !workflow?.invalidated" class="flow-heading__count"
        >{{ completed }} / {{ taskNodes.length }} 已完成</span
      >
    </summary>
    <p v-if="workflow?.invalidated" class="flow-notice">查询期间数据发生变化，请重新查询。</p>
    <template v-else>
    <div v-if="workflow?.plan || events.length" class="flow-phase">
      <span><strong>总协调 Agent</strong><small>{{ live ? coordination?.detail || phase : phase }}</small></span>
    </div>
    <div v-if="taskNodes.length" class="flow-steps">
      <details
        v-for="agent in taskNodes"
        :key="agent.agentId"
        class="flow-step"
        :class="agent.status.toLowerCase()"
      >
        <summary>
          <AgentStatusIndicator :status="agent.status" class="step-icon" />
          <span class="step-info"
            ><strong>{{ latestStep(agent)?.label || AGENTS[agent.agentId].name }}</strong
            ><span v-if="agent.status === 'UNCALLED' && agent.dependencies.length">前序：{{ agent.dependencies.map(agentName).join('、') }}</span>
            <span v-else-if="agent.status === 'WAITING' && agent.waitingFor.length">前序：{{ agent.waitingFor.map(agentName).join('、') }}</span>
            <span v-else-if="latestStep(agent)">{{ AGENTS[agent.agentId].name }}</span>
            <span v-else>{{ agent.status === 'UNCALLED' ? '本次未调用' : agent.status === 'WAITING' ? '等待调用' : '等待实际步骤记录' }}</span>
            <span v-if="latestStep(agent)?.detail" class="step-description">{{ latestStep(agent).detail }}</span></span
          >
          <span class="step-state">{{ agent.status === 'WAITING' && agent.waitingFor.length ? '等待前序' : stateText(agent.status) }}</span
          >
          <ChevronRight class="step-chevron" :size="14" />
        </summary>
        <div class="step-detail">
          <div v-if="time(agent.startedAt)" class="step-time">
            {{ time(agent.startedAt) }} 开始<span
              v-if="time(agent.completedAt)"
            >
              · {{ time(agent.completedAt) }} 完成</span
            >
          </div>
          <ol v-if="agent.steps.length" class="observed-steps">
            <li v-for="step in agent.steps" :key="step.id">
              <strong>{{ step.label || step.id }}</strong> · {{ stateText(step.status) }}<time v-if="time(step.at)"> · {{ time(step.at) }}</time>
              <p v-if="step.detail">{{ step.detail }}</p>
            </li>
          </ol>
          <p v-else-if="agent.step" class="step-note">
            {{ agent.step.label }} · {{ stateText(displayStatus(agent.step.status)) }}
            <span v-if="agent.step.detail"> · {{ agent.step.detail }}</span>
          </p>
          <p v-else class="step-note">{{ agent.status === 'UNCALLED' ? '本次运行结束时未调用。' : agent.status === 'WAITING' ? '等待实际调用记录。' : '尚未收到实际步骤记录，不能推断已执行检索或分析。' }}</p>
          <ChatBubble
            v-if="agent.summary"
            role="assistant"
            :text="agent.summary"
          />
          <p v-else class="step-pending">
            {{
              agent.status === 'RUNNING'
                ? '正在处理，结果返回后显示在这里。'
                : '暂无可展示的任务摘要。'
            }}
          </p>
          <div v-if="agent.evidence?.length" class="evidence-list">
            <details
              v-for="(evidence, index) in agent.evidence"
              :key="evidence.queryRef || index"
            >
              <summary>{{ evidenceName(evidence) }} <span>↗</span></summary>
              <dl>
                <dt>查询状态</dt>
                <dd>{{ evidence.status || '已返回' }}</dd>
                <dt>证据编号</dt>
                <dd>{{ evidence.queryRef || '未提供' }}</dd>
                <template v-if="evidence.checkedAt || evidence.queriedAt"
                  ><dt>查询时间</dt>
                  <dd>
                    {{ evidence.checkedAt || evidence.queriedAt }}
                  </dd></template
                >
              </dl>
              <p v-if="evidence.missing?.length">
                这组证据存在
                {{ evidence.missing.length }} 项限制，请结合最终结论查看。
              </p>
            </details>
          </div>
          <p v-if="agent.summary && agent.agentId !== 'SUP'" class="step-note">
            专家摘要用于解释，数量与状态以本次业务证据为准。
          </p>
        </div>
      </details>
    </div>
    <details v-if="workflow && supervisor?.summary && !taskNodes.includes(supervisor)" class="flow-supervisor-summary">
      <summary>总协调摘要</summary>
      <ChatBubble role="assistant" :text="supervisor.summary" />
    </details>
    <details v-if="events.length" class="flow-log" aria-label="协作记录">
      <summary>完整协作记录（{{ events.length }}）</summary>
      <ol>
        <li v-for="event in events" :key="event.seq">
          <time :datetime="event.at">{{ time(event.at) }}</time>
          <strong>{{ event.kind === 'HANDOFF' ? `${agentName(event.fromAgent)} → ${agentName(event.toAgent)}` : agentName(event.agentId) }}</strong>
          <span>{{ event.label }} · {{ timelineStatus(event) }}</span>
          <p v-if="event.detail">{{ event.detail }}</p>
        </li>
      </ol>
      <p v-if="workflow.truncated" class="flow-notice">记录达到显示上限，后续事件未列出。</p>
    </details>
    </template>
  </details>
</template>

<style scoped>
.agent-flow {
  margin: 8px 0 16px;
  font-family: var(--aw-font-sans);
  color: var(--aw-text);
  font-size: 14px;
  line-height: 1.6;
}
.flow-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  max-width: 100%;
  padding: 4px 0;
  color: var(--aw-muted);
  font-size: 13px;
}
.agent-flow > summary,
.flow-step > summary { cursor: pointer; list-style: none; }
.agent-flow > summary::-webkit-details-marker,
.flow-step > summary::-webkit-details-marker { display: none; }
.flow-heading:hover { color: var(--aw-text); }
.flow-heading__name { min-width: 0; overflow-wrap: anywhere; }
.flow-heading__count { flex: none; font-variant-numeric: tabular-nums; }
.flow-expand, .step-chevron { flex: none; font-size: 12px; transition: transform .15s; }
.agent-flow[open] > summary .flow-expand,
.flow-step[open] > summary .step-chevron { transform: rotate(90deg); }
.agent-flow--live .flow-heading { color: var(--aw-text); }
.flow-status-announcement {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.flow-phase { padding: 10px 0 10px 20px; }
.flow-phase strong, .step-info strong { font-size: 14px; font-weight: 500; }
.flow-phase small { display: block; color: var(--aw-muted); font-size: 13px; }
.flow-steps { padding: 0 0 0 5px; }
.flow-step { position: relative; padding-left: 15px; }
.flow-step > summary { display: flex; align-items: flex-start; gap: 8px; padding: 8px 0; }
.step-icon { flex: none; margin-top: 3px; color: var(--aw-muted); }
.completed .step-icon { color: var(--aw-success); }
.failed .step-icon { color: var(--aw-danger); }
.step-info { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 2px; }
.step-info > span { color: var(--aw-muted); font-size: 13px; overflow-wrap: anywhere; }
.step-info > .step-description {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  white-space: pre-wrap;
}
.step-state { flex: none; color: var(--aw-muted); font-size: 13px; white-space: nowrap; }
.step-chevron { color: var(--aw-subtle); }
.step-detail { padding: 4px 0 12px; color: var(--aw-muted); font-size: 13px; overflow-wrap: anywhere; }
.step-detail :deep(.chat-msg__assistant-body) { font-size: 13px; line-height: 1.7; }
.step-time, .step-note, .step-pending { margin: 6px 0; color: var(--aw-muted); font-size: 13px; }
.observed-steps { margin: 8px 0; padding-left: 18px; }
.observed-steps li { margin: 6px 0; }
.observed-steps strong { color: var(--aw-text); font-weight: 500; }
.observed-steps p { margin: 3px 0; white-space: pre-wrap; }
.observed-steps time { font-variant-numeric: tabular-nums; }
.evidence-list { margin-top: 8px; }
.evidence-list > details { padding: 4px 0; }
.evidence-list summary, .flow-log summary, .flow-supervisor-summary summary { cursor: pointer; }
.evidence-list dl { display: grid; grid-template-columns: 64px 1fr; gap: 4px 8px; margin: 8px 0; }
.evidence-list dd { margin: 0; overflow-wrap: anywhere; }
.evidence-list p { margin: 6px 0; }
.flow-log, .flow-supervisor-summary { padding: 8px 0 0 20px; color: var(--aw-muted); font-size: 13px; }
.flow-log ol { margin: 8px 0 0; padding: 8px; list-style: none; max-height: 256px; overflow: auto; background: var(--aw-panel); font: 12px/1.6 var(--aw-font-mono); }
.flow-log li { display: flex; flex-wrap: wrap; gap: 2px 8px; padding: 5px 0; overflow-wrap: anywhere; }
.flow-log li strong { font-weight: 500; color: var(--aw-text); }
.flow-log li time { font-variant-numeric: tabular-nums; }
.flow-log li p { flex-basis: 100%; margin: 0; white-space: pre-wrap; }
.flow-notice { margin: 0; padding: 8px 0; color: var(--aw-muted); font-size: 13px; }
summary:focus-visible { outline: 2px solid var(--aw-subtle); outline-offset: 3px; border-radius: 2px; }
@media (prefers-reduced-motion: reduce) {
  .flow-expand, .step-chevron { transition: none; }
}
@media (max-width: 650px) {
  .flow-heading { flex-wrap: wrap; gap: 6px; }
  .flow-step > summary { gap: 6px; }
  .flow-heading__count { margin-left: 18px; }
}
</style>
