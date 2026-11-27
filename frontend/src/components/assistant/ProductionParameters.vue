<script setup>
import { computed } from 'vue'
import OperatorAvatar from './OperatorAvatar.vue'
const props = defineProps({ values: { type: Object, required: true }, operatorOptions: { type: Array, default: () => [] }, editable: Boolean, busy: Boolean })
defineEmits(['operator-change'])
const visibleValues = computed(() => Object.fromEntries(Object.entries(props.values).filter(([key]) => !['portfolioInputs','planning'].includes(key) && !(props.values.portfolioInputs && ['schedules','orderId'].includes(key)))))
const candidates = row => props.operatorOptions.filter(option => option.processStep === row.processStep)

const dispatchColumns = [['processStep','工序'],['recommendedOperatorName','操作员'],['activeLoad','在手任务'],['equipmentName','设备'],['planQty','数量'],['recommendReason','安排依据'],['conflictSummary','需留意']]
const labels = {
  conflictSummary: '需留意',
  preparePlan: '同时发布并提交计划', executionSteps: '本次确认执行内容', idleOnly: '人员选择', availability: '人员状态', notificationStatus: '任务送达状态',
  id: '编号', planId: '计划编号', orderId: '订单编号', dispatchId: '派工编号', workOrderId: '工单编号',
  productModel: '产品型号', productName: '产品', quantity: '数量', planQty: '计划数量', reportQty: '报工数量',
  planStart: '计划开始', planEnd: '计划结束', startTime: '开始时间', endTime: '结束时间',
  schedules: '排程明细', recommendations: '派工明细', dispatches: '已生成的派工单',
  processStep: '工序', processName: '工序名称', stepName: '工序名称', stepIndex: '工序序号', stepNo: '工序序号',
  workshop: '车间', workshopName: '车间', equipmentCode: '设备编号', equipmentName: '设备',
  operator: '操作员', recommendedOperator: '建议操作员', operatorName: '操作员姓名',
  targetUsername: '通知接收人', notificationId: '通知编号', action: '审核决定', estimatedHours: '预计工时',
  shift: '班次', shiftName: '班次', shiftDate: '班次日期', planDate: '计划日期', startDate: '开始日期',
  endDate: '结束日期', durationHours: '工时', count: '数量', reason: '原因', remark: '备注',
  status: '状态', pickedCount: '领料数量', failures: '未完成明细', allDone: '是否全部完成', message: '说明',
  recommendedOperatorName: '操作员姓名', recommendReason: '推荐理由', department: '部门', activeLoad: '在手任务',
  historyCount: '历史任务数', plannedStart: '计划开始', plannedEnd: '计划结束', plannedQuantity: '计划数量',
  plannedQty: '计划数量', standardHours: '标准工时', schedulingMode: '排产策略', conflicts: '排程冲突', saveAction: '保存方式',
  workOrderNo: '工单编号', operatorUsername: '操作员账号',
  label: '项目', detail: '说明', severity: '级别', code: '类型', finishDate: '预计完工',
  equipmentUtilization: '设备利用率', delayDays: '延期天数', canSubmit: '可提交', summary: '方案说明',
}
function display(key, value) {
  if (value == null || value === '') return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (key === 'idleOnly') return value === 'true' ? '只安排无在手任务人员' : '无任务人员优先，可追加待接收队列'
  if (key === 'action') return ({ pass: '通过', reject: '驳回', supplement: '要求补充', defer: '暂缓' })[value] || value
  if (key === 'schedulingMode') return ({ BALANCE: '负载均衡', DELIVERY: '交期优先', COST: '成本优先' })[value] || value
  if (key === 'saveAction' && value === 'draft') return '保存为草稿'
  return value
}
function summary(row) {
  return [row?.stepName || row?.processStep, row?.plannedStart || row?.workshopName, row?.recommendedOperatorName || row?.recommendedOperator].filter(Boolean).join(' · ')
}
</script>
<template>
  <dl class="production-fields">
    <div v-for="(value, key) in visibleValues" :key="key" class="field">
      <template v-if="Array.isArray(value)">
        <dt>{{ labels[key] || key }}</dt>
        <dd><ol v-if="key === 'executionSteps'" class="execution-steps"><li v-for="(step,index) in value" :key="index">{{ step }}</li></ol><div v-if="key === 'recommendations'" class="dispatch-table">
          <table><thead><tr><th v-for="[name,label] in dispatchColumns" :key="name">{{ label }}</th></tr></thead>
          <tbody><tr v-for="(row,index) in value" :key="index"><td v-for="[name] in dispatchColumns" :key="name"><div v-if="name === 'recommendedOperatorName'" class="operator-cell"><OperatorAvatar :username="row.recommendedOperator || ''" :name="row.recommendedOperatorName || ''" />
            <select v-if="editable && candidates(row).length" :aria-label="'更换' + row.processStep + '操作员'" :value="row.recommendedOperator" :disabled="busy" @change="$emit('operator-change', { processStep: row.processStep, username: $event.target.value })">
              <option v-for="option in candidates(row)" :key="option.username" :value="option.username" :disabled="option.disabled">{{ option.name || option.username }} · {{ option.activeLoad }} 项任务{{ option.disabled ? '（' + option.reason + '）' : '' }}</option>
            </select><span v-else>{{ row.recommendedOperatorName || row.recommendedOperator || '未匹配' }}</span></div>
            <template v-else>{{ display(name,row[name] ?? (name === 'equipmentName' ? row.equipmentCode : name === 'recommendedOperatorName' ? row.recommendedOperator : null)) }}</template></td></tr></tbody></table>
        </div><div v-else-if="key === 'schedules'" class="preview-rows">
          <div v-for="(row, index) in value.slice(0, 3)" :key="index">{{ summary(row) }}</div>
        </div><details v-if="value.length && key !== 'executionSteps'"><summary>查看 {{ value.length }} 项明细</summary>
          <ol><li v-for="(row, index) in value" :key="index">
            <ProductionParameters v-if="row && typeof row === 'object'" :values="row" />
            <template v-else>{{ display(key, row) }}</template>
          </li></ol>
        </details><span v-else-if="!value.length">无</span></dd>
      </template>
      <template v-else>
        <dt>{{ labels[key] || key }}</dt>
        <dd><ProductionParameters v-if="value && typeof value === 'object'" :values="value" />
          <template v-else>{{ display(key, value) }}</template></dd>
      </template>
    </div>
  </dl>
