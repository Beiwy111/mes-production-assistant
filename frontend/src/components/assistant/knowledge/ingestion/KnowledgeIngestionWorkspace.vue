<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Database, Download, FileSearch, Files, FileText, ListTree, Network, RotateCw, Search, Trash2, X } from 'lucide-vue-next'
import FileUploadZone from './FileUploadZone.vue'
import DocumentTable from './DocumentTable.vue'
import ParsingPipelineStatus from './ParsingPipelineStatus.vue'
import ChunkExplorer from './ChunkExplorer.vue'
import IngestionGraphPreview from './IngestionGraphPreview.vue'
import { downloadSource, ingestionRequest, uploadFile, validateFile } from './ingestionApi'
import { fileType, jobStates } from './documentPresentation'

const props = defineProps({ token:String, visible:Boolean })
const emit = defineEmits(['graph'])
const jobs = ref([])
const selected = ref('')
const detail = ref(null)
const query = ref('')
const status = ref('')
const tab = ref('pipeline')
const listError = ref('')
const detailError = ref('')
const actionError = ref('')
const loading = ref(false)
const loadingDetail = ref(false)
const busy = ref(false)
const uploading = ref(false)
const uploads = ref([])
const confirmation = ref(null)
const scope = new AbortController()
let timer, listSerial = 0, detailSerial = 0, uploadSerial = 0
const jobPath = id => '/jobs/' + encodeURIComponent(id)
const shown = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return jobs.value.filter(row => (!status.value || row.status === status.value)
    && (!keyword || row.filename?.toLowerCase().includes(keyword) || fileType(row).toLowerCase().includes(keyword)))
})
const summary = computed(() => jobs.value.find(row => row.id === selected.value))
const job = computed(() => detail.value?.id === selected.value ? detail.value : summary.value)
const canRetry = computed(() => ['failed','completed'].includes(job.value?.status))
const retryLabel = computed(() => job.value?.status === 'failed' ? '重试' : '重新解析')

async function readDetail(id) {
  const serial = ++detailSerial
  detailError.value = ''
  loadingDetail.value = Boolean(id)
  if (!id) return
  try {
    const data = await ingestionRequest(props.token, jobPath(id), 'GET', scope.signal)
    if (serial === detailSerial && selected.value === id) detail.value = data
  } catch (error) {
    if (!scope.signal.aborted && serial === detailSerial) detailError.value = error.message
  } finally {
    if (serial === detailSerial) loadingDetail.value = false
  }
}
function setSelection(id) {
  if (selected.value === id) return
  selected.value = id
  confirmation.value = null
  detail.value = null
  actionError.value = ''
  tab.value = 'pipeline'
}
function select(id) {
  setSelection(id)
  readDetail(id)
}
function availableSelection() {
  return shown.value.some(row => row.id === selected.value) ? selected.value : shown.value[0]?.id || ''
}
watch([query,status], () => {
  const id = availableSelection()
  if (id !== selected.value) select(id)
})
async function refresh(onlyChanges = false) {
  clearTimeout(timer)
  const serial = ++listSerial
  if (!props.visible || scope.signal.aborted) return
  loading.value = true
  try {
    const data = await ingestionRequest(props.token, '/jobs', 'GET', scope.signal)
    if (serial !== listSerial) return
    jobs.value = data.jobs
    listError.value = ''
    setSelection(availableSelection())
    if (detail.value && detail.value.status !== summary.value?.status) detail.value = null
    const current = detail.value?.updatedAt && detail.value.updatedAt === summary.value?.updatedAt
    if (!onlyChanges || !current || detailError.value) await readDetail(selected.value)
  } catch (error) {
    if (!scope.signal.aborted && serial === listSerial) listError.value = error.message
  } finally {
    if (serial === listSerial) {
      loading.value = false
      if (props.visible && !scope.signal.aborted && jobs.value.some(row => ['queued','running'].includes(row.status)))
        timer = setTimeout(() => refresh(true), 1600)
    }
  }
}
watch(() => props.visible, () => refresh(), { immediate:true })
watch(() => job.value?.status, () => { confirmation.value = null })
onBeforeUnmount(() => { scope.abort(); clearTimeout(timer); listSerial++; detailSerial++ })

