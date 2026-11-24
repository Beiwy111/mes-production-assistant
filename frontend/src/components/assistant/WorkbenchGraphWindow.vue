<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Expand, Minimize, X, Workflow } from 'lucide-vue-next'
import KnowledgeStudioHeader from './knowledge/KnowledgeStudioHeader.vue'
import './knowledge/knowledge-studio.css'
const WorkflowStudio = defineAsyncComponent(() => import('./workflow/WorkflowStudio.vue'))
const KnowledgeStudio = defineAsyncComponent(() => import('./knowledge/KnowledgeStudio.vue'))
const IngestionWorkspace = defineAsyncComponent(() => import('./knowledge/ingestion/KnowledgeIngestionWorkspace.vue'))
const props = defineProps({ mode: String, message: Object, token: String, canIngest: Boolean })
const emit = defineEmits(['close'])
const dialog = ref(null)
const maximized = ref(false)
const tab = ref('graph')
const graphJob = ref('')
const graphChunk = ref('')
const graphVisit = ref(0)
const uploadOpened = ref(false)
const isWorkflow = computed(() => props.mode === 'workflow')
function openUpload() { uploadOpened.value = true; tab.value = 'upload' }
function showGraph(id, chunkId = '') {
  graphJob.value = id
  graphChunk.value = chunkId
  graphVisit.value++
  tab.value = 'graph'
}
onMounted(() => dialog.value.showModal())
onBeforeUnmount(() => dialog.value?.close())
watch(() => props.token, () => emit('close'))
watch(() => props.canIngest, allowed => { if (!allowed) { tab.value = 'graph'; uploadOpened.value = false; graphJob.value = '' } })
</script>
<template>
  <dialog ref="dialog" class="graph-studio" :class="{maximized,'ks-workspace':!isWorkflow}" :aria-label="isWorkflow ? 'Dify 工作流' : '联合知识图'" @cancel.prevent="emit('close')">
    <KnowledgeStudioHeader v-if="!isWorkflow" :tab="tab" :can-ingest="canIngest" :maximized="maximized"
      @view="$event==='upload'?openUpload():tab='graph'" @maximize="maximized=!maximized" @close="emit('close')"/>
    <header v-else class="studio-top">
      <span class="studio-glyph"><Workflow/></span>
      <h2>Dify 工作流</h2>
      <span class="studio-source">仓库 DSL</span>
      <div class="studio-actions"><button :aria-label="maximized?'退出全屏':'全屏查看'" :title="maximized?'退出全屏':'全屏查看'" @click="maximized=!maximized"><Minimize v-if="maximized"/><Expand v-else/></button>
      <button aria-label="关闭窗口" title="关闭窗口" @click="emit('close')"><X/></button></div>
    </header>
    <div v-if="isWorkflow" class="studio-pane"><WorkflowStudio :message="message"/></div>
    <template v-else>
      <div v-show="tab==='graph'" class="studio-pane"><KnowledgeStudio :key="graphVisit" :message="message" :token="token" :can-ingest="canIngest" :job-id="graphJob" :chunk-id="graphChunk" :visible="tab==='graph'"/></div>
      <div v-if="canIngest && uploadOpened" v-show="tab==='upload'" class="studio-pane"><IngestionWorkspace :token="token" :visible="tab==='upload'" @graph="showGraph"/></div>
    </template>
  </dialog>
</template>
<style scoped>
.graph-studio{position:fixed;inset:0;margin:auto;box-sizing:border-box;width:calc(100vw - 42px);max-width:1900px;height:calc(100dvh - 42px);max-height:none;padding:0;overflow:hidden;border:1px solid #d7dde5;border-radius:16px;color:#242c38;background:white;font:14px/1.5 var(--aw-font-sans,system-ui);box-shadow:0 22px 85px #13253a26}
.graph-studio[open]{display:flex;flex-direction:column}.graph-studio::backdrop{background:#19253759}.graph-studio.maximized{width:100vw;max-width:none;height:100dvh;border-radius:0;border:0}
.studio-top{display:flex;align-items:center;gap:12px;height:57px;flex:none;padding:0 18px;border-bottom:1px solid #e1e6ec;background:white}
.studio-top h2{margin:0;font-size:16px;font-weight:550;letter-spacing:0}.studio-glyph{width:32px;height:32px;display:grid;place-items:center;background:#eef2fc;border-radius:9px;color:#4b5f98}.studio-top svg{width:17px;height:17px}
.studio-source{font-size:12px;color:#657186;border-left:1px solid #dce1e8;padding-left:12px}.studio-tabs,.studio-actions{display:flex;gap:4px}.studio-tabs{margin-left:13px;border-left:1px solid #e1e6ec;padding-left:16px}.studio-actions{margin-left:auto}
.studio-top button{background:transparent;color:#546075;border:0;border-radius:7px;padding:7px;display:inline-flex;align-items:center;justify-content:center;gap:6px;font:inherit;font-size:13px;cursor:pointer}.studio-tabs button{padding:6px 11px}.studio-top button:hover{background:#f4f6f8}.studio-top button[aria-pressed=true]{color:#414d7c;background:#edf0f8;font-weight:500}
.studio-pane{flex:1;display:flex;min-width:0;min-height:0;position:relative;overflow:hidden}
@media(max-width:760px){.graph-studio{width:100vw;height:100dvh;border:0;border-radius:0}.studio-top{gap:6px;padding:0 8px}.studio-tabs{margin:0;padding-left:5px}.studio-tabs button{padding:5px}.studio-glyph{display:none}}
.graph-studio.ks-workspace{border:1px solid var(--ks-border);border-radius:9px;color:var(--ks-text);font:400 14px/1.55 var(--ks-font);box-shadow:0 12px 40px #14254226;background:var(--ks-surface)}
.graph-studio.ks-workspace.maximized{border:0;border-radius:0}
@media(max-width:760px){.graph-studio.ks-workspace{border-radius:0;border:0}}
</style>
