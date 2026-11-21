<script setup>
/**
 * 通用对话输入（全角色助手对话复用）。
 * variant="hero"：欢迎页 GPT 风格圆角输入；
 * variant="slim"：对话底栏单行输入（自动增高）。
 */
import { ref, watch, onMounted, nextTick } from 'vue'
import { Microphone, Promotion } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  busy: { type: Boolean, default: false },
  recording: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入消息，回车发送…' },
  variant: { type: String, default: 'slim' }
})
const emit = defineEmits(['update:modelValue', 'send', 'mic'])

const fieldRef = ref(null)

const MIN_HERO = 24
const MAX_HERO = 200
const MAX_SLIM = 160

function resizeField() {
  const el = fieldRef.value
  if (!el) return
  el.style.height = 'auto'
  const max = props.variant === 'hero' ? MAX_HERO : MAX_SLIM
  const min = props.variant === 'hero' ? MIN_HERO : 44
  el.style.height = `${Math.min(Math.max(el.scrollHeight, min), max)}px`
}

function onInput(e) {
  emit('update:modelValue', e.target.value)
  nextTick(resizeField)
}

function onEnter(e) {
  if (e.shiftKey) return
  e.preventDefault()
  emit('send')
}

watch(() => props.modelValue, () => nextTick(resizeField))

onMounted(() => nextTick(resizeField))
</script>

<template>
  <div :class="['chat-composer', `chat-composer--${variant}`]">
    <textarea
      ref="fieldRef"
      class="chat-composer__field"
      :value="modelValue"
      :placeholder="placeholder"
      rows="1"
      @input="onInput"
      @keydown.enter="onEnter"
    />
    <div class="chat-composer__btns" :class="{ 'chat-composer__btns--inline': variant === 'slim' }">
      <button
        type="button"
        class="chat-composer__icon-btn"
        :class="{ 'is-recording': recording }"
        :disabled="busy && !recording"
        :title="recording ? '停止录音' : '语音输入'"
        @click="emit('mic')"
      >
        <el-icon><Microphone /></el-icon>
      </button>
      <button
        type="button"
        class="chat-composer__icon-btn chat-composer__icon-btn--send"
        :disabled="busy || !modelValue.trim()"
        title="发送"
        @click="emit('send')"
      >
        <el-icon><Promotion /></el-icon>
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-composer {
  width: 100%;
  font-family: Inter, "PingFang SC", "Noto Sans SC", "Microsoft YaHei", system-ui, sans-serif;
  background: #fff;
  border: 1px solid #e5e5e5;
  box-sizing: border-box;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.chat-composer:focus-within {
  border-color: #d0d0d0;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.chat-composer--hero {
  max-width: 768px;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 14px 14px 14px 18px;
  border-radius: 28px;
  box-shadow: 0 1px 8px rgba(0, 0, 0, 0.04);
}

.chat-composer--slim {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 24px;
}

.chat-composer__field {
  flex: 1;
  width: 100%;
  min-height: 24px;
  max-height: 200px;
  margin: 0;
  padding: 6px 0;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  box-shadow: none;
  font-family: inherit;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.5;
  color: #0d0d0d;
  overflow-y: auto;
}
.chat-composer--slim .chat-composer__field {
  min-height: 44px;
  max-height: 160px;
  font-size: 15px;
  line-height: 1.7;
}
.chat-composer__field::placeholder {
  color: #8e8e8e;
}

.chat-composer__btns {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
  padding-bottom: 2px;
}

.chat-composer__icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: #5d5d5d;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.chat-composer__icon-btn:hover:not(:disabled) {
  background: #f0f0f0;
  color: #0d0d0d;
}
.chat-composer__icon-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.chat-composer__icon-btn.is-recording {
  background: #fef2f2;
  color: #dc2626;
}
.chat-composer__icon-btn--send {
  background: #0d0d0d;
  color: #fff;
}
.chat-composer__icon-btn--send:hover:not(:disabled) {
  background: #2f2f2f;
  color: #fff;
}
.chat-composer__icon-btn--send:disabled {
  background: #e5e5e5;
  color: #a3a3a3;
  opacity: 1;
}
</style>
