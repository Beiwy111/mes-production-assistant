<script setup lang="ts">
import { computed } from 'vue'
import { Check, CircleAlert, Minus, Clock3, Circle } from 'lucide-vue-next'
const props = withDefaults(defineProps<{ status?: string; animate?: boolean }>(), { status: 'UNCALLED', animate: true })
const state = computed(() => props.status.toUpperCase())
const icon = computed(() => state.value === 'COMPLETED' ? Check
  : ['FAILED', 'INVALIDATED'].includes(state.value) ? CircleAlert
  : ['CANCELLED', 'INTERRUPTED', 'SKIPPED'].includes(state.value) ? Minus
  : ['WAITING', 'QUEUED', 'PENDING'].includes(state.value) ? Clock3 : Circle)
</script>

<template>
  <span class="agent-status-indicator" :class="state.toLowerCase()" :data-status="state" aria-hidden="true">
    <svg v-if="state === 'RUNNING'" class="status-ring" :class="{ rotating: animate }" viewBox="0 0 20 20" fill="none">
      <circle cx="10" cy="10" r="7.5" stroke="currentColor" stroke-width="2" opacity=".18" />
      <path d="M10 2.5a7.5 7.5 0 0 1 7.5 7.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
    </svg>
    <component v-else :is="icon" :size="17" :stroke-width="2" />
  </span>
</template>

<style scoped>
.agent-status-indicator { display:inline-flex; align-items:center; justify-content:center; width:18px; height:18px; flex:0 0 18px; color:var(--aw-subtle,#778193); vertical-align:-3px; transition:color .16s ease; }
.agent-status-indicator.running { color:var(--mes-accent,#7460a6); }
.agent-status-indicator.completed { color:var(--aw-success,#348061); }
.agent-status-indicator.failed,.agent-status-indicator.invalidated { color:var(--aw-danger,#bf4c4c); }
.agent-status-indicator svg { display:block; width:17px; height:17px; }
.status-ring.rotating { transform-origin:center; animation:agent-ring-rotate 1s linear infinite, agent-ring-breathe 1.8s ease-in-out infinite; }
@keyframes agent-ring-rotate { to { transform:rotate(360deg); } }
@keyframes agent-ring-breathe { 0%,100% { opacity:1; } 50% { opacity:.76; } }
@media (prefers-reduced-motion:reduce) { .status-ring.rotating { animation:none; } .agent-status-indicator { transition:none; } }
</style>
