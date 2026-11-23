<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ChevronDown, ChevronRight, LayoutDashboard, Minus, PanelLeft, Plus, RotateCcw, Search, X } from 'lucide-vue-next'

const props = defineProps({ nodes: Array, layout: String, zoom: Number, sidebarOpen: Boolean })
const emit = defineEmits(['layout', 'focus', 'zoom', 'reset', 'sidebar'])
const query = ref('')
const locatedId = ref('')
const searchInput = ref(null)
const matches = computed(() => {
  const term = query.value.trim().toLocaleLowerCase()
  if (!term) return []
  const exact = [], titles = [], content = []
  for (const node of props.nodes) {
    const label = (node.label || '').toLocaleLowerCase()
    if (label === term) exact.push(node)
    else if (label.includes(term)) titles.push(node)
    else if ([node.detail, node.metadata?.['Chunk ID'], node.metadata?.['资料编号']].some(value =>
      typeof value === 'string' && value.toLocaleLowerCase().includes(term))) content.push(node)
  }
  return [...exact, ...titles, ...content]
})
const matchIndex = computed(() => matches.value.findIndex(node => node.id === locatedId.value))
let timer
function locateNext() {
  clearTimeout(timer)
  if (!matches.value.length) return
  const node = matches.value[(matchIndex.value + 1) % matches.value.length]
  locatedId.value = node.id
  emit('focus', node.id)
}
function clearQuery() {
  query.value = ''
  searchInput.value?.focus()
}
watch(query, value => {
  clearTimeout(timer)
  locatedId.value = ''
  if (value.trim()) timer = setTimeout(locateNext, 180)
})
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="kg-toolbar" role="group" aria-label="图谱工具">
    <button type="button" class="side-toggle" aria-label="显示或隐藏资料源" title="资料源与筛选"
      :aria-expanded="sidebarOpen" @click="emit('sidebar')"><PanelLeft aria-hidden="true"/></button>
    <form class="kg-find" role="search" aria-label="搜索图谱" @submit.prevent="locateNext">
      <Search aria-hidden="true"/>
      <input ref="searchInput" v-model="query" aria-label="搜索图谱节点" placeholder="搜索节点或片段…" @keydown.esc.prevent.stop="clearQuery"/>
      <span v-if="query.trim()" class="match-count" aria-live="polite">
        {{ matches.length ? (matchIndex + 1) + '/' + matches.length : '无匹配' }}
      </span>
      <button v-if="matches.length > 1" class="search-action" title="下一个匹配节点" aria-label="下一个匹配节点"><ChevronRight/></button>
      <button v-else-if="query" type="button" class="search-action" aria-label="清除搜索" title="清除搜索" @click="clearQuery"><X/></button>
    </form>
    <div class="toolbar-actions">
      <div class="layout-select">
        <LayoutDashboard aria-hidden="true"/>
        <select :value="layout" aria-label="图谱布局" @change="emit('layout', $event.target.value)">
          <option value="spatial">关系聚类</option><option value="force">力导向布局</option>
          <option value="layered">分层布局</option><option value="circular">环形布局</option>
        </select><ChevronDown aria-hidden="true"/>
      </div>
      <div class="kg-zoom" role="group" aria-label="图谱缩放">
        <button type="button" aria-label="缩小图" title="缩小" :disabled="zoom <= 0.25" @click="emit('zoom', 0.8)"><Minus/></button>
        <output aria-label="当前缩放比例">{{ Math.round(zoom * 100) }}%</output>
        <button type="button" aria-label="放大图" title="放大" :disabled="zoom >= 5" @click="emit('zoom', 1.25)"><Plus/></button>
      </div>
      <button type="button" title="重置布局与视图" aria-label="重置图谱布局与视图" @click="emit('reset')"><RotateCcw/></button>
    </div>
  </div>
</template>

<style scoped>
.kg-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; padding: 11px 14px; border-bottom: 1px solid var(--ks-border, #d8e0ea); background: var(--ks-surface, white); color: var(--ks-text, #1f2937); }
button, input, select { font: inherit; }
button, select { cursor: pointer; }
button, .kg-find, .layout-select, .kg-zoom { height: 34px; background: var(--ks-surface, white); border: 1px solid var(--ks-border, #d8e0ea); border-radius: 5px; }
button { display: grid; place-items: center; width: 34px; padding: 0; color: var(--ks-secondary, #4b5563); }
button:hover { background: var(--ks-hover, #eef4ff); color: var(--ks-deep, #163b85); }
button:disabled { opacity: .4; cursor: default; }
button:focus-visible, select:focus-visible, .kg-find:focus-within { outline: 2px solid var(--ks-brand, #2457e6); outline-offset: 1px; }
svg { width: 16px; height: 16px; flex: none; stroke-width: 1.8; }
.kg-find { display: flex; align-items: center; gap: 6px; flex: 1 1 145px; min-width: 110px; margin: 0; padding: 0 7px 0 10px; color: var(--ks-secondary, #4b5563); background: var(--ks-soft, #f6f8fb); }
.kg-find input { width: 100%; min-width: 0; padding: 0; border: none; outline: none; color: var(--ks-strong, #111827); background: transparent; font-size: 13px; }
.kg-find input::placeholder { color: var(--ks-secondary, #4b5563); }
.match-count { flex: none; font-size: 12px; white-space: nowrap; font-variant-numeric: tabular-nums; }
button.search-action { width: 22px; height: 24px; flex: none; background: transparent; border: 0; }
.toolbar-actions { display: flex; align-items: center; gap: 6px; }
.layout-select { position: relative; display: grid; align-items: center; }
.layout-select svg { position: absolute; width: 14px; height: 14px; color: var(--ks-secondary, #4b5563); pointer-events: none; }
.layout-select svg:first-child { left: 8px; }.layout-select svg:last-child { right: 5px; width: 12px; }
select { appearance: none; height: 100%; width: 100%; min-width: 0; padding: 0 17px 0 27px; color: var(--ks-text, #1f2937); border: 0; border-radius: inherit; background: transparent; font-size: 13px; font-weight: 500; }
.kg-zoom { display: flex; align-items: center; }
.kg-zoom button { width: 26px; height: 30px; border: 0; }
.kg-zoom output { width: 38px; color: var(--ks-text, #1f2937); font-size: 12px; font-variant-numeric: tabular-nums; text-align: center; }
@media (max-width: 560px) {
  .kg-toolbar { padding: 8px 10px; gap: 5px; }
  .toolbar-actions { flex: 1 1 100%; }
  .kg-zoom { margin-left: auto; }
}
</style>
