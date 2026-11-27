<script setup>
import { computed } from 'vue'
const props = defineProps({ message: { type: Object, required: true } })
const stages = computed(() => {
  const latest = new Map()
  for (const event of props.message.workflow?.events || []) {
    if (event.kind !== 'CALCULATION') continue
    latest.set(event.key, { ...event, status: event.status === 'RUNNING' && props.message.status !== 'RUNNING' ? 'INTERRUPTED' : event.status })
  }
  return [...latest.values()]
})
const labels = { RUNNING: '计算中', COMPLETED: '已完成', FAILED: '未完成', INTERRUPTED: '已中断' }
</script>
<template>
  <details v-if="stages.length" class="calculation-flow" :open="message.status === 'RUNNING'">
    <summary><span class="flow-symbol">⌘</span> 生产计算过程 <span class="flow-state">{{ message.status === 'RUNNING' ? '实时核算' : '查看计算依据' }}</span></summary>
    <div class="calculation-grid">
      <article v-for="stage in stages" :key="stage.key" :class="['calculation-node', stage.status.toLowerCase()]">
        <div class="node-heading"><span class="node-dot"/><strong>{{ stage.label }}</strong><span>{{ labels[stage.status] }}</span></div>
        <p>{{ stage.detail }}</p>
      </article>
    </div>
  </details>
</template>
<style scoped>
.calculation-flow { margin: 10px 0 18px; border: 1px solid var(--aw-border); border-radius: 12px; background: #fff; font-size: 13px; }
summary { padding: 13px 16px; cursor: pointer; color: var(--aw-text); }
.flow-symbol { color: #4564d9; margin-right: 6px; }
.flow-state { color: var(--aw-muted); font-size: 12px; margin-left: 12px; }
.calculation-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(min(100%,240px),1fr)); gap: 10px; padding: 0 14px 14px; }
.calculation-node { border: 1px solid var(--aw-border); border-radius: 8px; padding: 12px; background: #fafbfe; min-width: 0; transition: border-color .2s,background .2s; }
.node-heading { display:flex; gap:8px; align-items:center; }
.node-heading strong { font-size:13px;font-weight:500;flex:1; }
.node-heading > span:last-child { font-size:11px;color:var(--aw-muted);white-space:nowrap; }
.node-dot { width:7px;height:7px;border-radius:50%;background:#298367;flex-shrink:0; }
p { color:var(--aw-muted);font-size:12px;line-height:1.7;margin:8px 0 0;overflow-wrap:anywhere; }
.running { border-color:#8599ee;background:#f5f7ff; }
.running .node-dot { background:#4564d9;animation:calculation-pulse 1.2s ease-in-out infinite; }
.failed .node-dot,.interrupted .node-dot { background:#ba5b40; }
@keyframes calculation-pulse { 50% { opacity:.35;transform:scale(.8); } }
@media(prefers-reduced-motion:reduce) { .running .node-dot { animation:none; } }
</style>
