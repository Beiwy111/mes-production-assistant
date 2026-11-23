import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { existsSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { workflowCatalog } from './build/workflowCatalog.js'

const localServices = {
  name: 'mes-local-services',
  configureServer(server) {
    if (process.platform !== 'win32' || process.env.CI || process.env.MES_SKIP_LOCAL_SERVICES === '1') return
    const deploy = new URL('../backend/assistant/deploy/', import.meta.url)
    const installed = existsSync(new URL('.env.workbench.local', deploy)) ||
      existsSync(new URL('../../deploy/compreface/mes-face.local.yaml', deploy))
    if (!installed) return

    server.httpServer?.once('listening', () => {
      const launcher = fileURLToPath(new URL('Start-AssistantLocal.ps1', deploy))
      const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', launcher], {
        windowsHide: true, stdio: ['ignore', 'inherit', 'inherit']
      })
      child.on('error', err => server.config.logger.warn(`本机服务启动失败：${err.message}`))
    })
  }
}

export default defineConfig({
  plugins: [vue(), workflowCatalog(), localServices],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@picture': fileURLToPath(new URL('./picture', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api/assistant-workbench': {
        target: 'http://127.0.0.1:18083',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      },
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
