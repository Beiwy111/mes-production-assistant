<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { LabelLayout } from 'echarts/features'
import { CanvasRenderer } from 'echarts/renderers'
import { layoutGraph, neighborhood, nodeTypes } from '@/utils/workbenchGraph'
import { graphSymbol } from './graphSymbols'
import { nodeDiameter, spatialLayout } from './graphLayout'

use([GraphChart, TooltipComponent, LabelLayout, CanvasRenderer])
const props = defineProps({
  graph: { type: Object, required: true }, layout: { type: String, default: 'spatial' },
  selected: String, depth: { type: Number, default: 1 }, path: { type: Array, default: () => [] },
})
const emit = defineEmits(['select', 'zoom'])
const element = ref(null)
const seriesId = 'knowledge'
const fontFamily = '"MES Inter", Inter, "PingFang SC", "Noto Sans SC Variable", "Microsoft YaHei", sans-serif'
const provenance = new Set(['支持', '向量化', '提及'])
let chart, observer, resizeFrame, lastGraph, lastLayout, lastWidth, lastHeight
let lastSelection, lastDepth, lastPath
let positions = new Map(), pending = false, navigated = false
const series = () => chart?.getModel()?.getSeriesByIndex(0)

function graphPosition(id) {
  const point = series()?.getGraph().getNodeById(id)?.getLayout()
  return point?.every(Number.isFinite) ? point : null
}
function sameRecords(a, b) {
  return a === b || (a?.length === b?.length && a?.every((record, i) => record === b[i]))
}
function tooltip({ data, dataType }) {
  const content = document.createElement('div')
  const heading = document.createElement('div')
  heading.style.cssText = 'color:#4B5563;font-size:12px;margin-bottom:3px'
  heading.textContent = dataType === 'edge' ? '节点关系' : data.categoryName
  const name = document.createElement('strong')
  name.textContent = data.name
  content.append(heading, name)
  return content
}
function viewport(width, height, count) {
  const insetX = Math.min(width * 0.17, 80)
  const insetY = Math.min(height * 0.18, 70)
  const w = Math.min(Math.max(1, width - 2 * insetX), Math.sqrt(count) * 164)
  const h = Math.min(Math.max(1, height - 2 * insetY), Math.sqrt(count) * 120)
  return { width: w, height: h, left: (width - w) / 2, top: (height - h) / 2 - 3 }
}

