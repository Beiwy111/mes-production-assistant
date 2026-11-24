<script setup>
import { computed, reactive, watch } from 'vue'
import AgentAvatar from './AgentAvatar.vue'
const props = defineProps({ flow: { type: Object, required: true }, busy: Boolean, customer: Boolean, historical: Boolean })
const emit = defineEmits(['action'])
const draft = computed(() => props.flow.orderDraft)
const identityLocked = computed(() => props.customer || draft.value?.customerIdentityLocked === true)
const editable = computed(() => draft.value && ['COLLECTING', 'READY'].includes(draft.value.status) && !props.flow.needsRecovery && !props.historical)
const selectedProduct = computed(() => draft.value?.products?.find((product) => String(product.materialId) === String(draft.value?.fields?.materialId)))
const productSummary = computed(() => {
  const values = draft.value?.fields || {}
  const name = selectedProduct.value?.materialName || values.productName || (props.flow.needsRecovery ? '产品待恢复' : '尚未选择产品')
  const quantity = values.quantity == null ? '数量待补充' : `${values.quantity} ${selectedProduct.value?.unit || values.unit || ''}`.trim()
  return `${name} · ${quantity}`
})
const form = reactive({})
const fields = ['materialId', 'quantity', 'requiredDeliveryDate', 'receiverName', 'receiverPhone', 'receiverAddress', 'customerName', 'remark']
watch(() => draft.value, (value) => {
  for (const key of fields) form[key] = value?.fields?.[key] ?? ''
}, { immediate: true })
const changed = computed(() => Object.keys(changedFields()).length > 0)
const formProduct = computed(() => draft.value?.products?.find((product) => String(product.materialId) === String(form.materialId)))
const labels = { COLLECTING: '待补充', READY: '待确认', SUBMITTED: '已提交', CANCELLED: '已取消', EXPIRED: '已过期' }
const expiryLabel = computed(() => {
  const value = new Date(draft.value?.expiresAt)
  return Number.isNaN(value.getTime()) ? '' : value.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
})
const actorLabel = (agentId) => ({ SUP: '总协调 Agent', ORDER: '订单 Agent', SERVICE: '业务服务' })[agentId] || ''
const stepStatus = (status) => ({ RUNNING: '处理中', COMPLETED: '已完成', SUCCEEDED: '已完成', SUCCESS: '已完成', FAILED: '未完成', SKIPPED: '已跳过', WAITING: '等待确认', PENDING: '待处理' })[status] || status
function changedFields() {
  const patch = {}
  for (const key of fields) {
    if (identityLocked.value && key === 'customerName') continue
    if (String(form[key] ?? '') !== String(draft.value?.fields?.[key] ?? '')) patch[key] = key === 'quantity' && form[key] !== '' ? Number(form[key]) : form[key]
  }
  return patch
}
function revise() {
  if (props.busy || !editable.value) return
  emit('action', { action: 'revise', fields: changedFields() })
}
function submit() {
  if (props.busy || !editable.value) return
  emit('action', { action: 'confirm', fields: changedFields() })
}
</script>

