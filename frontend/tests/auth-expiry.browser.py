"""Exercise invalid credentials against real MES/gateway, never mock auth responses."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright
out=Path('docs/ui-verification/auth-expiry');out.mkdir(parents=True,exist_ok=True)
with sync_playwright() as p:
 browser=p.chromium.launch(channel='chrome',headless=True)
 context=browser.new_context(viewport={'width':1440,'height':900})
 response=context.request.post('http://localhost:5173/api/auth/login',data={'username':'admin','password':'123456'}).json()
 token=response['data']['token']
 headers={'Authorization':'Bearer '+token}
 user=context.request.get('http://localhost:5173/api/auth/userInfo',headers=headers).json()['data']
 menus=context.request.get('http://localhost:5173/api/auth/menus',headers=headers).json()['data']
 user.update(roleKey='admin',dashboardPath='/dashboard/admin')
 # Seed only our isolated browser tab with an invalid token and real user/menu shape.
 context.add_init_script("""if(!sessionStorage.getItem('expiry-test-seeded')) {
  const seed=SEED; sessionStorage.setItem('token','expired-test-token');
  sessionStorage.setItem('userInfo',JSON.stringify(seed.user));
  sessionStorage.setItem('menus',JSON.stringify(seed.menus));sessionStorage.setItem('menuVersion','25');
  sessionStorage.setItem('expiry-test-seeded','1');localStorage.setItem('expiry-history-sentinel','keep');
 }""".replace('SEED',json.dumps({'user':user,'menus':menus})))
 page=context.new_page();page.goto('http://localhost:5173/assistant',wait_until='networkidle')
 page.screenshot(path=str(out/'expired.png'))
 page.wait_for_url('**/login?redirect=**',timeout=10000)
 assert page.evaluate("!sessionStorage.getItem('token')")
 assert page.evaluate("localStorage.getItem('expiry-history-sentinel')==='keep'")
 page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456')
 page.locator('button[type=submit]').click();page.wait_for_url('**/assistant',timeout=30000)
 page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
 page.screenshot(path=str(out/'reconnected.png'))
 # Exercise the legacy HTTP 200 + code 401 path independently of workbench fetches.
 result=page.evaluate("""async()=>{
   const {useUserStore}=await import('/src/stores/user.js');
   const {getUserInfo}=await import('/src/api/auth.js');
   const store=useUserStore();store.token='another-expired-test-token';
   try {await getUserInfo();return false;} catch {return true;}
 }""")
 assert result
 page.wait_for_url('**/login?redirect=**',timeout=10000)
 assert page.evaluate("!sessionStorage.getItem('token')")
 (out/'result.json').write_text(json.dumps({'status':'passed','fetch401':True,'business401':True,'loginReturn':True,'historyPreserved':True}),encoding='utf-8')
 browser.close();print('AUTH_EXPIRY_BROWSER_PASS')