async function enqueue(files) {
  if (uploading.value) return
  uploading.value = true
  try {
    for (const file of files) {
      if (scope.signal.aborted) break
      const item = { id:++uploadSerial, name:file.name, percent:0, error:validateFile(file) }
      uploads.value.push(item)
      const row = uploads.value.at(-1)
      if (row.error) continue
      try {
        const data = await uploadFile(props.token, file, value => { row.percent = value }, scope.signal)
        uploads.value = uploads.value.filter(transfer => transfer.id !== row.id)
        query.value = ''; status.value = ''
        jobs.value = [data, ...jobs.value.filter(existing => existing.id !== data.id)]
        setSelection(data.id)
        await refresh()
      } catch (error) { if (!scope.signal.aborted) row.error = error.message }
    }
  } finally { uploading.value = false }
}
function confirmAction(kind) {
  confirmation.value = { kind, id:job.value.id, status:job.value.status }
  actionError.value = ''
}
async function performAction() {
  const request = confirmation.value
  if (!request || busy.value || request.id !== job.value?.id || request.status !== job.value.status) return
  busy.value = true
  confirmation.value = null
  try {
    const path = jobPath(request.id)
    if (request.kind === 'delete') {
      await ingestionRequest(props.token, request.status === 'completed' ? path + '/document' : path, 'DELETE', scope.signal)
      jobs.value = jobs.value.filter(row => row.id !== request.id)
      setSelection(availableSelection())
    } else {
      const queued = await ingestionRequest(props.token, path + '/retry', 'POST', scope.signal)
      jobs.value = jobs.value.map(row => row.id === request.id ? queued : row)
      if (selected.value === request.id) { detail.value = queued; tab.value = 'pipeline' }
    }
    await refresh()
  } catch (error) {
    if (!scope.signal.aborted) { await refresh(); actionError.value = error.message }
  } finally { busy.value = false }
}
async function download() {
  const { id, filename } = job.value
  actionError.value = ''
  try { await downloadSource(props.token, id, filename, scope.signal) }
  catch (error) { if (!scope.signal.aborted && selected.value === id) actionError.value = error.message }
}
</script>

