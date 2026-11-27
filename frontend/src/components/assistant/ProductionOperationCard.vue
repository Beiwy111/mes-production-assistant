<script setup>
import { reactive } from 'vue'
import ProductionParameters from './ProductionParameters.vue'
import ProductionPlanningSummary from './ProductionPlanningSummary.vue'
const props = defineProps({ flow: { type: Object, required: true }, latest: Boolean, busy: Boolean })
defineEmits(['decision', 'supplement', 'operator-change'])
const statuses = { PENDING: '待确认', EXECUTED: '已执行', REJECTED: '已取消', FAILED: '未完成' }
const inputs = reactive(Object.fromEntries((props.flow.missingFields || []).map(field => [field.name, props.flow.fields?.[field.name] || ''])))
const inputTypes = { reportQty: 'number', plannedQty: 'number', planStart: 'date', planEnd: 'date', startTime: 'datetime-local', endTime: 'datetime-local' }
</script>
<template>
  <form v-if="flow.status === 'NEEDS_INPUT' && latest && flow.missingFields?.length" class="production-card supplement" @submit.prevent="$emit('supplement', { ...inputs })">
    <p v-if="flow.dataNote">{{ flow.dataNote }}</p>
    <div v-for="field in flow.missingFields" :key="field.name" class="input-field" :class="{ 'input-field--schemes': field.name === 'schedulingMode' && flow.schemes?.length }">
      <fieldset v-if="field.name === 'schedulingMode' && flow.schemes?.length" class="scheme-picker">
        <legend>{{ field.label }}</legend>
        <p class="scheme-hint">比较工序安排与冲突，选择方案后继续预览。</p>
        <div class="scheme-grid">
          <article v-for="scheme in flow.schemes" :key="scheme.key" class="scheme" :class="{ 'scheme--selected': inputs.schedulingMode === scheme.key }">
            <label class="scheme-choice"><input v-model="inputs.schedulingMode" type="radio" name="schedulingMode" :value="scheme.key" :disabled="busy || scheme.canSubmit === false" required /> <strong>{{ scheme.label }}</strong></label>
            <p v-if="scheme.summary">{{ scheme.summary }}</p>
            <dl class="scheme-metrics"><div><dt>预计完工</dt><dd>{{ scheme.finishDate || '—' }}</dd></div><div><dt>设备利用率</dt><dd>{{ scheme.equipmentUtilization == null ? '—' : `${scheme.equipmentUtilization}%` }}</dd></div><div><dt>延期</dt><dd>{{ scheme.delayDays == null ? '—' : `${scheme.delayDays} 天` }}</dd></div></dl>
            <p v-if="scheme.canSubmit === false" class="scheme-warning">此方案暂不可提交，请检查冲突或调整排产条件。</p>
            <ProductionParameters :values="{ schedules: scheme.schedules || [], conflicts: scheme.conflicts || [] }" />
          </article>
        </div>
      </fieldset>
      <label v-else>{{ field.label }}
        <select v-if="flow.options?.[field.name]?.length" v-model="inputs[field.name]" :disabled="busy" required>
          <option disabled value="">请选择{{ field.label }}</option>
          <option v-for="option in flow.options[field.name]" :key="option.value" :value="option.value">{{ option.label }}{{ option.description ? ` · ${option.description}` : '' }}</option>
        </select>
        <select v-else-if="field.name === 'action'" v-model="inputs[field.name]" :disabled="busy" required><option disabled value="">选择审核决定</option><option value="通过">通过</option><option value="驳回">驳回</option></select>
        <input v-else v-model="inputs[field.name]" :disabled="busy" :type="inputTypes[field.name] || 'text'" :maxlength="500" :min="inputTypes[field.name] === 'number' ? 1 : undefined" required />
      </label>
    </div>
    <button type="submit" :disabled="busy">继续预览</button>
  </form>
  <ProductionPlanningSummary v-if="flow.planning?.orderPlans?.length" :planning="flow.planning" />
  <section v-if="flow.confirmation" class="production-card" aria-label="生产操作方案">
    <header><strong>{{ flow.confirmation.title }}</strong><span>{{ flow.result?.allDone === false ? '部分完成' : statuses[flow.status] }}</span></header>
    <ProductionParameters v-if="flow.status === 'PENDING'" :values="flow.confirmation.params" :operator-options="flow.confirmation.operatorOptions || []" :editable="latest && flow.actionCode === 'confirmSmartDispatch' && !flow.uncertainDecision" :busy="busy" @operator-change="$emit('operator-change', $event)" />
    <ProductionParameters v-if="flow.result && typeof flow.result === 'object'" :values="flow.result" />
    <template v-if="flow.status === 'PENDING' && latest">
      <p v-if="flow.confirmation.expiresAt">有效期至 {{ String(flow.confirmation.expiresAt).replace('T', ' ').slice(0, 19) }}</p>
      <p v-if="flow.confirmation.adjustmentHint">{{ flow.confirmation.adjustmentHint }}</p>
      <div class="decisions">
        <button v-if="!flow.uncertainDecision || flow.uncertainDecision === 'APPROVE'" type="button" class="approve" :disabled="busy" @click="$emit('decision', 'APPROVE')">{{ flow.deciding === 'APPROVE' ? '正在提交…' : flow.uncertainDecision ? '核对并重试原确认' : '确认执行' }}</button>
        <button v-if="!flow.uncertainDecision || flow.uncertainDecision === 'SKIP'" type="button" :disabled="busy" @click="$emit('decision', 'SKIP')">{{ flow.deciding === 'SKIP' ? '正在取消…' : flow.uncertainDecision ? '重试取消原方案' : '取消' }}</button>
      </div>
    </template>
    <p v-else-if="flow.status === 'PENDING'">历史方案，如需办理请重新预览。</p>
  </section>
