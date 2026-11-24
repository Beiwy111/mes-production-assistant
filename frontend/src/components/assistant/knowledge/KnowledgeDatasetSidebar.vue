<script setup>
import { computed } from 'vue'
import { BookOpen, Check, ChevronDown, Database, FileSearch, FolderOpen, MessagesSquare, RefreshCw, Search } from 'lucide-vue-next'
import { filterGraph, nodeTypes } from '@/utils/workbenchGraph'
import { scopeGraphByDocument } from './scopeByDocument'

const props = defineProps({
  source: String, mode: String, query: String, type: String, file: String,
  graph: Object, hidden: Array, loading: Boolean, jobs: Array, jobId: String,
  canIngest: Boolean, documents: Array, documentId: String, scopedDocument: String,
})
const emit = defineEmits(['source', 'mode', 'query', 'search', 'type', 'file', 'relation', 'job', 'document', 'scoped-document'])
const sources = computed(() => [
  { id: 'catalog', label: '公开知识库总览', icon: Database },
  { id: 'search', label: '公开资料检索', icon: FileSearch },
  ...(props.canIngest ? [{ id: 'uploads', label: '入库资料', icon: FolderOpen }] : []),
  { id: 'turn', label: '当前回答证据', icon: MessagesSquare },
])
const modes = [
  { id: 'hybrid', label: '混合', title: '文档片段与实体关系' },
  { id: 'rag', label: 'RAG', title: '文档与索引片段' },
  { id: 'graph', label: 'GraphRAG', title: '实体与研究关系' },
]
const scopeOptions = computed(() => props.graph.nodes.filter(node => node.type === 'document'))
const scopedGraph = computed(() => scopeGraphByDocument(props.graph, props.scopedDocument))
const kinds = computed(() => [...new Set(scopedGraph.value.nodes.map(node => node.type))])
const files = computed(() => [...new Set(scopedGraph.value.nodes.flatMap(node => node.fileTypes || []))].sort())
const relations = computed(() => {
  const counts = new Map()
  const available = filterGraph(scopedGraph.value, { mode: props.mode, type: props.type, file: props.file })
  for (const edge of available.edges) counts.set(edge.label, (counts.get(edge.label) || 0) + 1)
  return [...counts]
})
</script>

<template>
  <aside id="knowledge-graph-sources" class="kg-sidebar" aria-label="知识库与资料源">
    <section class="library">
      <h3><BookOpen aria-hidden="true"/>资料源</h3>
      <nav aria-label="选择资料源">
        <button v-for="item in sources" :key="item.id" type="button" class="dataset"
          :aria-pressed="source === item.id" @click="emit('source', item.id)">
          <component :is="item.icon" aria-hidden="true"/><span>{{ item.label }}</span>
        </button>
      </nav>

      <div v-if="source === 'catalog'" class="source-fields">
        <div class="section-caption">
          <label for="kg-catalog-document">浏览文档</label>
          <button type="button" class="refresh" :disabled="loading" title="刷新目录" @click="emit('search')">
            <RefreshCw aria-hidden="true"/>刷新目录
          </button>
        </div>
        <div class="select-wrap">
          <select id="kg-catalog-document" :disabled="loading || !documents.length" :value="documentId" @change="emit('document', $event.target.value)">
            <option value="">{{ loading ? '正在读取目录…' : '全部公开文档' }}</option>
            <option v-for="doc in documents" :key="doc.id" :value="doc.id">{{ doc.title }}（{{ doc.chunks }} 个片段）</option>
          </select><ChevronDown aria-hidden="true"/>
        </div>
      </div>
      <div v-if="source === 'uploads' || source === 'search'" class="source-fields">
        <label v-if="source === 'uploads'" class="field">已入库文件
          <span class="select-wrap"><select aria-label="已入库文件" :value="jobId" @change="emit('job', $event.target.value)">
            <option value="">全部文件</option><option v-for="job in jobs" :key="job.id" :value="job.id">{{ job.filename }}</option>
          </select><ChevronDown aria-hidden="true"/></span>
        </label>
        <form @submit.prevent="emit('search')">
          <label class="field">检索资料
            <input :value="query" maxlength="200" :aria-label="source === 'search' ? '公开知识检索词' : '入库资料检索词'"
              placeholder="输入检索关键词" @input="emit('query', $event.target.value)"/>
          </label>
          <button class="search" :disabled="loading || (!query.trim() && !(source === 'uploads' && jobId))">
            <Search aria-hidden="true"/>{{ loading ? '检索中…' : '检索资料' }}
          </button>
        </form>
      </div>
    </section>

    <section>
      <h3>图谱模式</h3>
      <div class="modes" role="group" aria-label="图谱模式">
        <button v-for="item in modes" :key="item.id" type="button" :aria-pressed="item.id === mode"
          :title="item.title" @click="emit('mode', item.id)">{{ item.label }}</button>
      </div>
    </section>
    <section>
      <h3>节点筛选</h3>
      <label v-if="source !== 'catalog' && scopeOptions.length > 1" class="field">来源文档
        <span class="select-wrap"><select aria-label="来源文档" :value="scopedDocument || ''" @change="emit('scoped-document', $event.target.value)">
          <option value="">全部来源文档</option>
          <option v-for="doc in scopeOptions" :key="doc.id" :value="doc.id">{{ doc.label }}</option>
        </select><ChevronDown aria-hidden="true"/></span>
      </label>
      <label class="field">文件类型
        <span class="select-wrap"><select aria-label="文件类型" :value="file" @change="emit('file', $event.target.value)">
          <option value="">全部格式</option><option v-for="format in files" :key="format">{{ format }}</option>
        </select><ChevronDown aria-hidden="true"/></span>
      </label>
      <label class="field">节点类别
        <span class="select-wrap"><select aria-label="节点类别" :value="type" @change="emit('type', $event.target.value)">
          <option value="">全部类别</option><option v-for="kind in kinds" :key="kind" :value="kind">{{ nodeTypes[kind]?.label || kind }}</option>
        </select><ChevronDown aria-hidden="true"/></span>
      </label>
    </section>
    <section v-if="relations.length">
      <h3>关系显示</h3>
      <div class="relations">
        <label v-for="[relation, count] in relations" :key="relation">
          <span class="checkbox"><input type="checkbox" :aria-label="relation" :checked="!hidden.includes(relation)" @change="emit('relation', relation)"/>
            <Check aria-hidden="true"/></span>
          <span>{{ relation }}</span><small title="关系条数">{{ count }}</small>
        </label>
      </div>
    </section>
  </aside>
