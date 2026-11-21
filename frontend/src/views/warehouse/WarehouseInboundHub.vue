<template>
  <div class="warehouse-hub">
    <el-tabs v-model="activeTab" class="warehouse-hub__tabs" @tab-change="syncRoute">
      <el-tab-pane label="采购入库" name="purchase" lazy>
        <PurchaseInView />
      </el-tab-pane>
      <el-tab-pane label="成品入库" name="finished" lazy>
        <InboundView />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PurchaseInView from './PurchaseInView.vue'
import InboundView from './InboundView.vue'
import { useMesStore } from '@/stores/mes'

const route = useRoute()
const router = useRouter()
const mes = useMesStore()
const activeTab = ref('purchase')

function syncRoute(name) {
  router.replace({ query: { ...route.query, tab: name } })
}

function syncFromRoute() {
  const tab = route.query.tab
  if (tab === 'finished' || tab === 'purchase') {
    activeTab.value = tab
  }
}

watch(() => route.query.tab, syncFromRoute, { immediate: true })

onMounted(async () => {
  try {
    await mes.hydrateFromApi({ force: true })
  } catch { /* ignore */ }
  // 默认进入成品入库（质检合格任务在此）；仅显式 ?tab=purchase 时留在采购入库
  if (route.query.tab === 'purchase') {
    activeTab.value = 'purchase'
  } else {
    activeTab.value = 'finished'
    if (route.query.tab !== 'finished') syncRoute('finished')
  }
})
</script>

<style scoped>
.warehouse-hub {
  padding: 0;
}

.warehouse-hub__tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.warehouse-hub__tabs :deep(.el-tabs__item) {
  font-size: 15px;
  font-weight: 600;
}
</style>