function render(explicitReset = false) {
  if (!chart || !element.value?.clientWidth || !element.value?.clientHeight) {
    pending = true
    return
  }
  pending = false
  const { nodes, edges } = props.graph
  const width = chart.getWidth(), height = chart.getHeight()
  const reset = explicitReset || lastLayout !== props.layout ||
    !sameRecords(lastGraph?.nodes, nodes) || !sameRecords(lastGraph?.edges, edges)
  // Increasing the neighbourhood can add off-page nodes. Keep the inspected
  // node under the pointer at the current zoom while the surrounding graph fits.
  const inspectionChanged = props.selected !== lastSelection || props.depth !== lastDepth || props.path !== lastPath
  const priorPoint = !explicitReset && props.layout === lastLayout && props.selected && inspectionChanged
    ? graphPosition(props.selected) : null
  const anchor = priorPoint && chart.convertToPixel({ seriesId }, priorPoint)
  const priorZoom = anchor ? series().get('zoom') : 1
  const box = viewport(width, height, nodes.length)
  if (reset || width !== lastWidth || height !== lastHeight) {
    if (props.layout === 'layered') {
      positions = new Map(layoutGraph(nodes, edges).nodes.map(n => [n.id, [n.y, n.x]]))
    } else {
      positions = spatialLayout(nodes, edges, box.width, box.height)
    }
  }
  lastGraph = props.graph
  lastLayout = props.layout
  lastWidth = width
  lastHeight = height
  lastSelection = props.selected
  lastDepth = props.depth
  lastPath = props.path
  const pathOrder = new Map(props.path.map((id, i) => [id, i]))
  const highlighted = pathOrder.size ? new Set(props.path) : neighborhood(props.graph, props.selected, props.depth)
  const degree = new Map(nodes.map(node => [node.id, 0]))
  for (const { from, to } of edges) {
    degree.set(from, (degree.get(from) || 0) + 1)
    degree.set(to, (degree.get(to) || 0) + 1)
  }
  const labelWidth = Math.min(156, Math.max(70, Math.sqrt(width * height / Math.max(1, nodes.length)) * 0.84))
  const data = nodes.map(node => {
    const selected = node.id === props.selected
    const dim = highlighted.size > 0 && !highlighted.has(node.id)
    const [x, y] = (!reset && graphPosition(node.id)) || positions.get(node.id)
    const densityScale = Math.max(0.3, Math.min(1, Math.sqrt(50 / Math.max(1, nodes.length))))
    const size = selected ? nodeDiameter(node.type, degree.get(node.id), true) : Math.max(node.type === 'document' ? 18 : 7, nodeDiameter(node.type, degree.get(node.id), false) * densityScale)
    return {
      id: node.id, name: node.label, value: degree.get(node.id), x, y,
      categoryName: (nodeTypes[node.type] || nodeTypes.entity).label,
      symbol: graphSymbol(node.type, selected), symbolSize: size,
      itemStyle: { opacity: dim ? 0.26 : 1 },
      label: {
        show: nodes.length <= 100 || selected || node.type === 'document' || highlighted.has(node.id),
        color: selected ? '#163B85' : '#1F2937', opacity: dim ? 0.30 : 1,
        fontSize: node.type === 'document' || selected ? 14 : 13,
        fontWeight: selected ? 600 : 400, width: selected ? Math.max(labelWidth, 134) : labelWidth,
      },
    }
  })
  const dense = edges.length > 30 || nodes.length > 20
  const links = edges.map(({ from, to, label }) => {
    const active = pathOrder.size ? Math.abs(pathOrder.get(from) - pathOrder.get(to)) === 1 :
      highlighted.has(from) && highlighted.has(to)
    const evidence = provenance.has(label)
    const showName = active && (pathOrder.size > 0 || (!dense && (from === props.selected || to === props.selected)))
    return {
      source: from, target: to, name: label,
      symbolSize: active ? 7 : dense ? 3.5 : 5,
      lineStyle: {
        color: active ? '#2457E6' : evidence ? '#669A98' : '#8294A9',
        width: active ? 2 : dense ? 1 : 1.45, type: evidence ? 'dashed' : 'solid',
        opacity: active ? 0.92 : highlighted.size ? 0.15 : dense ? 0.73 : 0.83,
        curveness: evidence ? 0.09 : 0.035,
      },
      label: { show: showName, formatter: label },
    }
  })
  chart.setOption({
    animation: false, textStyle: { fontFamily },
    tooltip: {
      trigger: 'item', confine: true, formatter: tooltip, transitionDuration: 0,
      backgroundColor: '#fff', borderColor: '#D8E0EA', borderWidth: 1, padding: [8, 12],
      textStyle: { color: '#111827', fontSize: 13, fontFamily },
      extraCssText: 'max-width:min(320px,80%);white-space:normal;overflow-wrap:anywhere;line-height:1.45;box-shadow:0 3px 12px #162a481a',
    },
    series: [{
      id: seriesId, type: 'graph', layout: ['force', 'circular'].includes(props.layout) ? props.layout : 'none',
      data, links, left: box.left, right: box.left, top: box.top, bottom: height - box.top - box.height,
      roam: true, draggable: true, nodeScaleRatio: 0.5, scaleLimit: { min: 0.25, max: 8 },
      ...(reset ? { zoom: priorZoom } : {}),
      force: { initLayout: 'none', repulsion: [150, 420], edgeLength: 88, gravity: 0.11, friction: 0.5, layoutAnimation: false },
      edgeSymbol: ['none', 'arrow'],
      label: { show: true, position: 'bottom', distance: 2, overflow: 'truncate', ellipsis: '…',
        lineHeight: 18, textBorderColor: '#fff', textBorderWidth: 3.5 },
      edgeLabel: { fontFamily, color: '#163B85', fontSize: 12, backgroundColor: '#fff', padding: [1, 3] },
      labelLayout: { hideOverlap: true },
      emphasis: { focus: 'adjacency', scale: 1.12, itemStyle: { opacity: 1 },
        label: { show: true, color: '#163B85', opacity: 1 }, lineStyle: { color: '#2457E6', opacity: 1, width: 2 } },
      blur: { itemStyle: { opacity: 0.30 }, label: { opacity: 0.35 }, lineStyle: { opacity: 0.18 } },
    }],
  }, { notMerge: reset })
  if (anchor) {
    const position = graphPosition(props.selected)
    if (position) {
      const [x, y] = chart.convertToPixel({ seriesId }, position)
      if (Math.abs(anchor[0] - x) + Math.abs(anchor[1] - y) > 0.5) roam({ dx: anchor[0] - x, dy: anchor[1] - y })
    }
  }
  if (reset) {
    navigated = Boolean(anchor)
    emit('zoom', priorZoom)
  }
}
function roam(fields) {
  chart?.dispatchAction({ type: 'graphRoam', seriesId, ...fields })
}
function zoom(factor) {
  if (chart) roam({ zoom: factor, originX: chart.getWidth() / 2, originY: chart.getHeight() / 2 })
}
function focus(id) {
  resize()
  const point = graphPosition(id)
  if (!point) return
  const pixel = chart.convertToPixel({ seriesId }, point)
  if (pixel?.every(Number.isFinite)) roam({ dx: chart.getWidth() / 2 - pixel[0], dy: chart.getHeight() / 2 - pixel[1] })
}
function reset() { render(true) }
defineExpose({ zoom, focus, reset })

function initialize() {
  chart = init(element.value)
  chart.on('click', p => { if (p.dataType === 'node') emit('select', p.data.id) })
  chart.on('dblclick', p => {
    if (p.dataType === 'node') {
      emit('select', p.data.id)
      focus(p.data.id)
    }
  })
  chart.on('graphroam', () => {
    navigated = true
    emit('zoom', series()?.get('zoom') ?? 1)
  })
  chart.getZr().on('dragend', () => { navigated = true })
  render(true)
}
function resize() {
  const host = element.value
  if (!host?.clientWidth || !host.clientHeight) return
  if (!chart) {
    initialize()
    return
  }
  if (chart.getWidth() !== host.clientWidth || chart.getHeight() !== host.clientHeight) {
    chart.resize()
    render(!navigated)
  } else if (pending) render(true)
}
onMounted(() => {
  resize()
  observer = new ResizeObserver(() => {
    cancelAnimationFrame(resizeFrame)
    resizeFrame = requestAnimationFrame(resize)
  })
  observer.observe(element.value)
})
watch([() => props.graph, () => props.layout, () => props.selected, () => props.depth, () => props.path],
  () => render(), { flush: 'post' })
onBeforeUnmount(() => {
  observer?.disconnect()
  cancelAnimationFrame(resizeFrame)
  chart?.dispose()
  chart = null
})
</script>

<template><div ref="element" class="kg-chart" :data-node-count="graph.nodes.length" :data-edge-count="graph.edges.length" role="img" aria-label="交互知识图谱，可通过搜索节点或节点列表浏览"/></template>
<style scoped>
.kg-chart { position:absolute; inset:0; touch-action:none; }
</style>