<template>
  <div class="ingestion-frame"><div class="ingestion">
    <main class="documents">
      <header class="workspace-heading"><div class="library"><Database :size="14"/>私有知识库</div><h2>资料入库</h2></header>
      <FileUploadZone :uploading="uploading" @files="enqueue"/>
      <ul v-if="uploads.length" class="transfers" aria-label="上传进度"><li v-for="row in uploads" :key="row.id">
        <FileText :size="15"/><span>{{ row.name }}</span><strong :class="{ error:row.error }">{{ row.error || (row.percent === 100 ? '服务器接收中' : row.percent + '%') }}</strong>
        <progress v-if="!row.error" :value="row.percent" max="100"/>
        <button v-else type="button" :aria-label="'移除上传提示 ' + row.name" @click="uploads = uploads.filter(transfer => transfer.id !== row.id)"><X :size="14"/></button>
      </li></ul>
      <IngestionGraphPreview :job="detail || job" @graph="id=>emit('graph',id)"/>
      <section class="library-list" aria-label="文档管理">
        <header class="list-title"><h3>入库文档 <small>{{ jobs.length }}</small></h3>
          <button type="button" class="control" aria-label="刷新资料列表" @click="refresh()"><RotateCw :size="14"/>刷新</button></header>
        <div class="filters"><label class="search"><Search :size="16"/><input v-model="query" type="search" aria-label="搜索文档" placeholder="搜索文件名或类型"></label>
          <select v-model="status" aria-label="筛选解析状态"><option value="">全部状态</option><option v-for="(label,key) in jobStates" :key="key" :value="key">{{ label }}</option></select>
        </div>
        <p v-if="listError" class="error" role="alert">{{ listError }}</p>
        <DocumentTable v-if="shown.length" :jobs="shown" :selected="selected" @select="select"/>
        <div v-else-if="!listError" class="empty"><Files :size="28"/><h4>{{ loading ? '正在读取文档…' : jobs.length ? '没有匹配的文档' : '暂无入库文档' }}</h4>
          <p v-if="!loading">{{ jobs.length ? '可以更换搜索词或状态筛选。' : '上传第一份资料后，可在这里查看解析和入库进度。' }}</p></div>
      </section>
    </main>
    <aside class="receipt" aria-label="文件处理详情">
      <h3 class="receipt-title">文档详情</h3>
      <template v-if="job">
        <div class="file-heading"><FileText :size="19"/><h4>{{ job.filename }}</h4></div>
        <p class="counts">{{ fileType(job) }}<template v-if="job.counts?.chunks != null"> · {{ job.counts.chunks }} 个片段</template><template v-if="job.counts?.entities != null"> · {{ job.counts.entities }} 个实体</template><template v-if="job.counts?.relations != null"> · {{ job.counts.relations }} 条关系</template></p>
        <div class="file-actions">
          <button v-if="job.status === 'completed'" class="control" type="button" @click="emit('graph', job.id)"><Network/>查看图谱</button>
          <button class="control" type="button" @click="download"><Download/>原文件</button>
          <button v-if="canRetry" class="control" type="button" :disabled="busy" @click="confirmAction('retry')"><RotateCw/>{{ retryLabel }}</button>
          <button class="control delete" type="button" :disabled="busy" @click="confirmAction('delete')"><Trash2/>删除</button>
        </div>
        <div v-if="confirmation" class="confirm" role="group" aria-label="确认文档操作">
          <strong>{{ confirmation.kind === 'delete' ? '删除' : retryLabel }}《{{ job.filename }}》？</strong>
          <p>{{ confirmation.kind === 'delete' ? '原文件及其解析结果将被移除，此操作无法撤销。' : job.status === 'completed' ? '文档将重新排队，重新入库前会暂停检索。' : '使用原文件重新执行入库流程。' }}</p>
          <button class="control" type="button" @click="confirmation = null">取消</button>
          <button class="control accept" :class="{ danger:confirmation.kind === 'delete' }" type="button" @click="performAction">{{ confirmation.kind === 'delete' ? '确认删除' : '确认' + retryLabel }}</button>
        </div>
        <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
        <nav class="detail-tabs" aria-label="文档详情视图">
          <button type="button" :aria-pressed="tab === 'pipeline'" @click="tab = 'pipeline'"><ListTree :size="15"/>处理日志</button>
          <button type="button" :aria-pressed="tab === 'chunks'" @click="tab = 'chunks'"><FileSearch :size="15"/>文档片段</button>
        </nav>
        <p v-if="detailError" class="error" role="alert">{{ detailError }}</p>
        <ParsingPipelineStatus v-if="tab === 'pipeline'" :job="job"/>
        <p v-else-if="loadingDetail && !detail" class="counts" role="status">正在读取片段…</p>
        <ChunkExplorer v-else-if="detail" :job="detail" @graph="(id,chunkId) => emit('graph', id, chunkId)"/>
      </template>
      <div v-else class="receipt-empty"><FileText :size="23"/><p>选择文档查看处理日志和片段。</p></div>
    </aside>
  </div></div>
</template>

