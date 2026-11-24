<script setup>
import { computed } from 'vue'

const props = defineProps({ message: Object, agentId: String })
const state = { MATCHES: '已核对来源', NO_MATCHES: '没有可核验的匹配', UNAVAILABLE: '暂不可用',
  NOT_CONFIGURED: '服务未配置', SCOPE_NOT_COVERED: '当前范围未检索全厂案例' }
const relations = { SHARED_EQUIPMENT: '共用设备', SHARED_WORK_ORDER: '同一工单', SHARED_PROCESS: '同一工序',
  SHARED_DEFECT: '相同缺陷', ON_EQUIPMENT: '涉及设备', RECORDED_ACTION: '记录处置', IN_PROCESS: '涉及工序',
  HAS_DEFECT: '记录缺陷', FOR_WORK_ORDER: '涉及工单', AT_PROCESS: '涉及工序',
  SHARED_CAUSE: '原记录注明同一根因', RECORDED_CAUSE: '原记录确认的根因' }
const relationLabel = value => String(value).split('→').map(code => relations[code] || code).join(' → ')
const bundles = computed(() => {
  const m = props.message
  if (!m?.result || m.evidenceInvalidated || m.workflow?.invalidated || m.result.historyEvidenceOmitted ||
    m.result.verdict === 'DATA_CHANGED' || !Array.isArray(m.result.experienceEvidence)) return []
  return m.result.experienceEvidence.filter(b => b && (!props.agentId || b.agentId === props.agentId)).map(b => {
    const hits = new Map()
    for (const hit of Array.isArray(b.hits) && b.status === 'MATCHES' ? b.hits : []) {
      if (!['QUALITY','MAINTENANCE'].includes(hit?.kind) || b.domain !== 'DIAGNOSTIC' && hit.kind !== b.domain || !hit.title || !hit.text || !/^[0-9a-f]{64}$/.test(hit.record_hash) ||
          !/^experience:[a-f0-9-]{36}:[a-f0-9]{64}$/.test(hit.evidence_ref) || !hit.evidence_ref.endsWith(':' + hit.record_hash) ||
          !/^[1-9]\d*$/.test(hit.record_id) ||
          hit.source_table !== ({ QUALITY: 'nonconforming_product', MAINTENANCE: 'equipment_maintenance_record' })[hit.kind]) continue
      hits.set(hit.evidence_ref, hit)
    }
    const paths = Array.isArray(b.graph_paths) && b.graph_status === 'MATCHES'
      ? b.graph_paths.filter(p => p?.hops === 2 && hits.has(p.source_ref) && hits.has(p.target_ref)) : []
    return { ...b, hits, paths }
  })
})
</script>

<template>
  <details v-for="b in bundles" :key="b.agentId" class="experience">
    <summary>{{ ({ QUALITY: '质量', MAINTENANCE: '维修', DIAGNOSTIC: '维修与质量' })[b.domain] }}历史依据 · {{ state[b.status] || '状态未知' }}<span v-if="b.hits.size"> · {{ b.hits.size }} 条</span></summary>
    <p v-if="b.retrieved_at">核验时间 {{ new Date(b.retrieved_at).toLocaleString('zh-CN') }}</p>
    <p v-if="b.stale_record_count > 0">已排除与 MySQL 不一致或已撤除的 {{ b.stale_record_count }} 条候选记录。</p>
    <details v-for="[key, hit] in b.hits" :key="key" class="record">
      <summary>{{ hit.title }}</summary>
      <p>{{ hit.text }}</p><p>MySQL 来源 <code>{{ hit.source_table }} #{{ hit.record_id }}</code></p>
      <p>召回来源 {{ hit.retrieval_source === 'graph' ? 'Neo4j' : 'Qdrant' }} · 记录引用 <code>{{ key }}</code></p>
    </details>
    <details v-if="b.paths.length" class="record">
      <summary>查看 Neo4j 关联图 · {{ b.paths.length }} 条路径</summary>
      <figure v-for="(p, index) in b.paths" :key="index">
        <figcaption>{{ relationLabel(p.relation) }}</figcaption>
        <div class="path"><span>{{ b.hits.get(p.source_ref).title }}</span><b aria-hidden="true">→</b><span>{{ p.entity_label }}</span>
          <template v-if="p.source_ref !== p.target_ref"><b aria-hidden="true">←</b><span>{{ b.hits.get(p.target_ref).title }}</span></template>
        </div>
      </figure>
      <p>每条关系均引用上方已核验的历史记录。关联本身不能证明当前故障的原因。</p>
    </details>
    <p v-if="b.graph_status === 'UNAVAILABLE_OR_STALE'">图谱暂不可用，本次仅展示通过核验的检索记录。</p>
    <p v-if="b.vector_status === 'UNAVAILABLE'">Qdrant 暂不可用，本次仅使用可核验的图谱记录。</p>
  </details>
</template>

<style scoped>
.experience { color:var(--aw-text,#202124); font-size:13px; line-height:1.7; margin:10px 0; overflow-wrap:anywhere; }
summary { cursor:pointer; }
.record { border-left:2px solid var(--aw-border,#ddd); padding-left:12px; margin:8px 0 8px 5px; }
p { margin:6px 0; white-space:pre-wrap; }
code, figcaption { color:var(--aw-muted,#555); font-size:12px; }
figure { margin:10px 0; }
.path { display:flex; align-items:center; flex-wrap:wrap; gap:5px; }
.path span { padding:4px 8px; max-width:100%; border:1px solid var(--aw-border,#ddd); border-radius:4px; background:white; }
</style>
