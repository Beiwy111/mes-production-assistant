<script setup>
import { computed, defineAsyncComponent, ref } from 'vue'
import { analyticalQuestion, analysisVisuals } from '@/utils/analysisPresentation'
import { businessSnapshot } from '@/utils/businessPresentation'
import { inventorySnapshot } from '@/utils/inventoryPresentation'
import BusinessQueryAnswer from './BusinessQueryAnswer.vue'
import InventoryAnswer from './InventoryAnswer.vue'

const AnswerChart = defineAsyncComponent(() => import('./AnswerChart.vue'))
const props = defineProps({ message: { type: Object, required: true } })
const snapshot = computed(() => businessSnapshot(props.message))
const inventory = computed(() => inventorySnapshot(props.message))
const fold = computed(() => analyticalQuestion(props.message.result) && !snapshot.value.unavailable &&
  Boolean(snapshot.value.tables.length || inventory.value))
const opened = ref(false)
const plots = computed(() => analysisVisuals(props.message))
</script>

<template>
  <section v-if="(message.text || message.status !== 'RUNNING') && (plots.metrics.length || plots.charts.length)" aria-label="分析指标与图表" class="analysis-visuals">
    <dl v-if="plots.metrics.length" class="analysis-metrics">
      <div v-for="metric in plots.metrics" :key="metric.label">
        <dt>{{ metric.label }}</dt><dd>{{ metric.value }} <small>{{ metric.unit }}</small><span v-if="metric.detail" class="metric-detail">{{ metric.detail }}</span></dd>
      </div>
    </dl>
    <p v-if="message.result?.facts?.quality?.qualityStatistics?.scopeNote" class="sample-scope">{{ message.result.facts.quality.qualityStatistics.scopeNote }}</p>
    <AnswerChart v-for="plot in plots.charts" :key="plot.title" :chart="plot" />
  </section>
  <component :is="fold ? 'details' : 'div'" :class="{ 'answer-data': fold }" @toggle="opened = $event.currentTarget.open">
    <summary v-if="fold">数据依据 <span>{{ snapshot.tables.length + (inventory ? 1 : 0) }} 张表</span></summary>
    <template v-if="!fold || opened">
      <InventoryAnswer v-if="inventory" :message="message" />
      <BusinessQueryAnswer :message="message" :snapshot="snapshot" />
    </template>
  </component>
</template>

<style scoped>
.analysis-visuals { margin:16px 0; }
.analysis-metrics { display:grid; grid-template-columns:repeat(auto-fit,minmax(112px,1fr)); gap:8px; margin:0; }
.analysis-metrics div { border:1px solid var(--aw-border); border-radius:7px; padding:10px 12px; }
dt,.answer-data summary span { color:var(--aw-muted); font-size:12px; }
.sample-scope { color:var(--aw-muted); font-size:12px; line-height:1.65; margin:8px 0; }
dd { font-size:21px; font-weight:650; line-height:1.5; margin:3px 0 0; font-variant-numeric:tabular-nums; }
dd small { font-size:12px; font-weight:400; }
.metric-detail { display:block; color:var(--aw-muted); font-weight:400; font-size:11px; overflow-wrap:anywhere; }
.answer-data { margin:14px 0; border-block:1px solid var(--aw-border); }
.answer-data summary { cursor:pointer; font-weight:550; padding:9px 0; font-size:14px; }
.answer-data summary span { margin-left:6px; font-weight:400; }
</style>
