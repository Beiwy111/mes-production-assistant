<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { CircleAlert, FolderSearch, LoaderCircle, PanelRightOpen, Share2 } from 'lucide-vue-next'
import { filterGraph, neighbors, nodeTypes, shortestPath } from '@/utils/workbenchGraph'
import { downloadSource } from './ingestion/ingestionApi'
import { scopeGraphByDocument } from './scopeByDocument'
import './knowledge-studio.css'
import { useKnowledgeDatasets } from './useKnowledgeDatasets'
import DynamicKnowledgeGraphCanvas from './DynamicKnowledgeGraphCanvas.vue'
import GraphToolbar from './GraphToolbar.vue'
import GraphNodeDetailPanel from './GraphNodeDetailPanel.vue'
import KnowledgeDatasetSidebar from './KnowledgeDatasetSidebar.vue'

const props = defineProps({ message:Object, token:String, canIngest:Boolean, jobId:String, chunkId:String, visible:{type:Boolean,default:true} })
const { source, jobId, query, jobs, graph, catalog, documents, documentId, notice, loading, error, jobsError, lastQuery, search, chooseJob, listJobs } = useKnowledgeDatasets(props)
const mode = ref('hybrid'), nodeType = ref(''), file = ref(''), hidden = ref([]), layout = ref('spatial')
const selectedId = ref(''), pins = ref([]), depth = ref(1), zoom = ref(1)
const sidebar = ref(!window.matchMedia('(max-width:760px)').matches)
const detailsOpen = ref(!window.matchMedia('(max-width:1000px)').matches)
const canvas = ref(null), detail = ref(null)
const downloading = ref(false), downloadError = ref('')
let download
const scopedDocument = ref('')
const scopedGraph = computed(() => scopeGraphByDocument(graph.value, scopedDocument.value))
const filtered = computed(() => filterGraph(scopedGraph.value, { mode:mode.value, type:nodeType.value, file:file.value, hidden:hidden.value }))
const destination = ref('')
const path = computed(() => destination.value && selectedId.value ? shortestPath(filtered.value,selectedId.value,destination.value) : [])
const drawn = filtered
const selected = computed(() => scopedGraph.value.nodes.find(n => n.id === selectedId.value))
const adjacent = computed(() => selected.value ? neighbors(filtered.value, selectedId.value) : [])
const pinned = computed(() => pins.value.map(id => scopedGraph.value.nodes.find(node => node.id === id)).filter(Boolean))
const legend = computed(() => [...new Set(drawn.value.nodes.map(node => node.type))])
const allJobs = computed(() => source.value === 'uploads' && !jobId.value && !lastQuery.value)
const scopeTitle = computed(() => ({ all:'全部可访问资料', catalog:'公开知识库', search:'公开检索结果', turn:'当前回答证据', uploads:'入库资料' })[source.value])
const scopeChunks = computed(() => scopedGraph.value.nodes.filter(node => node.type === 'chunk').length)
const emptyText = computed(() => {
  if (loading.value) return '正在读取知识节点…'
  if (error.value) return error.value
  if (graph.value.nodes.length) return '当前筛选没有匹配节点。'
  if (source.value === 'catalog') return '当前发布版本没有公开文档。'
  if (source.value === 'search' && !notice.value) return '输入关键词检索公开资料。'
  if (source.value === 'turn' && !notice.value) return '本轮回答没有可展示的公开知识证据。'
  if (allJobs.value) return '当前没有已完成入库的文件。'
  return notice.value || '检索没有匹配到知识节点。'
})
function resetFilters() { mode.value='hybrid'; nodeType.value=''; file.value=''; hidden.value=[] }
function toggleRelation(rel) { hidden.value = hidden.value.includes(rel) ? hidden.value.filter(r=>r!==rel) : [...hidden.value,rel] }
function togglePin(id) { pins.value = pins.value.includes(id) ? pins.value.filter(p=>p!==id) : [...pins.value,id] }
function select(id) {
  if (selectedId.value !== id) destination.value=''
  selectedId.value = id; downloadError.value = ''; detailsOpen.value = true
  if (window.matchMedia('(max-width:760px)').matches) sidebar.value = false
  nextTick(() => detail.value?.showDetail())
}
async function focus(id) {
  let index = filtered.value.nodes.findIndex(node => node.id===id)
  if (index === -1) {
    resetFilters()
    index = filtered.value.nodes.findIndex(node => node.id===id)
  }
  if (index === -1) return
  select(id)
  await nextTick()
  canvas.value?.focus(id)
}
function toggleSidebar() {
  sidebar.value = !sidebar.value
  if (sidebar.value && window.matchMedia('(max-width:760px)').matches) detailsOpen.value=false
}
async function downloadFile(hit) {
  if (!props.canIngest || downloading.value) return
  const request = download = new AbortController()
  downloading.value=true; downloadError.value=''
  try { await downloadSource(props.token,hit.jobId,hit.filename,request.signal) }
  catch (e) { if (!request.signal.aborted) downloadError.value=e.message }
  finally { if (request === download) downloading.value=false }
}
watch(graph, () => { scopedDocument.value=''; selectedId.value=''; pins.value=[]; resetFilters(); download?.abort(); downloadError.value='' })
// A document jump arrives before its asynchronous job details. Resolve only in the requested job.
const pendingChunk = ref(props.chunkId)
watch(graph, value => {
  if (source.value !== 'uploads' || jobId.value !== props.jobId || !pendingChunk.value) return
  const node = value.nodes.find(n => n.type === 'chunk' && n.evidence.some(hit =>
    hit.jobId === props.jobId && hit.chunkId === pendingChunk.value))
  if (node) { pendingChunk.value = ''; focus(node.id) }
}, { flush:'post' })
watch(scopedDocument, () => { resetFilters(); pins.value=[]; selectedId.value=''; destination.value='' })
watch(pins, ids => { if (!ids.includes(destination.value)) destination.value='' })
watch(filtered, value => {
  if (!value.nodes.some(n => n.id===selectedId.value)) selectedId.value=''
})
onBeforeUnmount(() => download?.abort())
watch(() => props.visible, value => {
  if (!value) return
  if (source.value === 'uploads' && props.canIngest) { listJobs(); search() }
  if (['catalog','all'].includes(source.value)) search()
})
</script>

