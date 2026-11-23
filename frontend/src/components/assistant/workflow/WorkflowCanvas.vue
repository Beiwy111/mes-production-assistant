<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { Maximize, Minus, Plus } from 'lucide-vue-next'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'
import { colorByTone, layoutNodes, typeInfo } from './workflowModel'
import WorkflowNodeCard from './WorkflowNodeCard.vue'

const props = defineProps({ graph: Object, direction: String, selected: String, states: Object, overview: Boolean })
const emit = defineEmits(['select', 'open'])
const host = ref(null)
const instance = 'workflow-' + useId()
const { fitView, zoomIn, zoomOut, viewport } = useVueFlow(instance)
const positions = layoutNodes(props.graph, props.direction)
const nodes = ref(props.graph.nodes.map(node => ({
  id: node.id, type: 'card', position: positions.get(node.id), data: node,
  deletable: false, ariaLabel: node.label,
})))
const edges = computed(() => props.graph.edges.map((edge, index) => {
  const touch = edge.from === props.selected || edge.to === props.selected
  const live = props.overview && props.states[edge.to]?.status === 'RUNNING'
  const color = touch || live ? '#358f89' : '#afc1c9'
  return {
    id: 'edge-' + index, source: edge.from, target: edge.to, selectable: false, deletable: false,
    type: 'default', label: props.overview ? '' : edge.label, labelStyle: { fill: '#536873', fontSize: 10 },
    animated: live, style: { stroke: color, strokeWidth: touch || live ? 2.1 : 1.5 },
    markerEnd: { type: MarkerType.ArrowClosed, color },
  }
}))

const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
const duration = reducedMotion ? 0 : 220
const fitOptions = { padding: 0.15, maxZoom: 1.05, minZoom: 0.035 }
function focus(id) {
  if (id) fitView({ nodes: [id], padding: 0.3, maxZoom: 1.12, duration })
}
function selectFromKeyboard(event) {
  const id = event.target.closest('.vue-flow__node')?.dataset.id
  if (id) {
    event.preventDefault()
    emit('select', id)
  }
}
function frame() {
  if (props.overview || nodes.value.length <= 4) return fitView(fitOptions)
  // Very long DSLs otherwise open at an illegible scale. Frame the entry and
  // its first successor. The fit control and minimap always cover the full DAG.
  const entry = props.graph.nodes.find(n => ['start','iteration-start'].includes(n.kind)) || props.graph.nodes[0]
  const next = props.graph.edges.find(e => e.from === entry.id)?.to
  return fitView({ ...fitOptions, nodes: [entry.id, next].filter(Boolean) })
}
let initialized = false, observer, resizeTimer
function initialize() {
  if (initialized) return
  initialized = true
  nextTick(frame)
}
onMounted(() => {
  observer = new ResizeObserver(() => {
    clearTimeout(resizeTimer)
    if (initialized && props.overview) resizeTimer = setTimeout(() => fitView(fitOptions), 110)
  })
  observer.observe(host.value)
})
onBeforeUnmount(() => { observer?.disconnect(); clearTimeout(resizeTimer) })
defineExpose({ focus })
</script>
<template>
  <div ref="host" class="canvas" aria-label="工作流交互画布" @keydown.enter="selectFromKeyboard" @keydown.space="selectFromKeyboard">
    <VueFlow :id="instance" v-model:nodes="nodes" :edges="edges" :min-zoom="0.035" :max-zoom="2.4"
      :nodes-connectable="false" :delete-key-code="null" :multi-selection-key-code="null"
      :zoom-on-double-click="false" :elevate-edges-on-select="false"
      @nodes-initialized="initialize" @node-click="$emit('select',$event.node.id)"
      @node-double-click="$emit('open',$event.node.id)" @pane-click="$emit('select','')">
      <Background pattern-color="#c9d8dc" :gap="19" :size="1.1"/>
      <template #node-card="{ data }">
        <WorkflowNodeCard :data="data" :vertical="direction==='vertical'" :current="selected===data.id"
          :status="overview ? states[data.id]?.status : ''"/>
      </template>
      <Controls :show-interactive="false">
        <template #control-zoom-out><button title="缩小" aria-label="缩小画布" @click="zoomOut({duration})"><Minus :size="15"/></button></template>
        <template #control-zoom-in><button title="放大" aria-label="放大画布" @click="zoomIn({duration})"><Plus :size="15"/></button></template>
        <template #control-fit-view><button title="适应全部节点" aria-label="适应全部节点" @click="fitView({...fitOptions,duration})"><Maximize :size="14"/></button></template>
        <template #default><span class="zoom-value">{{ Math.round(viewport.zoom * 100) }}%</span></template>
      </Controls>
      <MiniMap aria-label="工作流缩略图" pannable zoomable :width="124" :height="77"
        :node-color="n => colorByTone[typeInfo(n.data.kind)[2]]" :node-border-radius="7"
        mask-color="rgba(224,235,237,.62)" mask-stroke-color="#80aea9" :mask-stroke-width="1.3"
        @node-click="$emit('select',$event.node.id); focus($event.node.id)"/>
    </VueFlow>
  </div>
</template>
<style scoped>
.canvas{min-width:0;min-height:0;position:relative;flex:1;background:#f6f9fa;isolation:isolate;animation:reveal-graph .18s ease-out}
.canvas :deep(.vue-flow){position:absolute;inset:0}
.canvas :deep(.vue-flow__node-card){border-radius:13px}
.canvas :deep(.vue-flow__controls){display:flex;align-items:center;gap:2px;padding:4px;margin:12px;border:1px solid #d8e3e7;border-radius:8px;background:white;box-shadow:0 3px 10px #243f4810}
.canvas :deep(.vue-flow__controls button){display:grid;place-items:center;width:29px;height:28px;border:0;border-radius:5px;background:white;color:#48606b;cursor:pointer}
.canvas :deep(.vue-flow__controls button:hover){color:#147971;background:#edf5f4}
.zoom-value{min-width:36px;text-align:center;font-size:10px;color:#657781;font-variant-numeric:tabular-nums}
.canvas :deep(.vue-flow__minimap){margin:12px;border:1px solid #d6e1e4;border-radius:8px;overflow:hidden;box-shadow:0 3px 10px #243f4810}
@keyframes reveal-graph{from{opacity:.35}to{opacity:1}}
@media(prefers-reduced-motion:reduce){.canvas,.canvas :deep(.vue-flow__edge-path){animation:none}}
</style>