</template>

<style scoped>
.kg-sidebar {
  --rail-line: var(--ks-border, #d8e0ea);
  width: clamp(205px, 16vw, 232px); min-height: 0; flex: none; overflow: auto; overscroll-behavior: contain;
  scrollbar-width: thin; padding: 19px 15px; border-right: 1px solid var(--rail-line);
  background: var(--ks-sidebar, #f4f7fa); color: var(--ks-text, #1f2937);
}
h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 10px; color: var(--ks-strong, #111827); font-size: 15px; line-height: 1.4; font-weight: 650; }
h3 svg { color: var(--ks-deep, #163b85); }
section + section { border-top: 1px solid var(--rail-line); margin-top: 16px; padding-top: 15px; }
svg { width: 16px; height: 16px; flex: none; stroke-width: 1.8; }
button, input, select { font: inherit; }
button, select, .relations label { cursor: pointer; }
button:disabled, select:disabled { cursor: default; opacity: .65; }
button:focus-visible, input:focus-visible, select:focus-visible { outline: 2px solid var(--ks-brand, #2457e6); outline-offset: 2px; }
nav { display: grid; gap: 2px; }
.dataset {
  position: relative; display: flex; align-items: center; gap: 10px; min-height: 35px; padding: 5px 11px;
  background: transparent; border: 0; border-radius: 5px; color: var(--ks-text, #1f2937);
  text-align: left; font-size: 14px; font-weight: 500; line-height: 1.5;
}
.dataset svg { color: var(--ks-secondary, #4b5563); }
.dataset:hover, .relations label:hover { background: var(--ks-hover, #eef4ff); }
.dataset[aria-pressed=true] { background: var(--ks-active, #e5eeff); color: var(--ks-deep, #163b85); font-weight: 650; }
.dataset[aria-pressed=true]::before { content: ''; position: absolute; width: 3px; inset: 8px auto 8px 0; border-radius: 2px; background: var(--ks-brand, #2457e6); }
.dataset[aria-pressed=true] svg { color: var(--ks-brand, #2457e6); }
.source-fields { margin-top: 13px; }
.section-caption { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-bottom: 5px; }
.field, .section-caption label { font-size: 13px; font-weight: 500; color: var(--ks-secondary, #4b5563); }
.field { display: grid; gap: 5px; margin: 9px 0 0; }
.refresh { display: inline-flex; gap: 4px; align-items: center; padding: 3px; border: 0; background: transparent; color: var(--ks-deep, #163b85); font-size: 12px; }
.refresh svg { width: 12px; height: 12px; }
.select-wrap { position: relative; display: block; }
.select-wrap svg { position: absolute; top: 50%; right: 8px; transform: translateY(-50%); pointer-events: none; width: 14px; height: 14px; color: var(--ks-secondary, #4b5563); }
select, .field input { display: block; width: 100%; min-width: 0; height: 34px; padding: 0 9px; border: 1px solid var(--rail-line); border-radius: 5px; background: var(--ks-surface, #fff); color: var(--ks-text, #1f2937); font-size: 13px; font-weight: 500; }
select { appearance: none; padding-right: 26px; text-overflow: ellipsis; }
.search { display: flex; align-items: center; justify-content: center; gap: 6px; width: 100%; min-height: 32px; margin-top: 7px; border: 0; border-radius: 5px; background: var(--ks-deep, #163b85); color: white; font-size: 13px; font-weight: 600; }
.modes { display: grid; grid-template-columns: .8fr .8fr 1.2fr; gap: 2px; padding: 3px; background: var(--ks-surface, #fff); border: 1px solid var(--rail-line); border-radius: 6px; }
.modes button { min-width: 0; padding: 5px 1px; background: transparent; border: 0; border-radius: 3px; font-size: 12px; font-weight: 600; line-height: 1.3; color: var(--ks-secondary, #4b5563); }
.modes button[aria-pressed=true] { background: var(--ks-deep, #163b85); color: white; }
.relations { display: grid; }
.relations label { display: flex; align-items: center; gap: 7px; min-height: 26px; padding: 2px 4px; border-radius: 3px; font-size: 13px; }
.relations small { margin-left: auto; font-size: 12px; font-variant-numeric: tabular-nums; color: var(--ks-secondary, #4b5563); }
.checkbox { position: relative; display: grid; flex: none; }
.checkbox input { appearance: none; width: 15px; height: 15px; margin: 0; border: 1px solid #8996a7; border-radius: 3px; background: white; cursor: pointer; }
.checkbox input:checked { background: var(--ks-deep, #163b85); border-color: var(--ks-deep, #163b85); }
.checkbox svg { display: none; position: absolute; pointer-events: none; inset: 1px; width: 13px; height: 13px; stroke-width: 2.3; color: white; }
.checkbox input:checked + svg { display: block; }
</style>
