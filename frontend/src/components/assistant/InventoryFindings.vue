<script setup>
import { computed, ref } from 'vue'
import { inventorySnapshot, inventoryQuantity } from '@/utils/inventoryPresentation'
const props = defineProps({ message: { type: Object, required: true } })
const snapshot = computed(() => inventorySnapshot(props.message))
const expanded = ref(false)
const rows = computed(() => snapshot.value?.rows.slice(0, expanded.value ? 100 : 6) || [])
</script>

<template>
  <section v-if="snapshot?.provisional" class="inventory-findings" aria-label="物料供应 Agent 查询发现">
    <div class="findings-heading"><span class="findings-check">✓</span><strong>物料供应 Agent · 已读取库存明细</strong><span>{{ snapshot.rows.length }} 条</span></div>
    <p class="findings-scope">{{ snapshot.period }}<template v-if="snapshot.category"> · {{ snapshot.category }}库存</template><template v-if="snapshot.keyword"> · {{ snapshot.keyword }}</template> · 等待总协调完成数据复核</p>
    <div class="finding-grid">
      <article v-for="(row, index) in rows" :key="index" class="finding-row">
        <div><strong>{{ row.material_name || '未提供物料名称' }}</strong><small>{{ row.material_code || '编码未提供' }} · {{ row.warehouse_code || '仓库未提供' }} · {{ row.batch_no || '批次未提供' }}</small></div>
        <p><span>可用</span> <b>{{ inventoryQuantity(row.quantity_available) }}</b> <span>{{ row.unit || '单位未提供' }}</span></p>
      </article>
    </div>
    <button v-if="snapshot.rows.length > 6" class="findings-more" @click="expanded = !expanded" :aria-expanded="expanded">{{ expanded ? '收起明细' : `查看其余 ${snapshot.rows.length - 6} 条已返回记录` }} <span>{{ expanded ? '↑' : '↓' }}</span></button>
    <p v-if="!rows.length" class="findings-scope">本次未返回匹配的库存记录。</p>
    <p v-if="snapshot.partial" class="findings-scope">本次返回不完整，不能代表全部库存。</p>
  </section>
</template>

<style scoped>
.inventory-findings { margin: 8px 20px 20px 48px; padding: 16px; border: 1px solid var(--aw-border, #e4e4e7); border-radius: 6px; background: var(--aw-surface, #fafafa); color: var(--aw-text, #202124); font: 14px/1.6 var(--aw-font-sans, Inter, sans-serif); }
.findings-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 13px; }
.findings-heading strong { font-weight: 500; }
.findings-heading > span:last-child { margin-left: auto; color: var(--aw-muted, #62666d); font: 11px var(--aw-font-mono, monospace); }
.findings-check { display: grid; place-items: center; width: 20px; height: 20px; border: 1px solid var(--aw-border, #e4e4e7); border-radius: 4px; background: var(--aw-panel, #f5f5f5); color: var(--aw-success, #347453); }
.findings-scope { font-size: 12px; color: var(--aw-muted, #62666d); margin: 7px 0 12px; line-height: 1.6; }
.finding-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.finding-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 10px 12px; background: var(--aw-panel, #f5f5f5); border: 1px solid var(--aw-border, #e4e4e7); border-radius: 4px; }
.finding-row > div { min-width: 0; }
.finding-row strong { font-size: 13px; font-weight: 500; overflow-wrap: anywhere; }
.finding-row small { display: block; font: 11px/1.6 var(--aw-font-mono, monospace); color: var(--aw-muted, #62666d); margin-top: 4px; overflow-wrap: anywhere; }
.finding-row p { margin: 0 0 0 auto; text-align: right; flex-shrink: 0; }
.finding-row p b { font: 500 16px var(--aw-font-mono, monospace); color: var(--aw-success, #347453); font-variant-numeric: tabular-nums; }
.finding-row p span { font-size: 11px; color: var(--aw-muted, #62666d); }
.findings-more { border: 0; background: none; color: var(--aw-text, #202124); padding: 12px 0 0; cursor: pointer; font: 12px var(--aw-font-sans, Inter, sans-serif); }
.findings-more:hover { color: var(--aw-success, #347453); }
.findings-more:focus-visible { outline: 2px solid var(--aw-muted, #62666d); outline-offset: 3px; }
.findings-more span { margin-left: 8px; }
@media (max-width: 650px) { .finding-grid { grid-template-columns: 1fr; } .inventory-findings { margin: 8px 12px 14px; padding: 12px; } .finding-row { align-items: flex-start; } }
</style>
