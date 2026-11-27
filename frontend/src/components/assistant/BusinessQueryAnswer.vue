<script setup lang="ts">
import { computed } from 'vue'
import { businessSnapshot, type BusinessMessage, type BusinessSnapshot } from '@/utils/businessPresentation'
import { evidenceValue } from '@/utils/workbenchEvidence'

const props = defineProps<{ message: BusinessMessage; snapshot?: BusinessSnapshot }>()
const snapshot = computed(() => props.snapshot ?? businessSnapshot(props.message))
const collapsible = (key: string) => key.startsWith('quality-stats-') ||
  ['inspection-alarmRows', 'inspection-rounds', 'inspection-round-items'].includes(key) ||
  (key === 'quality-daily-trend' && props.message.result?.facts?.quality?.presentationMode === 'LIST') ||
  (key === 'process-route' && snapshot.value.tables.length > 1)
const numeric = (key: string) => /quantity|qty|rate|minutes|hours/i.test(key)
const identity = (key: string) => /(^|_)id$|Id$|Pk$|Refs$|Ids$|_no$|_code$/i.test(key)
const cellText = (value: unknown) => value !== null && typeof value === 'object' && !(Array.isArray(value) && value.every(item => typeof item === 'string'))
  ? JSON.stringify(value) : evidenceValue(value)
</script>

<template>
  <section v-if="snapshot.tables.length || snapshot.unavailable" class="business-answer" aria-label="业务查询结果">
    <component :is="collapsible(table.key) ? 'details' : 'div'" v-for="table in snapshot.tables" :key="table.key" class="business-result">
      <summary v-if="collapsible(table.key)">{{ table.label }}{{ table.key === 'inspection-alarmRows' ? '（' + table.rows.length + ' 条）' : '' }}</summary>
      <h3 v-else-if="snapshot.tables.length > 1">{{ table.label }}</h3>
      <p v-if="table.partial" class="business-notice">查询结果不完整或已截断，以下仅展示本次返回的全部明细，不能据此确定完整总数。</p>
      <p v-if="table.returned !== null && table.returned !== String(table.rows.length)" class="business-note">服务端报告返回 {{ table.returned }} 条；可展示明细 {{ table.rows.length }} 条。</p>
      <div v-if="table.rows.length" class="business-table-scroll" tabindex="0" :aria-label="`${table.label}，可左右滚动查看全部字段`">
        <table>
          <caption class="sr-only">{{ table.label }}，全部 {{ table.rows.length }} 条已返回明细；空缺字段显示为未提供。</caption>
          <thead><tr><th v-for="column in table.columns" :key="column.key" scope="col" :class="{ numeric: numeric(column.key) }">{{ column.label }}</th></tr></thead>
          <tbody><tr v-for="(row, index) in table.rows" :key="index">
            <td v-for="column in table.columns" :key="column.key" :class="{ numeric: numeric(column.key), identity: identity(column.key) }">{{ cellText(row[column.key]) }}</td>
          </tr></tbody>
        </table>
      </div>
      <p v-else class="business-empty">本次未返回此类记录。</p>
      <p v-for="note in table.notes" :key="note" class="business-note">{{ note }}</p>

    </component>
    <ul v-if="snapshot.notices.length" class="business-caveats"><li v-for="notice in snapshot.notices" :key="notice">{{ notice }}</li></ul>

  </section>
</template>

<style scoped>
.business-answer { min-width:0; max-width:100%; margin:16px 0; color:var(--aw-text, #202124); font:400 14px/1.65 var(--aw-font-sans, sans-serif); }
.business-result + .business-result { margin-top:22px; }
.business-result-header { display:flex; align-items:baseline; flex-wrap:wrap; gap:8px 16px; margin-bottom:9px; }
.business-result-header h3 { margin:0; color:var(--aw-text, #202124); font-size:14px; font-weight:500; }
.business-result-header span,.business-checked { color:var(--aw-muted, #62666d); font-size:12px; }
.business-table-scroll { max-width:100%; overflow-x:auto; scrollbar-color:var(--aw-border, #e4e4e7) transparent; scrollbar-width:thin; }
.business-table-scroll:focus-visible { outline:1px solid var(--aw-muted, #62666d); outline-offset:3px; }
table { width:100%; border-collapse:separate; border-spacing:0; text-align:left; white-space:nowrap; font-size:14px; }
th,td { padding:11px 12px; border-bottom:1px solid var(--aw-border, #e4e4e7); font-weight:400; }
thead th { font-weight:500; font-size:14px; }
tbody tr:last-child td { border-bottom:0; }
h3 { font: 500 14px/1.6 var(--aw-font-sans); margin: 0 0 8px; }
.numeric { text-align:right; font-family:var(--aw-font-sans, sans-serif); font-variant-numeric:tabular-nums; }
.identity { font-family:var(--aw-font-sans, sans-serif); font-size:13px; font-variant-numeric:tabular-nums; }
.business-notes { margin-top:8px; }
.business-notes p,.business-note,.business-empty { margin:4px 0; color:var(--aw-muted, #62666d); font-size:12px; overflow-wrap:anywhere; }
.business-empty { padding:14px 0; border-top:1px solid var(--aw-border, #e4e4e7); border-bottom:1px solid var(--aw-border, #e4e4e7); }
.business-notice { margin:8px 0; padding-left:10px; border-left:2px solid var(--aw-warning, #8c6228); color:var(--aw-warning, #8c6228); font-size:12px; }
.business-caveats { margin:12px 0 0; padding-left:18px; color:var(--aw-muted, #62666d); font-size:12px; overflow-wrap:anywhere; }
.business-checked { margin:10px 0 0; }
.business-checked time { font-family:var(--aw-font-mono, monospace); }
.sr-only { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0,0,0,0); }
@media(max-width:600px) { th,td { padding:8px 10px; } .business-result-header { gap:3px 12px; } }
</style>
