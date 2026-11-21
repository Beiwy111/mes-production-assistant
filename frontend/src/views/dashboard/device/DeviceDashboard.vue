<template>
  <div class="isc-page">
    <header class="isc-topbar">
      <div class="isc-topbar__left">
        <span class="isc-live" />
        <h1 class="isc-topbar__title">设备健康数字孪生</h1>
        <span class="isc-topbar__sub">健康评分 · 报警记录 · 维保分析</span>
      </div>
      <div class="isc-topbar__right">
        <span class="isc-topbar__clock">{{ clockText }}</span>
        <span class="isc-topbar__sync">数据 {{ updatedText }}</span>
        <button class="isc-topbar__btn" :disabled="loading" @click="loadAll">
          {{ loading ? '刷新中' : '刷新' }}
        </button>
      </div>
    </header>

    <main class="isc-main">
      <FactoryScene ref="factorySceneRef" />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import FactoryScene from '@/views/device/FactoryScene.vue'
import { fetchAlarmViews, fetchEquipmentHealth } from '@/api/business'

const loading = ref(false)
const factorySceneRef = ref(null)
const updatedAt = ref(null)
const now = ref(new Date())
let timer = null
let clockTimer = null

const updatedText = computed(() => {
  if (!updatedAt.value) return '—'
  const d = updatedAt.value
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})

const clockText = computed(() => {
  const d = now.value
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})


async function loadAll() {
  loading.value = true
  try {
    const [healthRes, alarmRes] = await Promise.all([
      fetchEquipmentHealth({ silent: true }),
      fetchAlarmViews({ silent: true })
    ])
    const healthList = Array.isArray(healthRes) ? healthRes : (healthRes?.data ?? [])
    const alarmList = Array.isArray(alarmRes) ? alarmRes : (alarmRes?.data ?? [])
    updatedAt.value = new Date()
    factorySceneRef.value?.syncEquipments(healthList, alarmList)
  } catch (e) {
    ElMessage.error(e?.message || '加载设备数据失败')
  } finally {
    loading.value = false
  }
}

const DEVICE_HEALTH_POLL_MS = 5000

onMounted(() => {
  loadAll()
  timer = setInterval(loadAll, DEVICE_HEALTH_POLL_MS)
  clockTimer = setInterval(() => { now.value = new Date() }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<style scoped>
.isc-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 52px);
  min-height: 640px;
  margin: -12px;
  background: #fff;
  color: #4a5568;
  font-size: 12px;
  overflow: hidden;
}

.isc-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
}

.isc-topbar__left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.isc-live {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #67c23a;
  box-shadow: 0 0 6px rgba(103, 194, 58, 0.5);
  animation: isc-pulse 2s infinite;
}

@keyframes isc-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.isc-topbar__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #001b3f;
  letter-spacing: 0.5px;
}

.isc-topbar__sub {
  font-size: 11px;
  color: #909399;
}

.isc-topbar__right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.isc-topbar__clock {
  font-family: Consolas, monospace;
  color: #606266;
  font-size: 12px;
}

.isc-topbar__sync {
  color: #909399;
  font-size: 11px;
}

.isc-topbar__btn {
  padding: 4px 14px;
  background: #fff;
  border: 1px solid #d8dee8;
  color: #606266;
  font-size: 11px;
  cursor: pointer;
}

.isc-topbar__btn:hover:not(:disabled) {
  border-color: #3b82f6;
  color: #1d4ed8;
  background: #f0f7ff;
}

.isc-main {
  flex: 1;
  min-height: 0;
  position: relative;
  background: #f5f7fa;
}
</style>