<template>
  <div class="kg-studio">
    <KnowledgeDatasetSidebar v-if="sidebar" :source="source" :mode="mode" :query="query" :graph="graph" :type="nodeType" :file="file"
      :hidden="hidden" :loading="loading" :jobs="jobs" :job-id="jobId" :can-ingest="canIngest" @source="source=$event" @mode="mode=$event"
      :scoped-document="scopedDocument" @scoped-document="scopedDocument=$event" :documents="documents" :document-id="documentId" @document="documentId=$event"
      @query="query=$event" @search="search" @type="nodeType=$event" @file="file=$event" @relation="toggleRelation" @job="chooseJob"/>
    <main class="kg-center">
      <div class="kg-scope" role="status"><strong>{{ scopeTitle }}</strong>
        <span v-if="source==='catalog' && catalog">全库 {{ catalog.counts.documents }} 份文档 · {{ catalog.counts.chunks }} 个片段<template v-if="!documentId"> · {{ graph.nodes.length }} 个节点</template></span>
        <span v-if="source==='all'">含公开库与授权入库资料 · {{ graph.nodes.filter(n => n.type==='document').length }} 份文档</span>
        <span v-if="graph.nodes.length && (source!=='catalog' || documentId)">{{ documentId && source==='catalog' ? '所选文档' : '当前范围' }} {{ scopeChunks }} 个片段 · {{ scopedGraph.nodes.length }} 个节点</span>
      </div>
      <GraphToolbar :sidebar-open="sidebar" :key="source+jobId" :nodes="filtered.nodes" :layout="layout" :zoom="zoom" @sidebar="toggleSidebar"
        @layout="layout=$event" @focus="focus" @zoom="canvas?.zoom($event)" @reset="canvas?.reset()"/>
      <div class="kg-field">
        <button v-if="!detailsOpen" class="open-detail" title="打开图谱详情" aria-label="打开图谱详情" @click="detailsOpen=true"><PanelRightOpen/></button>
        <DynamicKnowledgeGraphCanvas v-if="drawn.nodes.length" ref="canvas" :graph="drawn" :layout="layout" :selected="selectedId" :depth="depth" :path="path" @select="select" @zoom="zoom=$event"/>
        <div v-else class="kg-placeholder" role="status">
          <span class="empty-icon"><LoaderCircle v-if="loading" class="spin"/><CircleAlert v-else-if="error"/><FolderSearch v-else/></span>
          <p>{{ emptyText }}</p><button v-if="error" @click="search">重新加载</button>
          <button v-else-if="graph.nodes.length" @click="resetFilters">清除筛选</button>
          <button v-else-if="allJobs && !sidebar" @click="toggleSidebar">选择文件</button>
        </div>

      </div>
      <div v-if="error && drawn.nodes.length" class="kg-notice" role="alert">{{ error }} <button @click="search">重新加载全部来源</button></div>
      <div v-if="jobsError" class="kg-notice" role="alert">{{ jobsError }} <button @click="listJobs">重试文件列表</button></div>
      
      <footer v-if="drawn.nodes.length">        <div class="kg-overview"><Share2/><span>{{ {hybrid:'混合图谱',rag:'文档片段图',graph:'实体关系图'}[mode] }}</span>
          <small>已展示 {{ drawn.nodes.length }} / {{ filtered.nodes.length }} 节点 · {{ drawn.edges.length }} 条连线</small></div>
        <span v-if="filtered.nodes.length !== scopedGraph.nodes.length" class="kg-filter-count">筛选前 {{ scopedGraph.nodes.length }} 个节点</span>
        <div class="kg-legend" aria-label="图例"><span v-for="kind in legend" :key="kind"><i :style="{ background:nodeTypes[kind].color }"/>{{ nodeTypes[kind].label }}</span></div>
        <details v-if="notice" class="release-info"><summary>数据来源</summary><div>{{ notice }}</div></details>
      </footer>
    </main>
    <GraphNodeDetailPanel v-if="detailsOpen" ref="detail" :selected="selected" :nodes="drawn.nodes" :neighbors="adjacent" :pins="pinned" :depth="depth"
      :downloading="downloading" :download-error="downloadError" :destination="destination" :path="path" @destination="destination=$event"
      @download="downloadFile" @pin="togglePin" @depth="depth=$event; destination=''" @focus="focus" @hide="detailsOpen=false"/>
    <button v-if="sidebar" class="mobile-close" @click="sidebar=false">收起资料源</button>
  </div>
