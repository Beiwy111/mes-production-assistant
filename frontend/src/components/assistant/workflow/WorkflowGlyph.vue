<script setup>
import { Bot, Box, Braces, Cable, ClipboardList, Factory, Flag, GitBranch, GitMerge, Headset,
  Layers3, Network, Play, Repeat2, Search, ShieldCheck, Sparkles, Wrench, Workflow } from 'lucide-vue-next'
defineProps({ kind: String, size: { type: Number, default: 16 } })
const glyphs = {
  SUP: Network, ORDER: ClipboardList, SUPPLY: Box, PRODUCTION: Factory, QUALITY: ShieldCheck,
  EQUIPMENT: Wrench, AFTERSALES: Headset, start: Play, end: Flag, code: Braces, llm: Sparkles,
  agent: Bot, supervisor: Network, tool: Wrench, 'http-request': Cable, 'if-else': GitBranch,
  iteration: Repeat2, 'iteration-start': Play, 'variable-aggregator': GitMerge,
  'knowledge-retrieval': Search, template: Layers3,
}
</script>
<template><component :is="glyphs[kind] || Workflow" :size="size" :stroke-width="1.8" aria-hidden="true"/></template>
