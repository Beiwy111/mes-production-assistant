<template>
  <div class="derive" :class="{ 'derive--running': running }">
    <header class="derive__head">
      <div>
        <h3 class="derive__title">{{ title }}</h3>
        <p class="derive__sub">多源数据输入 → 约束过滤 → 组合匹配 → 规则评分 → 方案收敛</p>
      </div>
      <div class="derive__status">
        <span class="derive__dot" :class="{ 'derive__dot--on': running || finished }" />
        <span>{{ statusText }}</span>
        <em v-if="running && activeElapsedMs > 0" class="derive__elapsed">{{ formatMs(activeElapsedMs) }}</em>
      </div>
    </header>

    <div class="derive__body">
      <div class="derive__rail">
        <template v-for="(stageKey, idx) in stageOrder" :key="stageKey">
          <section
            class="derive-stage"
            :class="stageClass(viewModel[stageKey])"
          >
            <div class="derive-stage__head">
              <span class="derive-stage__idx">{{ String(idx + 1).padStart(2, '0') }}</span>
              <h4 class="derive-stage__title">{{ stageTitles[stageKey] }}</h4>
              <span v-if="viewModel[stageKey].done" class="derive-stage__check">✓</span>
              <span v-else-if="viewModel[stageKey].active && running" class="derive-stage__loading">
                <i class="derive-spinner" />
              </span>
            </div>

            <p v-if="viewModel[stageKey].active && running" class="derive-stage__live">
              {{ viewModel[stageKey].liveTip }}
            </p>

            <!-- 1 多源 -->
            <template v-if="stageKey === 'sources'">
              <div class="derive-metrics">
                <div v-for="item in viewModel.sources.items" :key="item.key" class="derive-metric">
                  <strong class="derive-metric__num">
                    <AnimatedNum :value="item.value" :active="viewModel.sources.unlocked" />
                  </strong>
                  <span class="derive-metric__label">{{ item.label }}</span>
                  <em class="derive-metric__unit">{{ item.unit }}</em>
                </div>
              </div>
              <p class="derive-stage__note">{{ viewModel.sources.conclusion }}</p>
            </template>

            <!-- 2 过滤 -->
            <template v-else-if="stageKey === 'filter'">
              <div class="derive-chips">
                <span v-for="c in viewModel.filter.conditions" :key="c.key" class="derive-chip">{{ c.label }}</span>
              </div>
              <div class="derive-filter-list">
                <div v-for="row in viewModel.filter.rows" :key="row.key" class="derive-filter-row">
                  <div class="derive-filter-row__main">
                    <strong class="derive-filter-row__nums">
                      {{ row.label }}
                      <AnimatedNum :value="row.from" :active="viewModel.filter.unlocked" />
                      {{ row.unit }}
                      <i>→</i>
                      可用
                      <AnimatedNum :value="row.to" :active="viewModel.filter.unlocked" />
                      {{ row.unit }}
                    </strong>
                  </div>
                  <p class="derive-filter-row__reason">{{ row.reason }}</p>
                </div>
              </div>
            </template>

            <!-- 3 匹配 -->
            <template v-else-if="stageKey === 'match'">
              <div class="derive-combo">
                <div class="derive-combo__block">
                  <strong><AnimatedNum :value="viewModel.match.initialCombos" :active="viewModel.match.unlocked" /></strong>
                  <span>组候选</span>
                </div>
                <div class="derive-combo__arrow">→</div>
                <div class="derive-combo__block derive-combo__block--ok">
                  <strong><AnimatedNum :value="viewModel.match.validCombos" :active="viewModel.match.unlocked" /></strong>
                  <span>组有效</span>
                </div>
              </div>
              <div class="derive-best">
                <div
                  v-for="row in viewModel.match.bestPairs.slice(0, 4)"
                  :key="row.process"
                  class="derive-best__row"
                >
                  <span class="derive-best__proc">{{ row.process }}</span>
                  <span class="derive-best__pair">{{ row.equipment }} · {{ row.operator }}</span>
                </div>
                <button
                  v-if="viewModel.match.bestPairs.length > 4"
                  type="button"
                  class="derive-link"
                  @click.stop="openDetail(viewModel.match)"
                >
                  其余 {{ viewModel.match.bestPairs.length - 4 }} 道工序组合…
                </button>
              </div>
            </template>

            <!-- 4 评分 -->
            <template v-else-if="stageKey === 'score'">
              <div class="derive-scores">
                <div v-for="m in viewModel.score.metrics" :key="m.key" class="derive-score">
                  <div class="derive-score__line">
                    <span class="derive-score__label">{{ m.label }}</span>
                    <strong class="derive-score__val">
                      <AnimatedNum :value="m.value" :active="viewModel.score.unlocked" /> 分
                    </strong>
                    <span
                      class="derive-score__tag"
                      :class="m.pass ? 'derive-score__tag--pass' : 'derive-score__tag--warn'"
                    >
                      {{ m.pass ? '通过' : '关注' }}
                    </span>
                  </div>
                  <div class="derive-score__bar">
                    <i :style="{ width: viewModel.score.unlocked ? `${animScores[m.key] ?? 0}%` : '0%' }" />
                  </div>
                  <p class="derive-score__reason">{{ m.reason }}</p>
                </div>
              </div>
            </template>

            <!-- 5 收敛 -->
            <template v-else-if="stageKey === 'converge'">
              <div class="derive-schemes">
                <button
                  v-for="s in viewModel.converge.schemes"
                  :key="s.id"
                  type="button"
                  class="derive-scheme"
                  :class="{
                    'derive-scheme--picked': selectedScheme === s.id,
                    'derive-scheme--rec': s.recommended
                  }"
                  @click.stop="pickScheme(s.id)"
                >
                  <div class="derive-scheme__top">
                    <span class="derive-scheme__name">{{ s.name }}</span>
                    <span v-if="s.recommended" class="derive-scheme__badge">推荐</span>
                  </div>
                  <div class="derive-scheme__metrics">
                    <span v-for="m in s.metrics" :key="m.label">
                      <em>{{ m.label }}</em>{{ m.value }}
                    </span>
                  </div>
                </button>
              </div>
              <p v-if="pickedScheme" class="derive-scheme__why">
                {{ pickedScheme.reason }}
              </p>
            </template>

            <button type="button" class="derive-link derive-link--detail" @click.stop="openDetail(viewModel[stageKey])">
              查看详情
            </button>
          </section>

          <div
            v-if="idx < stageOrder.length - 1"
            class="derive-flow"
            :class="{
              'derive-flow--lit': viewModel[stageOrder[idx + 1]]?.unlocked || viewModel[stageKey]?.done,
              'derive-flow--active': viewModel[stageKey]?.active && running
            }"
          >
            <span /><span /><span />
          </div>
        </template>
      </div>
    </div>

    <footer class="derive__foot">
      <p class="derive__summary">
        {{ finished ? model.summary.text : (running ? (viewModel.liveTip || model.liveTip || '分析执行中…') : '点击重新分析开始推导') }}
      </p>
      <div class="derive__actions">
        <el-button :disabled="running" @click="$emit('reanalyze')">重新分析</el-button>
        <el-button type="primary" :disabled="!finished" @click="goResult">
          {{ resultLabel }}
        </el-button>
      </div>
    </footer>

    <el-drawer v-model="drawerOpen" :title="drawerTitle" size="400px" append-to-body>
      <ul class="derive-drawer">
        <li v-for="(line, i) in drawerLines" :key="i">{{ line }}</li>
      </ul>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onBeforeUnmount, ref, watch } from 'vue'
