<script setup>
import { computed, ref } from 'vue'
import { ChevronRight, Network, Search, X } from 'lucide-vue-next'
import { agentStateText } from '../../../utils/agentInspector.js'
import WorkflowGlyph from './WorkflowGlyph.vue'
const props = defineProps({ catalog: Array, active: String, states: Object })
defineEmits(['choose', 'close'])
const query = ref('')
const matching = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return props.catalog.filter(f => [f.label, f.agentId, f.description, f.workflowName, f.name]
    .filter(Boolean).join(' ').toLowerCase().includes(needle))
})
</script>
<template>
  <nav class="library" aria-label="工作流列表">
    <div class="heading"><h3>工作流目录</h3><span>{{ catalog.length }}</span><button class="mobile-close" aria-label="收起工作流目录" @click="$emit('close')"><X :size="16"/></button></div>
    <label class="search"><Search :size="15"/><input v-model="query" type="search" aria-label="搜索工作流" placeholder="搜索工作流"/></label>
    <button class="overview" :aria-current="active==='overview' ? 'page' : undefined" @click="$emit('choose','overview')">
      <Network :size="17"/><strong>Agent 协作总览</strong><ChevronRight :size="15"/>
    </button>
    <div class="entries">
      <template v-for="group in ['总 Agent','子 Agent','知识流程']" :key="group">
        <template v-if="matching.some(f => f.group === group)">
          <h4>{{ group }}<span>{{ matching.filter(f => f.group===group).length }}</span></h4>
          <button v-for="f in matching.filter(item=>item.group===group)" :key="f.name" class="entry"
              :aria-current="active===f.name ? 'page' : undefined" :title="f.description" @click="$emit('choose', f.name)">
            <span class="glyph"><WorkflowGlyph :kind="f.agentId || 'knowledge-retrieval'"/></span>
            <span class="copy"><b>{{ f.label }}</b><small>{{ f.description }}</small>
              <span class="facts"><i :data-state="states[f.agentId]?.status"/>{{ states[f.agentId] ? agentStateText(states[f.agentId].status) : '流程定义' }}<em>·</em>{{ f.nodes.length }} 节点</span>
            </span>
          </button>
        </template>
      </template>
      <p v-if="!matching.length" class="no-match">没有匹配的工作流</p>
    </div>
  </nav>
</template>
<style scoped>
.library{display:flex;flex-direction:column;min-height:0;background:#fff;border-right:1px solid #dee7eb;overflow:hidden}
.heading,.overview,.search,.entry,.facts,h4{display:flex;align-items:center}
.heading{gap:7px;padding:17px 15px 11px}.heading h3{margin:0;font-size:15px;font-weight:650}.heading>span,h4 span{color:#758790;font-size:10px;background:#f0f4f5;padding:1px 6px;border-radius:10px}
.search{margin:0 12px 11px;gap:7px;padding:7px 9px;border:1px solid #dce5e9;border-radius:7px;color:#71838c}.search:focus-within{border-color:#268783}.search input{width:100%;min-width:0;border:0;outline:none;background:transparent;font:inherit;font-size:12px;color:inherit}
button{font:inherit;cursor:pointer;text-align:left;color:inherit}
.overview{margin:0 10px;padding:10px;gap:8px;border:1px solid #dbe6e8;background:#f8fafb;border-radius:8px;color:#47606a;font-size:12px}.overview svg:last-child{margin-left:auto}
.entries{min-height:0;overflow:auto;padding:0 8px 14px}.overview[aria-current],.entry[aria-current]{border-color:#a6d4ce;background:#eaf6f3;color:#166b64}
h4{margin:17px 7px 6px;gap:6px;color:#657a82;font-size:11px;font-weight:600;line-height:1.4}h4 span{font-weight:500}
.entry{width:100%;border:1px solid transparent;border-radius:8px;gap:9px;padding:8px 6px;background:transparent}.entry:hover,.overview:hover{background:#f1f7f6}
.glyph{display:grid;place-items:center;align-self:flex-start;flex:0 0 28px;height:28px;background:#eef3f5;border-radius:7px;color:#59737e}.entry[aria-current] .glyph{background:#d9efea;color:#19756b}
.copy{min-width:0;display:grid;gap:3px}.copy b{font-size:13px;line-height:1.3}.copy small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px;color:#647780}
.facts{gap:4px;font-size:10px;color:#748790}.facts i{width:5px;height:5px;background:#a9b6bc;border-radius:50%}.facts i[data-state=COMPLETED]{background:#259c72}.facts i[data-state=RUNNING]{background:#428ad9}.facts i[data-state=FAILED]{background:#c86150}.facts em{font-style:normal}
.no-match{margin:18px 8px;color:#657a82;font-size:12px}.mobile-close{display:none;margin-left:auto;background:transparent;border:0;padding:4px}
@container workflow (max-width:880px){.mobile-close{display:grid}}
</style>
