<template>
  <div class="thought-shell" :class="shellClass">
    <div v-if="!embedded && !fullscreen" class="thought-shell__hero">
      <div class="thought-shell__hero-left">
        <h3>{{ title }}</h3>
        <p>{{ subtitle }}</p>
      </div>
      <div class="thought-shell__hero-right">
        <div class="progress-ring">
          <span class="progress-ring__num">{{ progressPercent }}%</span>
          <span class="progress-ring__label">{{ running ? '推演中' : thoughtStream.length ? '已完成' : '待开始' }}</span>
        </div>
        <div class="progress-meta">
          <span>步骤 {{ Math.min(activeIndex, totalSteps) }}/{{ totalSteps }}</span>
          <el-progress :percentage="progressPercent" :stroke-width="8" :show-text="false" />
        </div>
      </div>
    </div>

    <div v-else-if="embedded && !fullscreen && !hideBar" class="thought-shell__bar">
      <div>
        <h3>{{ title }}</h3>
        <p>{{ subtitle }}</p>
      </div>
      <div class="thought-shell__bar-meta">
        <span>{{ running ? '推演中' : thoughtStream.length ? '分析完成' : '待开始' }}</span>
        <el-progress :percentage="progressPercent" :stroke-width="6" :show-text="false" style="width:120px" />
        <span class="thought-shell__bar-pct">{{ progressPercent }}%</span>
      </div>
    </div>

    <div class="thought-layout" :class="[layoutClass, layoutModeClass]">
      <div class="thought-stream thought-stream--matrix" :class="{ 'thought-stream--solo': isSourcesOnly }">
        <div class="thought-stream__header">
          <span class="thought-stream__title">
            <span class="pulse-dot" :class="{ 'pulse-dot--on': running }" />
            数据库证据检索
          </span>
          <span class="thought-stream__hint">
            {{ rightMode === 'calc-engine' ? '多源取数 → 数据汇聚 → 约束计算' : '多源并行取数 · 点击卡片查看明细' }}
          </span>
        </div>

        <div v-if="sourceCards.length || running" class="source-overview">
          <div class="source-overview__item">
            <span class="source-overview__label">已连接数据源</span>
            <strong class="source-overview__value">{{ sourceOverview.connected }}</strong>
          </div>
          <div class="source-overview__item">
            <span class="source-overview__label">检索成功</span>
            <strong class="source-overview__value">{{ sourceOverview.success }}</strong>
          </div>
          <div class="source-overview__item">
            <span class="source-overview__label">累计证据</span>
            <strong class="source-overview__value">{{ sourceOverview.evidence }}</strong>
          </div>
          <div class="source-overview__item">
            <span class="source-overview__label">检索耗时</span>
            <strong class="source-overview__value">{{ sourceOverview.elapsed }}</strong>
          </div>
        </div>

        <div class="source-body" ref="thoughtListRef">
          <div v-if="!sourceCards.length && !running" class="thought-empty">
            <div class="thought-empty__icon">◎</div>
            <div>点击「重新分析」后，将并行连接 ERP / WMS / BOM / MES / HR 等数据源取证</div>
          </div>

          <!-- 排产：多源卡片 + 汇聚中心（紧凑横排） -->
          <div
            v-else-if="rightMode === 'calc-engine'"
            class="source-funnel"
          >
            <div class="source-funnel__lanes">
              <div
                v-for="card in sourceCards"
                :key="card.id"
                class="source-funnel__lane"
              >
                <button
                  type="button"
                  class="source-card source-card--compact"
                  :class="{
                    'source-card--pending': card.status === 'pending',
                    'source-card--running': card.status === 'running',
                    'source-card--done': card.status === 'done',
                    'source-card--selected': isCardSelected(card)
                  }"
                  @click="toggleExpand(card.primaryKey)"
                >
                  <div class="source-card__top">
                    <span class="source-card__code" :style="{ background: card.color }">{{ card.code }}</span>
                    <div class="source-card__identity">
                      <span class="source-card__name">{{ card.name }}</span>
                      <span class="source-card__table">{{ card.tableHint }}</span>
                    </div>
                    <span class="source-card__status" :class="`source-card__status--${card.status}`">
                      {{ card.statusLabel }}
                    </span>
                  </div>
                  <div class="source-card__meta">
                    <span>证据 {{ card.evidenceCount }} 条</span>
                    <span v-if="card.badge">{{ card.badge }}</span>
                  </div>
                  <p class="source-card__summary">
                    {{ card.summary }}
                    <span v-if="card.typing" class="thought-cursor">|</span>
                  </p>
                </button>
                <div
                  class="funnel-line funnel-line--h"
                  :class="lineStateClass(card)"
                />
              </div>
            </div>

            <div class="source-funnel__collector">
              <div
                class="funnel-spine"
                :class="{ 'funnel-line--lit': hubLineLit }"
              />
              <div
                class="funnel-line funnel-line--to-hub"
                :class="{
                  'funnel-line--lit': hubLineLit,
                  'funnel-line--flow': running && hubLineLit
                }"
              />

              <div
                class="hub-node"
                :class="{
                  'hub-node--active': hubLineLit,
                  'hub-node--ready': hubOutput.ready
                }"
              >
                <div class="hub-node__head">
                  <span class="hub-node__title">结构化数据汇聚中心</span>
                  <span class="hub-node__state">{{ hubStateLabel }}</span>
                </div>
                <div class="hub-node__metrics">
                  <div
                    v-for="m in hubMetrics"
                    :key="m.key"
                    class="hub-metric"
                    :class="{
                      'hub-metric--on': m.revealed,
                      'hub-metric--active': m.active
                    }"
                  >
                    <span class="hub-metric__label">{{ m.label }}</span>
                    <strong class="hub-metric__value">
                      <template v-if="m.revealed">{{ m.value }} {{ m.unit }}</template>
                      <template v-else>—</template>
                    </strong>
                  </div>
                </div>
                <div class="hub-node__output">
                  <div class="hub-node__output-label">汇聚输出</div>
                  <div v-if="hubOutput.ready" class="hub-node__output-body">
                    <div class="hub-node__output-main">
                      建议排产 <strong>{{ hubOutput.recommended }}</strong> 台
                      <span v-if="hubOutput.bottlenecks.length" class="hub-node__output-tag">
                        瓶颈：{{ hubOutput.bottlenecks.join(' / ') }}
                      </span>
                    </div>
                    <p v-if="hubOutput.note" class="hub-node__output-note">{{ hubOutput.note }}</p>
                  </div>
                  <div v-else class="hub-node__output-wait">
                    {{ running ? '等待约束字段汇齐…' : '完成多源检索后输出建议排产' }}
                  </div>
                </div>
              </div>

              <div class="funnel-out">
                <div
                  class="funnel-line funnel-line--out"
                  :class="{
                    'funnel-line--lit': hubOutput.ready,
                    'funnel-line--flow': running && hubOutput.ready
                  }"
                />
                <div
                  class="funnel-out__label"
                  :class="{ 'funnel-out__label--lit': hubOutput.ready }"
                >
                  <span class="funnel-out__arrow">→</span>
                  <span>排产计算引擎</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 派工等：紧凑矩阵（单栏时四列横排） -->
          <div
            v-else
            class="source-matrix"
            :class="{ 'source-matrix--solo': isSourcesOnly }"
          >
            <button
              v-for="card in sourceCards"
              :key="card.id"
              type="button"
              class="source-card source-card--compact"
              :class="{
                'source-card--pending': card.status === 'pending',
                'source-card--running': card.status === 'running',
                'source-card--done': card.status === 'done',
                'source-card--selected': isCardSelected(card)
              }"
              @click="toggleExpand(card.primaryKey)"
            >
              <div class="source-card__top">
                <span class="source-card__code" :style="{ background: card.color }">{{ card.code }}</span>
                <div class="source-card__identity">
                  <span class="source-card__name">{{ card.name }}</span>
                  <span class="source-card__table">{{ card.tableHint }}</span>
                </div>
                <span class="source-card__status" :class="`source-card__status--${card.status}`">
                  {{ card.statusLabel }}
                </span>
              </div>
              <div class="source-card__meta">
                <span>证据 {{ card.evidenceCount }} 条</span>
                <span v-if="card.badge">{{ card.badge }}</span>
              </div>
              <p class="source-card__summary">
                {{ card.summary }}
                <span v-if="card.typing" class="thought-cursor">|</span>
              </p>

              <div v-if="isCardExpanded(card)" class="source-card__detail" @click.stop>
                <div class="source-card__detail-title">取证动作</div>
                <div class="source-card__detail-text">{{ card.action }}</div>
                <div class="source-card__detail-title">数据库明细</div>
                <div class="source-card__detail-text">{{ card.thought }}</div>
                <ul v-if="card.detailLines.length" class="source-card__lines">
                  <li v-for="(line, li) in card.detailLines" :key="li">{{ line }}</li>
                </ul>
              </div>
            </button>
          </div>

          <div v-if="conclusionBar && rightMode !== 'calc-engine'" class="source-conclusion">
            <span class="source-conclusion__label">汇总结论</span>
            <span class="source-conclusion__text">{{ conclusionBar }}</span>
          </div>
        </div>
      </div>

      <SchedulingCalcEnginePanel
        v-if="rightMode === 'calc-engine'"
        :analysis="analysis"
        :revealed-step-keys="calcRevealedStepKeys"
        :active-step-key="activeStepKey"
        :running="running"
        :fullscreen="fullscreen"
      />
      <DispatchValidationPanel
        v-else-if="rightMode === 'dispatch-validation'"
        :preview="dispatchPreview"
        :mes="dispatchMes"
        :revealed-step-keys="dispatchRevealedStepKeys"
        :active-step-key="activeStepKey"
        :running="running"
      />
      <div v-else-if="rightMode === 'evidence'" class="evidence-panel">
        <div class="evidence-panel__header">
          <span>证据库</span>
          <em>({{ visibleEvidence.length }}{{ allEvidenceCount ? ` / ${allEvidenceCount}` : '' }})</em>
        </div>
        <div v-if="selectedStepKey" class="evidence-filter">
          当前筛选：<strong>{{ stepLabel(selectedStepKey) }}</strong>
          <el-button link type="primary" size="small" @click="clearFilter">显示全部</el-button>
        </div>
        <div class="evidence-list" ref="evidenceListRef">
          <div
            v-for="(ev, idx) in visibleEvidence"
            :key="ev.id || idx"
            class="evidence-card"
            :class="{
              'evidence-card--new': idx === visibleEvidence.length - 1 && running,
              'evidence-card--highlight': highlightedEvidenceId === ev.id
            }"
            @click="toggleEvidence(ev.id)"
          >
            <div class="evidence-card__top">
              <span class="evidence-tag">{{ ev.tag || ev.source }}</span>
              <span class="evidence-code">#{{ idx + 1 }}</span>
              <span class="evidence-reliability">可信度 {{ ev.reliability ?? 85 }}%</span>
            </div>
            <div class="evidence-card__title">{{ ev.title }}</div>
            <div class="evidence-card__snippet">{{ ev.snippet }}</div>
            <div v-if="metricEntries(ev).length" class="evidence-metrics">
              <div v-for="(m, mi) in metricEntries(ev)" :key="mi" class="evidence-metric">
                <span class="evidence-metric__key">{{ m.key }}</span>
                <span class="evidence-metric__val">{{ m.value }}</span>
              </div>
            </div>
            <div v-if="expandedEvidenceId === ev.id && ev.detail" class="evidence-card__detail">
              {{ ev.detail }}
            </div>
            <div class="evidence-card__source">{{ ev.source }} · {{ ev.code }}</div>
          </div>
          <div v-if="!visibleEvidence.length" class="evidence-empty">
            {{ running ? '推演中，正在抓取数据源…' : '等待规划路径触发数据源…' }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import SchedulingCalcEnginePanel from '@/components/mes/SchedulingCalcEnginePanel.vue'
import DispatchValidationPanel from '@/components/mes/DispatchValidationPanel.vue'
import { STEP_ORDER, buildCalcEngineModel } from '@/composables/useSchedulingCalcEngine'
import { DISPATCH_STEP_ORDER } from '@/composables/useDispatchValidation'
import { scrollChildIntoView } from '@/utils/scrollFocus'

const PLANNER_SOURCE_DEFS = [
  {
    id: 'erp', code: 'ERP', name: '订单主数据', tableHint: 'customer_order',
    color: '#2563eb', keys: ['order']
  },
  {
    id: 'wms', code: 'WMS', name: '成品库存', tableHint: 'inventory',
    color: '#0f766e', keys: ['inventory']
  },
  {
    id: 'bom', code: 'BOM', name: '物料齐套', tableHint: 'bom_item / material',
    color: '#b45309', keys: ['material']
  },
  {
    id: 'mes', code: 'MES', name: '设备台账', tableHint: 'equipment / workshop',
    color: '#475569', keys: ['equipment', 'allocate']
  },
  {
    id: 'hr', code: 'HR', name: '人员编制', tableHint: 'user / dispatch',
    color: '#9a3412', keys: ['operator']
  }
]

const DISPATCH_SOURCE_DEFS = [
  {
    id: 'route', code: 'MES', name: '工艺路线', tableHint: 'process_route',
    color: '#2563eb', keys: ['route']
  },
  {
    id: 'eq', code: 'MES', name: '设备台账', tableHint: 'equipment',
    color: '#475569', keys: ['equipment']
  },
  {
    id: 'hr', code: 'HR', name: '人员编制', tableHint: 'user / dispatch',
    color: '#9a3412', keys: ['operator']
  },
  {
    id: 'aps', code: 'APS', name: '匹配结果', tableHint: 'dispatch_recommend',
    color: '#0f766e', keys: ['match', 'result']
  }
]

const props = defineProps({
  title: { type: String, default: '智能排产引擎' },
  subtitle: { type: String, default: '订单库 → 库存表 → 物料 BOM → 设备台账 → 人员编制 → 车间产能 → 排产结论' },
  embedded: { type: Boolean, default: false },
  fullscreen: { type: Boolean, default: false },
  hideBar: { type: Boolean, default: false },
  rightMode: { type: String, default: 'evidence' },
  analysis: { type: Object, default: null },
  dispatchPreview: { type: Object, default: null },
  dispatchMes: { type: Object, default: null },
  thoughtStream: { type: Array, default: () => [] },
  evidenceList: { type: Array, default: () => [] },
  allEvidence: { type: Array, default: () => [] },
  activeStepKey: { type: String, default: '' },
  selectedStepKey: { type: String, default: '' },
  activeIndex: { type: Number, default: 0 },
  totalSteps: { type: Number, default: 7 },
  running: { type: Boolean, default: false },
  pendingText: { type: String, default: '正在从数据库检索证据…' }
})

const emit = defineEmits(['select-step'])

const expandedKey = ref('')
const expandedEvidenceId = ref('')
const highlightedEvidenceId = ref('')
const evidenceListRef = ref(null)
const thoughtListRef = ref(null)
const elapsedMs = ref(0)
let elapsedTimer = null
let runStartedAt = 0

const shellClass = computed(() => ({
  'thought-shell--embedded': props.embedded && !props.fullscreen,
  'thought-shell--fullscreen': props.fullscreen
}))

const layoutClass = computed(() => ({
  'thought-layout--embedded': props.embedded && !props.fullscreen,
  'thought-layout--fullscreen': props.fullscreen
}))

const sourceDefs = computed(() => (
  props.rightMode === 'dispatch-validation' || props.rightMode === 'sources-only'
    ? DISPATCH_SOURCE_DEFS
    : PLANNER_SOURCE_DEFS
))

const isSourcesOnly = computed(() => props.rightMode === 'sources-only')

const allEvidenceCount = computed(() => {
  const all = props.allEvidence?.length ? props.allEvidence : props.evidenceList
  return all.length
})

const progressPercent = computed(() => {
  if (!props.totalSteps) return 0
  if (props.running) {
    return Math.min(95, Math.round((props.activeIndex / props.totalSteps) * 100))
  }
  return props.thoughtStream.length ? 100 : 0
})

const stepByKey = computed(() => {
  const map = new Map()
  for (const item of props.thoughtStream || []) {
    if (item?.key) map.set(item.key, item)
  }
  return map
})

const revealedKeys = computed(() =>
  props.thoughtStream.map((t) => t.key).filter(Boolean)
)

function plainSummary(item) {
  if (!item) return ''
  return String(item.displaySummary ?? item.summary ?? item.thought ?? item.detail ?? '').trim()
}

function isTyping(item) {
  if (!props.running || !item) return false
  const full = String(item.summary || item.thought || item.detail || '')
  const shown = String(item.displaySummary ?? '')
  return shown.length > 0 && shown.length < full.length
}

const sourceCards = computed(() => {
  const defs = sourceDefs.value
  const revealed = revealedKeys.value
  const active = props.activeStepKey
  const allEv = props.allEvidence?.length ? props.allEvidence : props.evidenceList

  return defs.map((def) => {
    const matched = def.keys
      .map((key) => stepByKey.value.get(key))
      .filter(Boolean)
    const primaryKey = matched[0]?.key || def.keys[0]
    const latest = matched[matched.length - 1]
    const typing = isTyping(latest)

    let status = 'pending'
    let statusLabel = '待连接'
    if (props.running && (def.keys.includes(active) || typing)) {
      status = 'running'
      statusLabel = '检索中'
    } else if (def.keys.some((key) => revealed.includes(key))) {
      status = 'done'
      statusLabel = '检索成功'
    } else if (props.running) {
      status = 'pending'
      statusLabel = '并行等待'
    }

    const fromSteps = matched.reduce((sum, item) => sum + Number(item.evidenceCount || 0), 0)
    const fromList = allEv.filter((ev) =>
      (ev.relatedSteps || []).some((k) => def.keys.includes(k))
    ).length
    const evidenceCount = fromSteps || fromList

    const summary = plainSummary(latest)
      || (status === 'running'
        ? '正在并行查询该数据源…'
        : status === 'pending' && props.running
          ? '已发起连接，等待返回…'
          : '尚未开始检索')

    return {
      ...def,
      primaryKey,
      status,
      statusLabel,
      evidenceCount,
      badge: latest?.badge || latest?.actionType || (status === 'done' ? '查询' : ''),
      summary,
      typing,
      action: matched.map((m) => m.action || m.title).filter(Boolean).join('；') || def.tableHint,
      thought: matched.map((m) => m.thought || m.detail).filter(Boolean).join('\n') || '',
      detailLines: matched.flatMap((m) => m.detailLines || [])
    }
  }).filter(() => props.running || props.thoughtStream.length > 0)
})

const sourceOverview = computed(() => {
  const cards = sourceCards.value
  const connected = cards.length || (props.running ? sourceDefs.value.length : 0)
  const success = cards.filter((c) => c.status === 'done').length
  const evidence = cards.reduce((sum, c) => sum + Number(c.evidenceCount || 0), 0)
    || allEvidenceCount.value
  return {
    connected,
    success,
    evidence,
    elapsed: formatElapsed(elapsedMs.value)
  }
})

const conclusionBar = computed(() => {
  const resultStep = stepByKey.value.get('result')
  const parts = []
  if (resultStep) {
    parts.push(`推演步骤 ${props.totalSteps} 步`)
    const text = plainSummary(resultStep) || resultStep.thought || ''
    if (text) parts.push(text)
    return parts.join(' · ')
  }
  if (props.running || !props.analysis) return ''
  const a = props.analysis
  parts.push(`推演步骤 ${props.totalSteps} 步`)
  if (a.recommendedPlanQty != null) parts.push(`建议排产 ${a.recommendedPlanQty} 台`)
  if (a.inventoryCheck?.shipFromStock != null) parts.push(`现货 ${a.inventoryCheck.shipFromStock} 台`)
  if (a.recommendation) parts.push(a.recommendation)
  return parts.join(' · ')
})

const calcModel = computed(() => buildCalcEngineModel(props.analysis))

const hubMetrics = computed(() => {
  const inputs = calcModel.value?.inputs
  const revealed = revealedKeys.value
  const active = props.activeStepKey
  const defs = [
    { key: 'orderQty', label: '订单需求', unlock: 'order', field: 'orderQty', unit: '台' },
    { key: 'needProduce', label: '库存缺口', unlock: 'inventory', field: 'needProduce', unit: '台' },
    { key: 'materialLimit', label: '物料可产', unlock: 'material', field: 'materialLimit', unit: '台' },
    { key: 'equipmentLimit', label: '设备可产', unlock: 'equipment', field: 'equipmentLimit', unit: '台' },
    { key: 'operatorLimit', label: '人员上限', unlock: 'operator', field: 'operatorHeadcount', unit: '人' }
  ]
  return defs.map((d) => {
    const unlocked = revealed.includes(d.unlock)
      || (!props.running && Boolean(inputs))
    const val = inputs?.[d.field]
    return {
      ...d,
      revealed: unlocked && val != null,
      active: props.running && active === d.unlock,
      value: val != null ? val : '—',
      unit: d.unit
    }
  })
})

const hubLineLit = computed(() =>
  sourceCards.value.some((c) => c.status === 'done' || c.status === 'running')
)

const hubOutput = computed(() => {
  const model = calcModel.value
  const resultReady = revealedKeys.value.includes('result')
    || (!props.running && Boolean(model))
  if (!model || !resultReady) {
    return { ready: false, recommended: '—', bottlenecks: [], note: '' }
  }
  return {
    ready: true,
    recommended: model.outputs.recommended,
    bottlenecks: model.outputs.bottlenecks || [],
    note: model.outputs.recommendation || ''
  }
})

const hubStateLabel = computed(() => {
  if (hubOutput.value.ready) return '已汇齐'
  if (props.running) return '汇聚中'
  if (sourceCards.value.length) return '待汇齐'
  return '待机'
})

function lineStateClass(card) {
  const lit = card.status === 'done' || card.status === 'running' || isCardSelected(card)
  return {
    'funnel-line--lit': lit,
    'funnel-line--flow': props.running && (card.status === 'done' || card.status === 'running')
  }
}

const layoutModeClass = computed(() => ({
  'thought-layout--calc-engine': props.rightMode === 'calc-engine',
  'thought-layout--dispatch-validation': props.rightMode === 'dispatch-validation',
  'thought-layout--sources-only': isSourcesOnly.value
}))

const filterKey = computed(() => props.selectedStepKey || '')

const visibleEvidence = computed(() => {
  const list = props.allEvidence?.length ? props.allEvidence : props.evidenceList
  if (!list.length) return []
  if (filterKey.value) {
    const filtered = list.filter((ev) => (ev.relatedSteps || []).includes(filterKey.value))
    return filtered.length ? filtered : list
  }
  if (!props.running) return list
  const keys = revealedKeys.value
  if (!keys.length) return []
  const cumulative = list.filter((ev) => {
    const related = ev.relatedSteps || []
    return related.some((k) => keys.includes(k))
  })
  return cumulative.length ? cumulative : list.slice(0, Math.max(1, keys.length))
})

const calcRevealedStepKeys = computed(() => {
  const streamKeys = revealedKeys.value
  if (props.running || !props.selectedStepKey) return streamKeys
  const idx = STEP_ORDER.indexOf(props.selectedStepKey)
  if (idx < 0) return streamKeys
  return STEP_ORDER.slice(0, idx + 1)
})

const dispatchRevealedStepKeys = computed(() => {
  const streamKeys = revealedKeys.value
  if (props.running || !props.selectedStepKey) return streamKeys
  const idx = DISPATCH_STEP_ORDER.indexOf(props.selectedStepKey)
  if (idx < 0) return streamKeys
  return DISPATCH_STEP_ORDER.slice(0, idx + 1)
})

function formatElapsed(ms) {
  if (!ms || ms < 0) return props.running ? '0.0s' : '—'
  if (ms < 1000) return `${(ms / 1000).toFixed(1)}s`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(ms / 60000)
  const s = Math.round((ms % 60000) / 1000)
  return `${m}m${s}s`
}

function startElapsed() {
  stopElapsed()
  runStartedAt = Date.now()
  elapsedMs.value = 0
  elapsedTimer = setInterval(() => {
    elapsedMs.value = Date.now() - runStartedAt
  }, 100)
}

function stopElapsed() {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
  if (runStartedAt) {
    elapsedMs.value = Date.now() - runStartedAt
  }
}

watch(() => props.running, (on, was) => {
  if (on && !was) startElapsed()
  if (!on && was) stopElapsed()
}, { immediate: true })

onBeforeUnmount(() => stopElapsed())

watch(() => props.activeStepKey, (key) => {
  if (props.running && key) {
    if (!props.fullscreen) expandedKey.value = key
    else expandedKey.value = ''
    emit('select-step', key)
    scrollThoughtToFocus()
  }
})

watch(() => props.thoughtStream.length, (len, prev) => {
  if (len > prev && props.running) {
    if (!props.fullscreen) {
      const last = props.thoughtStream[len - 1]
      if (last?.key) expandedKey.value = last.key
    } else {
      expandedKey.value = ''
    }
    scrollThoughtToFocus()
  }
  if (!props.running && len && !expandedKey.value) {
    expandedKey.value = props.fullscreen ? '' : (props.thoughtStream[len - 1]?.key || '')
  }
})

watch(expandedKey, () => {
  if (props.running) scrollThoughtToFocus()
})

watch(() => visibleEvidence.value.length, (len, prev) => {
  if (len > prev && props.running) scrollEvidenceToFocus()
})

function scrollThoughtToFocus() {
  scrollChildIntoView(
    thoughtListRef.value,
    (container) => container.querySelector('.source-card--running, .source-card--selected')
  )
}

function scrollEvidenceToFocus() {
  scrollChildIntoView(
    evidenceListRef.value,
    (container) => container.querySelector('.evidence-card--new') || container.querySelector('.evidence-card:last-child')
  )
}

function stepEvidence(stepKey) {
  const list = props.allEvidence?.length ? props.allEvidence : props.evidenceList
  return list.filter((ev) => (ev.relatedSteps || []).includes(stepKey))
}

function stepLabel(key) {
  const step = props.thoughtStream.find((t) => t.key === key)
  return step ? `${step.agentName} · ${step.action || step.title}` : key
}

function isCardSelected(card) {
  const sel = props.selectedStepKey || expandedKey.value
  return Boolean(sel && card.keys.includes(sel))
}

function isCardExpanded(card) {
  return Boolean(expandedKey.value && card.keys.includes(expandedKey.value))
}

function toggleExpand(key) {
  const card = sourceCards.value.find((c) => c.keys.includes(key))
  if (card && isCardExpanded(card)) {
    expandedKey.value = ''
    emit('select-step', '')
    return
  }
  expandedKey.value = key
  emit('select-step', key)
}

function focusEvidence(stepKey) {
  emit('select-step', stepKey)
  nextTick(() => {
    const first = stepEvidence(stepKey)[0]
    if (first) {
      highlightedEvidenceId.value = first.id
      expandedEvidenceId.value = first.id
    }
  })
}

function clearFilter() {
  emit('select-step', '')
  highlightedEvidenceId.value = ''
}

function toggleEvidence(id) {
  expandedEvidenceId.value = expandedEvidenceId.value === id ? '' : id
  highlightedEvidenceId.value = id
}

function metricEntries(ev) {
  const metrics = ev?.metrics
  if (!metrics || typeof metrics !== 'object') return []
  return Object.entries(metrics).map(([key, value]) => ({
    key,
    value: value == null ? '—' : String(value)
  }))
}
</script>

<style scoped>
.thought-shell--embedded {
  border-radius: 4px;
  border: 1px solid #e5e7eb;
  box-shadow: none;
  background: #fff;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.thought-shell__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid #e5e7eb;
  background: #fafafa;
  flex-shrink: 0;
}
.thought-shell__bar h3 {
  margin: 0 0 2px;
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}
.thought-shell__bar p {
  margin: 0;
  font-size: 12px;
  color: #6b7280;
}
.thought-shell__bar-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #6b7280;
}
.thought-shell__bar-pct {
  font-weight: 600;
  color: #3d7a5f;
  min-width: 36px;
}
.thought-layout--embedded {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.thought-shell--embedded .thought-stream,
.thought-shell--embedded :deep(.dispatch-validation),
.thought-shell--embedded .evidence-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.thought-shell--embedded .thought-stream__header,
.thought-shell--embedded .evidence-panel__header {
  flex-shrink: 0;
}

.thought-shell--embedded .source-overview {
  flex-shrink: 0;
}

.thought-shell--embedded .source-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.thought-shell--embedded .source-funnel,
.thought-shell--embedded .source-matrix,
.thought-shell--embedded .source-conclusion,
.thought-shell--embedded .evidence-list {
  max-height: none;
}
.thought-shell {
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid #dbe4f3;
  background: #fff;
  box-shadow: 0 8px 28px rgba(26, 43, 74, 0.08);
}
.thought-shell__hero {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  background: linear-gradient(135deg, #0f2744 0%, #1a4a8a 55%, #2d6cdf 100%);
  color: #fff;
}
.thought-shell__hero h3 {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 700;
}
.thought-shell__hero p {
  margin: 0;
  font-size: 12px;
  opacity: 0.88;
}
.thought-shell__hero-right {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 180px;
}
.progress-ring {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: 4px solid rgba(255,255,255,0.25);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: rgba(255,255,255,0.08);
}
.progress-ring__num { font-size: 15px; font-weight: 700; }
.progress-ring__label { font-size: 10px; opacity: 0.85; }
.progress-meta { flex: 1; font-size: 11px; }
.progress-meta :deep(.el-progress-bar__outer) { background: rgba(255,255,255,0.2); }
.progress-meta :deep(.el-progress-bar__inner) { background: linear-gradient(90deg, #7ee8fa, #eec0ff); }
.thought-layout {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 0;
  min-height: 360px;
}
.thought-layout--sources-only {
  grid-template-columns: 1fr;
  min-height: 0;
}
.thought-stream { border-right: 1px solid #eef2f7; }
.thought-stream--solo {
  border-right: none;
}
.thought-stream--matrix {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.thought-stream__header,
.evidence-panel__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid #eef2f7;
  background: #f8fafc;
  font-size: 14px;
  font-weight: 600;
  color: #1a2b4a;
}
.thought-stream__hint {
  margin-left: auto;
  font-size: 11px;
  font-weight: 400;
  color: #909399;
}
.pulse-dot {
  display: inline-block;
  width: 8px; height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
  margin-right: 6px;
}
.pulse-dot--on {
  background: #67c23a;
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(103, 194, 58, 0.5); }
  70% { box-shadow: 0 0 0 10px rgba(103, 194, 58, 0); }
  100% { box-shadow: 0 0 0 0 rgba(103, 194, 58, 0); }
}
.evidence-panel__header em {
  font-style: normal;
  color: #909399;
  font-weight: 400;
}
.evidence-filter {
  padding: 8px 12px;
  font-size: 12px;
  color: #606266;
  border-bottom: 1px dashed #eef2f7;
  background: #fffdf6;
}
.thought-empty, .evidence-empty {
  padding: 48px 20px;
  text-align: center;
  color: #909399;
  font-size: 13px;
  line-height: 1.7;
}
.thought-empty__icon { font-size: 28px; margin-bottom: 8px; opacity: 0.5; }

.source-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  align-items: stretch;
  background: #fbfcfe;
}

.source-overview {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  margin: 0;
  border-bottom: 1px solid #e5e7eb;
  background: #e5e7eb;
  flex-shrink: 0;
}
.source-overview__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  background: #f8fafc;
}
.source-overview__label {
  font-size: 11px;
  color: #6b7280;
  letter-spacing: 0.02em;
}
.source-overview__value {
  font-size: 16px;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.source-matrix {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  padding: 10px 12px;
  flex: 0 0 auto;
  align-content: start;
  align-items: start;
}
.source-matrix--solo {
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 10px;
  padding: 12px 14px;
}

/* —— 多源汇聚漏斗（顶部对齐，不纵向撑开） —— */
.source-funnel {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(200px, 0.9fr);
  gap: 0 4px;
  padding: 10px 10px 10px 12px;
  flex: 0 0 auto;
  align-items: start;
  background: transparent;
}

.source-funnel__lanes {
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  gap: 6px;
  min-width: 0;
}

.source-funnel__lane {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 18px;
  align-items: center;
  min-height: 0;
}

.source-funnel__collector {
  position: relative;
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) 48px;
  align-items: center;
  gap: 0 4px;
  min-width: 0;
  padding-left: 2px;
  align-self: stretch;
}

.funnel-spine {
  position: absolute;
  left: 0;
  top: 8%;
  bottom: 8%;
  width: 1px;
  background: #c5d0dc;
  transition: background-color 0.25s ease;
}
.funnel-spine.funnel-line--lit {
  background: #86efac;
}

.funnel-line {
  position: relative;
  background: #c5d0dc;
  transition: background-color 0.25s ease;
}
.funnel-line--h {
  height: 1px;
  width: 100%;
  justify-self: stretch;
}
.funnel-line--to-hub {
  height: 1px;
  width: 100%;
}
.funnel-line--out {
  height: 1px;
  width: 100%;
  margin-bottom: 6px;
}
.funnel-line--lit {
  background: #86efac;
}
.funnel-line--flow::after {
  content: '';
  position: absolute;
  top: 50%;
  left: 0;
  width: 5px;
  height: 5px;
  margin-top: -2.5px;
  border-radius: 50%;
  background: #6ee7b7;
  opacity: 0.85;
  animation: funnel-flow 1.6s linear infinite;
}
.source-funnel__lane:nth-child(1) .funnel-line--flow::after { animation-delay: 0s; }
.source-funnel__lane:nth-child(2) .funnel-line--flow::after { animation-delay: 0.25s; }
.source-funnel__lane:nth-child(3) .funnel-line--flow::after { animation-delay: 0.5s; }
.source-funnel__lane:nth-child(4) .funnel-line--flow::after { animation-delay: 0.75s; }
.source-funnel__lane:nth-child(5) .funnel-line--flow::after { animation-delay: 1s; }

@keyframes funnel-flow {
  0% { left: 0; opacity: 0; }
  15% { opacity: 0.9; }
  85% { opacity: 0.9; }
  100% { left: calc(100% - 5px); opacity: 0; }
}

.hub-node {
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #fff;
  padding: 12px 12px 10px;
  min-width: 0;
  transition: border-color 0.25s ease;
}
.hub-node--active {
  border-color: #a7f3d0;
}
.hub-node--ready {
  border-color: #86efac;
}
.hub-node__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid #eef2f7;
}
.hub-node__title {
  font-size: 15px;
  font-weight: 700;
  color: #111827;
}
.hub-node__state {
  font-size: 13px;
  color: #6b7280;
  font-weight: 600;
}
.hub-node--ready .hub-node__state {
  color: #047857;
}

.hub-node__metrics {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
}
.hub-metric {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 4px 8px;
  border-radius: 4px;
  background: #f9fafb;
  opacity: 0.55;
  transition: opacity 0.2s ease, background-color 0.2s ease;
}
.hub-metric--on {
  opacity: 1;
  background: #f0fdf4;
}
.hub-metric--active {
  opacity: 1;
  background: #eff6ff;
}
.hub-metric__label {
  font-size: 13px;
  color: #4b5563;
}
.hub-metric__value {
  font-size: 16px;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
}

.hub-node__output {
  border-top: 1px solid #e5e7eb;
  padding-top: 8px;
}
.hub-node__output-label {
  font-size: 12px;
  font-weight: 700;
  color: #047857;
  letter-spacing: 0.04em;
  margin-bottom: 4px;
}
.hub-node__output-main {
  font-size: 14px;
  color: #065f46;
  line-height: 1.5;
}
.hub-node__output-main strong {
  font-size: 18px;
  font-weight: 700;
  color: #047857;
}
.hub-node__output-tag {
  display: inline-block;
  margin-left: 6px;
  font-size: 12px;
  color: #6b7280;
  font-weight: 500;
}
.hub-node__output-note {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: #4b5563;
}
.hub-node__output-wait {
  font-size: 13px;
  color: #9ca3af;
  line-height: 1.4;
}

.funnel-out {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: center;
  min-width: 0;
  align-self: center;
}
.funnel-out__label {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  font-size: 10px;
  color: #94a3b8;
  text-align: center;
  line-height: 1.25;
}
.funnel-out__label--lit {
  color: #059669;
}
.funnel-out__arrow {
  font-size: 14px;
  line-height: 1;
  color: #c5d0dc;
}
.funnel-out__label--lit .funnel-out__arrow {
  color: #86efac;
}

.source-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
  font: inherit;
  color: inherit;
}
.source-card--compact {
  gap: 4px;
  padding: 8px 9px;
  height: auto;
  align-self: start;
}
.source-card--compact .source-card__summary {
  -webkit-line-clamp: 2;
  min-height: 0;
  font-size: 11px;
  line-height: 1.4;
}
.source-card--compact .source-card__name {
  font-size: 12px;
}
.source-card--compact .source-card__table {
  font-size: 10px;
}
.source-card--compact .source-card__meta {
  font-size: 10px;
}
.source-card--compact .source-card__code {
  min-width: 34px;
  padding: 3px 5px;
  font-size: 10px;
}
.source-card--compact .source-card__status {
  font-size: 10px;
  padding: 1px 5px;
}
.source-card:hover {
  border-color: #94a3b8;
}
.source-card--running {
  border-color: #2563eb;
  box-shadow: inset 3px 0 0 #2563eb;
}
.source-card--done {
  border-color: #d1d5db;
}
.source-card--pending {
  opacity: 0.78;
  border-style: dashed;
}
.source-card--selected {
  border-color: #2563eb;
  background: #f8fafc;
}

