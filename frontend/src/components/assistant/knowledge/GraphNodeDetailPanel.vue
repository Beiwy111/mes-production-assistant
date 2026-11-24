<script setup>
import { computed, ref, watch } from 'vue'
import {
  ArrowDownRight, ArrowUpLeft, ChevronDown, ChevronRight, FileText, Focus,
  GitBranch, Link2, ListTree, Pin, SlidersHorizontal, X,
} from 'lucide-vue-next'
import { nodeTypes } from '@/utils/workbenchGraph'
import EvidencePanel from './EvidencePanel.vue'

const props = defineProps({
  selected: Object, nodes: Array, neighbors: Array, pins: Array, depth: Number,
  destination: String, path: Array, downloading: Boolean, downloadError: String,
})
const emit = defineEmits(['focus', 'depth', 'pin', 'download', 'hide', 'destination'])
const tab = ref('detail')
const neighborCount = ref(18)
const sharedCount = ref(8)
const fullDescription = ref(false)
const body = ref(null)
watch(() => props.selected?.id, () => {
  neighborCount.value = 18
  fullDescription.value = false
  if (body.value) body.value.scrollTop = 0
})
const selectedType = computed(() => nodeTypes[props.selected?.type])
const pinned = computed(() => props.pins.some(node => node.id === props.selected?.id))
const otherPins = computed(() => props.pins.filter(node => node.id !== props.selected?.id))
watch(() => props.pins, () => { sharedCount.value = 8 })
const description = computed(() => {
  const detail = props.selected?.detail || ''
  if (detail === props.selected?.label) return ''
  return fullDescription.value || detail.length <= 340 ? detail : detail.slice(0, 340) + '…'
})
const metadata = computed(() => {
  const rows = []
  for (const [key, value] of Object.entries(props.selected?.metadata || {})) {
    if (typeof value === 'string' && value.trim() || typeof value === 'number') rows.push([key, value])
    else if (typeof value === 'boolean') rows.push([key, value ? '是' : '否'])
    else if (Array.isArray(value)) {
      const values = value.filter(item => typeof item === 'string' || typeof item === 'number')
      if (values.length) rows.push([key, values.join(' · ')])
    }
  }
  return rows
})
const documentNodes = computed(() => props.nodes.filter(node => node.type === 'document').slice(0, 4))
const groups = computed(() => {
  const visible = props.neighbors.slice(0, neighborCount.value)
  return [
    { label: '上游', icon: ArrowUpLeft, items: visible.filter(item => !item.outgoing) },
    { label: '下游', icon: ArrowDownRight, items: visible.filter(item => item.outgoing) },
  ].filter(group => group.items.length)
})
const sharedEvidence = computed(() => {
  if (props.pins.length < 2) return []
  const references = props.pins.slice(1).map(node => new Set(node.evidence.map(hit => hit.evidence_ref)))
  return props.pins[0].evidence.filter(hit => references.every(ids => ids.has(hit.evidence_ref)))
})
const pathNodes = computed(() => {
  const nodes = new Map(props.nodes.map(node => [node.id, node]))
  return props.path.map(id => nodes.get(id)).filter(Boolean)
})
function inspect(id) {
  tab.value = 'detail'
  emit('focus', id)
}
function toggleTab(event) {
  const tabs = Array.from(event.currentTarget.querySelectorAll('[role=tab]'))
  const position = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : Number(tab.value === 'detail')
  tabs[position].focus()
  tabs[position].click()
}
defineExpose({ showDetail: () => { tab.value = 'detail' } })
</script>

