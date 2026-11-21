<script setup>
import { computed } from 'vue'
import { Document, Link } from '@element-plus/icons-vue'
import { limitationLabel } from '@/utils/workbenchLabels'
import { overviewEvidence, deliveryEvidence, detailedDomainEvidence, evidenceColumns, evidenceValue, knowledgeEvidence, analysisEvidence, qualityResearchEvidence } from '@/utils/workbenchEvidence'
import KnowledgeEvidence from './KnowledgeEvidence.vue'
import AnalysisEvidence from './AnalysisEvidence.vue'
import VisionImageEvidence from './VisionImageEvidence.vue'
const props = defineProps({ result: { type: Object, default: null } })
const facts = computed(() =>
  props.result?.verdict === 'DATA_CHANGED' || props.result?.historyEvidenceOmitted ? {} : props.result?.facts || {},
)
const knowledge = computed(() => knowledgeEvidence(props.result))
const analyses = computed(() => analysisEvidence(props.result))
const quality = computed(() => qualityResearchEvidence(facts.value))
const qty = (value) => (value == null ? '未知' : String(value))
const delivery = computed(() => deliveryEvidence(facts.value))
const lines = computed(() => delivery.value.lines)
const materials = computed(() => delivery.value.materials)
const workOrders = computed(() => delivery.value.workOrders)
const workOrderColumns = computed(() => evidenceColumns(workOrders.value))
const overviews = computed(() => overviewEvidence(facts.value))
const detailedDomains = computed(() => detailedDomainEvidence(facts.value))
const domains = computed(() =>
  [
    ['quality', '质量检验', 'inspectionCount'],
    ['equipment', '关联设备', 'equipmentCount'],
    ['aftersales', '售后案件', 'caseCount'],
  ].filter(([key, , count]) => facts.value[key]?.[count] != null && !facts.value[key]?.overview),
)
</script>

