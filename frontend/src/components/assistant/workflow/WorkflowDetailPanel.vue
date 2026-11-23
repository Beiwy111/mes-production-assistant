<script setup>
import { computed } from 'vue'
import { ArrowDownRight, ArrowRight, ArrowUpLeft, MousePointer2, PanelRight, X } from 'lucide-vue-next'
import { agentStateText } from '../../../utils/agentInspector.js'
import { colorByTone, typeInfo } from './workflowModel'
import WorkflowGlyph from './WorkflowGlyph.vue'
const props = defineProps({ node: Object, graph: Object, role: Object, childCount: Number, notice: String })
defineEmits(['select','open','close'])
const links = computed(() => {
  if (!props.node) return []
  const nodes = new Map(props.graph.nodes.map(n => [n.id,n]))
  return props.graph.edges.filter(e => e.to === props.node.id || e.from === props.node.id).map(e => {
    const next = e.from === props.node.id
    return { node: nodes.get(next ? e.to : e.from), next, label: e.label }
  }).filter(item => item.node)
})
</script>
<template>
  <aside class="inspector" aria-label="节点详情">
    <div class="section-title"><PanelRight :size="15"/><h3>节点详情</h3><button v-if="node" class="dismiss" aria-label="关闭节点详情" @click="$emit('close')"><X :size="15"/></button></div>
    <div v-if="!node" class="empty"><MousePointer2 :size="25"/><strong>选择一个节点</strong><p>点击画布节点查看配置、连线与执行记录。</p></div>
    <div v-else :key="node.id" class="body">
      <div class="type-line" :style="{'--accent':colorByTone[typeInfo(node.kind)[2]]}">
        <span class="type"><WorkflowGlyph :kind="node.flowName ? node.id : node.kind" :size="14"/>{{ typeInfo(node.kind)[0] }}</span>
        <span class="state" :data-state="role?.status">{{ agentStateText(role?.status || 'UNKNOWN') }}</span>
      </div>
      <h2>{{ node.label }}</h2><p class="description">{{ node.detail || typeInfo(node.kind)[1] }}</p>
      <button v-if="node.flowName || childCount" class="enter" @click="$emit('open',node.id)">
        {{ node.flowName ? '打开 Agent 工作流' : '展开迭代子流程 · ' + childCount }}<ArrowRight :size="15"/>
      </button>
      <section><h4>配置摘要</h4><dl><dt>节点 ID</dt><dd>{{ node.id }}</dd>
        <template v-for="[label,value] in node.config" :key="label"><dt>{{ label }}</dt><dd>{{ value }}</dd></template>
      </dl></section>
      <section><h4>上下游关系 <small>{{ links.length }}</small></h4>
        <button v-for="(item,i) in links" :key="i" class="relation" @click="$emit('select',item.node.id)">
          <ArrowDownRight v-if="item.next" :size="14"/><ArrowUpLeft v-else :size="14"/>
          <span>{{ item.next ? '下游' : '上游' }}</span><b>{{ item.node.label }}</b><small v-if="item.label">{{ item.label }}</small>
        </button><p v-if="!links.length">此节点没有可见连线。</p>
      </section>
      <details v-for="field in [['inputs','输入参数'],['outputs','输出字段']]" :key="field[0]">
        <summary>{{ field[1] }} <small>{{ node[field[0]].length }}</small></summary>
        <div v-if="node[field[0]].length" class="params"><div v-for="([name,value],i) in node[field[0]]" :key="i"><code>{{ name }}</code><span>{{ value }}</span></div></div>
        <p v-else>流程定义未声明{{ field[1] }}。</p>
      </details>
      <details open><summary>执行结果与日志</summary><p v-if="notice">{{ notice }}</p>
        <p v-if="role?.summary">{{ role.summary }}</p>
        <ol v-if="role?.logs?.length"><li v-for="log in role.logs" :key="log.seq"><strong>{{ log.label || log.kind }}</strong><span>{{ agentStateText(log.status) }}</span><p v-if="log.detail">{{ log.detail }}</p></li></ol>
        <p v-else-if="!notice && !role?.summary">{{ node.flowName ? '本轮暂无该 Agent 的执行回执。' : '本轮未提供该 DSL 节点的执行回执。' }}</p>
      </details>
    </div>
  </aside>
</template>
<style scoped>
.inspector{display:flex;flex-direction:column;overflow:hidden;min-height:0;border-left:1px solid #dee7eb;background:white;color:#263f4a}
.section-title,.type-line,.type,.relation,.enter,h4{display:flex;align-items:center}
.section-title{height:47px;flex:none;padding:0 15px;gap:7px;border-bottom:1px solid #e5ecef;color:#57717d}.section-title h3{font-size:13px;color:#344d58;margin:0}
button{font:inherit;cursor:pointer;color:inherit}.dismiss{margin-left:auto;display:grid;place-items:center;width:25px;height:25px;border:0;background:transparent;border-radius:5px}.dismiss:hover{background:#edf5f4}
.body{min-height:0;overflow:auto;padding:16px;overflow-wrap:anywhere}.type-line{gap:8px;justify-content:space-between}.type{gap:5px;color:var(--accent);background:color-mix(in srgb,var(--accent) 9%,white);border-radius:5px;font-weight:600;padding:3px 7px;font-size:11px}
.state,.description,.body p,.empty p{color:#647984}.state{font-size:11px}.state[data-state=COMPLETED]{color:#21815c}.state[data-state=RUNNING]{color:#3576bd}.state[data-state=FAILED]{color:#b25042}
h2{font-size:19px;line-height:1.4;margin:10px 0 5px}.body p{margin:5px 0;font-size:12px;line-height:1.65;white-space:pre-line}.enter{width:100%;justify-content:space-between;border:1px solid #bcdcd5;background:#f0f8f5;color:#146e67;padding:8px 9px;border-radius:7px;margin-top:12px;font-size:12px}
section,details{margin-top:15px;border-top:1px solid #e5ecef;padding-top:12px}h4,summary{font-size:12px;color:#354e5a;font-weight:650}h4{gap:6px;margin:0 0 8px}small{font-size:10px;font-weight:450;color:#788b94}summary{cursor:pointer;margin-bottom:6px}summary small{margin-left:4px}
dl{display:grid;grid-template-columns:max-content 1fr;gap:6px 10px;margin:0;font-size:11px;line-height:1.6;background:#f6f9fa;border:1px solid #e5ecef;border-radius:7px;padding:9px}dt{color:#6c7f88}dd{margin:0;word-break:break-word;font-family:var(--aw-font-mono,monospace)}
.relation{gap:4px;width:100%;margin:3px 0;padding:5px 3px;border:0;border-radius:5px;text-align:left;background:transparent;color:#56757b}.relation:hover{background:#edf6f4}.relation span{flex:none;font-size:10px}.relation b{color:#344f5a;font-weight:500;font-size:11px}.relation small{margin-left:auto}
.params{display:grid;gap:5px}.params>div{display:grid;background:#f6f9fa;border-radius:5px;padding:6px 8px;gap:2px}.params code{font-size:11px}.params span,li span{color:#6d808a;font-size:10px}
ol{padding-left:17px;margin:8px 0}li{margin:6px 0;font-size:12px}li strong{font-weight:550}li span{margin-left:5px}.empty{margin:50px 20px;text-align:center;display:grid;justify-items:center;gap:9px;color:#69818d}.empty strong{font-size:14px}.empty p{font-size:12px;margin:0}
</style>