<template>
  <section class="order-flow" aria-label="订单处理记录">
    <details v-if="!historical && (flow.steps?.length || flow.pending)" class="order-process" :open="!!flow.pending">
      <summary><span>{{ flow.pending || '查看处理过程' }}</span><small>{{ flow.pending ? '处理中' : `${flow.steps?.length || 0} 个步骤` }}</small></summary>
      <ol class="order-steps" aria-live="polite">
      <li v-for="(step, index) in flow.steps || []" :key="index">
        <div><span><AgentAvatar v-if="step.agentId === 'SUP' || step.agentId === 'ORDER'" class="order-avatar" :agent-id="step.agentId" :active="!!flow.pending && step.status === 'RUNNING'" /><small v-if="actorLabel(step.agentId)">{{ actorLabel(step.agentId) }} · </small>{{ step.label }}</span><small>{{ stepStatus(step.status) }}</small></div>
        <p v-if="step.detail">{{ step.detail }}</p>
      </li>
      <li v-if="flow.pending"><div><span>{{ flow.pending }}</span><small>处理中</small></div></li>
      </ol>
    </details>
    <div v-if="draft" class="order-draft">
      <div class="draft-heading"><strong>订单草稿</strong><span>{{ labels[draft.status] || '待恢复' }}</span></div>
      <template v-if="historical">
        <p class="product-summary">{{ productSummary }}</p>
        <p class="draft-notice">请使用下方最新的订单草稿操作。</p>
      </template>
      <p v-else-if="flow.needsRecovery">请从服务器恢复当前草稿，再查看或操作。</p>
      <template v-else>
        <p v-if="!editable" class="product-summary">{{ productSummary }}</p>
        <p v-if="editable && draft.missing?.length" class="draft-notice">待补充：{{ draft.missing.map((item) => item.label).join('、') }}</p>
        <form v-if="editable" class="draft-form" @submit.prevent="submit">
          <label class="full-row">产品
            <select v-model="form.materialId" required :disabled="busy">
              <option disabled value="">请选择产品</option>
              <option v-if="form.materialId && !formProduct" :value="form.materialId">{{ draft.fields?.productName || '已选产品' }}</option>
              <option v-for="product in draft.products || []" :key="product.materialId" :value="product.materialId">{{ product.materialName }} · {{ product.materialCode }}{{ product.specification ? ` · ${product.specification}` : '' }}</option>
            </select>
            <small v-if="formProduct?.unit" class="field-hint">计量单位：{{ formProduct.unit }}</small>
          </label>
          <label>数量<input v-model="form.quantity" type="number" min="1" step="1" required :disabled="busy" /></label>
          <label>交付日期<input v-model="form.requiredDeliveryDate" type="date" required :disabled="busy" /></label>
          <label>客户名称<input v-model="form.customerName" required :disabled="busy || identityLocked" maxlength="100" /></label>
          <label>收货人<input v-model="form.receiverName" required :disabled="busy" maxlength="80" /></label>
          <label>联系电话<input v-model="form.receiverPhone" type="tel" required :disabled="busy" maxlength="30" /></label>
          <label class="full-row">收货地址<input v-model="form.receiverAddress" required :disabled="busy" maxlength="500" /></label>
          <label class="full-row">备注（选填）<input v-model="form.remark" :disabled="busy" maxlength="500" placeholder="如有特殊需求，可在这里补充" /></label>
          <p v-if="draft.priceNotice" class="full-row draft-notice">{{ draft.priceNotice }}</p>
          <div v-if="editable" class="full-row draft-actions">
            <button type="submit" class="submit-order" :disabled="busy">提交审核</button>
            <button type="button" :disabled="busy || !changed" @click="revise">暂存</button>
            <button type="button" class="cancel-order" :disabled="busy" @click="emit('action', { action: 'cancel' })">取消草稿</button>
          </div>
        </form>
        <p v-if="!editable && draft.priceNotice" class="draft-notice">{{ draft.priceNotice }}</p>
        <div v-if="draft.result" class="order-result" role="status">
          <strong>{{ draft.result.orderNo ? `订单 ${draft.result.orderNo}` : '订单处理结果' }}</strong>
          <p>{{ draft.result.message }}</p>
          <p v-if="draft.result.status">{{ draft.result.status === 'PENDING' ? '待订单审核' : draft.result.status }}</p>
        </div>
        <p v-if="expiryLabel && editable" class="draft-expiry">草稿有效期至 {{ expiryLabel }}（北京时间）</p>
      </template>
      <button v-if="!historical" type="button" class="recover-draft" :disabled="busy" @click="emit('action', { action: 'recover' })">{{ flow.needsRecovery ? '恢复订单草稿' : '刷新订单状态' }}</button>
    </div>
    <p v-else-if="flow.needsRecovery">本轮未保存草稿编号，请继续描述订单需求。系统不会自动重新提交。</p>
  </section>
</template>