</template>
<style scoped>
.operator-cell { display:flex;align-items:center;gap:8px;min-width:140px; }
.operator-cell select { max-width:230px;min-height:34px;border:1px solid var(--aw-border);border-radius:6px;background:white;color:var(--aw-text);font:inherit;padding:4px 6px;cursor:pointer; }
.operator-cell select:focus-visible { outline:2px solid var(--mes-accent);outline-offset:0; }
.operator-cell select:disabled { cursor:wait;opacity:.6; }

.dispatch-table { max-width: 100%; overflow-x: auto; }
.dispatch-table table { width:100%; border-collapse:collapse; min-width:920px; }
.dispatch-table th,.dispatch-table td { text-align:left; padding:8px; border-bottom:1px solid var(--aw-border); vertical-align:top; }
.dispatch-table th { font-weight:500; white-space:nowrap; }
.field:has(.dispatch-table) { grid-template-columns: minmax(0,1fr); }
.execution-steps { margin: 0; }
.execution-steps li { border: 0; padding: 0; }
.dispatch-table td:nth-child(1), .dispatch-table td:nth-child(2) { white-space: nowrap; }
.dispatch-table td:nth-child(3), .dispatch-table td:nth-child(5) { text-align: right; }
.dispatch-table td:nth-child(4) { min-width:100px; }
.dispatch-table td:nth-child(6), .dispatch-table td:nth-child(7) { min-width:150px; }
.production-fields { margin: 0; font-size: 13px; line-height: 1.6; }
.field { display: grid; grid-template-columns: minmax(5em, 8em) minmax(0, 1fr); gap: 12px; padding: 4px 0; }
dt { color: var(--aw-muted); overflow-wrap: anywhere; }
dd { margin: 0; overflow-wrap: anywhere; }
summary { cursor: pointer; color: var(--mes-accent); }
ol { margin: 8px 0; padding: 0 0 0 1.6em; }
li { border-top: 1px solid var(--aw-border); padding: 4px 0; }
li .field { grid-template-columns: minmax(3.5em, 6em) minmax(0, 1fr); }
@media(max-width: 540px) { .field:has(details) { grid-template-columns: 1fr; gap: 2px; } }
</style>
