"""UI-only order fixture: all order endpoints mocked, no business writes."""
import copy, json
from pathlib import Path
from playwright.sync_api import sync_playwright

out = Path('docs/ui-verification/stream-refinement/order-fixture')
out.mkdir(parents=True, exist_ok=True)
draft = {'id': 'ui-fixture-only', 'version': 1, 'status': 'COLLECTING',
 'fields': {'quantity': 100}, 'missing': [], 'customerIdentityLocked': False,
 'products': [{'materialId': 1, 'materialCode': 'PRD-001', 'materialName': '27寸办公显示器', 'specification': '2560×1440 IPS', 'unit': '台'},
              {'materialId': 2, 'materialCode': 'PRD-002', 'materialName': '24寸显示器', 'specification': '1920×1080 IPS', 'unit': '台'}]}
calls=[]
def envelope():
 return {'handled':True, 'reply':'请补充收货信息，核对后可直接提交审核。', 'orderDraft':copy.deepcopy(draft), 'steps':[
  {'agentId':'SUP','status':'COMPLETED','label':'识别订单需求','detail':'识别为创建订单，交给订单 Agent 补充产品与收货信息。'},
  {'agentId':'ORDER','status':'COMPLETED','label':'整理订单信息','detail':'已记录数量 100 台，返回可选型号并检查必填信息。'}]}
def orders(route):
 path=route.request.url.split('/orders',1)[1]
 if path=='/status': data={'available':True,'canQuery':True}
 elif path=='/turn': data=envelope()
 elif route.request.method=='GET': calls.append('read'); data=envelope()
 else:
  action=path.rsplit('/',1)[1];body=route.request.post_data_json;calls.append(action)
  assert body['version']==draft['version']
  draft['version']+=1
  if action=='revise': draft['fields'].update(body['fields']);draft['status']='READY'
  elif action=='confirm':
   assert not draft['fields'].get('remark')
   draft['status']='SUBMITTED';draft['result']={'orderNo':'UI-MOCK-ONLY','status':'PENDING','message':'测试订单已提交审核（浏览器模拟，未写入业务）'}
  else: raise AssertionError(action)
  data=envelope()
 route.fulfill(json={'code':200,'data':data})

with sync_playwright() as p:
 browser=p.chromium.launch(headless=True)
 context=browser.new_context(viewport={'width':1440,'height':1000})
 page=context.new_page();errors=[]
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.get_by_placeholder('用户名',exact=True).fill('admin')
 page.get_by_placeholder('密码',exact=True).fill('123456')
 page.locator('button[type=submit]').click()
 page.wait_for_function("!!sessionStorage.getItem('token') && !location.pathname.includes('login')",timeout=40000)
 page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
 page.screenshot(path=str(out/'welcome.png'))
 # All order requests below are intercepted: no draft or business order is created.
 page.route('**/api/assistant-v2/orders/**',orders)
 page.get_by_label('输入问题',exact=True).fill('我想下单100台显示器')
 page.get_by_label('发送问题',exact=True).click()
 card=page.locator('.order-flow').last
 card.locator('select').select_option('1')
 for label,value in [('数量','125'),('交付日期','2026-12-20'),('客户名称','界面验证公司'),('收货人','张先生'),('联系电话','13800138000'),('收货地址','广东省深圳市南山区示例路1号')]:
  card.get_by_label(label,exact=True).fill(value)
 assert not card.locator('.order-process').evaluate('(el)=>el.open')
 assert card.get_by_role('button',name='提交审核',exact=True).is_enabled()
 print(page.locator('.agent-workbench').evaluate('el=>({root:getComputedStyle(el).fontWeight,input:getComputedStyle(el.querySelector(".draft-form input")).fontWeight,body:getComputedStyle(el.querySelector(".workbench-markdown p")).fontWeight,bodySize:getComputedStyle(el.querySelector(".workbench-markdown p")).fontSize})'));field_style=card.get_by_label('数量',exact=True).evaluate('(el)=>({font:getComputedStyle(el).fontSize,color:getComputedStyle(el).color})')
 assert field_style['color']=='rgb(32, 33, 36)',field_style
 assert field_style['font']=='13px',field_style
 card.get_by_label('客户名称',exact=True).focus()
 focus_style=card.get_by_label('客户名称',exact=True).evaluate('(el)=>({outline:getComputedStyle(el).outlineStyle,border:getComputedStyle(el).borderTopWidth,shadow:getComputedStyle(el).boxShadow})')
 assert focus_style=={'outline':'none','border':'2px','shadow':'none'},focus_style
 card.scroll_into_view_if_needed();page.screenshot(path=str(out/'order-desktop.png'))
 page.get_by_label('收起 Agent 面板',exact=True).click();page.set_viewport_size({'width':390,'height':844});card.scroll_into_view_if_needed()
 assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 assert card.locator('input').first.evaluate('(el)=>el.getBoundingClientRect().right<=innerWidth')
 page.screenshot(path=str(out/'order-mobile.png'))
 page.set_viewport_size({'width':1440,'height':1000})
 card.get_by_role('button',name='提交审核',exact=True).click()
 card.get_by_text('UI-MOCK-ONLY',exact=False).wait_for(timeout=10000)
 assert calls==['read','revise','confirm'],calls
 assert draft['fields']['quantity']==125 and str(draft['fields']['materialId'])=='1'
 assert card.get_by_text('待订单审核',exact=True).is_visible()
 # Verify process appearance using a clearly identified fixture in the mounted UI.
 page.locator('.agent-workbench').evaluate('''el => {
  const v=el.__vueParentComponent.setupState;
  const m=v.messages[v.messages.length-1];
  delete m.orderFlow; m.status='RUNNING'; m.text=''; m.result=null;
  m.agents=[{agentId:'SUPPLY',status:'RUNNING',steps:[{id:'BUSINESS_READ',status:'COMPLETED',label:'读取物料库存',detail:'已查询 PCB 板和背光模组的库存记录，正在汇总可用数量与缺料情况。'}]}];
 }''')
 flow=page.locator('.agent-flow').last
 page.wait_for_function("document.querySelector('.agent-flow')?.open")
 assert flow.locator('.step-description').is_visible()
 page.screenshot(path=str(out/'agent-running.png'))
 page.locator('.agent-workbench').evaluate('''el => {
  const v=el.__vueParentComponent.setupState; const m=v.messages[v.messages.length-1];
  m.status='COMPLETED';m.text='库存查询已完成。以下为本次查询结果。';m.agents[0].status='COMPLETED';
 }''')
 page.wait_for_function("!document.querySelector('.agent-flow')?.open")
 assert flow.bounding_box()['height']<55
 assert page.locator('.workbench-composer').bounding_box()['height']<150
 page.screenshot(path=str(out/'agent-completed.png'))
 assert not errors,errors
 report={'result':'passed','realLoginAndStatus':True,'orderRequests':'all mocked; no real business or draft writes',
  'submitSequence':calls,'fieldStyle':field_style,'mobileOverflow':False,'remarksOmitted':True,
  'workflowFixture':'running detail visible; completion collapsed','pageErrors':errors}
 (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps(report,ensure_ascii=False))
 context.close();browser.close()