import { buildDispatchDerivationModel } from '@/composables/useDispatchDerivation'

const AnimatedNum = defineComponent({
  name: 'AnimatedNum',
  props: {
    value: { type: Number, default: 0 },
    active: { type: Boolean, default: false },
    duration: { type: Number, default: 420 }
  },
  setup(props) {
    const display = ref(0)
    let raf = 0
    function animateTo(target) {
      cancelAnimationFrame(raf)
      if (!props.active) {
        display.value = 0
        return
      }
      const from = display.value
      const start = performance.now()
      const tick = (now) => {
        const t = Math.min(1, (now - start) / props.duration)
        const eased = 1 - (1 - t) ** 3
        display.value = Math.round(from + (target - from) * eased)
        if (t < 1) raf = requestAnimationFrame(tick)
      }
      raf = requestAnimationFrame(tick)
    }
    watch(
      () => [props.value, props.active],
      ([v, on]) => {
        if (!on) {
          display.value = 0
          return
        }
        animateTo(Number(v) || 0)
      },
      { immediate: true }
    )
    onBeforeUnmount(() => cancelAnimationFrame(raf))
    return () => h('span', { class: 'anim-num' }, String(display.value))
  }
})

const props = defineProps({
  preview: { type: Object, default: null },
  mes: { type: Object, default: null },
  revealedStepKeys: { type: Array, default: () => [] },
  activeStepKey: { type: String, default: '' },
  running: { type: Boolean, default: false },
  resultLabel: { type: String, default: '查看工单结果' },
  title: { type: String, default: '工单方案推导' }
})

