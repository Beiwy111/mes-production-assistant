<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  initial: String, imageRef: String, sessionId: String, token: String, count: Number,
})
const restored = ref('')
const unavailable = ref(false)
const caption = computed(() => props.count === 0 ? '检测画面 · 未检出划痕框'
  : props.count > 0 ? `YOLO 标注 · ${props.count} 处划痕候选` : 'YOLO 检测画面')

watch(() => [props.initial, props.imageRef, props.sessionId, props.token], async ([initial, id, session, token], _, cleanup) => {
  restored.value = ''
  unavailable.value = false
  if (initial || !id || !session || !token) return
  const controller = new AbortController()
  let url = ''
  cleanup(() => { controller.abort(); if (url) URL.revokeObjectURL(url) })
  try {
    const response = await fetch(`/api/assistant-v2/images/${encodeURIComponent(id)}/frame?sessionId=${encodeURIComponent(session)}`, {
      headers: { Authorization: 'Bearer ' + token }, cache: 'no-store', signal: controller.signal,
    })
    if (!response.ok || !response.headers.get('Content-Type')?.startsWith('image/jpeg')) throw new Error()
    const blob = await response.blob()
    if (!controller.signal.aborted) { url = URL.createObjectURL(blob); restored.value = url }
  } catch { if (!controller.signal.aborted) unavailable.value = true }
}, { immediate: true })
</script>

<template>
  <figure v-if="initial || restored" class="detection-frame">
    <img :src="initial || restored" :alt="caption" />
    <figcaption>{{ caption }}</figcaption>
  </figure>
  <p v-else-if="unavailable" class="frame-note">检测图片暂不可用，可重新上传原图。</p>
</template>

<style scoped>
.detection-frame { margin: 12px 0; }
.detection-frame img { display: block; max-width: 100%; max-height: 420px; width: auto; border-radius: 6px; }
.detection-frame figcaption, .frame-note { margin-top: 5px; color: var(--aw-muted); font-size: 12px; }
</style>
