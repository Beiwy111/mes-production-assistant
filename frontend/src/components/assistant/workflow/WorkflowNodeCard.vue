<script setup>
import { Handle, Position } from '@vue-flow/core'
import { ChevronRight } from 'lucide-vue-next'
import { agentStateText } from '../../../utils/agentInspector.js'
import { colorByTone, typeInfo } from './workflowModel'
import WorkflowGlyph from './WorkflowGlyph.vue'
defineProps({ data: Object, vertical: Boolean, current: Boolean, status: String })
</script>
<template>
  <div class="node-card" :class="{active:current, running:status==='RUNNING'}"
       :style="{'--node-accent': colorByTone[typeInfo(data.kind)[2]]}" :title="data.label">
    <Handle type="target" :position="vertical ? Position.Top : Position.Left" :connectable="false"/>
    <div class="meta">
      <span class="kind"><WorkflowGlyph :kind="data.flowName ? data.id : data.kind" :size="14"/>{{ typeInfo(data.kind)[0] }}</span>
      <span class="status" :data-status="status"><i/>{{ agentStateText(status || 'UNKNOWN') }}</span>
    </div>
    <strong>{{ data.label }}</strong>
    <span class="caption">{{ data.detail || typeInfo(data.kind)[1] }}</span>
    <ChevronRight v-if="data.flowName || data.kind==='iteration'" class="drill" :size="13"/>
    <Handle type="source" :position="vertical ? Position.Bottom : Position.Right" :connectable="false"/>
  </div>
</template>
<style scoped>
.node-card{position:relative;box-sizing:border-box;width:228px;height:98px;background:#fff;border:1px solid #d2dee4;border-radius:13px;padding:11px 14px;text-align:left;color:#223b47;box-shadow:0 3px 9px #203f4910;transition:border-color .16s,box-shadow .16s}
.node-card:hover,.node-card.active{border-color:var(--node-accent);box-shadow:0 0 0 3px color-mix(in srgb,var(--node-accent) 15%,transparent),0 4px 12px #17363f12}
.node-card.running{animation:wf-pulse 2s ease-in-out infinite}
.meta,.kind,.status{display:flex;align-items:center}.meta{justify-content:space-between;gap:5px;margin-bottom:7px}
.kind{gap:5px;background:color-mix(in srgb,var(--node-accent) 9%,white);border-radius:5px;padding:2px 6px;color:var(--node-accent);font-size:10px;font-weight:650;line-height:15px}
.status{gap:4px;font-size:10px;color:#5d707a}.status i{width:5px;height:5px;border-radius:50%;background:#a4b1b8}
.status[data-status=RUNNING] i{background:#3d86da}.status[data-status=COMPLETED] i{background:#1b986c}.status[data-status=FAILED] i{background:#d55f52}
strong,.caption{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
strong{font-size:15px;line-height:22px;color:#163440}.caption{margin-top:3px;padding-right:7px;color:#637681;font-size:11px;line-height:16px}
.drill{position:absolute;right:7px;bottom:13px;color:#8da1a8}
.node-card :deep(.vue-flow__handle){width:7px;height:7px;border:2px solid white;background:var(--node-accent);pointer-events:none}
@keyframes wf-pulse{50%{box-shadow:0 0 0 4px #498bd523}}
@media(prefers-reduced-motion:reduce){.node-card.running{animation:none}}
</style>
