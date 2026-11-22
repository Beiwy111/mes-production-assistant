<script setup>
defineProps({ source: { type: String, required: true }, rag: Boolean, busy: Boolean })
defineEmits(['source', 'rag'])
</script>

<template>
  <div class="mode-selector">
    <label class="source">
      回答方式
      <select aria-label="回答方式" :value="source" :disabled="busy" @change="$emit('source', $event.target.value)">
        <option value="WORKFLOW">业务工作流</option>
        <option value="API">API 模型</option>
        <option value="LOCAL">本地模型</option>
      </select>
    </label>
    <label v-if="source !== 'WORKFLOW'" class="rag">
      <input type="checkbox" role="switch" :checked="rag" :disabled="busy" @change="$emit('rag', $event.target.checked)" />
      RAG 知识检索
    </label>
  </div>
</template>

<style scoped>
.mode-selector { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 18px; padding: 0 4px 8px; color: var(--aw-muted); font: 13px/1.4 var(--aw-font-sans); }
.source, .rag { display: inline-flex; align-items: center; gap: 7px; }
select { border: 1px solid var(--aw-border, #d7ddda); background: var(--aw-surface, white); color: var(--aw-text); border-radius: 6px; padding: 4px 6px; font: inherit; }
.rag { min-height: 28px; cursor: pointer; }
.rag input { appearance: none; position: relative; width: 30px; height: 17px; margin: 0; border: 1px solid var(--aw-subtle); border-radius: 12px; background: var(--aw-hover); cursor: pointer; }
.rag input::before { content: ''; position: absolute; left: 2px; top: 2px; width: 11px; height: 11px; background: var(--aw-muted); border-radius: 50%; }
.rag input:checked { border-color: var(--mes-accent); background: var(--mes-accent); }
.rag input:checked::before { left: 15px; background: white; }
.rag input:disabled, select:disabled { cursor: default; opacity: .6; }
@media (forced-colors: active) { .rag input { appearance: auto; } .rag input::before { display: none; } }
select:focus-visible, input:focus-visible { outline: 2px solid var(--mes-accent, #356751); outline-offset: 2px; }
</style>
