<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { GitBranch } from 'lucide-vue-next'
import catalog from 'virtual:mes-workflows'
import { inspectAgentMessage } from '../../../utils/agentInspector.js'
import { flowGraph, overviewGraph } from './workflowModel'
import WorkflowCanvas from './WorkflowCanvas.vue'
import WorkflowDetailPanel from './WorkflowDetailPanel.vue'
import WorkflowListPanel from './WorkflowListPanel.vue'
import WorkflowToolbar from './WorkflowToolbar.vue'

const props = defineProps({ message: Object })
const overview = overviewGraph(catalog)
const active = ref('overview'), selected = ref('SUP'), scope = ref('')
const direction = ref('horizontal'), mainOnly = ref(false), revision = ref(0)
const libraryOpen = ref(false), detailsOpen = ref(false), canvas = ref(null)
const observation = computed(() => inspectAgentMessage(props.message))
const roles = computed(() => props.message ? Object.fromEntries(observation.value.roles.map(role => [role.agentId, role])) : {})
const isOverview = computed(() => active.value === 'overview')
const flow = computed(() => catalog.find(f => f.name === active.value))
const graph = computed(() => isOverview.value ? overview : flowGraph(flow.value, scope.value, mainOnly.value))
const current = computed(() => graph.value.nodes.find(n => n.id === selected.value))
const parent = computed(() => flow.value?.nodes.find(n => n.id === scope.value))
const childCount = computed(() => current.value && flow.value?.nodes.filter(n => n.parent === current.value.id).length || 0)
const canvasKey = computed(() => [active.value, scope.value, direction.value, mainOnly.value, revision.value].join('/'))
function select(id, focus = false) {
  selected.value = id
  detailsOpen.value = Boolean(id)
  if (focus && id) nextTick(() => canvas.value?.focus(id))
}
function choose(name) {
  active.value = name
  scope.value = ''
  mainOnly.value = false
  libraryOpen.value = false
  detailsOpen.value = false
  selected.value = name === 'overview' ? 'SUP' : graph.value.nodes[0]?.id || ''
}
function open(id) {
  const node = graph.value.nodes.find(n => n.id === id)
  if (node?.flowName) return choose(node.flowName)
  if (flow.value?.nodes.some(n => n.parent === id)) {
    scope.value = id
    selected.value = ''
    detailsOpen.value = false
  } else select(id, true)
}
function back() {
  const from = scope.value
  scope.value = parent.value?.parent || ''
  select(from, true)
}
watch(graph, value => {
  if (!value.nodes.some(n => n.id === selected.value)) select('')
})
</script>
<template>
  <div class="workflow-studio">
    <div class="columns">
      <button v-if="libraryOpen" class="scrim" aria-label="收起目录" @click="libraryOpen=false"/>
      <WorkflowListPanel class="catalog" :class="{revealed:libraryOpen}" :catalog="catalog" :active="active"
        :states="roles" @choose="choose" @close="libraryOpen=false"/>
      <main class="center">
        <WorkflowToolbar :title="isOverview ? 'Agent 协作总览' : flow.label" :direction="direction"
          :parent-title="parent?.label" :overview="isOverview" :main-only="mainOnly" :nodes="graph.nodes"
          :selected="selected" @direction="direction=$event" @main="mainOnly=$event"
          @reset="revision++" @locate="select($event,true)" @focus="canvas?.focus(selected)"
          @back="back" @library="libraryOpen=true"/>
        <WorkflowCanvas ref="canvas" :key="canvasKey" :graph="graph" :overview="isOverview" :states="roles"
          :direction="direction" :selected="selected" @select="select" @open="open"/>
        <footer><GitBranch :size="12"/><span>{{ isOverview ? 'Agent 调度关系' : flow.name + '.yml' }}</span><i/>
          {{ graph.nodes.length }} 节点 <b>·</b> {{ graph.edges.length }} 连线
          <template v-if="mainOnly"><b>·</b> 最长结构路径</template>
        </footer>
      </main>
      <WorkflowDetailPanel class="details" :class="{revealed:detailsOpen}"
        :node="current" :role="isOverview && current ? roles[current.id] : undefined"
        :graph="graph" :notice="observation.invalidated || observation.omitted ? observation.notice : ''"
        :child-count="childCount" @close="select('')" @select="select($event,true)" @open="open"/>
    </div>
  </div>
</template>
<style scoped>
.workflow-studio{container:workflow / inline-size;display:flex;flex:1;width:100%;height:100%;min-height:0;min-width:0;overflow:hidden;color:#213d48;font-family:var(--aw-font-sans,inherit);line-height:1.45}
.columns{width:100%;display:grid;grid-template-columns:clamp(220px,17.5%,285px) minmax(0,1fr) clamp(265px,20%,340px);position:relative;overflow:hidden}
.center{display:flex;flex-direction:column;min-width:0;min-height:0}.scrim{display:none}
footer{flex:none;display:flex;align-items:center;gap:6px;background:#fff;border-top:1px solid #dfe8eb;padding:6px 12px;font-size:11px;color:#637781}footer i{margin-left:auto}footer b{font-weight:400}footer span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
@container workflow (min-width:881px) and (max-width:1150px){.columns{grid-template-columns:192px minmax(0,1fr) 228px}}
@container workflow (max-width:880px){
  .columns{grid-template-columns:minmax(0,1fr) 222px}
  .catalog{display:none}.catalog.revealed{position:absolute;inset:0 auto 0 0;width:min(278px,86%);display:flex;z-index:6;box-shadow:4px 0 22px #1a39451a}
  .scrim{display:block;position:absolute;inset:0;background:#17374130;border:0;z-index:5}
}
@container workflow (max-width:650px){
  .columns{grid-template-columns:minmax(0,1fr);grid-template-rows:minmax(0,1fr) auto}
  .details{display:none}.details.revealed{display:flex;max-height:32dvh;border-left:0;border-top:1px solid #dce6e9}.details.revealed :deep(.section-title){height:32px}
}
</style>
