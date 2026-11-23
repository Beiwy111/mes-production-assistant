<script setup>
import { computed, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, MapPin, Network, Search } from 'lucide-vue-next'
import { chunkLocation } from './documentPresentation'

const props = defineProps({ job: { type: Object, required: true } })
const emit = defineEmits(['graph'])
const query = ref('')
const page = ref(0)
const chunks = computed(() => (props.job.chunks || []).map((chunk, index) => ({
  chunk, index, location: chunkLocation(chunk), headings: (chunk.metadata?.headings || []).join(' / '),
})))
const matches = computed(() => {
  const search = query.value.trim().toLowerCase()
  return search ? chunks.value.filter(({ chunk, location, headings }) =>
    [chunk.text, chunk.id, chunk.location, location, headings].some(value => String(value ?? '').toLowerCase().includes(search))) : chunks.value
})
const selected = computed(() => matches.value[page.value])
watch(() => props.job.id, () => { query.value = ''; page.value = 0 })
watch(query, () => { page.value = 0 })
watch(() => matches.value.length, length => { page.value = Math.min(page.value, Math.max(0, length - 1)) })
</script>

<template>
  <section class="chunks" aria-label="文档片段浏览">
    <label v-if="chunks.length" class="search"><Search :size="16"/>
      <input v-model="query" type="search" aria-label="搜索片段" placeholder="搜索正文、位置或片段 ID">
    </label>
    <template v-if="selected">
      <div class="pager"><span>{{ query.trim() ? '匹配片段' : '浏览片段' }}</span>
        <button type="button" aria-label="上一个片段" :disabled="page === 0" @click="page--"><ChevronLeft :size="15"/></button>
        <span class="counter">{{ page + 1 }} / {{ matches.length }}</span>
        <button type="button" aria-label="下一个片段" :disabled="page + 1 >= matches.length" @click="page++"><ChevronRight :size="15"/></button>
      </div>
      <article>
        <header><strong>片段 {{ selected.index + 1 }}</strong>
          <span class="location"><MapPin :size="13"/>{{ selected.location }}</span></header>
        <p v-if="selected.headings" class="heading-path">{{ selected.headings }}</p>
        <p class="excerpt">{{ selected.chunk.text }}</p>
        <details class="metadata"><summary>片段元数据</summary>
          <dl><dt>ID</dt><dd>{{ selected.chunk.id }}</dd>
            <template v-if="selected.chunk.metadata?.charStart != null && selected.chunk.metadata?.charEnd != null">
              <dt>段内字符偏移</dt><dd>[{{ selected.chunk.metadata.charStart }}, {{ selected.chunk.metadata.charEnd }})</dd>
            </template>
            <template v-if="selected.chunk.metadata?.ocr"><dt>文字来源</dt><dd>OCR 识别</dd></template>
          </dl>
        </details>
        <button v-if="job.status === 'completed' && selected.chunk.id" class="graph-link" type="button" @click="emit('graph', job.id, selected.chunk.id)">
          <Network :size="15"/>在图谱中定位片段
        </button>
      </article>
    </template>
    <p v-else class="message" role="status">{{ chunks.length ? '未找到匹配片段。' : '暂无可预览的片段。' }}</p>
  </section>
</template>

<style scoped>
.chunks { font-size:13px; color:var(--ks-text,#1f2937) }
.search { display:flex; align-items:center; gap:8px; border:1px solid var(--ks-border,#d8e0ea); border-radius:5px; padding:7px 9px; color:var(--ks-secondary,#4b5563) }
.search:focus-within { outline:2px solid var(--ks-brand,#2457e6); outline-offset:1px }
.search input { font:inherit; color:var(--ks-text,#1f2937); min-width:0; flex:1; border:0; outline:none; background:transparent }
.pager,header,.location,.graph-link,.pager button { display:flex; align-items:center; gap:6px }
.pager { margin:14px 0 10px; color:var(--ks-secondary,#4b5563); font-size:12px }
.pager span:first-child { margin-right:auto }
.counter { font-variant-numeric:tabular-nums }
.pager button { padding:3px; border:1px solid var(--ks-border,#d8e0ea); background:var(--ks-surface,#fff); border-radius:4px; color:var(--ks-text,#1f2937); cursor:pointer }
.pager button:disabled { opacity:.4; cursor:default }
article { border-top:1px solid var(--ks-border,#d8e0ea); padding-top:14px }
header { align-items:baseline; flex-wrap:wrap; justify-content:space-between }
header strong { font-size:15px; color:var(--ks-strong,#111827) }
.location { color:var(--ks-secondary,#4b5563); overflow-wrap:anywhere }
.heading-path { color:var(--ks-secondary,#4b5563); margin:8px 0 0; font-size:12px }
.excerpt { margin:12px 0; padding:11px 13px; border-left:3px solid var(--ks-teal,#0f8b8d); background:var(--ks-soft,#f6f8fb); font-size:14px; line-height:1.8; white-space:pre-wrap; overflow-wrap:anywhere }
.metadata { color:var(--ks-secondary,#4b5563); font-size:12px }
.metadata summary { cursor:pointer; padding:3px 0 }
dl { display:grid; grid-template-columns:auto 1fr; gap:5px 8px; margin:8px 0 }
dd { margin:0; overflow-wrap:anywhere }
.graph-link { margin-top:15px; border:0; padding:0; background:transparent; color:var(--ks-deep,#163b85); font:inherit; font-weight:600; cursor:pointer }
.graph-link:hover { text-decoration:underline }
.message { color:var(--ks-secondary,#4b5563); padding:10px 0 }
</style>
