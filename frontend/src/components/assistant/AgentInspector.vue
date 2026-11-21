<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { Check, ChevronRight, Circle, CircleAlert, LoaderCircle, Network, X } from 'lucide-vue-next'
import { AGENTS } from '@/utils/agentWorkbench'
import { agentStateText, inspectAgentMessage } from '@/utils/agentInspector'
import AgentStatusIndicator from './AgentStatusIndicator.vue'

const props = defineProps<{ message: Record<string, any> | null }>()
defineEmits<{ close: [] }>()
const model = computed(() => inspectAgentMessage(props.message))
const tab = ref<'tasks' | 'calls'>('tasks')
const tabs = ['tasks', 'calls'] as const
const tabButtons = ref<HTMLButtonElement[]>([])
const instance = useId()
const expanded = ref<Record<string, boolean>>({})
// Follow lifecycle transitions, not each streamed token. A manual toggle remains respected.
watch(() => ({ message: props.message?.id ?? props.message, run: props.message?.runId, live: model.value.live,
  roles: model.value.roles.map(({ agentId, status, taskId }) => ({ agentId, status, taskId })) }), (current, previous) => {
  const changed = current.message !== previous?.message || current.run !== previous?.run
  if (changed) expanded.value = {}
  for (const role of current.roles) {
    const old = changed ? undefined : previous?.roles.find((item) => item.agentId === role.agentId)
    if (current.live && role.status === 'RUNNING' && (old?.status !== 'RUNNING' || old?.taskId !== role.taskId)) expanded.value[role.agentId] = true
    else if (old?.status === 'RUNNING' && role.status !== 'RUNNING') expanded.value[role.agentId] = false
  }
  if (!current.live && previous?.live) expanded.value = {}
}, { immediate: true })
const statusIcon = (status: string) => status === 'COMPLETED' ? Check : status === 'RUNNING'
  ? LoaderCircle : ['FAILED', 'INTERRUPTED', 'INVALIDATED'].includes(status) ? CircleAlert : Circle
const time = (value: string) => value && !Number.isNaN(Date.parse(value))
  ? new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : ''
async function moveTab(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const index = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : tab.value === 'tasks' ? 1 : 0
  tab.value = tabs[index]
  await nextTick()
  tabButtons.value[index]?.focus()
}
const researchState = (status: string) => ({ MATCHES: '有匹配资料', NO_MATCHES: '未命中',
  UNAVAILABLE: '不可用', UNAVAILABLE_OR_STALE: '不可用或已过期', SKIPPED: '未执行' })[status] || status || '未提供状态'
const actorName = (id: string) => id === 'SERVICE' ? '业务服务' : AGENTS[id]?.name || '未记录角色'
</script>

