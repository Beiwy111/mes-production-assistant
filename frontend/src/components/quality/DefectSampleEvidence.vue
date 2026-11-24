<template>
  <section class="qc-section evidence">
    <div class="qc-section__title">样本与工序实测</div>
    <p v-if="loading">正在读取样本记录…</p>
    <p v-else-if="loadError" role="alert">{{ loadError }} <el-button link @click="load">重试</el-button></p>
    <template v-else-if="detail">
      <p v-if="!detail.bindings.length">该缺陷尚未关联具体样本。</p>
      <div v-for="sample in detail.bindings" :key="sample.bindingId" class="entry">
        <strong>{{ sample.sampleLabel }}</strong>
        <div>{{ sample.sampleOrigin === 'MES_ITEM' ? 'MES 逐件检测项 · ' + sample.unitCode : '原始记录人工登记' }}</div>
        <div>关联依据：{{ sample.sourceRef }}</div>
        <small>登记人 ID {{ sample.recordedBy }} · {{ sample.recordedAt }}</small>
      </div>
      <details>
        <summary>关联缺陷样本</summary>
        <el-form label-position="top" size="small" @submit.prevent="saveBinding">
          <el-form-item label="样本来源">
            <el-radio-group v-model="mode">
              <el-radio value="item" :disabled="!detail.items.length">本单逐件检测项</el-radio>
              <el-radio value="manual">原始记录人工登记</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="mode === 'item'" label="本次质检样本" required>
            <el-select v-model="binding.inspectionItemId" placeholder="选择已保存的逐件检测项">
              <el-option v-for="i in detail.items" :key="i.itemId" :value="i.itemId" :label="i.code + ' · ' + i.label"/>
            </el-select>
          </el-form-item>
          <template v-else>
            <el-form-item label="原始记录上的质检单号" required><el-input v-model.trim="binding.inspectionNo" maxlength="50"/></el-form-item>
            <el-form-item label="原始样本标识" required><el-input v-model.trim="binding.sampleLabel" maxlength="120" placeholder="按原件填写条码或样本编号"/></el-form-item>
          </template>
          <el-form-item label="缺陷与该样本的关联依据" required>
            <el-input v-model.trim="binding.sourceRef" maxlength="255" placeholder="原始记录号、页码或附件编号"/>
          </el-form-item>
          <el-button native-type="submit" type="primary" plain :loading="saving" :disabled="!canBind">保存关联</el-button>
        </el-form>
      </details>
      <template v-if="detail.bindings.length">
        <p v-if="!detail.measurements.length">尚无工序实测读数。</p>
        <article v-for="m in detail.measurements" :key="m.measurementId" class="entry">
          <strong>#{{ m.measurementId }} · {{ m.sampleLabel }} · {{ m.stepName }} · {{ m.metricName }}</strong>
          <div v-if="m.beforeValue != null">工序前 {{ m.beforeValue }} {{ m.unit }} · {{ m.beforeMeasuredAt }}</div>
          <div v-if="m.afterValue != null">工序后 {{ m.afterValue }} {{ m.unit }} · {{ m.afterMeasuredAt }}</div>
          <div v-if="m.priorMeasurementId">补录自 #{{ m.priorMeasurementId }}</div>
          <div>测量来源：{{ m.sourceRef }}</div>
          <small>登记人 ID {{ m.recordedBy }} · {{ m.recordedAt }}</small>
          <el-button v-if="canComplete(m)" link type="primary" @click="complete(m)">补录另一侧读数</el-button>
        </article>
        <p v-if="!detail.steps.length">当前质检单未关联可选工单工序。</p>
        <details v-else ref="readingSection">
          <summary>登记工序实测</summary>
          <el-form label-position="top" size="small" @submit.prevent="saveReading">
            <p v-if="prior">正在补录 #{{ prior.measurementId }} <el-button link @click="resetReading">取消补录</el-button></p>
            <el-form-item label="缺陷样本" required>
              <el-select v-model="reading.bindingId" :disabled="!!prior">
                <el-option v-for="s in detail.bindings" :key="s.bindingId" :label="s.sampleLabel" :value="s.bindingId"/>
              </el-select>
            </el-form-item>
            <el-form-item label="工单工序" required>
              <el-select v-model="reading.stepId" :disabled="!!prior">
                <el-option v-for="s in detail.steps" :key="s.stepId" :label="s.name" :value="s.stepId"/>
              </el-select>
            </el-form-item>
            <el-form-item label="实测指标" required><el-input v-model.trim="reading.metricName" maxlength="100" :disabled="!!prior"/></el-form-item>
            <el-form-item label="计量单位" required><el-input v-model.trim="reading.unit" maxlength="24" :disabled="!!prior"/></el-form-item>
            <template v-for="side in sides" :key="side.key">
              <div v-if="prior?.[side.key + 'Value'] == null">
                <el-form-item :label="side.label + '读数'"><el-input v-model.trim="reading[side.key + 'Value']" inputmode="decimal" placeholder="按原始实测值填写，未测留空"/></el-form-item>
                <el-form-item :label="side.label + '实测时间（北京时间）'">
                  <el-date-picker v-model="reading[side.key + 'MeasuredAt']" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="原始记录时间"/>
                </el-form-item>
              </div>
            </template>
            <el-form-item label="测量来源" required><el-input v-model.trim="reading.sourceRef" maxlength="255" placeholder="仪器记录号、检验原始单或附件编号"/></el-form-item>
            <el-button native-type="submit" type="primary" plain :loading="saving" :disabled="!canRecord">保存实测</el-button>
          </el-form>
        </details>
      </template>
      <p v-if="saveError" role="alert">{{ saveError }}</p>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const props = defineProps({ defectId: { type: [String, Number], required: true } })
