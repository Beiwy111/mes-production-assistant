<template>
  <el-dialog :model-value="modelValue" :title="otherUser ? `人脸管理 · ${targetName || targetUserId}` : '我的人脸信息'" width="min(560px, 94vw)" top="5vh" class="face-auth-dialog" destroy-on-close :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy" @update:model-value="$emit('update:modelValue', $event)" @open="load">
    <el-alert title="人脸登录为演示功能，尚无可靠活体检测。原密码登录继续可用。" type="warning" :closable="false" show-icon />
    <div v-loading="loading">
      <p class="face-notice">{{ capabilities.notice }}</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-alert v-if="message" :title="message" type="success" :closable="false" />
      <el-alert v-if="status?.serviceMismatch" title="服务配置已变更。可撤销本地绑定，但远端清理需要管理员恢复原服务地址和 Key。" type="warning" :closable="false" />
      <el-descriptions v-if="status" :column="1" border class="face-status">
        <el-descriptions-item label="录入状态">{{ status.state === 'ENROLLING' ? '正在录入' : status.enrolled ? '已录入' : '未录入' }}</el-descriptions-item>
        <el-descriptions-item label="绑定版本">{{ status.bindingRevision ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="最近更新">{{ status.updatedAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="远端清理">{{ status.pendingCleanup ? `${status.pendingCleanup} 项待重试；已撤销的绑定不能用于登录` : '无待清理事项' }}</el-descriptions-item>
      </el-descriptions>
      <template v-if="!otherUser">
        <el-radio-group v-model="mode" :disabled="busy || loading" class="face-mode">
          <el-radio-button value="enroll">{{ status?.enrolled ? '更新人脸' : '录入人脸' }}</el-radio-button>
          <el-radio-button value="test" :disabled="!status?.enrolled">匹配测试</el-radio-button>
        </el-radio-group>
        <p v-if="mode === 'test'" class="face-notice">与当前账号已绑定的人脸比较，展示实际分数和阈值。请让本人和已授权的另一位参与者分别单独测试；测试不会修改绑定或创建登录会话。</p>
        <p v-else class="face-notice">{{ status?.enrolled ? '更新会替换这个账号原有的人脸。' : '' }} 自动采集后，请核对照片中是本人以及目标账号，再确认保存。</p>
        <figure v-if="comparisonFrame" class="face-captured-frame">
          <img :src="comparisonFrame" alt="本次匹配测试实际发送的画面，仅保存在页面内存中" />
          <figcaption>本次匹配测试实际发送的画面；请先核对画面中的参与者。</figcaption>
        </figure>
        <el-descriptions v-if="comparison" :column="1" border class="face-comparison">
          <el-descriptions-item label="比对分数">{{ comparison.similarity.toFixed(5) }}</el-descriptions-item>
          <el-descriptions-item label="当前阈值">{{ comparison.threshold.toFixed(5) }}</el-descriptions-item>
          <el-descriptions-item label="按当前阈值判定"><el-tag :type="comparison.matched ? 'warning' : 'info'">{{ comparison.matched ? '通过' : '拒绝' }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="绑定版本">{{ comparison.bindingRevision }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <p v-if="otherUser" class="face-notice">管理员可查看状态、撤销绑定和重试清理。人脸录入与更新由本人登录后授权完成。</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="再次验证当前登录账号的密码"><el-input v-model="password" type="password" show-password autocomplete="current-password" :maxlength="128" :disabled="busy" placeholder="不会保存该密码" /></el-form-item>
        <template v-if="!otherUser">
          <el-checkbox v-model="consent" :disabled="busy || !ready">{{ mode === 'test' ? '我已取得本次参与者授权，同意连续检测并进行匹配测试' : '我已阅读说明，同意自动采集并在确认后录入或更新本人人脸' }}</el-checkbox>
          <FaceCamera v-if="modelValue && !pendingCapture" :key="mode + ':' + password" :allowed="!!password && consent && ready && !busy && !loading" :processing="busy" :confirmation-required="mode === 'enroll'" :consent-version="capabilities.consentVersion" :start-label="mode === 'test' ? '开始匹配测试' : '开始自动采集'" @capture="onCapture" @started="error = ''; message = ''; comparisonFrame = ''; comparison = null" />
          <div v-if="pendingCapture" class="face-binding-confirm">
            <el-alert :title="`即将绑定到账号：${pendingAccountName}`" type="warning" :closable="false" />
            <figure class="face-captured-frame">
              <div class="face-captured-frame__image">
                <img :src="pendingCapture" alt="本次准备绑定的实际采集画面，仅保存在页面内存中" />
                <div v-if="pendingBox" class="face-captured-frame__box" :style="pendingBoxStyle" aria-hidden="true" />
              </div>
              <figcaption>这张照片将用于建立人脸特征。当前尚未提交或修改绑定。</figcaption>
            </figure>
            <el-checkbox v-model="confirmOwnFace" :disabled="busy">我确认框选的人脸是本人，并绑定到以上账号</el-checkbox>
            <div class="face-confirm-actions">
              <el-button :disabled="busy" @click="clearPending">重新采集</el-button>
              <el-button type="primary" :loading="busy" :disabled="!confirmOwnFace || !consent || !password || busy" @click="confirmEnrollment">确认绑定到此账号</el-button>
            </div>
          </div>
        </template>
      </el-form>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="load">刷新状态</el-button>
      <el-button v-if="status?.enrolled || status?.pendingCleanup" type="danger" plain :disabled="!password || busy" @click="remove">{{ status.enrolled ? '删除人脸绑定' : '重试远端清理' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { faceCapabilities, faceStatus, enrollFace, deleteFace, testFaceMatch } from '@/api/face'
import { useUserStore } from '@/stores/user'
import FaceCamera from './FaceCamera.vue'
const props = defineProps({ modelValue: Boolean, targetUserId: { type: [Number, String], default: null }, targetName: { type: String, default: '' } })
defineEmits(['update:modelValue'])
const user = useUserStore(), capabilities = ref({}), status = ref(null), error = ref(''), message = ref(''), password = ref(''), consent = ref(false), loading = ref(false), busy = ref(false)
const mode = ref('enroll'), comparison = ref(null)
const pendingCapture = ref(''), pendingBox = ref(null), pendingAccountId = ref(''), pendingAccountName = ref(''), confirmOwnFace = ref(false), comparisonFrame = ref('')
const pendingBoxStyle = computed(() => pendingBox.value ? { left: `${(1 - pendingBox.value.x - pendingBox.value.width) * 100}%`, top: `${pendingBox.value.y * 100}%`, width: `${pendingBox.value.width * 100}%`, height: `${pendingBox.value.height * 100}%` } : {})
const otherUser = computed(() => props.targetUserId != null && String(props.targetUserId) !== String(user.userInfo?.userId))
const ready = computed(() => !!capabilities.value.enabled && !!capabilities.value.configured && !!status.value && status.value.state !== 'ENROLLING' && !status.value.serviceMismatch && !status.value.pendingCleanup)
let loadGeneration = 0
function clearPending() { pendingCapture.value = ''; pendingBox.value = null; pendingAccountId.value = ''; pendingAccountName.value = ''; confirmOwnFace.value = false }
watch(() => props.modelValue, open => { if (!open) { loadGeneration++; password.value = ''; consent.value = false; comparison.value = null; comparisonFrame.value = ''; clearPending() } })
watch(mode, () => { password.value = ''; consent.value = false; comparison.value = null; comparisonFrame.value = ''; error.value = ''; message.value = ''; clearPending() })
watch(password, clearPending)
watch(consent, accepted => { if (!accepted) { clearPending(); comparisonFrame.value = ''; comparison.value = null } })
async function load() {
  const generation = ++loadGeneration
  const targetId = otherUser.value ? props.targetUserId : null
  loading.value = true; error.value = ''; message.value = ''; password.value = ''; consent.value = false; status.value = null
  mode.value = 'enroll'; comparison.value = null; comparisonFrame.value = ''; clearPending()
  try {
    const caps = await faceCapabilities()
    if (generation !== loadGeneration) return
    capabilities.value = caps
    if (!capabilities.value.enabled || !capabilities.value.configured) { error.value = '人脸服务尚未启用或配置，请联系管理员；密码登录仍可使用。'; return }
    const result = await faceStatus(targetId)
    if (generation === loadGeneration) { status.value = result; mode.value = result.enrolled ? 'test' : 'enroll' }
  } catch (err) { if (generation === loadGeneration) error.value = err.message || '状态查询失败' }
  finally { if (generation === loadGeneration) loading.value = false }
}
function onCapture(photo, box) {
  if (mode.value === 'test') { comparisonFrame.value = photo; save(photo); return }
  if (!props.modelValue || otherUser.value || !ready.value || !password.value || !consent.value || busy.value) return
  pendingCapture.value = photo; pendingBox.value = box
  pendingAccountId.value = String(user.userInfo?.userId ?? '')
  pendingAccountName.value = user.userInfo?.username || '当前账号'
  confirmOwnFace.value = false
}
function confirmEnrollment() {
  if (mode.value !== 'enroll' || !pendingCapture.value || !confirmOwnFace.value) return
  if (!pendingAccountId.value || pendingAccountId.value !== String(user.userInfo?.userId ?? '')) { clearPending(); error.value = '当前账号已变化，请重新采集。'; return }
  save(pendingCapture.value)
}
async function save(photo) {
  if (!props.modelValue || otherUser.value || busy.value || !ready.value || !photo || !password.value || !consent.value) return
  if (mode.value === 'enroll' && (!confirmOwnFace.value || photo !== pendingCapture.value)) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    const data = { imageBase64: photo, password: password.value, consent: true, consentVersion: capabilities.value.consentVersion }
    if (mode.value === 'test') {
      comparison.value = await testFaceMatch(data)
      message.value = '匹配测试已完成。分数用于核对当前判定，不代表识别准确率。'
    } else {
      comparison.value = null
      status.value = await enrollFace(data)
      message.value = status.value.pendingCleanup ? '绑定已更新，旧样本正在清理，请稍后刷新状态。' : '本人人脸已绑定，可在登录页使用演示人脸验证。'
    }
    if (mode.value === 'enroll') consent.value = false
  } catch (err) { error.value = err.message || '录入失败' }
  finally { password.value = ''; photo = null; clearPending(); busy.value = false }
}
async function remove() {
  if (busy.value || !password.value) return
  consent.value = false; comparison.value = null; comparisonFrame.value = ''; clearPending()
  try { await ElMessageBox.confirm('将撤销该用户的人脸登录能力并请求删除远端样本。远端不可用时会记录待清理任务并重试。', '删除人脸绑定', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }) }
  catch { return }
  busy.value = true; error.value = ''; message.value = ''
  try {
    status.value = await deleteFace(password.value, otherUser.value ? props.targetUserId : null)
    mode.value = 'enroll'
    await nextTick()
    message.value = status.value.pendingCleanup ? '本地绑定已撤销，远端数据待清理。请稍后刷新；不能将此状态视为数据已完全删除。' : '绑定已撤销，CompreFace 已确认对应样本删除。备份留存请联系管理员处理。'
    consent.value = false
  } catch (err) { error.value = err.message || '删除失败' }
  finally { password.value = ''; busy.value = false }
}
</script>
<style scoped>.face-notice { color: #64748b; font-size: 13px; line-height: 1.7; }.face-status, .el-form, .face-mode, .face-comparison, .face-binding-confirm { margin-top: 16px; }.el-checkbox { white-space: normal; height: auto; }.face-captured-frame { margin: 14px 0; }.face-captured-frame img { display: block; width: 100%; max-height: 300px; object-fit: contain; transform: scaleX(-1); border-radius: 10px; }.face-captured-frame__image { position: relative; }.face-captured-frame__image img { max-height: none; }.face-captured-frame__box { position: absolute; box-sizing: border-box; border: 3px solid #34d399; border-radius: 10px; pointer-events: none; }.face-captured-frame figcaption { margin-top: 8px; font-size: 12px; color: #64748b; line-height: 1.6; }.face-confirm-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; }:global(.face-auth-dialog .el-dialog__body) { max-height: calc(85vh - 110px); overflow-y: auto; }</style>
