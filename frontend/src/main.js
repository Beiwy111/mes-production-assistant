import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessage } from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles/typography.css'
import './styles/theme.css'
import { registerMesChartTheme } from './styles/chartTheme'
import './styles/index.css'
import './styles/home.css'
import './styles/mes.css'
import './styles/ruoyi.css'
import { MES_LIVE_MODE } from '@/config/mes'
import { BRAND_LOGO } from '@/constants/brand'
import { useUserStore } from '@/stores/user'
import { configureSessionExpiry } from '@/utils/sessionExpiry'

const favicon = document.querySelector('link[rel="icon"]')
if (favicon) {
  favicon.type = 'image/png'
  favicon.href = BRAND_LOGO
}

const app = createApp(App)
const pinia = createPinia()

if (!MES_LIVE_MODE) {
  pinia.use(({ store }) => {
    if (store.$id !== 'mes') return
    store.$subscribe((_mutation, state) => {
      const { selectedId, ...data } = state
      localStorage.setItem('mes-store-data', JSON.stringify(data))
    })
  })
}

app.use(pinia)
configureSessionExpiry({
  getToken: () => useUserStore().token,
  clearSession: () => useUserStore().logout({ revoke: false }),
  onExpired: () => {
    const route = router.currentRoute.value
    if (route.path !== '/login') {
      router.replace({ path: '/login', query: { redirect: route.fullPath } }).catch(() => {})
    }
    ElMessage.warning('登录已失效，请重新登录。')
  }
})
app.use(router)
app.use(ElementPlus)
registerMesChartTheme()
app.mount('#app')