const emit = defineEmits(['view-result', 'reanalyze', 'select-scheme'])

const stageOrder = ['sources', 'filter', 'match', 'score', 'converge']
const stageTitles = {
  sources: '多源数据输入',
  filter: '约束过滤',
  match: '组合匹配',
  score: '规则评分',
  converge: '方案收敛'
}

const selectedScheme = ref('balance')
const drawerOpen = ref(false)
const drawerTitle = ref('')
const drawerLines = ref([])
const activeElapsedMs = ref(0)
const animScores = ref({})
/** result 步骤内先揭示评分，再揭示收敛 */
const scoreGate = ref(false)
const convergeGate = ref(false)
let elapsedTimer = null
let stageStartedAt = 0
let gateTimers = []

const model = computed(() =>
  buildDispatchDerivationModel(props.preview, props.mes, {
    revealedStepKeys: props.revealedStepKeys,
    activeStepKey: props.activeStepKey,
    running: props.running
  })
)

const viewModel = computed(() => {
  const m = model.value
  if (!props.running) {
    return m
  }
  const scoreUnlocked = m.match.unlocked && scoreGate.value
  const convergeUnlocked = convergeGate.value
  let liveTip = m.liveTip
  if (convergeUnlocked) liveTip = m.converge.liveTip
  else if (scoreUnlocked) liveTip = m.score.liveTip
  return {
    ...m,
    liveTip,
    score: {
      ...m.score,
      unlocked: scoreUnlocked,
      active: scoreUnlocked && !convergeUnlocked,
      done: convergeUnlocked
    },
    converge: {
      ...m.converge,
      unlocked: convergeUnlocked,
      active: convergeUnlocked && props.activeStepKey === 'result',
      done: false
    }
  }
})

const finished = computed(() => model.value.ready)
const statusText = computed(() => {
  if (props.running) return '推导中'
  if (finished.value) return '推导完成'
  return '待开始'
})

const pickedScheme = computed(() =>
  viewModel.value.converge.schemes.find((s) => s.id === selectedScheme.value)
)

function stageClass(stage) {
  return {
    'derive-stage--locked': !stage.unlocked,
    'derive-stage--active': stage.active && props.running,
    'derive-stage--done': stage.done || (stage.unlocked && !props.running && finished.value),
    'derive-stage--enter': stage.unlocked
  }
}

function clearGateTimers() {
  gateTimers.forEach((t) => clearTimeout(t))
  gateTimers = []
}

function armResultGates() {
  clearGateTimers()
  scoreGate.value = true
  convergeGate.value = false
  animateScores()
  gateTimers.push(setTimeout(() => {
    convergeGate.value = true
  }, 750))
}

function formatMs(ms) {
  return `${(ms / 1000).toFixed(1)}s`
}

function openDetail(stage) {
  if (!stage?.detail) return
  drawerTitle.value = stage.detail.title
  drawerLines.value = stage.detail.lines || []
  drawerOpen.value = true
}

function pickScheme(id) {
  selectedScheme.value = id
  emit('select-scheme', id)
}

function goResult() {
  emit('select-scheme', selectedScheme.value)
  emit('view-result')
}

function startElapsed() {
  stopElapsed()
  stageStartedAt = Date.now()
  activeElapsedMs.value = 0
  elapsedTimer = setInterval(() => {
    activeElapsedMs.value = Date.now() - stageStartedAt
  }, 100)
}

