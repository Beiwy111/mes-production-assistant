"""Real Windows Chrome typography audit; no mocked business responses."""
import json, sys
from pathlib import Path
from playwright.sync_api import sync_playwright

out=Path('docs/ui-verification/typography');out.mkdir(parents=True,exist_ok=True)
mode=sys.argv[1] if len(sys.argv)>1 else 'before'
report={'mode':mode,'errors':[],'consoleErrors':[],'fontsRequested':[],'samples':{}}
with sync_playwright() as p:
 browser=p.chromium.launch(channel='chrome',headless=True)
 context=browser.new_context(viewport={'width':1440,'height':900},device_scale_factor=1)
 page=context.new_page()
 page.on('pageerror',lambda error:report['errors'].append(str(error)))
 page.on('console',lambda message:report['consoleErrors'].append(message.text) if message.type=='error' else None)
 page.on('response',lambda r:report['fontsRequested'].append({'url':r.url,'status':r.status}) if '.woff' in r.url else None)
 page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456')
 page.locator('button[type=submit]').click()
 page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
 page.goto('http://localhost:5173/assistant',wait_until='networkidle')
 page.evaluate('document.fonts.ready')
 cdp=context.new_cdp_session(page);cdp.send('DOM.enable');cdp.send('CSS.enable')
 def capture(label):
  page.evaluate('document.fonts.ready');page.wait_for_timeout(400)
  root=cdp.send('DOM.getDocument')['root']['nodeId'];samples={}
  for selector in ['.workbench-welcome h1','.welcome-eyebrow','.history-bottom a span','.history-heading strong','.role-name','.workbench-composer textarea','.session-item > button:first-child','.inventory-answer tbody td','.inventory-answer thead th']:
   node=cdp.send('DOM.querySelector',{'nodeId':root,'selector':selector})['nodeId']
   if not node:
    samples[selector]={'notRendered':True};continue
   samples[selector]={'fonts':cdp.send('CSS.getPlatformFontsForNode',{'nodeId':node})['fonts'],
    'css':page.locator(selector).first.evaluate("e=>{const s=getComputedStyle(e);return Object.fromEntries(['fontFamily','fontSize','fontWeight','lineHeight','letterSpacing','fontSynthesis','color','opacity','transform','zoom'].map(k=>[k,s[k]]))}")}
  report['samples'][label]=samples
  report[label+'Faces']=page.evaluate("Array.from(document.fonts).filter(f=>f.status==='loaded').map(f=>({family:f.family,weight:f.weight,status:f.status}))")
  assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
  page.screenshot(path=str(out/(label+'-1440.png')))
 if mode=='before':capture('before')
 else:
  capture('noto')
  override=page.add_style_tag(content=".agent-workbench {--aw-font-sans:'MES Inter','Microsoft YaHei UI',sans-serif !important;--aw-font-mono:'MES Mono','Microsoft YaHei UI',monospace !important;}")
  capture('system-yahei-ui');override.evaluate('(e)=>e.remove()')
  title=report['samples']['noto']['.workbench-welcome h1']
  assert title['css']['fontWeight']=='500' and title['css']['fontSize']=='27px' and title['css']['letterSpacing'] in ('normal','0px'),title
  assert any('Noto Sans SC' in f['familyName'] and f['isCustomFont'] for f in title['fonts']),title
  assert any(f['familyName']=='Microsoft YaHei UI' for f in report['samples']['system-yahei-ui']['.workbench-welcome h1']['fonts'])
  page.set_viewport_size({'width':1920,'height':1080});page.screenshot(path=str(out/'final-1920.png'))
  page.set_viewport_size({'width':1440,'height':900})
  textarea=page.get_by_label('输入问题',exact=True);textarea.fill('检查中文输入与 MES 混排\n保持原来的输入行为')
  page.wait_for_function("document.querySelector('.workbench-composer').getBoundingClientRect().height>=100")
  textarea.fill('');textarea.blur();page.wait_for_timeout(300)
  assert page.locator('.workbench-composer').evaluate('e=>e.getBoundingClientRect().height')<=56
  page.get_by_label('收起 Agent 面板',exact=True).click()
  page.get_by_label('展开 Agent 面板',exact=True).click()
  page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
  textarea.fill('帮我查询库存');page.get_by_label('发送问题',exact=True).click()
  page.locator('.inventory-answer tbody tr').first.wait_for(timeout=300000)
  page.get_by_label('停止回答',exact=True).wait_for(state='hidden',timeout=300000)
  capture('final-inventory')
  assert page.locator('.session-item.active > button:first-child').evaluate('e=>getComputedStyle(e).fontWeight')=='500'
  report['actualInventoryRows']=page.locator('.inventory-answer tbody tr').count()
  page.get_by_role('button',name='新对话',exact=True).click()
  assert page.locator('.session-item > button:first-child').first.evaluate('e=>getComputedStyle(e).fontWeight')=='400'
  page.locator('.session-item > button:first-child').first.click()
  assert page.locator('.inventory-answer tbody tr').count()==report['actualInventoryRows']
  report['notoBytes']=page.evaluate("performance.getEntriesByType('resource').filter(r=>r.name.includes('/noto-sans-sc/')&&r.name.includes('.woff')).reduce((n,r)=>n+r.encodedBodySize,0)")
 report['browser']=browser.version;report['platform']=page.evaluate('navigator.userAgent')
 report['fontsBytes']=page.evaluate("performance.getEntriesByType('resource').filter(r=>r.name.includes('.woff')).reduce((n,r)=>n+r.encodedBodySize,0)")
 assert not report['errors'] and not report['consoleErrors'],report
 (out/(mode+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 browser.close()
 print(json.dumps({'mode':mode,'status':'passed','browser':report['browser'],'fontsBytes':report['fontsBytes']}))
