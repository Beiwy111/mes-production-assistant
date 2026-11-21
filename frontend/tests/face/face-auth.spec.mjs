import { test, expect } from '@playwright/test'

// UI contract tests only. No webcam or real biometric is accessed; all /api requests are intercepted.
const session = { userId: 901, username: 'fiction_ui_admin', realName: '虚构测试管理员', roleId: 1, roleCode: 'ADMIN', roleName: '管理员' }
const capabilities = { enabled: true, configured: true, loginAllowed: true, demoOnly: true, livenessAvailable: false, consentVersion: 'face-demo-2026-11-v3', notice: '合成测试环境：仅处理程序生成的非人脸图案，不能作为真实识别验收。' }
const readyFrame = { ready: true, message: '清晰人脸', box: { x: 0.25, y: 0.2, width: 0.4, height: 0.5 } }
const loginConsent = '我已阅读说明，同意连续检测并自动验证本次人脸'
const enrollConsent = '我已阅读说明，同意自动采集并在确认后录入或更新本人人脸'
const confirmBinding = '我确认框选的人脸是本人，并绑定到以上账号'

test.beforeEach(async ({ page }) => {
  // Existing optional web fonts must not make local auth checks depend on Internet availability.
  await page.route(url => url.protocol === 'https:', route => route.abort())
})

async function mockApi(page, options = {}) {
  const calls = []
  calls.maxPreviewConcurrency = 0
  let previewCount = 0, pendingPreviews = 0
  let status = { enrolled: true, state: 'ACTIVE', bindingRevision: 4, updatedAt: '2026-11-14T10:00:00', pendingCleanup: 0, demoOnly: true, ...options.status }
  await page.route(url => url.hostname === '127.0.0.1' && url.pathname.startsWith('/api/'), async route => {
    const request = route.request(), path = new URL(request.url()).pathname.replace(/^\/api/, '')
    let body = null
    try { body = request.postDataJSON() } catch {}
    calls.push({ path, method: request.method(), body })
    let data = {}
    if (path === '/auth/face/capabilities') data = { ...capabilities, ...options.capabilities }
    else if (path === '/auth/face/preview') {
      const frames = options.previewResults || [readyFrame]
      data = frames[Math.min(previewCount++, frames.length - 1)]
      pendingPreviews++; calls.maxPreviewConcurrency = Math.max(calls.maxPreviewConcurrency, pendingPreviews)
      if (options.previewDelayMs) await new Promise(resolve => setTimeout(resolve, options.previewDelayMs))
      pendingPreviews--
    }
    else if (path === '/auth/face/challenge') data = { challengeId: 'fictional-one-time-challenge' }
    else if (path === '/auth/face/me/test') data = { similarity: 0.11, threshold: 0.85, matched: false, bindingRevision: 4, demoOnly: true }
    else if (path === '/auth/face/login' || path === '/auth/login') data = { token: 'fictional-ui-session-token', demoOnly: true }
    else if (path === '/auth/userInfo') data = session
    else if (path === '/auth/menus') data = []
    else if (/^\/auth\/face\/(me|users\/\d+)$/.test(path)) {
      if (request.method() === 'DELETE') status = { enrolled: false, state: 'NONE', pendingCleanup: 1, demoOnly: true }
      if (request.method() === 'PUT') status = { ...status, enrolled: true, state: 'ACTIVE', bindingRevision: status.bindingRevision + 1 }
      data = status
    } else if (path === '/system/user/list') data = [{ userId: 902, username: 'fiction_ui_bob', realName: '虚构测试用户', roleId: 2, status: 1 }]
    else if (path === '/system/role/list') data = [{ roleId: 1, roleCode: 'ADMIN', roleName: '管理员', status: 1 }, { roleId: 2, roleCode: 'OPERATOR', roleName: '操作员', status: 1 }]
    else if (/list|views|notices|menus|notifications/.test(path)) data = []
    const code = path === '/auth/face/login' ? (options.faceLoginCode || 200) : path === '/auth/face/preview' ? (options.previewCode || 200) : 200
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code, message: code === 200 ? 'TEST DOUBLE' : '人脸验证未通过', data: code === 200 ? data : null }) })
  })
  return calls
}
async function signedIn(page) {
  await page.addInitScript(value => {
    sessionStorage.setItem('token', 'fictional-ui-session-token')
    sessionStorage.setItem('userInfo', JSON.stringify({ ...value, roleKey: 'admin', dashboardPath: '/dashboard/admin' }))
    sessionStorage.setItem('menus', '[]'); sessionStorage.setItem('menuVersion', '25')
  }, session)
}
async function syntheticCamera(page, denied = false, permissionDelay = 0) {
  await page.addInitScript(({ deny, delay }) => {
    window.__faceTracksStopped = 0
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', { configurable: true, value: async () => {
      if (deny) throw new DOMException('Synthetic permission denial', 'NotAllowedError')
      if (delay) await new Promise(resolve => setTimeout(resolve, delay))
      const canvas = document.createElement('canvas'); canvas.width = 640; canvas.height = 480
      const context = canvas.getContext('2d')
      const draw = () => {
        context.fillStyle = '#768a9d'; context.fillRect(0, 0, 640, 480)
        context.fillStyle = '#e2e8f0'; context.font = '24px sans-serif'; context.fillText('SYNTHETIC TEST - NOT A FACE', 115, 240)
      }
      draw()
      const stream = canvas.captureStream(10), timer = setInterval(draw, 80)
      for (const track of stream.getTracks()) { const original = track.stop.bind(track); track.stop = () => { window.__faceTracksStopped++; clearInterval(timer); original() } }
      return stream
    } })
  }, { deny: denied, delay: permissionDelay })
}