function stopElapsed() {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

function animateScores() {
  const metrics = model.value.score.metrics || []
  const next = {}
  metrics.forEach((m) => { next[m.key] = 0 })
  animScores.value = { ...next }
  requestAnimationFrame(() => {
    const filled = {}
    metrics.forEach((m) => { filled[m.key] = m.value })
    animScores.value = filled
  })
}

watch(
  () => [props.activeStepKey, props.running],
  ([key, on]) => {
    if (on && key === 'result') armResultGates()
    if (on && key && key !== 'result') {
      scoreGate.value = false
      convergeGate.value = false
    }
    if (on && key) startElapsed()
  }
)

watch(
  () => props.running,
  (on, was) => {
    if (on && !was) {
      selectedScheme.value = 'balance'
      animScores.value = {}
      scoreGate.value = false
      convergeGate.value = false
      clearGateTimers()
      startElapsed()
    }
    if (!on && was) {
      stopElapsed()
      clearGateTimers()
      scoreGate.value = true
      convergeGate.value = true
      animateScores()
      const rec = model.value.converge.schemes.find((s) => s.recommended)
      if (rec) selectedScheme.value = rec.id
    }
  }
)

watch(
  () => viewModel.value.score.unlocked,
  (on) => {
    if (on) animateScores()
  }
)

onBeforeUnmount(() => {
  stopElapsed()
  clearGateTimers()
})
</script>

<style scoped>
.derive {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #f7f8fa;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  overflow: hidden;
}

.derive__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #e8edf2;
  flex-shrink: 0;
}
.derive__title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #111827;
}
.derive__sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: #6b7280;
}
.derive__status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #374151;
}
.derive__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #d1d5db;
}
.derive__dot--on { background: #0d9488; }
.derive__elapsed {
  font-style: normal;
  font-size: 12px;
  color: #0d9488;
  font-variant-numeric: tabular-nums;
}

.derive__body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 10px 12px;
}

.derive__rail {
  display: grid;
  grid-template-columns:
    minmax(0, 1.05fr) 16px
    minmax(0, 1.15fr) 16px
    minmax(0, 1.15fr) 16px
    minmax(0, 1.2fr) 16px
    minmax(0, 1.25fr);
  gap: 0;
  height: 100%;
  align-items: stretch;
}

.derive-stage {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 10px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  opacity: 1;
  transform: translateY(0);
  transition:
    opacity 0.4s ease,
    transform 0.4s ease,
    border-color 0.2s ease;
}
.derive-stage--locked {
  opacity: 0.38;
  pointer-events: none;
}
.derive-stage--active {
  border-color: #5eead4;
  box-shadow: inset 2px 0 0 #0d9488;
}
.derive-stage--done {
  border-color: #d1d5db;
}
.derive-stage--enter {
  animation: derive-enter 0.4s ease;
}

@keyframes derive-enter {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.derive-stage__head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.derive-stage__idx {
  font-size: 11px;
  font-weight: 700;
  color: #9ca3af;
  letter-spacing: 0.06em;
}
.derive-stage__title {
  margin: 0;
  flex: 1;
  font-size: 15px;
  font-weight: 700;
  color: #111827;
}
.derive-stage__check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #ecfdf5;
  color: #0d9488;
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.derive-stage__loading {
  display: inline-flex;
}
.derive-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid #ccfbf1;
  border-top-color: #0d9488;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.derive-stage__live {
  margin: 0;
  font-size: 12px;
  color: #0f766e;
  line-height: 1.35;
}
.derive-stage__note {
  margin: auto 0 0;
  font-size: 12px;
  color: #4b5563;
  line-height: 1.4;
}

.derive-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.derive-metric {
  padding: 6px 8px;
  background: #f9fafb;
  border-radius: 3px;
}
.derive-metric__num {
  display: block;
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
  color: #0f766e;
  font-variant-numeric: tabular-nums;
}
.derive-metric__label {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #1f2937;
}
.derive-metric__unit {
  font-size: 11px;
  font-style: normal;
  color: #9ca3af;
}

.derive-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.derive-chip {
  font-size: 11px;
  padding: 2px 6px;
  background: #f3f4f6;
  color: #4b5563;
  border-radius: 2px;
}

.derive-filter-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.derive-filter-row__main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.derive-filter-row__label {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
}
.derive-filter-row__nums {
  font-size: 15px;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
  line-height: 1.35;
}
.derive-filter-row__nums :deep(.anim-num) {
  font-size: 22px;
  color: #0f766e;
}
.derive-filter-row__nums i {
  font-style: normal;
  margin: 0 4px;
  color: #0d9488;
}
.derive-filter-row__reason {
  margin: 2px 0 0;
  font-size: 11px;
  color: #6b7280;
  line-height: 1.35;
}

