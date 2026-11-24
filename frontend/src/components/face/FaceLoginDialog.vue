<template>
  <el-dialog :model-value="modelValue" title="人脸登录" width="min(520px, 94vw)" top="5vh" class="face-auth-dialog" destroy-on-close :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="$emit('update:modelValue', $event)" @open="load">
    <el-alert title="尚无可靠活体检测，照片或视频可能通过验证。请仅使用本人的人脸登录。" type="warning" :closable="false" show-icon />
    <div v-loading="loading">
      <p class="face-notice">{{ capabilities.notice }}</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="MES 账号"><el-input v-model.trim="account" autocomplete="username" :maxlength="64" :disabled="busy" placeholder="先输入已录入人脸的账号" /></el-form-item>
        <el-checkbox v-model="consent" :disabled="busy || !capabilities.loginAllowed">我已阅读说明，同意连续检测并自动验证本次人脸</el-checkbox>
        <FaceCamera v-if="modelValue" :key="account" :allowed="!!account && consent && !!capabilities.loginAllowed && !busy && !loading" :processing="busy" :consent-version="capabilities.consentVersion" start-label="开始人脸登录" @capture="submit" @started="error = ''" />
      </el-form>
    </div>
    <template #footer><el-button :disabled="busy" @click="$emit('update:modelValue', false)">返回密码登录</el-button></template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { faceCapabilities, createFaceChallenge, faceLogin } from '@/api/face'
import { useUserStore } from '@/stores/user'
import FaceCamera from './FaceCamera.vue'
const props = defineProps({ modelValue: Boolean, username: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue', 'success'])
const account = ref(''), consent = ref(false), error = ref(''), loading = ref(false), busy = ref(false), capabilities = ref({})
const userStore = useUserStore()
let dialogGeneration = 0
watch(() => props.modelValue, open => { if (!open) { dialogGeneration++; consent.value = false } })
async function load() {
  const current = ++dialogGeneration
  account.value = props.username; consent.value = false; error.value = ''; loading.value = true
  try {
    const result = await faceCapabilities()
    if (current !== dialogGeneration) return
    capabilities.value = result
    if (!capabilities.value.loginAllowed) error.value = '当前尚未开放人脸登录，请继续使用密码登录，或联系管理员配置服务。'
  } catch (err) { if (current === dialogGeneration) error.value = err.message || '无法获取人脸服务状态，请使用密码登录。' }
  finally { if (current === dialogGeneration) loading.value = false }
}
async function submit(photo) {
  if (!props.modelValue || busy.value || !photo || !consent.value || !account.value || !capabilities.value.loginAllowed) return
  const current = dialogGeneration, username = account.value
  let issuedToken
  busy.value = true; error.value = ''
  try {
    const { challengeId } = await createFaceChallenge(username)
    if (current !== dialogGeneration || !props.modelValue) return
    const result = await faceLogin({ username, challengeId, imageBase64: photo, consent: true, consentVersion: capabilities.value.consentVersion })
    if (current !== dialogGeneration || !props.modelValue) return
    issuedToken = result.token
    await userStore.acceptLoginToken(issuedToken)
    emit('update:modelValue', false); emit('success')
  } catch (err) {
    if (issuedToken && userStore.token === issuedToken) userStore.logout()
    if (current === dialogGeneration) error.value = err.message || '验证失败，请重新开始或使用密码登录。'
  }
  finally { photo = null; busy.value = false }
}
</script>
<style scoped>.face-notice { color: #64748b; font-size: 13px; line-height: 1.7; }.el-form { margin-top: 16px; }.el-checkbox { white-space: normal; height: auto; }:global(.face-auth-dialog .el-dialog__body) { max-height: calc(85vh - 110px); overflow-y: auto; }</style>