async function openLoginCamera(page) {
  await page.getByRole('button', { name: '人脸登录 · 演示', exact: true }).click()
  await page.getByPlaceholder('先输入已录入人脸的账号').fill(session.username)
  await page.getByText(loginConsent, { exact: true }).click()
  await page.getByRole('button', { name: '开始人脸登录', exact: true }).click()
}

test('unconfigured face feature keeps password login usable', async ({ page }) => {
  const calls = await mockApi(page, { capabilities: { configured: false, loginAllowed: false } })
  await page.goto('/login?redirect=%2Fassistant', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '人脸登录 · 演示', exact: true }).click()
  await expect(page.getByText('当前尚未开放人脸演示登录', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始人脸登录' })).toBeDisabled()
  await page.getByRole('button', { name: '返回密码登录' }).click()
  await page.getByPlaceholder('用户名', { exact: true }).fill(session.username)
  await page.getByPlaceholder('密码', { exact: true }).fill('fictional-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/assistant$/)
  expect(calls.some(call => call.path === '/auth/login')).toBeTruthy()
})

test('camera permission failure is explained without breaking password login', async ({ page }) => {
  await mockApi(page); await syntheticCamera(page, true); await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '人脸登录 · 演示', exact: true }).click()
  await page.getByPlaceholder('先输入已录入人脸的账号').fill(session.username)
  await page.getByText(loginConsent, { exact: true }).click()
  await page.getByRole('button', { name: '开始人脸登录' }).click()
  await expect(page.getByText('摄像头权限未授予', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始人脸登录' })).toBeEnabled()
})

test('two stable frames automatically log in once and release the camera', async ({ page }) => {
  const calls = await mockApi(page); await syntheticCamera(page); await page.goto('/login?redirect=%2Fassistant', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '人脸登录 · 演示', exact: true }).click()
  await page.getByPlaceholder('先输入已录入人脸的账号').fill(session.username)
  await expect(page.getByRole('button', { name: '开始人脸登录' })).toBeDisabled()
  await page.getByText(loginConsent, { exact: true }).click(); await page.getByRole('button', { name: '开始人脸登录' }).click()
  await expect(page.getByRole('button', { name: '拍摄', exact: true })).toHaveCount(0)
  await expect(page).toHaveURL(/\/assistant$/)
  const request = calls.find(call => call.path === '/auth/face/login')
  expect(request.body.username).toBe(session.username)
  expect(request.body.consent).toBe(true)
  expect(request.body.imageBase64).toMatch(/^data:image\/jpeg;base64,/)
  expect(request.body.challengeId).toBe('fictional-one-time-challenge')
  expect(await page.evaluate(() => sessionStorage.getItem('token'))).toBe('fictional-ui-session-token')
  expect(calls.filter(call => call.path === '/auth/face/preview')).toHaveLength(2)
  expect(calls.filter(call => call.path === '/auth/face/login')).toHaveLength(1)
  expect(await page.evaluate(() => window.__faceTracksStopped)).toBeGreaterThan(0)
})

test('self deletion reports pending cleanup and clears the password field', async ({ page }) => {
  const calls = await mockApi(page); await signedIn(page); await page.goto('/system/user', { waitUntil: 'domcontentloaded' })
  await page.locator('.header-user__trigger').click(); await page.getByText('我的人脸信息', { exact: true }).click()
  await expect(page.getByText('已录入', { exact: true })).toBeVisible()
  await page.getByPlaceholder('不会保存该密码').fill('fictional-password')
  await page.getByRole('button', { name: '删除人脸绑定', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByText('本地绑定已撤销，远端数据待清理。', { exact: false })).toBeVisible()
  await expect(page.getByPlaceholder('不会保存该密码')).toHaveValue('')
  expect(calls.some(call => call.path === '/auth/face/me' && call.method === 'DELETE')).toBeTruthy()
})

test('administrator manages the selected existing user without a second account system', async ({ page }) => {
  const calls = await mockApi(page); await signedIn(page); await page.goto('/system/user', { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: '人脸', exact: true }).click()
  await expect(page.getByText('管理员可查看状态、撤销绑定和重试清理。', { exact: false })).toBeVisible()
  await expect(page.locator('.face-camera')).toHaveCount(0)
  await page.getByPlaceholder('不会保存该密码').fill('fictional-admin-password')
  await page.getByRole('button', { name: '删除人脸绑定', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByText('本地绑定已撤销，远端数据待清理。', { exact: false })).toBeVisible()
  expect(calls.some(call => call.path === '/auth/face/users/902' && call.method === 'DELETE')).toBeTruthy()
})

test('self update requires confirmation of the exact captured frame and target account', async ({ page }) => {
  const calls = await mockApi(page); await signedIn(page); await syntheticCamera(page)
  await page.goto('/system/user', { waitUntil: 'domcontentloaded' })
  await page.locator('.header-user__trigger').click(); await page.getByText('我的人脸信息', { exact: true }).click()
  await expect(page.getByRole('radio', { name: '匹配测试', exact: true })).toBeChecked()
  await page.getByText('更新人脸', { exact: true }).click()
  await expect(page.getByRole('button', { name: '开始自动采集' })).toBeDisabled()
  await page.getByPlaceholder('不会保存该密码').fill('fictional-password')
  await page.getByText(enrollConsent, { exact: true }).click()
  await page.getByRole('button', { name: '开始自动采集' }).click()
  const preview = page.getByAltText('本次准备绑定的实际采集画面，仅保存在页面内存中')
  await expect(preview).toBeVisible()
  await expect(page.getByText(`即将绑定到账号：${session.username}`, { exact: true })).toBeVisible()
  const confirmedFrame = await preview.getAttribute('src')
  if (process.env.FACE_BINDING_SCREENSHOT) await page.locator('.face-binding-confirm').screenshot({ path: 'node_modules/.cache/face-binding-confirm-preview.png', animations: 'disabled', timeout: 8000 })
  expect(calls.filter(call => call.path === '/auth/face/me' && call.method === 'PUT')).toHaveLength(0)
  await expect(page.getByRole('button', { name: '确认绑定到此账号' })).toBeDisabled()
  await page.getByText(confirmBinding, { exact: true }).click()
  await page.getByRole('button', { name: '确认绑定到此账号' }).click()
  await expect(page.getByText('本人人脸已绑定，可在登录页使用演示人脸验证。', { exact: true })).toBeVisible()
  await expect(page.getByPlaceholder('不会保存该密码')).toHaveValue('')
  const request = calls.find(call => call.path === '/auth/face/me' && call.method === 'PUT')
  expect(request.body.consent).toBe(true); expect(request.body.password).toBe('fictional-password')
  expect(request.body.imageBase64).toMatch(/^data:image\/jpeg;base64,/)
  expect(request.body.imageBase64).toBe(confirmedFrame)
  await expect(preview).toHaveCount(0)
  await expect(page.getByRole('button', { name: '开始自动采集' })).toBeDisabled()
  expect(calls.filter(call => call.path === '/auth/face/me' && call.method === 'PUT')).toHaveLength(1)
})

test('closing the capture dialog releases an active synthetic camera', async ({ page }) => {
  await mockApi(page, { previewResults: [{ ready: false, message: '未检测到人脸' }] }); await syntheticCamera(page); await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await openLoginCamera(page)
  await expect.poll(() => page.locator('.face-camera video').evaluate(video => video.videoWidth)).toBeGreaterThan(0)
  await page.getByRole('button', { name: '返回密码登录' }).click()
  await expect.poll(() => page.evaluate(() => window.__faceTracksStopped)).toBeGreaterThan(0)
})

test('a rejected face pauses without automatic login retries or a browser session', async ({ page }) => {
  const calls = await mockApi(page, { faceLoginCode: 401 }); await syntheticCamera(page)
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await openLoginCamera(page)
  await expect(page.getByText('人脸验证未通过', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.getItem('token'))).toBeNull()
  await expect(page).toHaveURL(/\/login$/)
  await page.waitForTimeout(1400)
  expect(calls.filter(call => call.path === '/auth/face/login')).toHaveLength(1)
  expect(calls.filter(call => call.path === '/auth/face/preview')).toHaveLength(2)
})

test('a face login revokes its issued token if MES cannot load the user profile', async ({ page }) => {
  const calls = await mockApi(page)
  await page.route('**/api/auth/userInfo', route => route.fulfill({
    json: { code: 503, message: '虚构测试用户资料暂不可用', data: null }
  }))
  await syntheticCamera(page)
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  await openLoginCamera(page)
  await expect.poll(() => calls.filter(call => call.path === '/auth/logout').length).toBe(1)
  await expect(page).toHaveURL(/\/login$/)
  expect(await page.evaluate(() => sessionStorage.getItem('token'))).toBeNull()
})

test('no face and moving faces reset stability before automatic login', async ({ page }) => {
  const moved = { ...readyFrame, box: { ...readyFrame.box, x: 0.55 } }
  const calls = await mockApi(page, { previewResults: [readyFrame, { ready: false, message: '画面中只能有一张人脸' }, readyFrame, moved, moved] })
  await syntheticCamera(page); await page.goto('/login?redirect=%2Fassistant', { waitUntil: 'domcontentloaded' }); await openLoginCamera(page)
  await expect(page.getByText('画面中只能有一张人脸', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.path === '/auth/face/login')).toHaveLength(0)
  await expect(page).toHaveURL(/\/assistant$/, { timeout: 12000 })
  expect(calls.filter(call => call.path === '/auth/face/preview')).toHaveLength(5)
  expect(calls.filter(call => call.path === '/auth/face/login')).toHaveLength(1)
})

test('slow preview requests remain serial', async ({ page }) => {
  const calls = await mockApi(page, { previewDelayMs: 1300 })
  await syntheticCamera(page); await page.goto('/login?redirect=%2Fassistant', { waitUntil: 'domcontentloaded' }); await openLoginCamera(page)
  await expect(page).toHaveURL(/\/assistant$/, { timeout: 12000 })
  expect(calls.maxPreviewConcurrency).toBe(1)
  expect(calls.filter(call => call.path === '/auth/face/login')).toHaveLength(1)
})

test('revoking consent cancels in-flight detection and prevents late submission', async ({ page }) => {
  const calls = await mockApi(page, { previewDelayMs: 1800 })
  await syntheticCamera(page); await page.goto('/login', { waitUntil: 'domcontentloaded' }); await openLoginCamera(page)
  await expect.poll(() => calls.filter(call => call.path === '/auth/face/preview').length).toBe(1)
  await page.getByText(loginConsent, { exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.__faceTracksStopped)).toBeGreaterThan(0)
  await page.waitForTimeout(2300)
  expect(calls.filter(call => call.path === '/auth/face/preview')).toHaveLength(1)
  expect(calls.filter(call => call.path === '/auth/face/login')).toHaveLength(0)
  await expect(page.getByRole('button', { name: '开始人脸登录' })).toBeDisabled()
})

test('closing while camera permission is pending releases the eventual stream', async ({ page }) => {
  const calls = await mockApi(page)
  await syntheticCamera(page, false, 1000); await page.goto('/login', { waitUntil: 'domcontentloaded' }); await openLoginCamera(page)
  await expect(page.getByText('正在请求摄像头权限…', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '返回密码登录' }).click()
  await expect.poll(() => page.evaluate(() => window.__faceTracksStopped)).toBeGreaterThan(0)
  expect(calls.filter(call => call.path === '/auth/face/preview')).toHaveLength(0)
})

test('first enrollment captures automatically but waits for explicit binding confirmation', async ({ page }) => {
  const calls = await mockApi(page, { status: { enrolled: false, state: 'NONE', bindingRevision: 0 } })
  await signedIn(page); await syntheticCamera(page); await page.goto('/system/user', { waitUntil: 'domcontentloaded' })
  await page.locator('.header-user__trigger').click(); await page.getByText('我的人脸信息', { exact: true }).click()
  await page.getByText(enrollConsent, { exact: true }).click()
  await expect(page.getByRole('button', { name: '开始自动采集' })).toBeDisabled()
  await page.getByPlaceholder('不会保存该密码').fill('fictional-password')
  await page.getByRole('button', { name: '开始自动采集' }).click()
  await expect(page.getByAltText('本次准备绑定的实际采集画面，仅保存在页面内存中')).toBeVisible()
  expect(calls.filter(call => call.path === '/auth/face/me' && call.method === 'PUT')).toHaveLength(0)
  await page.getByText(confirmBinding, { exact: true }).click()
  await page.getByRole('button', { name: '确认绑定到此账号' }).click()
  await expect(page.getByText('本人人脸已绑定，可在登录页使用演示人脸验证。', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.path === '/auth/face/me' && call.method === 'PUT')).toHaveLength(1)
  await expect(page.getByPlaceholder('不会保存该密码')).toHaveValue('')
})

test('preview rate limits stop the camera without trying to authenticate', async ({ page }) => {
  const calls = await mockApi(page, { previewCode: 429 })
  await syntheticCamera(page); await page.goto('/login', { waitUntil: 'domcontentloaded' }); await openLoginCamera(page)
  await expect(page.getByText('检测已暂停。', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => window.__faceTracksStopped)).toBeGreaterThan(0)
  await page.waitForTimeout(1200)
  expect(calls.filter(call => call.path === '/auth/face/preview')).toHaveLength(1)
  expect(calls.filter(call => call.path === '/auth/face/login')).toHaveLength(0)
})

test('authenticated match test shows the score without replacing the binding or logging in', async ({ page }) => {
  const calls = await mockApi(page)
  await signedIn(page); await syntheticCamera(page); await page.goto('/system/user', { waitUntil: 'domcontentloaded' })
  await page.locator('.header-user__trigger').click(); await page.getByText('我的人脸信息', { exact: true }).click()
  await expect(page.getByRole('radio', { name: '匹配测试', exact: true })).toBeChecked()
  await expect(page.getByRole('button', { name: '开始匹配测试' })).toBeDisabled()
  await page.getByPlaceholder('不会保存该密码').fill('fictional-password')
  await page.getByText('我已取得本次参与者授权，同意连续检测并进行匹配测试', { exact: true }).click()
  await page.getByRole('button', { name: '开始匹配测试' }).click()
  await expect(page.getByText('0.11000', { exact: true })).toBeVisible()
  await expect(page.getByText('0.85000', { exact: true })).toBeVisible()
  await expect(page.getByText('拒绝', { exact: true })).toBeVisible()
  expect(calls.filter(call => call.path === '/auth/face/me/test')).toHaveLength(1)
  expect(calls.filter(call => call.path === '/auth/face/me' && call.method === 'PUT')).toHaveLength(0)
  expect(calls.filter(call => call.path === '/auth/face/login')).toHaveLength(0)
  await expect(page.getByPlaceholder('不会保存该密码')).toHaveValue('')
  const testFrame = page.getByAltText('本次匹配测试实际发送的画面，仅保存在页面内存中')
  await expect(testFrame).toBeVisible()
  expect(await testFrame.getAttribute('src')).toBe(calls.find(call => call.path === '/auth/face/me/test').body.imageBase64)
  await page.getByText('我已取得本次参与者授权，同意连续检测并进行匹配测试', { exact: true }).click()
  await expect(testFrame).toHaveCount(0)
})

test('discarding a captured enrollment frame or switching mode never changes the binding', async ({ page }) => {
  const calls = await mockApi(page)
  await signedIn(page); await syntheticCamera(page); await page.goto('/system/user', { waitUntil: 'domcontentloaded' })
  await page.locator('.header-user__trigger').click(); await page.getByText('我的人脸信息', { exact: true }).click()
  await page.getByText('更新人脸', { exact: true }).click()
  await page.getByPlaceholder('不会保存该密码').fill('fictional-password')
  await page.getByText(enrollConsent, { exact: true }).click()
  await page.getByRole('button', { name: '开始自动采集' }).click()
  const frame = page.getByAltText('本次准备绑定的实际采集画面，仅保存在页面内存中')
  await expect(frame).toBeVisible()
  await page.getByRole('button', { name: '重新采集', exact: true }).click()
  await expect(frame).toHaveCount(0)
  await page.getByRole('button', { name: '开始自动采集' }).click()
  await expect(frame).toBeVisible()
  await page.getByText('匹配测试', { exact: true }).click()
  await expect(frame).toHaveCount(0)
  await expect(page.getByPlaceholder('不会保存该密码')).toHaveValue('')
  expect(calls.filter(call => call.path === '/auth/face/me' && call.method === 'PUT')).toHaveLength(0)
})
