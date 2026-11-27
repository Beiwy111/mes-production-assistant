<script setup>
import { computed, ref, watch } from 'vue'
import { ArrowRight, Check, LoaderCircle } from 'lucide-vue-next'
import DynamicKnowledgeGraphCanvas from '../DynamicKnowledgeGraphCanvas.vue'
import { uploadedKnowledgeGraph } from '../../../../utils/workbenchGraph.js'
import { jobEvidence } from '../knowledgeLibrary.js'
const props=defineProps({job:Object})
defineEmits(['graph'])
const selected=ref('')
const graph=computed(()=>{
  if (!props.job?.chunks?.length) return {nodes:[],edges:[]}
  return uploadedKnowledgeGraph(jobEvidence(props.job,false))
})
const node=computed(()=>graph.value.nodes.find(n=>n.id===selected.value))
const phases=[['parse','解析'],['chunk','切片'],['embedding','向量化'],['vector','Qdrant'],['entities','实体抽取'],['relations','关系构建'],['graph','Neo4j']]
const status=key=>props.job?.stages?.find(s=>s.key===key)?.status || 'pending'
const published=computed(()=>props.job?.status==='completed')
watch(()=>props.job?.id,()=>{selected.value=''})
</script>
<template>
  <section v-if="job" class="ingestion-growth" aria-label="文档入库与节点生成">
    <header><div><strong>{{ published ? '入库完成 · 新增节点可查看' : job.status==='failed' ? '处理未完成' : '文档正在转为知识节点' }}</strong>
      <p>{{ job.filename }}</p></div><button v-if="published" @click="$emit('graph',job.id)">查看本次新增图谱 <ArrowRight :size="14"/></button></header>
    <ol aria-label="实时入库流程"><li v-for="[key,label] in phases" :key="key" :class="status(key)">
      <Check v-if="status(key)==='success'" :size="13"/><LoaderCircle v-else-if="status(key)==='running'" class="spin" :size="13"/>
      <span>{{ label }}</span><span class="stage-state">{{ {success:'完成',running:'处理中',failed:'失败',pending:job.status==='failed'?'未执行':'等待'}[status(key)] }}</span>
    </li></ol>
    <div class="growth-counts" role="status">
      <span>已切分 <b>{{ job.counts?.chunks || 0 }}</b> 片段</span>
      <span>已抽取 <b>{{ job.counts?.entities || 0 }}</b> 实体</span>
      <span>候选关系 <b>{{ job.counts?.relations || 0 }}</b> 条</span>
      <span>{{ published ? '图谱展示' : '处理预览' }} <b>{{ graph.nodes.length }}</b> 节点</span>
    </div>
    <div class="growth-canvas"><DynamicKnowledgeGraphCanvas v-if="graph.nodes.length" :graph="graph" :selected="selected" @select="selected=$event"/>
      <p v-else>解析和切片完成后，真实片段将在这里形成节点。</p></div>
    <p v-if="node" class="growth-excerpt"><strong>{{ node.label }}</strong> {{ node.detail }}</p>
    <p class="growth-note">{{ published ? 'Qdrant 与 Neo4j 写入均已完成，可查看原文片段和检索来源。' : '当前为实际解析结果预览；两类索引写入成功前，不作为已入库资料。' }} 自动抽取的关系待复核，共现不代表因果。</p>
  </section>
</template>
<style scoped>
.ingestion-growth{margin-top:20px;border:1px solid var(--ks-border);border-radius:8px;overflow:hidden}
header{display:flex;justify-content:space-between;gap:12px;padding:14px 16px;background:#fafbfe;align-items:center}header strong{font-weight:500}header p{font-size:12px;color:var(--ks-secondary);margin:3px 0 0;overflow-wrap:anywhere}
button{display:flex;align-items:center;gap:5px;border:1px solid var(--ks-border);border-radius:5px;background:white;padding:6px 8px;cursor:pointer;color:var(--ks-deep);font:inherit;font-size:12px;flex-shrink:0}
ol{display:flex;flex-wrap:wrap;gap:8px;padding:12px 16px;margin:0;list-style:none}li{display:flex;align-items:center;gap:4px;font-size:12px;border:1px solid var(--ks-border);border-radius:4px;padding:5px 7px;color:var(--ks-secondary)}li.success{color:#166b51;background:#f3faf7}li.running{color:#2457e6;background:#eef4ff}li.failed{color:#a82c26}.stage-state{font-size:10px;opacity:.8}
.growth-counts{display:flex;flex-wrap:wrap;gap:8px 20px;padding:0 16px 12px;font-size:12px;color:var(--ks-secondary)}b{color:var(--ks-strong);font-size:16px;font-weight:500}
.growth-canvas{position:relative;height:320px;background:#fbfcfe;border-top:1px solid var(--ks-border)}.growth-canvas>p{padding:110px 18px;text-align:center;color:var(--ks-secondary);font-size:13px}
.growth-note,.growth-excerpt{padding:10px 16px;margin:0;font-size:12px;line-height:1.65;color:var(--ks-secondary);border-top:1px solid var(--ks-border)}.growth-excerpt{max-height:120px;overflow:auto;white-space:pre-wrap;color:var(--ks-text)}
@media(prefers-reduced-motion:no-preference){.spin{animation:spin 1.3s linear infinite}}@keyframes spin{to{transform:rotate(360deg)}}
</style>