.derive-combo {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
  padding: 4px 0;
}
.derive-combo__block {
  flex: 1;
  text-align: center;
}
.derive-combo__block strong {
  display: block;
  font-size: 28px;
  font-weight: 700;
  color: #6b7280;
  line-height: 1;
}
.derive-combo__block--ok strong { color: #0f766e; }
.derive-combo__block span {
  font-size: 12px;
  color: #6b7280;
}
.derive-combo__arrow {
  color: #0d9488;
  font-weight: 700;
  font-size: 16px;
}

.derive-best {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 2px;
}
.derive-best__row {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 4px 6px;
  background: #f9fafb;
  border-radius: 3px;
}
.derive-best__proc {
  font-size: 12px;
  font-weight: 700;
  color: #111827;
}
.derive-best__pair {
  font-size: 11px;
  color: #4b5563;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.derive-scores {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
  min-height: 0;
}
.derive-score__line {
  display: flex;
  align-items: center;
  gap: 6px;
}
.derive-score__label {
  flex: 1;
  font-size: 12px;
  color: #374151;
  font-weight: 600;
  min-width: 0;
}
.derive-score__val {
  font-size: 14px;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.derive-score__tag {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 2px;
}
.derive-score__tag--pass {
  color: #0d9488;
  background: #ecfdf5;
}
.derive-score__tag--warn {
  color: #b45309;
  background: #fffbeb;
}
.derive-score__bar {
  height: 4px;
  background: #e5e7eb;
  border-radius: 999px;
  overflow: hidden;
  margin: 3px 0 2px;
}
.derive-score__bar i {
  display: block;
  height: 100%;
  background: #14b8a6;
  border-radius: 999px;
  transition: width 0.45s ease;
}
.derive-score__reason {
  margin: 0;
  font-size: 11px;
  color: #6b7280;
  line-height: 1.3;
}

.derive-schemes {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.derive-scheme {
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  background: #fff;
  padding: 8px;
  text-align: left;
  cursor: pointer;
  font: inherit;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.derive-scheme:hover { border-color: #99f6e4; }
.derive-scheme--rec { border-color: #99f6e4; }
.derive-scheme--picked {
  border-color: #0d9488;
  background: #f0fdfa;
}
.derive-scheme__top {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.derive-scheme__name {
  font-size: 14px;
  font-weight: 700;
  color: #111827;
}
.derive-scheme__badge {
  font-size: 10px;
  font-weight: 700;
  color: #fff;
  background: #0d9488;
  padding: 1px 5px;
  border-radius: 2px;
}
.derive-scheme__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  font-size: 11px;
  color: #374151;
}
.derive-scheme__metrics em {
  font-style: normal;
  color: #9ca3af;
  margin-right: 3px;
}
.derive-scheme__why {
  margin: 4px 0 0;
  font-size: 12px;
  color: #0f766e;
  line-height: 1.4;
}

.derive-link {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  color: #0d9488;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
}
.derive-link--detail {
  margin-top: auto;
}

.derive-flow {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  opacity: 0.3;
}
.derive-flow span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #9ca3af;
}
.derive-flow--lit { opacity: 0.85; }
.derive-flow--lit span { background: #2dd4bf; }
.derive-flow--active span {
  background: #0d9488;
  animation: flow-dot 1.2s ease-in-out infinite;
}
.derive-flow--active span:nth-child(2) { animation-delay: 0.15s; }
.derive-flow--active span:nth-child(3) { animation-delay: 0.3s; }

@keyframes flow-dot {
  0%, 100% { opacity: 0.35; transform: scale(0.8); }
  50% { opacity: 1; transform: scale(1.2); }
}

.derive__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  background: #fff;
  border-top: 1px solid #e8edf2;
  flex-shrink: 0;
}
.derive__summary {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.45;
  color: #374151;
}
.derive__actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.derive-drawer {
  margin: 0;
  padding-left: 18px;
  font-size: 14px;
  line-height: 1.75;
  color: #374151;
}

@media (max-width: 1360px) {
  .derive-metric__num { font-size: 22px; }
  .derive-combo__block strong { font-size: 22px; }
  .derive-stage__title { font-size: 13px; }
}
</style>
