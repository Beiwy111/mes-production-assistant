<script setup lang="ts">
import { computed } from 'vue'
import { knowledgeSources, approvedSopSources } from '@/utils/knowledgeSources'
const props = defineProps<{ message: any }>()
const sources = computed(() => knowledgeSources(props.message))
const procedures = computed(() => approvedSopSources(props.message))
</script>
<template>
  <details v-if="sources.length || procedures.length" class="sources-panel"><summary>知识来源 <span>{{ sources.length + procedures.length }} 项</span></summary>
  <ul class="knowledge-sources" aria-label="知识回答来源">
    <li v-for="source in sources" :key="source.ref"><a :href="source.url" target="_blank" rel="noopener noreferrer">[{{ source.id }}] {{ source.type }} · {{ source.title }} · {{ source.location || `第 ${source.page} 页` }}</a></li>
    <li v-for="source in procedures" :key="source.ref">厂内 SOP · {{ source.title }} · {{ source.version }} · {{ source.section }}</li>
  </ul>
  </details>
</template>
<style scoped>
.knowledge-sources { margin:12px 0; padding:0; list-style:none; font-size:13px; line-height:1.7; overflow-wrap:anywhere; }
.sources-panel { margin:10px 0; font-size:13px; }
.sources-panel summary { cursor:pointer; }
.sources-panel summary span { margin-left:5px; color:var(--aw-muted); }
a { color:var(--aw-muted); text-decoration:none; } a:hover { color:var(--aw-text); text-decoration:underline; }
</style>