</template>

<style scoped>
.kg-studio {display:flex;position:relative;min-height:0;min-width:0;flex:1;height:100%;overflow:hidden}
.kg-studio :deep(*){box-sizing:border-box}
.kg-center {display:flex;flex:1 1 0;flex-direction:column;min-width:0;min-height:0;background:var(--ks-surface)}
.kg-scope {display:flex;align-items:baseline;flex-wrap:wrap;gap:4px 14px;min-height:55px;padding:14px 18px 8px;color:var(--ks-secondary);font-size:13px}
.kg-scope strong {color:var(--ks-strong);font-size:19px;line-height:1.35;font-weight:600}
.kg-field {position:relative;isolation:isolate;flex:1;min-height:0;overflow:hidden;background-color:#fbfcfe;background-image:radial-gradient(#d4dde8 .8px,transparent .9px);background-size:22px 22px}
.kg-overview,.kg-pages,.kg-legend,.kg-legend span {display:flex;align-items:center;gap:6px}
.kg-overview {color:var(--ks-secondary);font-size:12px}.kg-overview small{font-size:12px;color:var(--ks-secondary)}
.kg-overview svg,.kg-pages svg,.open-detail svg {width:15px;height:15px}
.kg-center footer {position:relative;display:flex;align-items:center;flex-wrap:wrap;gap:6px 14px;min-height:38px;padding:6px 14px;border-top:1px solid var(--ks-border);font-size:12px;background:var(--ks-surface)}
.kg-legend {flex-wrap:wrap;gap:4px 9px;color:var(--ks-secondary)}.kg-legend i{width:7px;height:7px;border-radius:2px}
.kg-pages {margin-left:auto;white-space:nowrap;font-variant-numeric:tabular-nums}
.kg-pages button,.open-detail,.kg-placeholder button,.kg-notice button,.mobile-close {border:1px solid var(--ks-border);border-radius:4px;background:var(--ks-surface);color:var(--ks-deep);cursor:pointer;font:inherit}
.kg-pages button{display:grid;place-items:center;width:24px;height:24px}.kg-pages button:disabled{opacity:.4;cursor:default}
.open-detail,.mobile-close {position:absolute;z-index:2;display:flex;align-items:center;gap:6px;padding:6px 9px;font-size:13px}
.open-detail {right:12px;top:12px}.mobile-close{display:none}
.release-info {margin-left:auto;color:var(--ks-secondary)}.release-info summary{cursor:pointer}.release-info>div{position:absolute;z-index:3;right:8px;bottom:100%;max-width:min(420px,90%);padding:12px;background:white;border:1px solid var(--ks-border);border-radius:5px;overflow-wrap:anywhere}
.kg-placeholder {position:absolute;inset:15px;display:grid;align-content:center;justify-items:center;text-align:center;font-size:14px;color:var(--ks-secondary)}
.empty-icon {display:grid;place-items:center;width:42px;height:42px;border:1px solid var(--ks-border);border-radius:6px;background:var(--ks-soft);color:var(--ks-deep)}
.empty-icon svg{width:20px}.kg-placeholder p {max-width:410px;overflow-wrap:anywhere}
.kg-notice{padding:8px 14px;border-top:1px solid var(--ks-border);font-size:13px;color:var(--ks-text)}
.kg-placeholder button,.kg-notice button{padding:6px 10px}
.spin{animation:kg-rotate 1s linear infinite}@keyframes kg-rotate{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.spin{animation:none}}
@media(max-width:1150px){.kg-center footer{column-gap:10px}.kg-overview>span{display:none}}
@media(max-width:1000px){.kg-detail{position:absolute;right:0;top:0;bottom:0;width:min(320px,85%);z-index:3;box-shadow:-4px 0 12px #24344912}}
@media(max-width:760px){.kg-sidebar{position:absolute;left:0;top:0;bottom:0;width:min(260px,86%);z-index:4;padding-bottom:52px;box-shadow:3px 0 12px #24344914}.mobile-close{display:block;left:12px;bottom:8px;z-index:5}.kg-scope{padding:10px 12px 6px;min-height:45px}.kg-scope strong{font-size:17px}.kg-center footer{padding:5px 8px}.kg-legend{gap:6px}}
</style>
