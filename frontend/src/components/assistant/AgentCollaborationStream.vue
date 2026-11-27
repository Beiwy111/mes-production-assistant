<script setup>
import { computed, ref, watch } from 'vue'
import { ChevronRight, Wrench, ArrowRightLeft } from 'lucide-vue-next'
import { collaborationTimeline } from '@/utils/collaborationTimeline'
import { agentStateText } from '@/utils/agentInspector'
import AgentAvatar from './AgentAvatar.vue'
import AgentStatusIndicator from './AgentStatusIndicator.vue'

const props = defineProps({ message: { type: Object, required: true } })
const model = computed(() => collaborationTimeline(props.message))
const expanded = ref(model.value.live)
watch(() => model.value.live, live => { expanded.value = live })
const active = computed(() => [...new Set(model.value.rows.filter(r => r.status === 'RUNNING').map(r => r.name))])
const title = computed(() => model.value.live ? active.value.length ? `${active.value.join('、')}正在协作` : '实时协作记录' : '查看协作过程')
const roleLabel = row => row.agentId === 'SUP' ? '规划协调' : row.agentId === 'SERVICE' ? '执行服务' : '业务专家'
</script>

<template>
  <details v-if="model.rows.length || model.notice" class="collaboration-stream" :class="{ 'is-live': model.live }" :open="expanded" aria-label="实时 Agent 协作流">
    <summary @click.prevent="expanded = !expanded">
      <AgentStatusIndicator v-if="model.live" status="RUNNING" />
      <ChevronRight v-else :size="15" class="expand-arrow" />
      <span>{{ title }}</span>
    </summary>
    <p v-if="model.notice" class="notice">{{ model.notice }}</p>
    <ol v-else class="collaboration-entries" aria-label="实际执行步骤">
      <li v-for="row in model.rows" :key="row.id" class="collaboration-entry" :class="{ working: row.status === 'RUNNING', failed: row.status === 'FAILED' }" :data-agent="row.agentId" :data-status="row.status">
        <div class="entry-avatar"><AgentAvatar v-if="row.agentId !== 'SERVICE'" :agent-id="row.agentId" :active="row.status === 'RUNNING'" /><Wrench v-else :size="19" /></div>
        <div class="entry-body">
          <div class="entry-meta"><strong>{{ row.name }}</strong><span class="role-label">{{ roleLabel(row) }}</span><span class="entry-state"><AgentStatusIndicator :status="row.status" />{{ agentStateText(row.status) }}</span></div>
          <p class="entry-task"><ArrowRightLeft v-if="row.kind === 'HANDOFF'" :size="13" />{{ row.label }}</p>
          <p v-if="row.request && row.request !== row.label" class="entry-description">{{ row.request }}</p>
          <p v-if="row.result && row.result !== row.request" class="entry-result">{{ row.result }}</p>
          <details v-if="row.toolId && row.toolId !== 'TASK_TASK_PLAN' && row.kind !== 'HANDOFF'" class="tool-reference"><summary><Wrench :size="12" />工具调用</summary><code>{{ row.toolId }}</code></details>
        </div>
      </li>
    </ol>
    <p v-if="model.truncated" class="notice">部分历史事件未保存在当前消息中。</p>
  </details>
</template>

<style scoped>
.collaboration-stream { margin: 4px 0 20px; color: var(--aw-text); font: 13px/1.7 var(--aw-font-sans); min-width: 0; }
summary { cursor: pointer; list-style: none; }
summary::-webkit-details-marker { display: none; }
.collaboration-stream > summary { display: flex; align-items: center; gap: 8px; padding: 8px 0; color: var(--aw-muted); width: fit-content; max-width: 100%; }
.collaboration-stream[open] > summary .expand-arrow { transform: rotate(90deg); }
.collaboration-stream.is-live > summary { color: var(--aw-text); }
.collaboration-entries { list-style: none; padding: 8px 0; margin: 0; }
.collaboration-entry { position: relative; display: flex; gap: 14px; padding: 10px 0 12px; animation: entry-appear .22s ease-out; }
.collaboration-entry:not(:last-child)::before { content: ''; position: absolute; top: 49px; left: 17px; bottom: 0; width: 1px; background: var(--aw-border); }
.entry-avatar { --avatar-size: 34px; width: 34px; flex: none; display: flex; align-items: flex-start; justify-content: center; color: var(--aw-muted); padding-top: 2px; }
.entry-body { flex: 1; min-width: 0; }
.entry-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; }
.entry-meta strong { font-size: 13px; font-weight: 500; }
.role-label { font-size: 11px; color: var(--aw-muted); padding: 0 6px; background: var(--aw-panel); border-radius: 4px; }
.entry-state { display: flex; align-items: center; gap: 4px; margin-left: auto; color: var(--aw-muted); font-size: 11px; }
.entry-state :deep(svg) { width: 13px; height: 13px; }
.entry-task { display: flex; align-items: center; gap: 6px; margin: 4px 0 0; font-weight: 500; }
.entry-description, .entry-result { margin: 3px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--aw-muted); }
.entry-result { color: var(--aw-text); }
.failed .entry-result { color: var(--aw-danger); }
.tool-reference { margin-top: 4px; font-size: 11px; color: var(--aw-muted); }
.tool-reference > summary { display: flex; align-items: center; gap: 4px; width: fit-content; }
.tool-reference code { font: 11px/1.6 var(--aw-font-mono); overflow-wrap: anywhere; }
.notice { color: var(--aw-muted); }
summary:focus-visible { outline: 2px solid var(--aw-subtle); outline-offset: 3px; }
@keyframes entry-appear { from { opacity: .3; transform: translateY(3px); } to { opacity: 1; transform: translateY(0); } }
@media (prefers-reduced-motion: reduce) { .collaboration-entry { animation: none; } }
@media (max-width: 650px) { .collaboration-entry { gap: 10px; } .entry-state { margin-left: 0; } }
</style>
