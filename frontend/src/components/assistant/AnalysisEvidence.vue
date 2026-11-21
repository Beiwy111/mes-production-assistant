<script setup>
import { AGENTS } from '@/utils/agentWorkbench'
defineProps({ analyses: { type: Array, default: () => [] }, businessRefs: { type: Array, default: () => [] } })
const groups = [['findings', '证据观察'], ['hypotheses', '待验证假设'], ['actions', '验证与处理建议']]
</script>

<template>
  <details v-for="(analysis, index) in analyses" :key="`${analysis.agentId}-${index}`" class="analysis">
    <summary>{{ AGENTS[analysis.agentId].name }} · 分析与建议</summary>
    <p v-if="analysis.summary">{{ analysis.summary }}</p>
    <p class="note">摘要为分析说明；论文观点和待验证假设不能替代本次业务事实或作为已证实根因。</p>
    <p v-if="analysis.omitted" class="note">{{ analysis.omitted }} 项分析缺少有效引用，未作为有依据结论展示。</p>
    <section v-for="[key, label] in groups" :key="key">
      <h4>{{ label }}</h4>
      <p v-if="!analysis[key].length" class="note">暂无通过引用核验的{{ label }}。</p>
      <ul v-else>
        <li v-for="(item, itemIndex) in analysis[key]" :key="itemIndex">
          <p>{{ item.text }}</p>
          <p v-if="item.verification">验证方法：{{ item.verification }}</p>
          <small v-for="ref in item.evidenceRefs" :key="ref">{{ businessRefs.includes(ref) ? '业务查询' : '公开研究' }}：<code>{{ ref }}</code></small>
        </li>
      </ul>
    </section>
    <ul v-if="analysis.missing.length" class="note"><li v-for="(item, itemIndex) in analysis.missing" :key="itemIndex">{{ item }}</li></ul>
  </details>
</template>

<style scoped>
.analysis { margin: 16px 0; color: var(--aw-text, #202124); font: 14px/1.8 var(--aw-font-sans, Inter, sans-serif); }
summary { cursor: pointer; font-size: 13px; }
summary:focus-visible { outline: 2px solid var(--aw-muted, #62666d); outline-offset: 3px; }
h4 { font-size: 13px; font-weight: 500; margin: 12px 0 4px; }
p { margin: 6px 0; white-space: pre-wrap; overflow-wrap: anywhere; }
ul { padding-left: 20px; }
small { display: block; overflow-wrap: anywhere; color: var(--aw-muted, #62666d); font-size: 11px; }
code { font-family: var(--aw-font-mono, monospace); }
.note { color: var(--aw-warning, #8c6228); font-size: 12px; }
</style>