<style scoped>
.ingestion-frame { width:100%; min-width:0; min-height:0; display:flex; container-type:inline-size; color:var(--ks-text,#1f2937); background:var(--ks-surface,#fff); font:14px/1.5 var(--ks-font, 'Inter Variable', Inter, 'PingFang SC', 'Noto Sans SC Variable', 'Microsoft YaHei', sans-serif) }
.ingestion { display:grid; flex:1; min-width:0; min-height:0; grid-template-columns:minmax(0,1fr) clamp(285px,28%,400px) }
.documents,.receipt { min-width:0; overflow:auto; overscroll-behavior:contain }
.documents { padding:clamp(18px,2.4vw,35px) }
.receipt { border-left:1px solid var(--ks-border,#d8e0ea); padding:22px 18px }
.workspace-heading { margin-bottom:18px }
.library { display:flex; align-items:center; gap:7px; color:var(--ks-secondary,#4b5563); font-size:12px; font-weight:500 }
.workspace-heading h2 { margin:5px 0 0; font-size:26px; font-weight:600; letter-spacing:-.03em; color:var(--ks-strong,#111827) }
.library-list { margin-top:25px }
.list-title,.list-title h3,.file-heading,.file-actions,.control,.receipt-empty,.transfers li,.filters,.search,.detail-tabs,.detail-tabs button { display:flex; align-items:center; gap:8px }
.list-title { justify-content:space-between; margin-bottom:12px }
.list-title h3,.receipt-title { margin:0; font-size:18px; font-weight:600; color:var(--ks-strong,#111827) }
.list-title h3 small { font-size:13px; font-weight:500; color:var(--ks-secondary,#4b5563) }
.filters { margin-bottom:12px }
.search,.filters select,.control { box-sizing:border-box; border:1px solid var(--ks-border,#d8e0ea); border-radius:5px; background:var(--ks-surface,#fff); color:var(--ks-text,#1f2937); font:inherit; font-size:13px }
.search,.filters select { height:35px; padding:0 10px }
.filters select { cursor:pointer }
.search { flex:1; max-width:350px; min-width:0; color:var(--ks-secondary,#4b5563) }
.search input { border:0; outline:none; width:100%; min-width:0; color:var(--ks-text,#1f2937); background:transparent; font:inherit }
.search:focus-within,.ingestion-frame :deep(:focus-visible) { outline:2px solid var(--ks-brand,#2457e6); outline-offset:2px }
.search input:focus-visible { outline:none }
.control { display:inline-flex; justify-content:center; gap:5px; padding:5px 9px; cursor:pointer; white-space:nowrap }
.control:hover { background:var(--ks-hover,#eef4ff); color:var(--ks-deep,#163b85) }
.control:disabled { opacity:.5; cursor:default }
.control svg { width:14px; height:14px }
.receipt-title { margin-bottom:17px }
.file-heading { align-items:flex-start; gap:8px }
.file-heading svg { margin-top:2px; flex:none; color:var(--ks-deep,#163b85) }
.file-heading h4 { margin:0; font-size:15px; color:var(--ks-strong,#111827); overflow-wrap:anywhere }
.counts { font-size:12px; color:var(--ks-secondary,#4b5563); margin:7px 0 12px }
.file-actions { flex-wrap:wrap; gap:5px }
.file-actions .delete { color:#a82c26 }
.detail-tabs { gap:16px; border-bottom:1px solid var(--ks-border,#d8e0ea); margin:18px 0 13px }
.detail-tabs button { gap:5px; padding:6px 0 9px; font:inherit; font-size:13px; border:0; border-bottom:2px solid transparent; margin-bottom:-1px; background:transparent; color:var(--ks-secondary,#4b5563); cursor:pointer }
.detail-tabs button[aria-pressed=true] { color:var(--ks-deep,#163b85); border-color:var(--ks-brand,#2457e6); font-weight:600 }
.empty { text-align:center; padding:36px 14px; border:1px solid var(--ks-border,#d8e0ea); border-radius:6px }
.empty svg,.receipt-empty svg { color:var(--ks-secondary,#4b5563) }
.empty h4 { margin:7px 0 3px; font-size:15px; font-weight:600 }
.empty p,.receipt-empty p { margin:0; font-size:13px; color:var(--ks-secondary,#4b5563) }
.receipt-empty { padding:15px 0; flex-wrap:wrap; align-items:center }
.transfers { list-style:none; padding:0; margin:10px 0; font-size:13px }
.transfers li { flex-wrap:wrap; padding:5px 0; overflow-wrap:anywhere }
.transfers strong { margin-left:auto; font-size:12px; font-weight:500 }
.transfers progress { width:65px; accent-color:var(--ks-brand,#2457e6) }
.transfers button { display:flex; border:0; padding:4px; background:transparent; cursor:pointer }
.error { color:#a82c26; font-size:13px; overflow-wrap:anywhere }
.confirm { background:var(--ks-soft,#f6f8fb); border:1px solid var(--ks-border,#d8e0ea); padding:10px; border-radius:5px; margin-top:10px; font-size:13px; overflow-wrap:anywhere }
.confirm p { color:var(--ks-secondary,#4b5563); margin:4px 0 8px }
.confirm .accept { margin-left:6px; background:var(--ks-brand,#2457e6); color:white; border-color:transparent }
.confirm .danger { background:#aa3024 }
@container(max-width:900px) {
  .ingestion { display:block; overflow:auto }
  .documents,.receipt { overflow:visible; padding:18px }
  .receipt { border-left:0; border-top:1px solid var(--ks-border,#d8e0ea) }
}
</style>