<template>
  <aside class="kg-detail" aria-label="节点详情与分析">
    <header>
      <div class="tabs" role="tablist" aria-label="节点信息"
        @keydown.left.prevent="toggleTab" @keydown.right.prevent="toggleTab" @keydown.home.prevent="toggleTab" @keydown.end.prevent="toggleTab">
        <button id="kg-tab-detail" role="tab" :aria-selected="tab === 'detail'" aria-controls="kg-panel-detail"
          :tabindex="tab === 'detail' ? 0 : -1" @click="tab = 'detail'">节点详情</button>
        <button id="kg-tab-pins" role="tab" :aria-selected="tab === 'pins'" aria-controls="kg-panel-pins"
          :tabindex="tab === 'pins' ? 0 : -1" @click="tab = 'pins'">分析面板<span v-if="pins.length" class="tab-count">{{ pins.length }}</span></button>
      </div>
      <button class="hide" title="收起详情" aria-label="收起图谱详情" @click="emit('hide')"><X aria-hidden="true"/></button>
    </header>

    <div v-if="tab === 'pins'" id="kg-panel-pins" class="body" role="tabpanel" aria-labelledby="kg-tab-pins">
      <div v-if="!pins.length" class="empty"><Pin aria-hidden="true"/><h3>选择分析节点</h3><p>在节点详情中点击「加入分析」，可对照来源、证据与关联路径。</p></div>
      <template v-else>
        <h4>已加入分析 <span class="count">{{ pins.length }}</span></h4>
        <div v-for="node in pins" :key="node.id" class="pin-row">
          <div><span class="eyebrow">{{ nodeTypes[node.type]?.label || node.type }}</span>
            <button class="text-link pin-link" @click="inspect(node.id)">{{ node.label }}<ChevronRight aria-hidden="true"/></button>
            <small>引用片段 {{ node.evidence.length }} 个</small>
          </div>
          <button class="remove" title="移出分析" aria-label="移出分析" @click="emit('pin', node.id)"><X aria-hidden="true"/></button>
        </div>
        <details v-if="pins.length > 1" class="section analysis" open>
          <summary><Link2 aria-hidden="true"/>共同引用<span class="count">{{ sharedEvidence.length }}</span><ChevronDown class="disclosure" aria-hidden="true"/></summary>
          <p v-if="!sharedEvidence.length" class="hint">这些分析节点没有共同引用的片段。</p>
          <ul v-else class="shared-list"><li v-for="hit in sharedEvidence.slice(0, sharedCount)" :key="hit.evidence_ref">
            <button class="text-link" @click="inspect(hit.jobId ? hit.evidence_ref : 'chunk:' + hit.evidence_ref)">
              <span>{{ hit.title }}<small>{{ hit.location || hit.chunk_id || hit.evidence_ref }}</small></span><ChevronRight aria-hidden="true"/>
            </button>
          </li></ul>
          <button v-if="sharedEvidence.length > sharedCount" class="text-link" @click="sharedCount += 16">更多共同片段（剩余 {{ sharedEvidence.length - sharedCount }}）</button>
        </details>
      </template>
    </div>

    <div v-else id="kg-panel-detail" ref="body" class="body" role="tabpanel" aria-labelledby="kg-tab-detail">
      <label v-if="nodes.length" class="node-list">定位节点
        <span class="select-wrap"><select aria-label="定位节点" :value="selected?.id || ''" @change="inspect($event.target.value)">
          <option value="" disabled>选择节点…</option>
          <option v-for="node in nodes" :key="node.id" :value="node.id">{{ nodeTypes[node.type]?.label || node.type }} · {{ node.label }}</option>
        </select><ChevronDown aria-hidden="true"/></span>
      </label>
      <template v-if="selected">
        <div class="node-head">
          <div class="node-kicker"><span class="type"><i :style="{ background: selectedType?.color }"/>{{ selectedType?.label || selected.type }}</span>
            <small v-if="selected.provenance">{{ selected.provenance }}</small></div>
          <h3>{{ selected.label }}</h3>
          <p v-if="description">{{ description }}</p>
          <button v-if="description && selected.detail.length > 340" class="text-link" @click="fullDescription = !fullDescription">{{ fullDescription ? '收起正文' : '展开完整正文' }}</button>
          <div class="actions"><button @click="emit('focus', selected.id)"><Focus aria-hidden="true"/>聚焦节点</button>
            <button :aria-pressed="pinned" @click="emit('pin', selected.id)"><Pin aria-hidden="true"/>{{ pinned ? '已加入分析' : '加入分析' }}</button>
          </div>
        </div>

        <EvidencePanel class="section" :node="selected" :downloading="downloading" :download-error="downloadError"
          @focus="inspect" @download="emit('download', $event)"/>

        <section class="section">
          <h4><GitBranch aria-hidden="true"/>上下游关系 <span class="count">{{ neighbors.length }}</span></h4>
          <div class="depths" role="group" aria-label="邻居高亮范围">
            <button v-for="level in [1, 2]" :key="level" :aria-pressed="level === depth && !destination" @click="emit('depth', level)">
              高亮{{ level === 1 ? '一' : '二' }}阶邻居
            </button>
          </div>
          <div v-for="group in groups" :key="group.label" class="direction">
            <h5><component :is="group.icon" aria-hidden="true"/>{{ group.label }}</h5>
            <button v-for="item in group.items" :key="JSON.stringify([item.edge.from, item.edge.to, item.edge.label])" class="neighbor" @click="inspect(item.node.id)">
              <span class="edge-label">{{ item.edge.label }}</span><span>{{ item.node.label }}</span><ChevronRight aria-hidden="true"/>
            </button>
          </div>
          <p v-if="!neighbors.length" class="hint">当前筛选下暂无相连节点。</p>
          <button v-if="neighbors.length > neighborCount" class="text-link" @click="neighborCount += 36">更多关系（剩余 {{ neighbors.length - neighborCount }}）</button>
        </section>
        <section v-if="otherPins.length" class="section">
          <h4><ListTree aria-hidden="true"/>路径分析</h4>
          <label class="node-list">到分析节点的最短路径
            <span class="select-wrap"><select :value="destination" @change="emit('destination', $event.target.value)">
              <option value="">选择终点…</option><option v-for="node in otherPins" :key="node.id" :value="node.id">{{ node.label }}</option>
            </select><ChevronDown aria-hidden="true"/></span>
          </label>
          <p v-if="destination" class="hint" role="status">{{ path.length ? '沿 ' + (path.length - 1) + ' 条关系相连' : '当前筛选下无相连路径' }}</p>
          <ol v-if="pathNodes.length" class="path-list"><li v-for="node in pathNodes" :key="node.id">{{ node.label }}</li></ol>
        </section>
        <details v-if="metadata.length" class="section metadata">
          <summary><SlidersHorizontal aria-hidden="true"/>元数据<span class="count">{{ metadata.length }}</span><ChevronDown class="disclosure" aria-hidden="true"/></summary>
          <dl><template v-for="[key, value] in metadata" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template></dl>
        </details>
      </template>
      <template v-else>
        <div class="empty"><Focus aria-hidden="true"/><h3>选择图谱节点</h3><p>点击画布中的节点，或搜索名称，即可查看引用片段与上下游关系。</p></div>
        <section v-if="documentNodes.length" class="section">
          <h4><FileText aria-hidden="true"/>本页来源文档</h4>
          <button v-for="node in documentNodes" :key="node.id" class="neighbor document-link" @click="inspect(node.id)">
            <span>{{ node.label }}</span><ChevronRight aria-hidden="true"/>
          </button>
        </section>
      </template>
    </div>
  </aside>
