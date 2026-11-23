<script setup lang="ts">
import { computed } from 'vue'
import { knowledgeSources } from '@/utils/knowledgeSources'
const props = defineProps<{ message: any }>()
const sources = computed(() => knowledgeSources(props.message))
</script>
<template>
  <ul v-if="sources.length" class="knowledge-sources" aria-label="知识回答来源">
    <li v-for="source in sources" :key="source.ref"><a :href="source.url" target="_blank" rel="noopener noreferrer">[{{ source.id }}] {{ source.type }} · {{ source.title }} · {{ source.location || `第 ${source.page} 页` }}</a></li>
  </ul>
</template>
<style scoped>
.knowledge-sources { margin:12px 0; padding:0; list-style:none; font-size:13px; line-height:1.7; overflow-wrap:anywhere; }
a { color:var(--aw-muted); text-decoration:none; } a:hover { color:var(--aw-text); text-decoration:underline; }
</style>