<template>
  <section class="agent-inspector" aria-label="Agent 任务检查器">
    <header class="inspector-header">
      <span><Network :size="18" /> Agent</span>
      <button class="icon-button" type="button" aria-label="关闭 Agent 面板" title="关闭 Agent 面板" @click="$emit('close')"><X :size="16" /></button>
    </header>
    <div v-if="model.requestStatus || model.draftStatus" class="current-task" aria-label="当前任务状态">
      <div v-if="model.requestStatus"><span>当前请求</span><span>{{ model.requestStatus }}</span></div>
      <div v-if="model.draftStatus"><span>订单草稿</span><span>{{ model.draftStatus }}</span></div>
    </div>
    <div class="inspector-tabs" role="tablist" aria-label="Agent 详情">
      <button v-for="(item, index) in tabs" :id="`${instance}-tab-${item}`" :key="item"
        :ref="(el) => { if (el) tabButtons[index] = el as HTMLButtonElement }" type="button" role="tab"
        :aria-selected="tab === item" :aria-controls="`${instance}-panel-${item}`" :tabindex="tab === item ? 0 : -1"
        @click="tab = item" @keydown="moveTab">{{ item === 'tasks' ? '任务' : '调用' }}<span v-if="item === 'calls'">{{ model.events.length }}</span></button>
    </div>
    <p v-if="message && model.notice" class="inspector-notice" role="status">{{ model.notice }}</p>
    <div :id="`${instance}-panel-tasks`" role="tabpanel" :aria-labelledby="`${instance}-tab-tasks`" :hidden="tab !== 'tasks'" class="inspector-body">
      <div v-if="!message" class="inspector-idle"><span class="idle-symbol"><Network :size="24" /></span><strong>协作，从一个问题开始</strong><p>任务执行时，在这里查看实际调用与结果。</p></div>
      <details v-for="agent in message ? model.roles : []" :key="agent.agentId" class="inspector-role" :class="agent.status.toLowerCase()" :open="expanded[agent.agentId] === true" :data-agent="agent.agentId">
        <summary @click.prevent="expanded[agent.agentId] = !expanded[agent.agentId]">
          <AgentStatusIndicator :status="agent.status" class="role-icon" />
          <span class="role-name">{{ agent.agentId === 'SUP' ? 'Supervisor' : agent.name.replace(' Agent', '') }}</span>
          <span class="role-status">{{ agentStateText(agent.status) }}</span><ChevronRight :size="14" class="role-chevron" />
        </summary>
        <div class="role-content">
          <p class="role-description">职责：{{ agent.action }}</p>
          <p v-if="agent.dependencies.length" class="role-description">前序：{{ agent.dependencies.map((id) => AGENTS[id].name.replace(' Agent', '')).join('、') }}</p>
          <p v-if="agent.status === 'WAITING'" class="role-description">已纳入执行计划，等待实际调用。</p>
          <p v-else-if="agent.status === 'UNCALLED'" class="role-description">本次未收到调用记录。</p>
          <ol v-if="agent.steps.length" class="inspector-steps">
            <li v-for="step in agent.steps" :key="step.id">
              <div class="step-label"><AgentStatusIndicator :status="step.status" /><span>{{ step.label || step.id }}</span><small>{{ agentStateText(step.status) }}</small></div>
              <p v-if="step.detail" class="step-preview">{{ step.detail }}</p>
              <details v-if="step.detail" class="log-detail"><summary>查看详情</summary><pre>{{ step.detail }}</pre></details>
            </li>
          </ol>
          <div v-else-if="agent.lastStep" class="observed-step">
            <div class="step-label"><span>{{ agent.lastStep.label }}</span><small>{{ agentStateText(agent.lastStep.status === 'RUNNING' && !model.live ? 'INTERRUPTED' : agent.lastStep.status) }}</small></div>
            <p v-if="agent.lastStep.detail" class="step-preview">{{ agent.lastStep.detail }}</p>
          </div>
          <p v-else-if="!['WAITING', 'UNCALLED', 'UNKNOWN', 'INVALIDATED'].includes(agent.status)" class="role-description">尚未收到实际步骤记录。</p>
          <section v-for="(evidence, index) in agent.evidence" :key="`data-${index}`" class="query-result">
            <h4>{{ evidence.label }}<span>{{ evidence.status || '未提供状态' }}</span></h4>
            <p v-if="evidence.keyword">查询条件：{{ evidence.keyword }}</p>
            <p v-if="evidence.result">{{ evidence.result }}</p>
            <ul v-if="evidence.preview.length"><li v-for="(row, rowIndex) in evidence.preview" :key="rowIndex">{{ row }}</li></ul>
            <p v-if="evidence.preview.length" class="result-note">以上为返回数据节选，完整结果见聊天区。</p>
            <p v-if="evidence.partialNote" class="result-note">{{ evidence.partialNote }}</p>
            <details class="log-detail evidence-log"><summary>查询凭据<ChevronRight :size="12" /></summary>
              <dl><template v-if="evidence.kind"><dt>证据类型</dt><dd>{{ evidence.kind }}</dd></template><template v-if="evidence.reference"><dt>证据引用</dt><dd>{{ evidence.reference }}</dd></template><template v-if="evidence.at"><dt>查询时间</dt><dd>{{ evidence.at }}</dd></template><template v-if="evidence.missingCount"><dt>缺失项</dt><dd>{{ evidence.missingCount }}</dd></template></dl>
            </details>
          </section>
          <section v-for="(knowledge, index) in agent.knowledge" :key="`rag-${index}`" class="query-result">
            <h4>RAG / GraphRAG</h4><p>{{ researchState(knowledge.status) }} · {{ knowledge.hitCount }} 条匹配</p>
            <p v-if="knowledge.mode">检索模式：{{ knowledge.mode }}</p><p v-if="knowledge.graphStatus">图谱：{{ researchState(knowledge.graphStatus) }}</p>
          </section>
          <div v-if="agent.summary" class="query-result"><h4>返回摘要</h4><p class="summary-preview">{{ agent.summary }}</p><details v-if="agent.summary.length > 100" class="log-detail"><summary>完整摘要</summary><p class="agent-summary">{{ agent.summary }}</p></details></div>
          <details v-if="agent.logs.length" class="log-detail"><summary>调用日志 · {{ agent.logs.length }}</summary><ol class="event-log"><li v-for="event in agent.logs" :key="event.seq"><span>{{ time(event.at) }} {{ event.label }} · {{ event.status === 'RUNNING' ? '已开始' : agentStateText(event.status) }}</span><p v-if="event.detail">{{ event.detail }}</p></li></ol></details>
        </div>
      </details>
      <section v-if="model.serviceLogs.length || model.serviceActivity" class="business-service" aria-label="订单业务服务记录">
        <h3>业务服务</h3>
        <p v-if="model.serviceActivity" class="service-activity" role="status"><AgentStatusIndicator status="RUNNING" /><span>{{ model.serviceActivity }}</span></p>
        <details v-for="event in model.serviceLogs" :key="event.seq" class="log-detail">
          <summary><AgentStatusIndicator :status="event.status" :animate="false" />{{ event.label }}<span>{{ agentStateText(event.status) }}</span></summary>
          <pre v-if="event.detail">{{ event.detail }}</pre>
        </details>
      </section>
    </div>
    <div :id="`${instance}-panel-calls`" role="tabpanel" :aria-labelledby="`${instance}-tab-calls`" :hidden="tab !== 'calls'" class="inspector-body">
      <p v-if="!model.events.length" class="inspector-empty">尚无实际调用日志。</p>
      <ol v-else class="call-list"><li v-for="event in model.events" :key="event.seq"><details class="log-detail"><summary><component :is="statusIcon(event.status)" :size="14" /><span>{{ event.label || event.kind }}</span><time>{{ time(event.at) }}</time></summary><div class="event-detail"><p>{{ actorName(event.agentId) }} · {{ event.status === 'RUNNING' ? '已开始' : agentStateText(event.status) }}</p><p v-if="event.kind === 'HANDOFF' && AGENTS[event.fromAgent] && AGENTS[event.toAgent]">{{ AGENTS[event.fromAgent].name }} → {{ AGENTS[event.toAgent].name }}</p><pre v-if="event.detail">{{ event.detail }}</pre></div></details></li></ol>
      <p v-if="model.serviceActivity" class="service-activity" role="status"><AgentStatusIndicator status="RUNNING" /><span>业务服务 · {{ model.serviceActivity }}</span></p>
      <p v-if="model.truncated" class="inspector-notice">记录达到显示上限，后续事件未列出。</p>
    </div>
  </section>
