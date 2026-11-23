<script setup>
import { computed, ref, watch } from 'vue'
import { BookOpen, ChevronRight, Download, ExternalLink, FileText, Focus } from 'lucide-vue-next'

const props = defineProps({ node: Object, downloading: Boolean, downloadError: String })
const emit = defineEmits(['focus', 'download'])
const visibleCount = ref(8)
watch(() => props.node.id, () => { visibleCount.value = 8 })
const documents = computed(() => {
  const groups = new Map()
  for (const hit of props.node.evidence) {
    const key = hit.jobId || JSON.stringify([hit.document_id, hit.document_version])
    if (!groups.has(key)) groups.set(key, { key, title: hit.title, hits: [] })
    groups.get(key).hits.push(hit)
  }
  return [...groups.values()]
})
const visibleDocuments = computed(() => {
  let remaining = visibleCount.value
  const visible = []
  for (const document of documents.value) {
    if (!remaining) break
    const hits = document.hits.slice(0, remaining)
    visible.push({ ...document, hits })
    remaining -= hits.length
  }
  return visible
})
function location(hit) {
  if (hit.location) return hit.location
  if (hit.location_type === 'document' || hit.page_start == null) return '文档片段'
  if (hit.page_end == null || hit.page_end === hit.page_start) return '第 ' + hit.page_start + ' 页'
  return '第 ' + hit.page_start + '–' + hit.page_end + ' 页'
}
</script>

<template>
  <section class="evidence-section" aria-label="证据链">
    <h4><BookOpen aria-hidden="true"/>证据链</h4>
    <p v-if="node.evidence.length" class="evidence-count">来源文档 {{ documents.length }} 份 · 关联片段 {{ node.evidence.length }} 个</p>
    <p v-else class="evidence-count">暂无关联引用片段。</p>
    <div v-for="document in visibleDocuments" :key="document.key" class="source-document">
      <h5><FileText aria-hidden="true"/>{{ document.title }}</h5>
      <details v-for="hit in document.hits" :key="hit.evidence_ref" class="evidence">
        <summary><ChevronRight class="chevron" aria-hidden="true"/>
          <span>{{ location(hit) }}<small v-if="hit.chunk_id">Chunk · {{ hit.chunk_id }}</small></span>
        </summary>
        <blockquote>{{ hit.text }}</blockquote>
        <div class="evidence-actions">
          <button v-if="node.type !== 'chunk'" type="button" @click="emit('focus', hit.jobId ? hit.evidence_ref : 'chunk:' + hit.evidence_ref)"><Focus aria-hidden="true"/>查看片段节点</button>
          <a v-if="hit.url" :href="hit.url" target="_blank" rel="noopener noreferrer"><ExternalLink aria-hidden="true"/>打开资料原文</a>
          <button v-if="hit.jobId" type="button" :disabled="downloading" @click="emit('download', hit)"><Download aria-hidden="true"/>下载原文件</button>
        </div>
      </details>
    </div>
    <button v-if="node.evidence.length > visibleCount" type="button" class="more" @click="visibleCount += 16">更多片段（剩余 {{ node.evidence.length - visibleCount }}）</button>
    <p v-if="downloadError" role="alert" class="download-error">{{ downloadError }}</p>
  </section>
</template>

<style scoped>
h4, h5 { display: flex; align-items: center; gap: 7px; color: var(--ks-strong, #111827); margin: 0; }
h4 { font-size: 15px; font-weight: 650; }
h5 { align-items: flex-start; margin: 10px 0 6px; font-size: 13px; line-height: 1.5; font-weight: 600; }
svg { flex: none; width: 15px; height: 15px; stroke-width: 1.8; }
h4 svg { color: var(--ks-teal, #0f8b8d); }
h5 svg { margin-top: 2px; color: var(--ks-secondary, #4b5563); }
.evidence-count { margin: 4px 0 10px; color: var(--ks-secondary, #4b5563); font-size: 12px; }
.evidence { border-left: 2px solid var(--ks-border, #d8e0ea); margin-left: 6px; }
.evidence[open] { border-left-color: var(--ks-teal, #0f8b8d); }
summary { display: flex; align-items: flex-start; gap: 4px; padding: 5px 5px 5px 7px; border-radius: 0 4px 4px 0; cursor: pointer; color: var(--ks-text, #1f2937); font-weight: 500; font-size: 13px; }
summary::-webkit-details-marker { display: none; }
summary:hover { background: var(--ks-hover, #eef4ff); }
summary small { display: block; font-size: 12px; font-weight: 400; color: var(--ks-secondary, #4b5563); }
.chevron { width: 13px; height: 13px; margin-top: 3px; color: var(--ks-secondary, #4b5563); }
details[open] .chevron { transform: rotate(90deg); }
blockquote { margin: 4px 0 8px 12px; padding: 8px 9px; border-radius: 3px; background: var(--ks-soft, #f6f8fb); color: var(--ks-text, #1f2937); font-size: 13px; line-height: 1.7; white-space: pre-wrap; }
.evidence-actions { display: flex; flex-wrap: wrap; gap: 4px 10px; margin: 4px 0 8px 12px; }
button, a { display: inline-flex; align-items: center; gap: 4px; padding: 3px 0; border: 0; background: transparent; color: var(--ks-deep, #163b85); font: inherit; font-size: 12px; font-weight: 550; text-decoration: none; cursor: pointer; }
button:hover, a:hover { text-decoration: underline; }
button:disabled { opacity: .55; cursor: default; }
.evidence-actions svg { width: 13px; height: 13px; }
.more { margin-top: 6px; }
.download-error { color: #9a321e; font-size: 13px; }
</style>