.source-card__top {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.source-card__code {
  flex-shrink: 0;
  min-width: 40px;
  padding: 4px 6px;
  border-radius: 3px;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-align: center;
  line-height: 1.2;
}
.source-card__identity {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.source-card__name {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}
.source-card__table {
  font-size: 11px;
  color: #9ca3af;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.source-card__status {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 3px;
  background: #f3f4f6;
  color: #6b7280;
}
.source-card__status--running {
  background: #eff6ff;
  color: #1d4ed8;
}
.source-card__status--done {
  background: #ecfdf5;
  color: #047857;
}
.source-card__status--pending {
  background: #f9fafb;
  color: #9ca3af;
}

.source-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
  color: #6b7280;
}
.source-card__summary {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: #374151;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.source-card__detail {
  margin-top: 2px;
  padding-top: 10px;
  border-top: 1px solid #e5e7eb;
}
.source-card__detail-title {
  font-size: 11px;
  color: #9ca3af;
  margin-bottom: 4px;
}
.source-card__detail-text {
  font-size: 12px;
  color: #374151;
  line-height: 1.55;
  margin-bottom: 8px;
  white-space: pre-wrap;
}
.source-card__lines {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  color: #4b5563;
  line-height: 1.7;
}

.source-conclusion {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 12px;
  border-top: 1px solid #d1fae5;
  background: #ecfdf5;
  flex: 0 0 auto;
}
.source-conclusion__label {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  color: #047857;
  padding-top: 1px;
}
.source-conclusion__text {
  font-size: 12px;
  line-height: 1.55;
  color: #065f46;
}

.evidence-list {
  padding: 10px;
  max-height: 400px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: #fbfcfe;
}
.evidence-card {
  padding: 12px 14px;
  border: 1px solid #e8edf5;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: box-shadow 0.22s ease, border-color 0.22s ease;
}
.evidence-card:hover { border-color: #b3d4ff; }
.evidence-card--new { box-shadow: 0 0 0 2px rgba(79, 140, 255, 0.15); }
.evidence-card--highlight {
  border-color: #4f8cff;
  box-shadow: 0 0 0 2px rgba(79, 140, 255, 0.2);
}
.evidence-card__top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 11px;
}
.evidence-tag {
  background: #e8f4ff;
  color: #1677ff;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
}
.evidence-code { color: #909399; }
.evidence-reliability {
  margin-left: auto;
  color: #67c23a;
  font-weight: 600;
}
.evidence-card__title {
  font-size: 13px;
  font-weight: 700;
  color: #1a2b4a;
  margin-bottom: 4px;
}
.evidence-card__snippet {
  font-size: 12px;
  color: #606266;
  line-height: 1.6;
  margin-bottom: 6px;
}
.evidence-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 10px;
  margin-bottom: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f6f8fb;
}
.evidence-metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.evidence-metric__key { font-size: 10px; color: #909399; }
.evidence-metric__val {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
  word-break: break-all;
}
.evidence-card__detail {
  font-size: 12px;
  color: #606266;
  line-height: 1.6;
  margin-bottom: 6px;
  padding: 8px;
  background: #fafcff;
  border-radius: 6px;
}
.evidence-card__source { font-size: 11px; color: #909399; }

/* —— 全屏模式：仿图3简洁双栏 —— */
.thought-shell--fullscreen {
  border: none;
  border-radius: 12px;
  box-shadow: none;
  background: #fff;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.thought-layout--fullscreen {
  flex: 1;
  min-height: 0;
  grid-template-columns: minmax(0, 1.45fr) minmax(320px, 0.85fr);
  min-height: 0;
}

.thought-layout--fullscreen.thought-layout--calc-engine {
  grid-template-columns: minmax(0, 1.2fr) minmax(380px, 1fr);
}

.thought-layout--embedded.thought-layout--dispatch-validation {
  min-height: 0;
  grid-template-columns: minmax(0, 1.2fr) minmax(280px, 0.85fr);
}

.thought-layout--embedded.thought-layout--sources-only {
  min-height: 0;
  grid-template-columns: 1fr;
}

.thought-layout--fullscreen.thought-layout--dispatch-validation {
  grid-template-columns: minmax(0, 1.2fr) minmax(340px, 0.95fr);
}

.thought-layout--fullscreen.thought-layout--sources-only {
  grid-template-columns: 1fr;
}

.thought-shell--fullscreen .thought-stream,
.thought-shell--fullscreen .evidence-panel,
.thought-shell--fullscreen :deep(.calc-engine),
.thought-shell--fullscreen :deep(.dispatch-validation) {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.thought-shell--fullscreen .thought-stream__header,
.thought-shell--fullscreen .evidence-panel__header {
  padding: 18px 22px;
  font-size: 17px;
  font-weight: 700;
  background: #fff;
  border-bottom: 1px solid #eef2f7;
  flex-shrink: 0;
}

.thought-shell--fullscreen .thought-stream__hint {
  font-size: 13px;
}

.thought-shell--fullscreen .source-body,
.thought-shell--fullscreen .evidence-list {
  flex: 1;
  min-height: 0;
  max-height: none;
}

.thought-shell--fullscreen .source-funnel,
.thought-shell--fullscreen .source-matrix {
  flex: 0 0 auto;
  padding: 12px 16px;
}

.thought-shell--fullscreen .source-funnel {
  grid-template-columns: minmax(0, 1.2fr) minmax(240px, 1fr);
  gap: 0 6px;
  padding-right: 12px;
}

.thought-shell--fullscreen .source-matrix {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.thought-shell--fullscreen .source-matrix--solo {
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
}

.thought-shell--fullscreen .source-overview__item {
  padding: 8px 14px;
}
.thought-shell--fullscreen .source-overview__value {
  font-size: 18px;
}
.thought-shell--fullscreen .source-card {
  padding: 12px;
}
.thought-shell--fullscreen .source-card--compact {
  padding: 8px 10px;
}
.thought-shell--fullscreen .source-card__name {
  font-size: 13px;
}
.thought-shell--fullscreen .source-card--compact .source-card__name {
  font-size: 12px;
}
.thought-shell--fullscreen .source-card__summary {
  font-size: 12px;
  min-height: 0;
}
.thought-shell--fullscreen .source-card--compact .source-card__summary {
  font-size: 11px;
  min-height: 0;
}
.thought-shell--fullscreen .hub-node {
  padding: 14px;
}
.thought-shell--fullscreen .hub-node__title {
  font-size: 16px;
}
.thought-shell--fullscreen .hub-metric__label {
  font-size: 14px;
}
.thought-shell--fullscreen .hub-metric__value {
  font-size: 18px;
}
.thought-shell--fullscreen .hub-node__output-main {
  font-size: 15px;
}
.thought-shell--fullscreen .hub-node__output-main strong {
  font-size: 20px;
}
.thought-shell--fullscreen .source-conclusion {
  padding: 10px 16px;
}
.thought-shell--fullscreen .source-conclusion__text {
  font-size: 12px;
}

.thought-cursor {
  display: inline-block;
  color: #2563eb;
  font-weight: 400;
  animation: thought-cursor-blink 0.9s step-end infinite;
  margin-left: 2px;
}

@keyframes thought-cursor-blink {
  50% { opacity: 0; }
}

.thought-shell--fullscreen .evidence-card {
  padding: 16px 18px;
  border-radius: 12px;
}

.thought-shell--fullscreen .evidence-card__title {
  font-size: 16px;
}

.thought-shell--fullscreen .evidence-card__snippet {
  font-size: 15px;
  line-height: 1.65;
}

.thought-shell--fullscreen .evidence-card__top {
  font-size: 12px;
}

.thought-shell--fullscreen .evidence-metric__val {
  font-size: 14px;
}

.thought-shell--fullscreen .evidence-empty,
.thought-shell--fullscreen .thought-empty {
  font-size: 15px;
  padding: 64px 24px;
}
</style>