<template>
  <details
    v-if="
      result && result.verdict !== 'DATA_CHANGED' &&
      (lines.length ||
        knowledge.length || analyses.length || quality || result.historyEvidenceOmitted ||
        materials.length ||
        workOrders.length ||
        overviews.length ||
        detailedDomains.length ||
        domains.length ||
        result.queryRefs?.length ||
        result.missing?.length)
    "
    class="result-evidence"
  >
    <summary>
      <el-icon><Document /></el-icon> 查看业务与研究证据<span
        >{{ result.queryRefs?.length || 0 }} 组查询 ·
        {{
          result.checkedAt
            ? new Date(result.checkedAt).toLocaleString('zh-CN')
            : '本次运行'
        }}</span
      >
    </summary>
    <div class="evidence-content">
      <p v-if="result.historyEvidenceOmitted" class="truncation-notice">历史证据因保存空间限制未保留，原回答仅供回顾；请重新查询以获取完整依据。</p>
      <details v-if="quality?.metrics" class="overview-evidence">
        <summary>质量统计口径</summary>
        <p v-if="quality.metrics.complete === false" class="truncation-notice">质量统计不完整，无法确认全部记录总数，以下仅反映本次返回的证据。</p>
        <p>按检验记录统计；多次检验可能对应相同实物，不能合计为批次产出。没有独立产品口径时，不提供批次合格率。</p>
        <dl class="quality-fields"><template v-for="field in quality.metricFields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
        <ul v-if="quality.defectTypes.length"><li v-for="(item, index) in quality.defectTypes" :key="index">{{ evidenceValue(item.defectType) }}：{{ evidenceValue(item.recordCount) }} 条缺陷记录</li></ul>
        <ul v-if="quality.sampleRates.length"><li v-for="(item, index) in quality.sampleRates" :key="index">检验 {{ evidenceValue(item.inspectionId) }} · 本次抽检合格比例 {{ evidenceValue(item.samplePassRate) }} · {{ evidenceValue(item.status) }}</li></ul>
      </details>
      <details v-if="quality?.vision" class="overview-evidence">
        <summary>视觉证据 · {{ quality.visionLabel }}</summary>
        <p class="truncation-notice">{{ quality.visionNotice }} 图像数不等于产品数；检测框数不等于独立缺陷产品数。</p>
        <dl class="quality-fields"><template v-for="field in quality.visionFields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
        <details v-for="(record, index) in quality.records" :key="index" class="detail-table-section">
          <summary>检验 {{ evidenceValue(record.inspectionId) }} · 归档 {{ evidenceValue(record.recordId) }}<span v-if="record.mock === true"> · 模拟记录</span></summary>
          <p v-if="record.mock === true" class="truncation-notice">模拟记录，不计为真实缺陷。</p>
          <p v-else-if="record.mock !== false" class="truncation-notice">未声明是否模拟，不能作为已确认的真实缺陷。</p>
          <dl class="quality-fields"><template v-for="field in record.fields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
          <VisionImageEvidence :record="record" />
          <div v-if="record.boxes.length" class="table-scroll overview-table" tabindex="0" aria-label="归档检测框明细，可滚动查看">
            <table><thead><tr><th>检测类别</th><th>置信度</th><th>检测框坐标</th></tr></thead><tbody>
              <tr v-for="(box, boxIndex) in record.boxes" :key="boxIndex"><td>{{ evidenceValue(box.className ?? box.classId) }}</td><td>{{ evidenceValue(box.confidence) }}</td><td>{{ box.coordinates }}</td></tr>
            </tbody></table>
          </div>
          <p v-else>归档中没有可展示的检测框明细。</p>
        </details>
        <details v-if="quality.summaries.length" class="detail-table-section"><summary>历史文字摘要 · {{ quality.summaries.length }} 条</summary>
          <p>文字摘要不能还原原图、检测框或模型版本。</p>
          <p v-for="(item, index) in quality.summaries" :key="index">检验 {{ evidenceValue(item.inspectionId) }} · {{ evidenceValue(item.itemCode) }}：{{ evidenceValue(item.text) }}</p>
        </details>
        <ul v-if="quality.limitations.length" class="limitations"><li v-for="(item, index) in quality.limitations" :key="index">{{ item }}</li></ul>
      </details>
      <KnowledgeEvidence :bundles="knowledge" />
      <AnalysisEvidence :analyses="analyses" :business-refs="result.queryRefs || []" />
      <details v-for="domain in detailedDomains" :key="domain.key" class="overview-evidence">
        <summary>{{ domain.label }}详细证据 <span>{{ domain.tables.length }} 类记录</span></summary>
        <p v-if="domain.partial" class="truncation-notice">
          查询快照不完整或已截断，以下仅展示本次返回的全部明细，不能据此确定完整总数；请缩小查询范围后查询。
        </p>
        <p v-if="domain.pathsIncomplete" class="truncation-notice">
          追溯路径尚不完整，可能存在未解析的关联或已达到返回上限。<template v-if="domain.pathLimit">本次路径上限 {{ domain.pathLimit }} 条。</template>
        </p>
        <ul v-if="domain.warnings.length" class="limitations">
          <li v-for="warning in domain.warnings" :key="warning">{{ warning }}</li>
        </ul>
        <details v-for="table in domain.tables" :key="table.key" class="detail-table-section">
          <summary>
            {{ table.label }} · 本次返回 {{ table.rows.length }} 条<template v-if="table.total != null"> · 查询记录数 {{ table.total }} 条</template>
          </summary>
          <div v-if="table.rows.length" class="table-scroll overview-table" tabindex="0" :aria-label="`${table.label}，可滚动查看`">
            <table>
              <thead><tr><th v-for="column in table.columns" :key="column.key">{{ column.label }}</th></tr></thead>
              <tbody>
                <tr v-for="(row, index) in table.rows" :key="index">
                  <td v-for="column in table.columns" :key="column.key">{{ evidenceValue(row[column.key]) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else>本次未返回此类记录。</p>
        </details>
      </details>
      <details v-for="overview in overviews" :key="overview.key" class="overview-evidence">
        <summary>
          {{ overview.label }}明细
          <span>匹配 {{ overview.total }} 条 · 本次取回 {{ overview.returned }} 条</span>
        </summary>
        <p>
          {{ overview.period }} · 按{{ overview.dateField }}筛选 · 时区 {{ overview.timeZone }}
          <template v-if="overview.hasRange"><br />从 {{ overview.start }}（含）至 {{ overview.end }}（不含）</template>
          <template v-if="overview.keyword"><br />关键词：{{ overview.keyword }}</template>
        </p>
        <p v-if="overview.countBasis || overview.quantityBasis">
          {{ overview.countBasis }}<template v-if="overview.countBasis && overview.quantityBasis">；</template>{{ overview.quantityBasis }}。
        </p>
        <p v-if="overview.truncated" class="truncation-notice">
          明细已截断：本次最多取回 {{ overview.limit }} 条，以下展示全部 {{ overview.rows.length }} 条已取回明细；其余记录请缩小查询范围后查询。
        </p>
        <div v-if="overview.rows.length" class="table-scroll overview-table" tabindex="0" :aria-label="`${overview.label}明细，可滚动查看`">
          <table>
            <thead><tr><th v-for="column in overview.columns" :key="column.key">{{ column.label }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, index) in overview.rows" :key="index">
                <td v-for="column in overview.columns" :key="column.key">{{ evidenceValue(row[column.key]) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else>本次未返回匹配明细。</p>
      </details>
      <template v-if="result.missing?.length">
        <h4>核验范围与待补充证据</h4>
        <ul class="limitations">
          <li
            v-for="item in [...new Set(result.missing.map(limitationLabel))]"
            :key="item"
          >
            {{ item }}
          </li>
        </ul>
      </template>
      <template v-if="lines.length"
        ><h4>交付数量</h4>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>订单行</th>
                <th>剩余交付</th>
                <th>确认可发货</th>
                <th>库存分配情景</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(line, i) in lines" :key="i">
                <td>{{ line.orderItemPk || line.order_item_id || i + 1 }}</td>
                <td>{{ qty(line.remainingDeliveryQty) }}</td>
                <td>{{ qty(line.shippableQty) }}</td>
                <td>{{ qty(line.candidateReadyQty) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          库存分配情景尚未预留，不能直接作为可发货数量。未知数量不按 0 处理。
        </p></template
      >
      <template v-if="materials.length"
        ><h4>物料缺口</h4>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>物料</th>
                <th>当前 BOM 情景缺口</th>
                <th>已确认精确缺口</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, i) in materials" :key="i">
                <td>{{ item.materialName || item.materialId || i + 1 }}</td>
                <td>{{ qty(item.scenarioGapQty) }}</td>
                <td>{{ qty(item.confirmedShortageNow) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          不同物料分别核算，情景结果受 BOM、领料与订单预留记录完整性影响。
        </p></template
      >
      <div v-if="domains.length" class="domain-stats">
        <div v-for="[key, label, count] in domains" :key="key">
          <span>{{ label }}</span
          ><strong>{{ qty(facts[key][count]) }} <small>条</small></strong>
        </div>
      </div>
      <details v-if="workOrders.length" class="overview-evidence">
        <summary>订单关联工单 <span>{{ workOrders.length }} 条</span></summary>
        <div class="table-scroll overview-table" tabindex="0" aria-label="订单关联工单，可滚动查看">
          <table>
            <thead><tr><th v-for="column in workOrderColumns" :key="column.key">{{ column.label }}</th></tr></thead>
            <tbody>
              <tr v-for="(row, index) in workOrders" :key="index">
                <td v-for="column in workOrderColumns" :key="column.key">{{ evidenceValue(row[column.key]) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>已保存的工单计划和执行记录，不代表交付承诺。</p>
      </details>
      <div v-if="result.queryRefs?.length" class="query-refs">
        <el-icon><Link /></el-icon><span>本次查询凭据</span
        ><code v-for="ref in result.queryRefs" :key="ref">{{ ref }}</code>
      </div>
    </div>
  </details>
</template>

<style scoped>
.result-evidence { border: 1px solid var(--aw-border, #e4e4e7); border-radius: 6px; margin-top: 18px; background: var(--aw-surface, #fafafa); color: var(--aw-text, #202124); font: 14px/1.7 var(--aw-font-sans, Inter, sans-serif); color-scheme: light; }
.quality-fields { display: grid; grid-template-columns: minmax(90px, 150px) 1fr; gap: 6px 10px; font-size: 13px; }
.quality-fields dt { color: var(--aw-muted, #62666d); }
.quality-fields dd { margin: 0; overflow-wrap: anywhere; }
.result-evidence > summary { display: flex; align-items: center; gap: 8px; cursor: pointer; list-style: none; padding: 12px 14px; color: var(--aw-text, #202124); font-size: 13px; font-weight: 500; }
.result-evidence > summary > span { margin-left: auto; color: var(--aw-muted, #62666d); font-size: 11px; font-weight: 400; }
.result-evidence > summary::-webkit-details-marker { display: none; }
summary:focus-visible, .table-scroll:focus-visible { outline: 2px solid var(--aw-muted, #62666d); outline-offset: 2px; }
.evidence-content { border-top: 1px solid var(--aw-border, #e4e4e7); padding: 4px 14px 14px; }
.limitations { padding-left: 18px; color: var(--aw-warning, #8c6228); font-size: 12px; line-height: 1.9; }
.evidence-content h4 { font-size: 13px; font-weight: 500; color: var(--aw-text, #202124); margin: 15px 0 10px; }
.table-scroll { overflow-x: auto; border: 1px solid var(--aw-border, #e4e4e7); border-radius: 4px; }
.overview-evidence { margin: 15px 0; min-width: 0; }
.overview-evidence > summary { cursor: pointer; color: var(--aw-text, #202124); font-size: 13px; }
.detail-table-section { margin: 12px 0; }
.detail-table-section > summary { cursor: pointer; color: var(--aw-text, #202124); font-size: 13px; padding: 8px 0; }
.overview-evidence > summary span { color: var(--aw-muted, #62666d); margin-left: 10px; font-size: 11px; }
.overview-table { max-height: 380px; overflow: auto; }
.overview-table th { position: sticky; top: 0; }
.evidence-content .truncation-notice { color: var(--aw-warning, #8c6228); }
table { border-collapse: collapse; width: 100%; white-space: nowrap; font-size: 13px; text-align: left; }
th { background: var(--aw-panel, #f5f5f5); color: var(--aw-muted, #62666d); font-weight: 500; font-size: 12px; }
td, th { padding: 10px; border-bottom: 1px solid var(--aw-border, #e4e4e7); }
td { color: var(--aw-text, #202124); font-variant-numeric: tabular-nums; }
tbody tr:hover { background: var(--aw-hover, #eceef0); }
.evidence-content p { font-size: 12px; color: var(--aw-muted, #62666d); line-height: 1.8; overflow-wrap: anywhere; }
.domain-stats { display: flex; flex-wrap: wrap; gap: 24px; margin: 15px 0; }
.domain-stats > div { display: flex; flex-direction: column; gap: 8px; }
.domain-stats span { font-size: 11px; color: var(--aw-muted, #62666d); }
.domain-stats strong { font: 500 20px var(--aw-font-mono, monospace); color: var(--aw-text, #202124); }
.domain-stats small { font-size: 12px; font-weight: 400; }
.query-refs { display: flex; flex-wrap: wrap; gap: 6px; color: var(--aw-muted, #62666d); font-size: 11px; align-items: center; margin-top: 14px; }
.query-refs code { font: 11px var(--aw-font-mono, monospace); overflow-wrap: anywhere; background: var(--aw-panel, #f5f5f5); border: 1px solid var(--aw-border, #e4e4e7); padding: 3px 5px; border-radius: 4px; }
@media (max-width: 650px) { .result-evidence > summary { flex-wrap: wrap; } .result-evidence > summary > span { width: 100%; margin-left: 22px; } .quality-fields { grid-template-columns: minmax(70px, 110px) minmax(0, 1fr); } }
</style>
