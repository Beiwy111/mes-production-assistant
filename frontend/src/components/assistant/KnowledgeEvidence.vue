<script setup>
import { AGENTS } from '@/utils/agentWorkbench'
defineProps({ bundles: { type: Array, default: () => [] } })
const relation = (value) => ({ DETECTS: '检测', USES: '使用', CLASSIFIES: '分类' })[value] || value
</script>

<template>
  <details v-for="(bundle, index) in bundles" :key="`${bundle.agentId}-${index}`" class="research-bundle">
    <summary>{{ AGENTS[bundle.agentId].name }} · 本轮公开资料检索 <span>{{ bundle.hits.length }} 个可核验片段</span></summary>
    <p>{{ bundle.modeLabel }} · {{ bundle.graphLabel }}</p>
    <p class="scope-note">论文观点不等于业务事实；图谱关系不证明本订单根因，也不构成验收规则。</p>
    <p v-if="bundle.notice" class="scope-note">{{ bundle.notice }}</p>
    <p v-if="bundle.omitted" class="scope-note">{{ bundle.omitted }} 项来源或关系未通过引用核验，未展示。</p>
    <dl>
      <dt>检索词</dt><dd>{{ bundle.query || '未提供' }}</dd>
      <template v-if="bundle.retrieved_at"><dt>检索时间</dt><dd>{{ bundle.retrieved_at }}</dd></template>
      <template v-if="bundle.release_id"><dt>资料版本</dt><dd>{{ bundle.release_id }}</dd></template>
      <template v-if="bundle.graph_release_id"><dt>图谱版本</dt><dd>{{ bundle.graph_release_id }}</dd></template>
    </dl>
    <details v-for="(hit, hitIndex) in bundle.hits" :key="`${hit.evidence_ref}-${hitIndex}`" class="source">
      <summary>{{ hit.title }} · {{ hit.location_type === 'document' ? '文档段落' : `第 ${hit.page_start}${hit.page_end !== hit.page_start ? '–' + hit.page_end : ''} 页` }}</summary>
      <p><a v-if="hit.url" :href="hit.url" target="_blank" rel="noopener noreferrer">打开公开原文</a><span v-else>未提供可安全打开的来源链接</span></p>
      <p>{{ ({ PUBLIC_RESEARCH: '公开研究论文', PUBLIC_MANUAL: '公开操作手册', PUBLIC_STANDARD: '公开标准' })[hit.authority_type] }}</p>
      <p class="excerpt">{{ hit.text }}</p>
      <p>文档版本：{{ hit.document_version }}<br />引用：<code>{{ hit.evidence_ref }}</code></p>
    </details>
    <details v-if="bundle.paths.length" class="source">
      <summary>已核验的图谱关系 · {{ bundle.paths.length }} 条</summary>
      <article v-for="(path, pathIndex) in bundle.paths" :key="`${path.claim_id}-${pathIndex}`">
        <p>{{ path.subject_name || path.subject_id }} → {{ relation(path.relation) }} → {{ path.object_name || path.object_id }}</p>
        <p>{{ path.statement }}</p><blockquote>{{ path.quote }}</blockquote>
        <p>第 {{ path.page }} 页 · 研究观点<template v-if="path.hops"> · {{ path.hops }} 跳关系</template></p>
        <code v-for="ref in path.evidence_refs" :key="ref">{{ ref }}</code>
      </article>
    </details>
    <ul v-if="bundle.limitations.length || bundle.missing.length" class="scope-note">
      <li v-for="(item, itemIndex) in [...bundle.limitations, ...bundle.missing]" :key="itemIndex">{{ item }}</li>
    </ul>
  </details>
</template>

<style scoped>
.research-bundle { margin: 16px 0; font: 14px/1.8 var(--aw-font-sans, Inter, sans-serif); color: var(--aw-text, #202124); }
summary { cursor: pointer; line-height: 1.8; font-size: 13px; }
summary span { color: var(--aw-muted, #62666d); margin-left: 8px; font-size: 11px; }
summary:focus-visible, a:focus-visible { outline: 2px solid var(--aw-muted, #62666d); outline-offset: 3px; }
.source { margin: 12px 0; padding: 10px 12px; border: 1px solid var(--aw-border, #e4e4e7); border-radius: 4px; background: var(--aw-panel, #f5f5f5); }
p, li { line-height: 1.8; overflow-wrap: anywhere; }
p { font-size: 13px; }
.scope-note { color: var(--aw-warning, #8c6228); font-size: 12px; }
.excerpt, blockquote { white-space: pre-wrap; max-height: 320px; overflow: auto; color: var(--aw-muted, #62666d); }
blockquote { margin: 8px 0; padding-left: 12px; border-left: 2px solid var(--aw-border, #e4e4e7); }
dl { display: grid; grid-template-columns: 70px minmax(0, 1fr); gap: 5px 10px; font-size: 12px; }
dt { color: var(--aw-muted, #62666d); }
dd { margin: 0; overflow-wrap: anywhere; }
code { font: 11px/1.7 var(--aw-font-mono, monospace); overflow-wrap: anywhere; color: var(--aw-muted, #62666d); }
article + article { border-top: 1px solid var(--aw-border, #e4e4e7); margin-top: 12px; padding-top: 8px; }
a { color: var(--aw-text, #202124); text-underline-offset: 3px; }
a:hover { color: var(--aw-success, #347453); }
</style>