</template>

<style scoped>
.kg-detail {
  width: clamp(260px, 21vw, 320px); min-height: 0; flex: none; display: flex; flex-direction: column;
  border-left: 1px solid var(--ks-border, #d8e0ea); background: var(--ks-surface, white); color: var(--ks-text, #1f2937);
}
header { display: flex; gap: 8px; align-items: center; padding: 0 15px; min-height: 53px; border-bottom: 1px solid var(--ks-border, #d8e0ea); }
.tabs { display: flex; gap: 17px; align-self: stretch; }
button, select { font: inherit; color: inherit; cursor: pointer; }
button { background: transparent; border: 0; }
button:focus-visible, select:focus-visible, summary:focus-visible { outline: 2px solid var(--ks-brand, #2457e6); outline-offset: 2px; }
svg { flex: none; width: 15px; height: 15px; stroke-width: 1.8; }
.tabs button { display: flex; align-items: center; gap: 4px; padding: 1px 0 0; border-bottom: 2px solid transparent; color: var(--ks-secondary, #4b5563); font-size: 14px; font-weight: 550; }
.tabs button[aria-selected=true] { border-color: var(--ks-brand, #2457e6); color: var(--ks-deep, #163b85); font-weight: 650; }
.count, .tab-count { padding: 0 5px; border-radius: 4px; background: var(--ks-soft, #f6f8fb); color: var(--ks-secondary, #4b5563); font-size: 12px; font-weight: 550; font-variant-numeric: tabular-nums; }
.hide, .remove { display: grid; place-items: center; width: 27px; height: 27px; margin-left: auto; border-radius: 4px; color: var(--ks-secondary, #4b5563); }
.hide:hover, .remove:hover, .neighbor:hover { background: var(--ks-hover, #eef4ff); }
.body { padding: 15px 16px 22px; overflow: auto; overflow-wrap: anywhere; min-height: 0; scrollbar-width: thin; overscroll-behavior: contain; }
.node-list { display: grid; gap: 5px; font-size: 13px; font-weight: 500; color: var(--ks-secondary, #4b5563); }
.select-wrap { position: relative; display: block; }
.select-wrap svg { position: absolute; right: 8px; top: 50%; transform: translateY(-50%); pointer-events: none; }
select { display: block; width: 100%; height: 34px; appearance: none; padding: 0 26px 0 8px; border: 1px solid var(--ks-border, #d8e0ea); border-radius: 5px; background: white; color: var(--ks-text, #1f2937); font-size: 13px; text-overflow: ellipsis; }
.node-head { margin-top: 17px; }
.node-kicker, .type, .actions, .actions button, h4, h5, summary, .depths { display: flex; align-items: center; gap: 6px; }
.node-kicker { flex-wrap: wrap; gap: 5px 8px; }
.type { padding: 2px 7px; background: var(--ks-soft, #f6f8fb); border: 1px solid var(--ks-border, #d8e0ea); border-radius: 4px; color: var(--ks-text, #1f2937); font-size: 12px; font-weight: 600; }
.type i { width: 7px; height: 7px; border-radius: 2px; }
small, .eyebrow, .hint { color: var(--ks-secondary, #4b5563); font-size: 12px; }
h3 { margin: 7px 0; color: var(--ks-strong, #111827); font-size: 19px; line-height: 1.45; font-weight: 650; }
.node-head p { margin: 6px 0; color: var(--ks-text, #1f2937); font-size: 14px; line-height: 1.7; white-space: pre-wrap; }
.actions { flex-wrap: wrap; margin-top: 10px; }
.actions button, .depths button { min-height: 30px; padding: 4px 7px; border: 1px solid var(--ks-border, #d8e0ea); border-radius: 4px; background: white; color: var(--ks-text, #1f2937); font-size: 12px; font-weight: 550; }
.actions button:hover, .depths button:hover { background: var(--ks-hover, #eef4ff); }
.actions button[aria-pressed=true], .depths button[aria-pressed=true] { background: var(--ks-active, #e5eeff); color: var(--ks-deep, #163b85); border-color: #a3bbea; }
.section { margin: 16px 0 0; padding: 13px 0 0; border-top: 1px solid var(--ks-border, #d8e0ea); }
h4, .section > summary { margin: 0; color: var(--ks-strong, #111827); font-size: 15px; font-weight: 650; }
h4 svg, .section > summary svg:first-child { color: var(--ks-deep, #163b85); }
.section > summary { cursor: pointer; }
summary::-webkit-details-marker { display: none; }
.disclosure { margin-left: auto; width: 13px; transform: rotate(-90deg); }
details[open] > summary .disclosure { transform: none; }
.depths, .section > .node-list { margin-top: 8px; }
.direction { margin-top: 10px; }
h5 { margin: 0; color: var(--ks-secondary, #4b5563); font-size: 12px; font-weight: 600; }
.neighbor { display: flex; align-items: center; gap: 7px; width: 100%; padding: 6px 3px; border-bottom: 1px solid var(--ks-border, #d8e0ea); border-radius: 2px; color: var(--ks-text, #1f2937); font-size: 13px; text-align: left; }
.neighbor svg, .pin-link svg { margin-left: auto; width: 13px; color: var(--ks-secondary, #4b5563); }
.edge-label { flex: none; max-width: 40%; color: var(--ks-secondary, #4b5563); font-size: 12px; }
.hint { margin: 7px 0; line-height: 1.6; }
.path-list { margin: 8px 0 0; padding-left: 24px; font-size: 13px; }
.path-list li { padding: 3px 0; }
.path-list li::marker { color: var(--ks-teal, #0f8b8d); font-weight: 650; }
dl { display: grid; grid-template-columns: minmax(54px, auto) minmax(0, 1fr); gap: 6px 9px; margin-bottom: 0; font-size: 12px; }
dt { color: var(--ks-secondary, #4b5563); }dd { margin: 0; }
.empty { padding: 17px 0 5px; }
.empty > svg { width: 30px; height: 30px; padding: 6px; border-radius: 5px; background: var(--ks-active, #e5eeff); color: var(--ks-deep, #163b85); }
.empty h3 { font-size: 16px; margin: 6px 0 3px; }
.empty p { margin: 0; color: var(--ks-secondary, #4b5563); font-size: 13px; line-height: 1.7; }
.document-link:first-of-type { margin-top: 5px; }
.pin-row { display: flex; align-items: center; gap: 6px; padding: 10px 0; border-bottom: 1px solid var(--ks-border, #d8e0ea); }
.pin-row > div { flex: 1; min-width: 0; }
.text-link { display: inline-flex; align-items: center; gap: 5px; padding: 2px 0; color: var(--ks-deep, #163b85); font-size: 12px; text-align: left; }
.text-link:hover { text-decoration: underline; }
.pin-link { display: flex; width: 100%; font-size: 14px; font-weight: 600; }
.shared-list { margin: 6px 0; padding: 0; list-style: none; }
.shared-list .text-link { width: 100%; justify-content: space-between; padding: 5px 0; font-size: 13px; }
.shared-list small { display: block; }
</style>