</template>

<style scoped>
.agent-inspector { height:100%; min-height:0; display:flex; flex-direction:column; background:var(--aw-surface); color:var(--aw-text); font:400 13px/1.7 var(--aw-font-sans); }
.inspector-header { display:flex; align-items:center; justify-content:space-between; height:var(--aw-header-height); padding:0 20px; border-bottom:1px solid var(--aw-border); flex-shrink:0; }
.inspector-header > span { display:flex; gap:10px; align-items:center; font-size:17px; font-weight:500; }
.inspector-header > span svg { color:var(--aw-muted); }
.current-task { padding:12px 20px; font-size:12px; flex-shrink:0; }
.current-task > div { display:flex; justify-content:space-between; gap:12px; }
.current-task > div + div { margin-top:4px; }
.current-task > div > span:first-child { color:var(--aw-subtle); }
.current-task > div > span:last-child { color:var(--aw-muted); font-weight:500; }
.icon-button { display:grid; place-items:center; width:30px; height:30px; border:0; border-radius:var(--mes-radius-sm); color:var(--aw-muted); background:transparent; cursor:pointer; transition:background var(--mes-motion-fast); }
.icon-button:hover { color:var(--aw-text); background:var(--aw-hover); }
.inspector-tabs { display:flex; padding:0 20px; gap:24px; border-bottom:1px solid var(--aw-border); flex-shrink:0; }
.inspector-tabs button { display:flex; gap:7px; align-items:center; padding:13px 0; color:var(--aw-muted); border:0; border-bottom:2px solid transparent; background:transparent; font:500 13px var(--aw-font-sans); cursor:pointer; transition:color var(--mes-motion-fast); }
.inspector-tabs button[aria-selected='true'] { color:var(--mes-accent); border-bottom-color:var(--mes-accent); }
.inspector-tabs button span { font-size:12px; color:var(--aw-subtle); font-variant-numeric:tabular-nums; }
.inspector-body { overflow:auto; padding:12px 12px 24px; min-height:0; }
.inspector-body[hidden] { display:none; }
.inspector-notice, .inspector-empty { margin:0; padding:16px 8px; color:var(--aw-muted); font-size:13px; }
.inspector-idle { padding:40px 16px; }
.idle-symbol { display:grid; place-items:center; width:44px; height:44px; border:1px solid var(--aw-border); background:var(--aw-bg); border-radius:var(--mes-radius-md); color:var(--mes-accent); margin-bottom:18px; }
.inspector-idle strong { display:block; font-size:14px; font-weight:500; }
.inspector-idle p { margin:8px 0 0; color:var(--aw-subtle); font-size:13px; line-height:1.85; }
.inspector-role { margin-bottom:6px; border-radius:var(--mes-radius-sm); }
summary { cursor:pointer; list-style:none; }
summary::-webkit-details-marker { display:none; }
.inspector-role > summary { display:flex; gap:10px; align-items:center; min-height:48px; padding:11px 8px; border-radius:var(--mes-radius-sm); transition:background var(--mes-motion-fast); }
.inspector-role > summary:hover { background:var(--aw-hover); }
.inspector-role.running > summary { background:var(--mes-accent-soft); }
.role-name { flex:1; min-width:0; font-size:14px; font-weight:500; }
.role-icon, .role-chevron { flex-shrink:0; color:var(--aw-muted); }
.role-chevron { transition:transform var(--mes-motion-normal) var(--mes-ease); }
.role-status { color:var(--aw-subtle); font-size:12px; white-space:nowrap; }
.completed > summary .role-icon, .completed > summary .role-status { color:var(--aw-success); }
.failed > summary .role-icon, .invalidated > summary .role-icon { color:var(--aw-danger); }
.running > summary .role-status, .running > summary .role-icon { color:var(--mes-accent); }
.inspector-role[open] > summary .role-chevron { transform:rotate(90deg); }
.role-content { padding:12px 8px 16px 18px; margin-left:16px; border-left:1px solid var(--aw-border); }
.role-description { margin:0 0 10px; color:var(--aw-muted); font-size:12px; line-height:1.8; }
.inspector-steps, .event-log, .call-list { margin:0; padding:0; list-style:none; }
.inspector-steps > li { padding:10px 0; }
.step-label { display:flex; align-items:flex-start; gap:7px; font-size:13px; font-weight:500; }
.step-label svg { margin-top:4px; flex-shrink:0; }
.step-label span { flex:1; }
.step-label small { color:var(--aw-subtle); font-size:12px; font-weight:400; white-space:nowrap; }
.step-preview { display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden; color:var(--aw-muted); font-size:13px; margin:6px 0; overflow-wrap:anywhere; }
.log-detail { margin-top:8px; color:var(--aw-muted); font-size:12px; }
.log-detail > summary { display:flex; align-items:center; gap:6px; }
.log-detail > summary:hover { color:var(--mes-accent); }
.log-detail > summary > span { margin-left:auto; font-size:12px; }
.log-detail pre, .event-log, .event-detail { max-height:256px; overflow:auto; padding:10px; margin:8px 0 0; background:var(--aw-bg); border:1px solid var(--mes-border-subtle); border-radius:var(--mes-radius-sm); color:var(--aw-muted); white-space:pre-wrap; overflow-wrap:anywhere; font:12px/1.8 var(--aw-font-mono); }
.log-detail dl { display:grid; grid-template-columns:60px minmax(0,1fr); gap:6px 8px; margin:10px 0; font:12px/1.8 var(--aw-font-mono); }
.log-detail dd { margin:0; overflow-wrap:anywhere; }
.event-log li + li { border-top:1px solid var(--aw-border); padding-top:8px; margin-top:8px; }
.event-log p, .event-detail p { margin:4px 0; }
.agent-summary { white-space:pre-wrap; overflow-wrap:anywhere; max-height:256px; overflow:auto; }
.query-result { margin:12px 0; padding:12px; background:var(--aw-bg); border:1px solid var(--aw-border); border-radius:var(--mes-radius-sm); font-size:13px; overflow-wrap:anywhere; }
.query-result h4 { display:flex; flex-wrap:wrap; gap:6px; margin:0 0 8px; font-size:13px; font-weight:500; color:var(--aw-text); }
.query-result h4 span { margin-left:auto; color:var(--aw-muted); font-size:12px; font-weight:400; }
.query-result p { margin:6px 0; }
.query-result ul { list-style:none; margin:8px 0; padding:0; }
.query-result li + li { border-top:1px solid var(--mes-border-subtle); padding-top:7px; margin-top:7px; }
.query-result .result-note { color:var(--aw-muted); font-size:12px; }
.summary-preview { display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:3; overflow:hidden; }
.call-list > li { padding:6px 0 12px; border-bottom:1px solid var(--aw-border); }
.call-list summary > span { flex:1; margin:0; font-size:13px; }
.call-list time { flex:none; font:12px var(--aw-font-mono); color:var(--aw-subtle); }
.business-service { padding:12px 8px; }
.business-service h3 { margin:0 0 8px; font-size:14px; font-weight:500; }
.service-activity { display:flex; align-items:flex-start; gap:7px; margin:8px 0; color:var(--aw-muted); font-size:13px; }
.service-activity svg { flex-shrink:0; margin-top:4px; }
button:focus-visible, summary:focus-visible { outline:2px solid var(--mes-accent); outline-offset:2px; border-radius:4px; }
@media (prefers-reduced-motion:reduce) { * { transition:none !important; } }
</style>
