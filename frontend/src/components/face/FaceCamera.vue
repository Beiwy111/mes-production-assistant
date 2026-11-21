<template>
  <div class="face-camera">
    <div class="face-camera__viewport" :style="{ aspectRatio }">
      <video v-show="stream" ref="video" autoplay muted playsinline aria-label="摄像头实时预览" />
      <div v-if="stream && box" class="face-camera__box" :style="boxStyle" aria-hidden="true" />
      <p v-if="!stream">请独自正视镜头，保持光线均匀、面部清晰</p>
      <span v-if="stream" class="face-camera__badge">自动检测中 · {{ stableFrames }}/2</span>
    </div>
    <p class="face-camera__status" role="status" aria-live="polite">{{ message }}</p>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <div class="face-camera__actions">
      <el-button v-if="!stream && !opening" type="primary" :disabled="!allowed || processing" @click="start">{{ startLabel }}</el-button>
      <el-button v-if="stream || opening" @click="cancel">停止检测</el-button>
      <span v-if="opening">正在请求摄像头权限…</span>
      <span v-if="processing">检测已通过，正在提交，请稍候…</span>
    </div>
    <p class="face-camera__hint">连续两次检测到清晰且位置稳定的单人脸后自动采集，无需拍摄按钮。{{ confirmationRequired ? '请核对采集照片和账号，确认后才会保存绑定。' : '采集完成后自动提交验证。' }} 自动检测不代表活体检测。</p>
    <p class="face-camera__hint">Windows 11：设置 → 隐私和安全性 → 摄像头，允许桌面应用访问；同时允许浏览器站点使用摄像头。请使用 localhost 或 HTTPS。</p>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { previewFace } from '@/api/face'

const props = defineProps({
  allowed: { type: Boolean, default: false },
  processing: { type: Boolean, default: false },
  confirmationRequired: { type: Boolean, default: false },
  consentVersion: { type: String, default: '' },
  startLabel: { type: String, default: '开始自动检测' }
})
const emit = defineEmits(['capture', 'started'])
const video = ref(null), stream = shallowRef(null), error = ref(''), opening = ref(false)
const message = ref('完成授权后点击开始，将自动检测并采集画面。'), box = ref(null), stableFrames = ref(0), aspectRatio = ref('4 / 3')
const boxStyle = computed(() => box.value ? {
  left: `${(1 - box.value.x - box.value.width) * 100}%`, top: `${box.value.y * 100}%`,
  width: `${box.value.width * 100}%`, height: `${box.value.height * 100}%`
} : {})
let generation = 0, frameTimer, deadlineTimer, requestController, frames = 0

