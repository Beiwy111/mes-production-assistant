<script setup>
import { FileText } from 'lucide-vue-next'
import { displayDate, fileType, indexState, jobStates } from './documentPresentation'
defineProps({ jobs: { type: Array, required: true }, selected: String })
defineEmits(['select'])
</script>

<template>
  <div class="table-scroll" tabindex="0" aria-label="横向查看文档字段">
    <table aria-label="入库文档">
      <thead><tr>
        <th scope="col">文件名 / 上传时间</th><th scope="col">类型</th><th scope="col">解析状态</th>
        <th scope="col">所属知识库</th><th scope="col" class="number">片段数</th>
        <th scope="col">向量化</th><th scope="col">图谱抽取</th>
      </tr></thead>
      <tbody><tr v-for="row in jobs" :key="row.id" :class="{ selected: selected === row.id }" @click="$emit('select', row.id)">
        <td><button type="button" class="file" :aria-pressed="selected === row.id" :title="row.filename" @click.stop="$emit('select', row.id)">
          <FileText :size="17"/><span><strong>{{ row.filename }}</strong><time :datetime="row.createdAt">{{ displayDate(row.createdAt) }}</time></span>
        </button></td>
        <td>{{ fileType(row) }}</td><td><span class="state" :data-state="row.status">{{ jobStates[row.status] || row.status }}</span></td>
        <td>{{ row.datasetName || '私有知识库' }}</td><td class="number">{{ row.counts?.chunks ?? '—' }}</td>
        <td>{{ indexState(row, 'embedding') }}</td><td>{{ indexState(row, 'graph') }}</td>
      </tr></tbody>
    </table>
  </div>
</template>

<style scoped>
.table-scroll { overflow-x:auto; border:1px solid var(--ks-border,#d8e0ea); border-radius:6px }
table { width:100%; min-width:690px; border-collapse:collapse; text-align:left; color:var(--ks-text,#1f2937); font-size:13px }
th,td { padding:10px 9px; white-space:nowrap; }
th { background:var(--ks-soft,#f6f8fb); color:var(--ks-secondary,#4b5563); font-size:12px; font-weight:600; border-bottom:1px solid var(--ks-border,#d8e0ea) }
td { height:46px; border-bottom:1px solid var(--ks-border,#d8e0ea) }
tr:last-child td { border-bottom:0 }
tbody tr { cursor:pointer; background:var(--ks-surface,#fff) }
tbody tr:hover { background:var(--ks-hover,#eef4ff) }
tbody tr.selected { background:var(--ks-active,#e5eeff) }
.selected td:first-child { box-shadow:inset 3px 0 var(--ks-brand,#2457e6) }
.file { display:flex; align-items:center; gap:8px; padding:0; max-width:clamp(150px,18vw,285px); border:0; color:inherit; background:transparent; text-align:left; font:inherit; cursor:pointer }
.file svg { flex:none; color:var(--ks-deep,#163b85) }
.file span { min-width:0 }
.file strong { display:block; overflow:hidden; text-overflow:ellipsis; font-size:14px; font-weight:600; color:var(--ks-strong,#111827) }
time { display:block; margin-top:3px; font-size:12px; color:var(--ks-secondary,#4b5563); font-variant-numeric:tabular-nums }
.number { text-align:right; font-variant-numeric:tabular-nums }
.state { display:inline-flex; align-items:center; gap:6px; font-weight:500 }
.state::before { content:''; width:6px; height:6px; background:#697386; border-radius:50% }
.state[data-state="completed"]::before { background:#15745c }
.state[data-state="running"]::before { background:var(--ks-brand,#2457e6) }
.state[data-state="failed"]::before { background:#b42318 }
</style>
