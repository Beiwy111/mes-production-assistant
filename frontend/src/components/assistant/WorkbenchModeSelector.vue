<script setup>
const props = defineProps({ model: { type: String, required: true }, busy: Boolean })
const emit = defineEmits(['change'])
function change(event) {
  const select = event.currentTarget
  const next = select.value
  select.value = props.model
  emit('change', next)
}
</script>

<template>
  <label class="mode-selector">
    <span>业务工作流</span>
    <select aria-label="业务工作流模型" :value="model" :disabled="busy" @change="change">
      <option value="API">API模型</option>
      <option value="LOCAL">本地模型</option>
    </select>
  </label>
</template>

<style scoped>
.mode-selector { display: flex; align-items: center; width: fit-content; gap: 7px; padding: 0 4px 8px; color: var(--aw-muted); font: 13px/1.4 var(--aw-font-sans); }
select { border: 1px solid var(--aw-border, #d7ddda); background: var(--aw-surface, white); color: var(--aw-text); border-radius: 6px; padding: 4px 6px; font: inherit; }
select:disabled { cursor: default; opacity: .6; }
select:focus-visible { outline: 2px solid var(--mes-accent, #356751); outline-offset: 2px; }
</style>