<style scoped>
.order-avatar { --avatar-size: 24px; margin-right: 8px; }
.order-flow { margin: 14px 0; color: var(--aw-text, #202124); font: 14px/1.6 var(--aw-font-sans, Inter, sans-serif); color-scheme: light; }
.order-process { margin-bottom: 12px; color: var(--aw-muted, #62666d); }
.order-process > summary { display: flex; align-items: center; gap: 10px; padding: 7px 0; cursor: pointer; list-style: none; font-size: 13px; }
.order-process > summary::-webkit-details-marker { display: none; }
.order-process > summary::before { content: '›'; font-size: 18px; line-height: 1; transition: transform .15s; }
.order-process[open] > summary::before { transform: rotate(90deg); }
.order-process > summary small { margin-left: auto; font-size: 13px; color: var(--aw-muted, #62666d); }
.order-steps { list-style: decimal; padding-left: 22px; margin: 8px 0; }
.order-steps li { padding: 5px 0; }
.order-steps li > div { display: flex; gap: 20px; justify-content: space-between; }
.order-steps small, .order-steps p, .draft-expiry { color: var(--aw-muted, #62666d); font-size: 13px; }
.order-steps p { margin: 4px 0; white-space: pre-wrap; }
.order-draft { border: 1px solid var(--aw-border, #e4e4e7); border-radius: 8px; padding: 20px; background: var(--aw-surface, #fafafa); }
.draft-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.draft-heading strong { font-size: 14px; font-weight: 500; }
.draft-heading span { background: var(--aw-panel, #f5f5f5); border: 1px solid var(--aw-border, #e4e4e7); padding: 2px 7px; border-radius: 4px; }
.draft-heading span, .draft-notice { color: var(--aw-muted, #62666d); font-size: 13px; line-height: 1.7; }
.product-summary { margin: 8px 0; }
.draft-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 16px; margin-top: 16px; }
.draft-form label { min-width: 0; display: flex; flex-direction: column; gap: 7px; color: var(--aw-muted, #62666d); font-size: 13px; }
.draft-form input, .draft-form select { width: 100%; min-width: 0; box-sizing: border-box; height: 40px; padding: 8px 11px; border: 1px solid var(--aw-border, #e4e4e7); border-radius: var(--mes-radius-sm, 6px); font-family: inherit; font-size: 14px; line-height: 22px; background: var(--aw-panel, #f5f5f5); color: var(--aw-text, #202124); color-scheme: light; }
.draft-form select option { background: var(--aw-panel, #f5f5f5); color: var(--aw-text, #202124); }
.draft-form input::placeholder { color: var(--aw-subtle, #676767); }
.draft-form input:focus, .draft-form select:focus { outline: none !important; box-shadow: none; border: 2px solid var(--mes-accent, #6e5a9b); padding: 7px 10px; }
.field-hint { color: var(--aw-subtle, #676767); font-size: 13px; }
.full-row { grid-column: 1 / -1; }
.draft-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding-top: 4px; }
button { background: var(--aw-panel, #f5f5f5); border: 1px solid var(--aw-border, #e4e4e7); border-radius: var(--mes-radius-sm, 6px); min-height: 36px; padding: 8px 12px; color: var(--aw-text, #202124); font: inherit; font-size: 13px; cursor: pointer; }
button:hover:not(:disabled) { background: var(--aw-hover, #eceef0); }
button:focus-visible, summary:focus-visible { outline: 2px solid var(--aw-muted, #62666d); outline-offset: 3px; }
.submit-order { background: var(--aw-text, #202124); border-color: var(--aw-text, #202124); color: var(--aw-bg, #ffffff); font-weight: 500; padding-inline: 18px; }
.submit-order:hover:not(:disabled) { background: var(--aw-muted, #62666d); border-color: var(--aw-muted, #62666d); }
.cancel-order { margin-left: auto; border-color: transparent; color: var(--aw-muted, #62666d); }
.cancel-order:hover:not(:disabled) { color: var(--aw-danger, #b34848); }
button:disabled { cursor: default; opacity: .45; }
input:disabled, select:disabled { background: var(--aw-surface, #fafafa); opacity: 1; -webkit-text-fill-color: var(--aw-muted, #62666d); color: var(--aw-muted, #62666d); }
.recover-draft { margin-top: 12px; padding: 3px 0; min-height: 28px; border: 0; background: transparent; font-size: 13px; color: var(--aw-muted, #62666d); }
.order-result { margin-top: 14px; padding: 12px 0; border-top: 1px solid var(--aw-border, #e4e4e7); }
.order-result strong { color: var(--aw-success, #347453); }
.order-result p { margin: 6px 0; }
@media (max-width: 540px) { .draft-form { grid-template-columns: minmax(0, 1fr); } .order-draft { padding: 16px; } }
@media (prefers-reduced-motion: reduce) { .order-process > summary::before { transition: none; } }
</style>
