import { defineConfig } from '@playwright/test'

const port = Number(process.env.FACE_TEST_PORT || 5183)
export default defineConfig({
  testDir: './tests/face',
  outputDir: './node_modules/.cache/face-test-results',
  workers: 1,
  timeout: 30000,
  reporter: [['list'], ['json', { outputFile: './node_modules/.cache/face-test-report.json' }]],
  use: { baseURL: `http://127.0.0.1:${port}`, browserName: 'chromium', channel: 'msedge', headless: true, viewport: { width: 1280, height: 900 } },
  webServer: { command: `npm run dev -- --host 127.0.0.1 --port ${port} --strictPort`, url: `http://127.0.0.1:${port}/login`, timeout: 30000, reuseExistingServer: false }
})
