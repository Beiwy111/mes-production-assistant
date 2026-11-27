<script setup>
defineProps({ planning: { type: Object, required: true } })
</script>
<template>
  <section class="planning-summary" aria-label="多订单生产计划">
    <header><strong>生产计划</strong><span>库存与交期核算 · 尚未派工</span></header>
    <div class="planning-table"><table><thead><tr><th>订单 / 产品</th><th>订购</th><th>库存分配</th><th>新生产</th><th>预计周期</th><th>截止日期</th><th>结论</th></tr></thead>
      <tbody><tr v-for="row in planning.orderPlans" :key="row.orderId"><td>{{ row.orderId }}<small>{{ row.productName }}</small></td><td>{{ row.quantity }}</td><td>{{ row.stockAllocation }}</td><td>{{ row.plannedQty }}</td><td>{{ row.plannedQty ? `${row.planStart} → ${row.planEnd}` : '无需生产' }}</td><td>{{ row.deliveryDate }}</td><td>{{ row.status }}</td></tr></tbody>
    </table></div>
    <ul v-if="planning.conflicts?.length" class="planning-conflicts"><li v-for="item in planning.conflicts" :key="item">{{ item }}</li></ul>
    <details v-if="planning.materialChecks?.length"><summary>查看物料齐套依据</summary><div class="planning-table"><table><thead><tr><th>物料</th><th>共享需求</th><th>可用</th><th>缺口</th></tr></thead><tbody><tr v-for="row in planning.materialChecks" :key="row.materialId"><td>{{ row.materialName }}</td><td>{{ row.required }}</td><td>{{ row.available }}</td><td>{{ row.shortage }}</td></tr></tbody></table></div></details>
    <p>{{ planning.note }}</p>
  </section>
</template>
<style scoped>
.planning-summary { margin:12px 0 18px;font-size:13px;min-width:0; }
header { display:flex;gap:12px;align-items:center;margin-bottom:12px;flex-wrap:wrap; }
header strong { font-size:16px;font-weight:500; } header span,p,small { color:var(--aw-muted);font-size:12px; }
.planning-table { overflow-x:auto;max-width:100%; } table { width:100%;border-collapse:collapse;white-space:nowrap; }
th,td { padding:9px 10px;border-bottom:1px solid var(--aw-border);text-align:left; } th { font-weight:500; }
td:nth-child(2),td:nth-child(3),td:nth-child(4) { text-align:right; } small { display:block;margin-top:3px; }
summary { cursor:pointer;color:var(--mes-accent);margin:12px 0; } p { line-height:1.7; } .planning-conflicts { color:#a55030;line-height:1.8; }
</style>
