<script setup>
import { computed } from 'vue'
import { inventorySnapshot, inventoryQuantity, inventoryTypeLabel } from '@/utils/inventoryPresentation'
const props = defineProps({ message: { type: Object, required: true } })
const snapshot = computed(() => props.message.result ? inventorySnapshot(props.message) : null)
const field = (value) => value == null || value === '' ? '未提供' : typeof value === 'object' ? JSON.stringify(value) : String(value)
</script>
<template>
  <section v-if="snapshot" class="inventory-answer" aria-label="库存查询结果">
    <p v-if="snapshot.category">{{ snapshot.category }}库存 · {{ snapshot.total ?? '数量未知' }} 条记录</p>
    <p v-if="snapshot.partial" class="inventory-warning">本次查询结果不完整，以下展示已返回的全部记录。</p>
    <div class="inventory-table-scroll" tabindex="0" aria-label="库存明细表，可左右滚动">
      <table><caption class="sr-only">库存按物料、仓库和批次记录展示；数量按原单位，不能跨物料相加。</caption><thead><tr><th scope="col">物料</th><th scope="col" class="numeric">现存</th><th scope="col" class="numeric">预留</th><th scope="col" class="numeric">可用</th><th scope="col">单位</th><th scope="col">仓库 / 库位</th><th scope="col">批次</th><th v-for="column in snapshot.extraColumns" :key="column.key" scope="col">{{ column.label }}</th></tr></thead>
        <tbody><tr v-for="(row, index) in snapshot.rows" :key="index"><th scope="row"><strong>{{ field(row.material_name) }}</strong><small>{{ field(row.material_code) }}<template v-if="row.material_type"> · {{ inventoryTypeLabel(row.material_type) }}</template></small></th><td class="numeric">{{ inventoryQuantity(row.quantity_on_hand) }}</td><td class="numeric reserved">{{ inventoryQuantity(row.quantity_reserved) }}</td><td class="numeric available">{{ inventoryQuantity(row.quantity_available) }}</td><td>{{ field(row.unit) }}</td><td>{{ field(row.warehouse_code) }}<small>{{ field(row.location_code) }}</small></td><td class="batch">{{ field(row.batch_no) }}</td><td v-for="column in snapshot.extraColumns" :key="column.key">{{ field(row[column.key]) }}</td></tr></tbody>
      </table>
      <p v-if="!snapshot.rows.length" class="inventory-empty">本次查询未返回库存记录。</p>
    </div>
  </section>
</template>
<style scoped>
.inventory-answer { margin: 12px 0 18px; min-width: 0; color: var(--aw-text); font: 14px/1.65 var(--aw-font-sans); }
.inventory-table-scroll { max-width: 100%; overflow-x: auto; scrollbar-width: thin; }
table { border-collapse: collapse; width: 100%; font-size: 14px; text-align: left; white-space: nowrap; }
th, td { padding: 11px 12px; border-bottom: 1px solid var(--aw-border); font-weight: 400; }
thead th { font-weight: 500; }
tbody th strong { font-weight: 500; }
th small, td small { display: block; color: var(--aw-muted); font-size: 12px; }
.numeric { text-align: right; font-variant-numeric: tabular-nums; }
.batch { font: 13px var(--aw-font-sans); }
.inventory-warning, .inventory-empty { margin: 8px 0; }
.inventory-warning { color: var(--aw-warning); }
.inventory-table-scroll:focus-visible { outline: 1px solid var(--aw-muted); outline-offset: 2px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); }
</style>