const path = `/quality/nonconforming/${props.defectId}/sample-evidence`
const loading = ref(false), saving = ref(false), detail = ref(null)
const loadError = ref(''), saveError = ref('')
const mode = ref('item'), readingSection = ref(null), prior = ref(null)
const sides = [{ key: 'before', label: '工序前' }, { key: 'after', label: '工序后' }]
const emptyBinding = () => ({ inspectionItemId: null, inspectionNo: '', sampleLabel: '', sourceRef: '' })
const emptyReading = () => ({
  bindingId: null, stepId: null, metricName: '', unit: '', beforeValue: '', beforeMeasuredAt: null,
  afterValue: '', afterMeasuredAt: null, sourceRef: ''
})
const binding = reactive(emptyBinding()), reading = reactive(emptyReading())
const canBind = computed(() => binding.sourceRef && (mode.value === 'item'
  ? binding.inspectionItemId : binding.inspectionNo && binding.sampleLabel))
const canRecord = computed(() => reading.bindingId && reading.stepId && reading.metricName && reading.unit && reading.sourceRef
  && (reading.beforeValue || reading.afterValue)
  && sides.every(({ key }) => Boolean(reading[key + 'Value']) === Boolean(reading[key + 'MeasuredAt'])))

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    detail.value = await request.get(path, { silent: true })
    if (!detail.value.items.length) mode.value = 'manual'
  } catch (e) {
    loadError.value = e.message?.includes('SQL') ? '暂时无法读取样本证据，请检查数据库迁移。' : e.message || '读取样本证据失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)

function canComplete(m) {
  return !m.priorMeasurementId && (m.beforeValue == null || m.afterValue == null)
    && detail.value.steps.some(s => s.stepId === m.stepId)
    && !detail.value.measurements.some(child => child.priorMeasurementId === m.measurementId)
}
function resetReading() {
  prior.value = null
  Object.assign(reading, emptyReading())
}
function complete(m) {
  resetReading()
  prior.value = m
  Object.assign(reading, { bindingId: m.bindingId, stepId: m.stepId, metricName: m.metricName, unit: m.unit })
  if (readingSection.value) {
    readingSection.value.open = true
    readingSection.value.scrollIntoView({ block: 'nearest' })
  }
}
const attempts = new Map()
function requestKey() {
  if (crypto.randomUUID) return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, n => n.toString(16).padStart(2, '0')).join('')
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-')
}
async function submit(kind, payload, reset) {
  if (saving.value) return
  saving.value = true
  saveError.value = ''
  try {
    const signature = kind + JSON.stringify(payload)
    if (!attempts.has(signature)) attempts.set(signature, requestKey())
    await request.post(`${path}/${kind}`, { ...payload, requestId: attempts.get(signature) }, { silent: true })
    attempts.delete(signature)
    reset()
    ElMessage.success('记录已保存')
    await load()
  } catch (e) {
    saveError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}
function saveBinding() {
  if (!canBind.value) return
  const identity = mode.value === 'item' ? { inspectionItemId: binding.inspectionItemId }
    : { inspectionNo: binding.inspectionNo, sampleLabel: binding.sampleLabel }
  return submit('bindings', { ...identity, sourceRef: binding.sourceRef }, () => Object.assign(binding, emptyBinding()))
}
function saveReading() {
  if (!canRecord.value) return
  return submit('measurements', {
    ...reading, priorMeasurementId: prior.value?.measurementId ?? null,
    beforeValue: reading.beforeValue || null, afterValue: reading.afterValue || null
  }, resetReading)
}
</script>

<style scoped>
.evidence { overflow-wrap: anywhere; }
.evidence p, .entry { font-size: 13px; }
.entry { padding: 8px 0; border-bottom: 1px solid var(--el-border-color-light); line-height: 1.6; }
.entry small { color: var(--el-text-color-secondary); }
details { margin-top: 12px; }
summary { cursor: pointer; font-weight: 600; color: var(--el-color-primary); }
.el-form { margin-top: 10px; }
[role="alert"] { color: var(--el-color-danger); }
</style>
