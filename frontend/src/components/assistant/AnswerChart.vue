<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([GridComponent, TooltipComponent, BarChart, LineChart, CanvasRenderer])
const props = defineProps({ chart: { type: Object, required: true } })
const host = ref(null), selected = ref(0)
const series = computed(() => props.chart.series[selected.value] || props.chart.series[0])
const dateLabel = value => new Date(value).toISOString().slice(0, 10)
let instance, observer
function draw() {
  if (!instance) return
  const { kind, unit } = props.chart
  const points = series.value.points
  const line = kind === 'line'
  const valueAxis = { type: 'value', min: 0, minInterval: unit === '条' ? 1 : undefined,
    axisLabel: { formatter: value => unit === '%' ? value + '%' : value }, splitLine: { lineStyle: { color: '#edf0f3' } } }
  instance.setOption({
    useUTC: true, animation: false, color: ['#326f96'], textStyle: { color: '#475467', fontSize: 12 },
    grid: { left: 8, top: 12, right: line ? 26 : 45, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true, valueFormatter: value => value == null ? '未提供' : value + ' ' + unit },
    xAxis: line ? { type: 'time', minInterval: 86400000, splitNumber: 4, axisLabel: { formatter: value => dateLabel(value).slice(5), hideOverlap: true } } : valueAxis,
    yAxis: line ? valueAxis : { type: 'category', inverse: true, data: points.map(([name]) => name),
      axisLabel: { width: 108, overflow: 'truncate', interval: 0 }, axisTick: { show: false } },
    series: [{ type: kind, name: props.chart.title, data: line ? points : points.map(([, value]) => value), symbolSize: 7,
      connectNulls: false, barMaxWidth: 18, label: { show: !line, position: 'right' } }],
  }, true)
}
onMounted(() => {
  instance = init(host.value)
  draw()
  observer = new ResizeObserver(() => instance?.resize())
  observer.observe(host.value)
})
watch([series, () => props.chart], draw, { flush: 'post' })
onBeforeUnmount(() => { observer?.disconnect(); instance?.dispose(); instance = null })
</script>

<template>
  <figure class="answer-chart">
    <figcaption><strong>{{ chart.title }}</strong><span>单位 · {{ chart.unit }}</span></figcaption>
    <select v-if="chart.series.length > 1" v-model="selected" :aria-label="chart.title + '分组'">
      <option v-for="(item, i) in chart.series" :key="i" :value="i">{{ item.label }}</option>
    </select>
    <p v-else-if="series.label">{{ series.label }}</p>
    <div ref="host" class="chart-canvas" role="img" :aria-label="chart.title + '，详细数值见数据依据'"
      :style="{ height: chart.kind === 'line' ? '185px' : Math.max(102, series.points.length * 29 + 40) + 'px' }"></div>
  </figure>
</template>

<style scoped>
.answer-chart { min-width:0; margin:12px 0; padding:14px; border:1px solid var(--aw-border); border-radius:8px; background:var(--aw-bg); }
figcaption { display:flex; align-items:baseline; flex-wrap:wrap; gap:6px 12px; }
figcaption strong { font-size:14px; font-weight:650; }
figcaption span,p,select { color:var(--aw-muted); font-size:12px; }
p,select { margin:5px 0 0; }
select { max-width:100%; border:1px solid var(--aw-border); border-radius:4px; background:var(--aw-bg); padding:3px 6px; }
.chart-canvas { width:100%; min-width:0; }
</style>