function stop() {
  generation++
  clearTimeout(frameTimer); clearTimeout(deadlineTimer)
  requestController?.abort(); requestController = null
  stream.value?.getTracks().forEach(track => track.stop())
  stream.value = null; box.value = null; stableFrames.value = 0
  if (video.value) video.value.srcObject = null
  opening.value = false
}
function cancel() { stop(); message.value = '已停止检测。点击开始可重新检测。' }
function active(current) { return current === generation && props.allowed && !document.hidden && !!stream.value }
function validBox(value) {
  return value && ['x', 'y', 'width', 'height'].every(key => Number.isFinite(value[key])) &&
    value.x >= 0 && value.y >= 0 && value.width > 0 && value.height > 0 &&
    value.x + value.width <= 1.001 && value.y + value.height <= 1.001
}
function stable(previous, current) {
  if (!previous) return false
  const width = Math.max(0, Math.min(previous.x + previous.width, current.x + current.width) - Math.max(previous.x, current.x))
  const height = Math.max(0, Math.min(previous.y + previous.height, current.y + current.height) - Math.max(previous.y, current.y))
  const intersection = width * height
  return intersection / (previous.width * previous.height + current.width * current.height - intersection) >= 0.65
}
function snapshot() {
  const element = video.value
  if (!element?.videoWidth || !element.videoHeight) return null
  const scale = Math.min(1, 1280 / element.videoWidth, 960 / element.videoHeight)
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(element.videoWidth * scale); canvas.height = Math.round(element.videoHeight * scale)
  canvas.getContext('2d').drawImage(element, 0, 0, canvas.width, canvas.height)
  return canvas.toDataURL('image/jpeg', 0.9)
}
async function inspect(current) {
  if (!active(current)) return
  if (frames >= 30) { stop(); message.value = '本轮检测已结束，请调整光线和位置后重新开始。'; return }
  let photo = null
  try {
    photo = snapshot()
    if (!photo) { frameTimer = setTimeout(() => inspect(current), 300); return }
    frames++
    requestController = new AbortController()
    const result = await previewFace({ imageBase64: photo, consent: true, consentVersion: props.consentVersion }, requestController.signal)
    if (!active(current)) return
    if (result.ready && validBox(result.box)) {
      stableFrames.value = stable(box.value, result.box) ? stableFrames.value + 1 : 1
      box.value = result.box
      message.value = `已检测到清晰人脸，请保持稳定（${stableFrames.value}/2）`
      if (stableFrames.value >= 2) {
        const capturedBox = { ...box.value }
        stop(); message.value = props.confirmationRequired ? '已自动采集，请核对照片和账号后确认绑定。' : '检测已通过，正在自动提交。'
        emit('capture', photo, capturedBox)
        return
      }
    } else {
      stableFrames.value = 0; box.value = null
      message.value = result.message || '未检测到合适的人脸，请调整位置和光线。'
    }
    // Schedule only after the previous response; never overlap detection requests.
    frameTimer = setTimeout(() => inspect(current), 900)
  } catch (err) {
    if (!active(current)) return
    stop(); error.value = err.message || '人脸检测暂不可用，请稍后重新开始。'
    message.value = '检测已暂停。'
  } finally { photo = null }
}
async function start() {
  if (!props.allowed || props.processing || opening.value || stream.value || document.hidden) return
  stop(); error.value = ''; message.value = '正在开启摄像头…'; frames = 0
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    error.value = '当前地址无法使用摄像头，请通过 localhost 或 HTTPS 访问。'
    return
  }
  opening.value = true
  const current = ++generation
  let acquired
  try {
    acquired = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } }, audio: false })
    if (current !== generation || !props.allowed || document.hidden) { acquired.getTracks().forEach(track => track.stop()); return }
    stream.value = acquired
    await nextTick()
    if (!video.value || current !== generation) { acquired.getTracks().forEach(track => track.stop()); return }
    video.value.srcObject = acquired
    await video.value.play()
    if (!active(current)) { acquired.getTracks().forEach(track => track.stop()); return }
    aspectRatio.value = `${video.value.videoWidth || 640} / ${video.value.videoHeight || 480}`
    message.value = '正在检测，请独自正视镜头…'
    emit('started')
    deadlineTimer = setTimeout(() => { if (active(current)) { stop(); message.value = '检测超过一分钟，已自动停止，请调整后重新开始。' } }, 60000)
    frameTimer = setTimeout(() => inspect(current), 350)
  } catch (err) {
    acquired?.getTracks().forEach(track => track.stop())
    if (current !== generation) return
    stop()
    error.value = ({ NotAllowedError: '摄像头权限未授予，请检查浏览器与 Windows 摄像头权限。', NotFoundError: '未找到可用摄像头。', NotReadableError: '摄像头被其他程序占用，请关闭占用程序后重试。' })[err.name] || '摄像头启动失败，请检查设备后重试。'
  } finally { if (current === generation) opening.value = false }
}
function hidden() { if (document.hidden && (stream.value || opening.value)) cancel() }
watch(() => props.allowed, allowed => {
  if (!allowed) { stop(); message.value = props.processing ? '检测已通过，正在自动提交。' : '已停止检测。完成授权后可重新开始。' }
})
watch(() => props.processing, (processing, previous) => {
  if (!processing && previous) message.value = '本轮检测已结束，需要重试时请重新开始。'
})
onMounted(() => document.addEventListener('visibilitychange', hidden))
onBeforeUnmount(() => { stop(); document.removeEventListener('visibilitychange', hidden) })
</script>

<style scoped>
.face-camera { margin-top: 14px; }
.face-camera__viewport { position: relative; display: flex; align-items: center; justify-content: center; width: 100%; max-width: 440px; margin: auto; background: #0f172a; border: 1px solid #dbe3ef; border-radius: 12px; overflow: hidden; }
.face-camera__viewport video { position: absolute; inset: 0; display: block; width: 100%; height: 100%; object-fit: contain; transform: scaleX(-1); }
.face-camera__viewport p { padding: 24px; color: #cbd5e1; text-align: center; line-height: 1.8; }
.face-camera__box { position: absolute; box-sizing: border-box; border: 3px solid #34d399; border-radius: 12px; pointer-events: none; }
.face-camera__badge { position: absolute; left: 12px; bottom: 12px; padding: 5px 10px; border-radius: 20px; color: white; background: #0f172acc; font-size: 12px; }
.face-camera__status { min-height: 22px; color: #334155; font-size: 13px; line-height: 1.6; text-align: center; }
.face-camera__actions { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; margin-top: 12px; gap: 10px; font-size: 13px; }
.face-camera__hint { color: #64748b; font-size: 12px; line-height: 1.7; }
</style>
