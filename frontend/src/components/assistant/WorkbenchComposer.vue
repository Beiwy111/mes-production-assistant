<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowUp, Paperclip, Square } from 'lucide-vue-next'
const props = defineProps<{ modelValue: string; busy: boolean; canSend: boolean; welcome?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; send: []; stop: []; attach: []; 'paste-files': [files: File[]] }>()
const textarea = ref<HTMLTextAreaElement | null>(null)
const composing = ref(false)
const expanded = computed(() => props.welcome || props.modelValue.length > 0)
const height = ref(props.welcome ? 104 : 54)
let observer: ResizeObserver | undefined
let lastWidth = 0
async function resize() {
  await nextTick()
  const el = textarea.value
  if (!el) return
  el.style.height = '0px'
  const content = Math.min(164, Math.max(56, el.scrollHeight))
  height.value = props.modelValue.length > 0 ? Math.min(220, content + 56) : props.welcome ? 104 : 54
  el.style.height = `${expanded.value ? height.value - 56 : 24}px`
}
watch([() => props.modelValue, () => props.busy, () => props.welcome], resize, { flush: 'post' })
onMounted(() => {
  resize()
  observer = new ResizeObserver(([entry]) => {
    if (entry.contentRect.width !== lastWidth) { lastWidth = entry.contentRect.width; resize() }
  })
  if (textarea.value) observer.observe(textarea.value)
})
onBeforeUnmount(() => observer?.disconnect())
function send() { if (!props.busy && props.canSend && !composing.value) emit('send') }
function keydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || composing.value || event.keyCode === 229) return
  event.preventDefault()
  send()
}
function paste(event: ClipboardEvent) {
  const clipboard = event.clipboardData
  if (!clipboard) return
  let files = Array.from(clipboard.files)
  if (!files.length) {
    files = Array.from(clipboard.items)
      .filter(item => item.kind === 'file')
      .map(item => item.getAsFile())
      .filter((file): file is File => file !== null)
  }
  if (!files.length) return
  event.preventDefault()
  emit('paste-files', files)
}
defineExpose({ focus: () => textarea.value?.focus() })
</script>

<template>
  <form class="workbench-composer" :class="{ expanded }" :style="{ height: `${height}px` }" @submit.prevent="send">
    <textarea ref="textarea" :value="modelValue" rows="1" maxlength="2000"
      placeholder="询问生产协作助手…" aria-label="输入问题"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      @compositionstart="composing = true" @compositionend="composing = false" @keydown="keydown" @paste="paste" />
    <button type="button" class="attachment-button" :disabled="busy" aria-label="附加图片或文本资料" @click="emit('attach')"><Paperclip :size="18" /></button>
    <button v-if="busy" class="send-button stop-button" type="button" aria-label="停止回答" @click="emit('stop')"><Square :size="12" fill="currentColor" /></button>
    <button v-else class="send-button" type="submit" aria-label="发送问题" :disabled="!canSend"><ArrowUp :size="19" /></button>
  </form>
</template>

<style scoped>
.workbench-composer { position: relative; box-sizing: border-box; min-height: 54px; max-height: 220px; border: 1px solid var(--aw-border); border-radius: var(--mes-radius-xl); box-shadow: var(--mes-shadow-composer); background: var(--aw-bg); transition: height var(--mes-motion-normal) var(--mes-ease), border-color var(--mes-motion-fast), box-shadow var(--mes-motion-fast); }
.workbench-composer:focus-within { border-color: var(--mes-accent); box-shadow: var(--mes-focus-halo), var(--mes-shadow-composer); }
.workbench-composer.expanded { border-radius: var(--mes-radius-xl); }
textarea { position: absolute; top: 14px; left: 46px; width: calc(100% - 98px); height: 24px; padding: 0; border: 0; resize: none; outline: none; background: transparent; color: var(--aw-text); font: 400 15px/24px var(--aw-font-sans); overflow-y: auto; scrollbar-width: thin; }
.expanded textarea { top: 12px; left: 16px; width: calc(100% - 32px); }
textarea::placeholder { color: var(--aw-muted); opacity: 1; }
textarea:focus-visible { outline: none !important; }
button { position: absolute; bottom: 9px; display: grid; place-items: center; width: 34px; height: 34px; padding: 0; border: 0; border-radius: 50%; cursor: pointer; }
.attachment-button { left: 8px; background: transparent; color: var(--aw-muted); }
.attachment-button:hover { background: var(--aw-hover); }
.send-button { right: 9px; color: var(--mes-on-brand); background: var(--mes-accent); transition: background .15s, transform .12s; }
.send-button:hover:not(:disabled) { background: var(--mes-accent-hover); }
.send-button:active:not(:disabled) { transform: scale(.94); }
.send-button:disabled { color: var(--mes-accent-hover); background: var(--mes-bg-selected); cursor: default; }
button:focus-visible { outline: 2px solid var(--aw-muted); outline-offset: 2px; }
@media(max-width:760px) { textarea { font-size: 16px; } }
@media(prefers-reduced-motion:reduce) { .workbench-composer, button { transition: none; } }
</style>