</template>
<style scoped>
.production-card { margin: 6px 0 12px; padding: 16px; border: 1px solid var(--aw-border); border-radius: 10px; background: #fff; color: var(--aw-text); }
header, .decisions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
header { justify-content: space-between; margin-bottom: 10px; }
header strong { font-size: 16px; }
header span, p { margin: 8px 0; color: var(--aw-muted); font-size: 12px; }
button { border: 1px solid var(--aw-border); border-radius: 6px; padding: 6px 14px; min-height: 34px; background: #fff; color: inherit; cursor: pointer; font: inherit; font-size: 13px; }
button.approve { background: var(--mes-brand-deep, #30334b); color: white; }
button:disabled { opacity: .55; cursor: wait; }
.supplement { display: grid; gap: 12px; justify-items: start; }
.input-field { width: min(100%, 560px); min-width: 0; }
.input-field--schemes { width: 100%; }
.supplement label { display: grid; gap: 5px; color: var(--aw-muted); font-size: 13px; }
.supplement input, .supplement select { box-sizing: border-box; width: 100%; min-height: 36px; padding: 5px 9px; border: 1px solid var(--aw-border); border-radius: 5px; color: var(--aw-text); background: #fff; font: inherit; }
.scheme-picker { border: 0; padding: 0; margin: 0; min-width: 0; }
.scheme-picker legend { font-size: 14px; font-weight: 600; }
.scheme-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 210px), 1fr)); gap: 10px; }
.scheme { border: 1px solid var(--aw-border); border-radius: 8px; padding: 12px; min-width: 0; }
.scheme--selected { border-color: var(--mes-accent, #30334b); background: #f8f9fc; }
.supplement .scheme-choice { display: flex; align-items: center; gap: 8px; color: var(--aw-text); cursor: pointer; }
.supplement .scheme-choice input { width: 16px; min-height: 16px; margin: 0; }
.scheme-metrics { margin: 10px 0; font-size: 12px; line-height: 1.8; }
.scheme-metrics div { display: flex; justify-content: space-between; gap: 8px; }
.scheme-metrics dt { color: var(--aw-muted); }
.scheme-metrics dd { margin: 0; }
.scheme-warning { color: #a14d13; }
</style>
