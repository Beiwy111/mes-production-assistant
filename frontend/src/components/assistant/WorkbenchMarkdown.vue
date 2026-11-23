<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { renderWorkbenchMarkdown } from '@/utils/workbenchMarkdown'
import { createTextStreamBuffer } from '@/utils/textStreamBuffer.js'

const props = withDefaults(defineProps<{ content?: string; streaming?: boolean; smooth?: boolean; stopped?: boolean; cursor?: boolean }>(),
  { content: '', streaming: false, smooth: false, stopped: false, cursor: true })
const emit = defineEmits<{ progress: [] }>()
const reduced = ref(typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
const hidden = ref(typeof document !== 'undefined' && document.hidden)
const displayedText = ref(props.streaming && props.smooth && !reduced.value ? '' : props.content)
const pending = ref(false)
const buffer = createTextStreamBuffer({
  request: (fn: FrameRequestCallback) => requestAnimationFrame(fn), cancel: (id: number) => cancelAnimationFrame(id),
  onChange: (text: string, active: boolean) => { if (text !== displayedText.value) { displayedText.value = text; emit('progress') }; pending.value = active },
})
watch(() => [props.content, props.streaming, props.smooth, props.stopped, reduced.value, hidden.value], () => {
  buffer.update(props.content, { live: props.streaming && props.smooth, stopped: props.stopped,
    reduced: reduced.value || hidden.value || !props.smooth })
}, { immediate: true })
const media = typeof window !== 'undefined' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null
const motionChanged = () => { reduced.value = Boolean(media?.matches) }
const visibilityChanged = () => { hidden.value = document.hidden }
onMounted(() => { media?.addEventListener('change', motionChanged); document.addEventListener('visibilitychange', visibilityChanged) })
onBeforeUnmount(() => { buffer.dispose(); media?.removeEventListener('change', motionChanged); document.removeEventListener('visibilitychange', visibilityChanged) })
const html = computed(() => renderWorkbenchMarkdown(displayedText.value))
async function copyCode(event: MouseEvent) {
  const button = (event.target as HTMLElement)?.closest('button.copy-code')
  const code = button?.closest('figure')?.querySelector('pre code')
  if (!button || !code) return
  try {
    await navigator.clipboard.writeText(code.textContent || '')
    button.textContent = '已复制'
  } catch { button.textContent = '复制失败，请选择代码复制' }
  setTimeout(() => { if (button.isConnected) button.textContent = '复制代码' }, 1800)
}
const showCursor = computed(() => props.smooth && props.cursor && pending.value && !props.stopped && !reduced.value)
</script>

<template>
  <div class="workbench-markdown" :class="{ 'is-streaming': showCursor }" :data-streaming="showCursor" @click="copyCode" v-html="html"></div>
</template>

<style scoped>
.workbench-markdown { min-width:0; max-width:100%; color:var(--aw-text, #202124); font:400 var(--aw-text-size, 15px)/1.85 var(--aw-font-sans, sans-serif); overflow-wrap:anywhere; }
.workbench-markdown :deep(> :first-child) { margin-top:0; }
.workbench-markdown :deep(> :last-child) { margin-bottom:0; }
.workbench-markdown.is-streaming :deep(> :last-child:not(ul):not(ol):not(pre):not(table))::after,
.workbench-markdown.is-streaming :deep(> :last-child:is(ul,ol) > li:last-child)::after,
.workbench-markdown.is-streaming :deep(> pre:last-child > code)::after { content:''; display:inline-block; width:3px; height:.96em; margin-left:4px; border-radius:2px; background:var(--mes-accent,currentColor); vertical-align:-2px; animation:answer-caret 1.15s ease-in-out infinite; }
@keyframes answer-caret { 0%,100% { opacity:1; } 50% { opacity:.2; } }
@media (prefers-reduced-motion:reduce) { .workbench-markdown::after,.workbench-markdown :deep(*)::after { animation:none !important; } }
.workbench-markdown { line-height:1.7; }
.workbench-markdown :deep(.code-block) { margin:14px 0; border:1px solid var(--aw-border); border-radius:var(--mes-radius-md); overflow:hidden; }
.workbench-markdown :deep(.code-block figcaption) { display:flex; justify-content:space-between; align-items:center; padding:7px 12px; background:var(--aw-panel); border-bottom:1px solid var(--aw-border); font:500 12px var(--aw-font-mono); color:var(--aw-muted); }
.workbench-markdown :deep(.code-block button) { background:transparent; border:none; color:var(--mes-accent); cursor:pointer; font:400 12px var(--aw-font-sans); }
.workbench-markdown :deep(.code-block pre) { margin:0; border:0; }
.workbench-markdown :deep(p) { margin:0 0 12px; }
.workbench-markdown :deep(h1),.workbench-markdown :deep(h2),.workbench-markdown :deep(h3),.workbench-markdown :deep(h4),.workbench-markdown :deep(h5),.workbench-markdown :deep(h6) { margin:22px 0 10px; color:var(--aw-text, #202124); font-weight:500; line-height:1.5; }
.workbench-markdown :deep(h1) { font-size:20px; }
.workbench-markdown :deep(h2) { font-size:18px; }
.workbench-markdown :deep(h3) { font-size:16px; }
.workbench-markdown :deep(h4),.workbench-markdown :deep(h5),.workbench-markdown :deep(h6) { font-size:14px; }
.workbench-markdown :deep(strong) { font-weight:500; }
.workbench-markdown :deep(ul),.workbench-markdown :deep(ol) { padding-left:23px; margin:10px 0 14px; }
.workbench-markdown :deep(li) { margin:4px 0; }
.workbench-markdown :deep(li p) { margin:4px 0; }
.workbench-markdown :deep(a) { color:var(--aw-link, #315e9e); text-decoration:underline; text-underline-offset:3px; }
.workbench-markdown :deep(a:focus-visible) { outline:1px solid var(--aw-muted, #62666d); outline-offset:3px; }
.workbench-markdown :deep(code) { font:400 12px/1.65 var(--aw-font-mono, monospace); background:var(--aw-panel, #f5f5f5); padding:2px 4px; }
.workbench-markdown :deep(pre) { max-width:100%; max-height:460px; margin:14px 0; padding:14px 16px; overflow:auto; background:var(--aw-surface, #fafafa); border:1px solid var(--aw-border, #e4e4e7); scrollbar-width:thin; }
.workbench-markdown :deep(pre code) { padding:0; background:transparent; white-space:pre; overflow-wrap:normal; }
.workbench-markdown :deep(blockquote) { margin:14px 0; padding:0 0 0 14px; border-left:2px solid var(--aw-border, #e4e4e7); color:var(--aw-muted, #62666d); }
.workbench-markdown :deep(hr) { margin:20px 0; border:0; border-top:1px solid var(--aw-border, #e4e4e7); }
.workbench-markdown :deep(table) { display:block; max-width:100%; max-height:420px; overflow:auto; margin:14px 0; border-collapse:collapse; font-size:13px; scrollbar-width:thin; }
.workbench-markdown :deep(th),.workbench-markdown :deep(td) { padding:8px 12px; border:1px solid var(--aw-border, #e4e4e7); text-align:left; white-space:nowrap; font-weight:400; }
.workbench-markdown :deep(th) { background:var(--aw-panel, #f5f5f5); color:var(--aw-muted, #62666d); font-size:13px; }
</style>
