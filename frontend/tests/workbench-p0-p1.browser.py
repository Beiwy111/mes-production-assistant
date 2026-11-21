"""Real read-only P0 scope/evidence and P1 trend acceptance; no business mutations."""
import json
from pathlib import Path
from decimal import Decimal,ROUND_HALF_UP
from playwright.sync_api import sync_playwright
out=Path('docs/ui-verification/p0-p1-continuation');out.mkdir(parents=True,exist_ok=True)
report={'cases':[],'errors':[],'businessWrites':False}
instrument=r"""window.__wire=[];const original=fetch.bind(window);window.fetch=async(...args)=>{const r=await original(...args);if(!String(args[0]).endsWith('/runs/stream')||!r.ok)return r;let b='';const d=new TextDecoder();return new Response(r.body.pipeThrough(new TransformStream({transform(chunk,c){b+=d.decode(chunk,{stream:true});let i;while((i=b.indexOf('\n\n'))>=0){const f=b.slice(0,i);b=b.slice(i+2);const s=f.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');if(s)window.__wire.push({...JSON.parse(s),at:performance.now()});}c.enqueue(chunk)}})),{status:r.status,headers:r.headers})};"""
def save(): (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1440,'height':1000},reduced_motion='no-preference');context.add_init_script(instrument);page=context.new_page()
 page.on('pageerror',lambda e:report['errors'].append(str(e)))
 page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456');page.locator('button[type=submit]').click()
 page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
 page.goto('http://localhost:5173/assistant',wait_until='networkidle')
 page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
 def ask(name,query,fresh=True):
  if fresh:page.get_by_role('button',name='新对话',exact=True).click()
  page.evaluate('window.__wire=[]');page.get_by_label('输入问题',exact=True).fill(query);page.get_by_label('发送问题',exact=True).click()
  page.wait_for_function("window.__wire.some(e=>['task_completed','task_failed'].includes(e.type))",timeout=240000)
  wire=page.evaluate('window.__wire');assert wire[-1]['type']=='task_completed',wire[-1]
  result=next(e['payload']['legacy']['data'] for e in wire if e['type']=='message_completed')
  assert ''.join(e['payload'].get('text','') for e in wire if e['type']=='message_delta')==result['reply']
  page.wait_for_function("!document.querySelector('.workbench-message.assistant:last-child .is-streaming')",timeout=5000)
  assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
  report['cases'].append({'name':name,'verdict':result['verdict'],'agents':result.get('executedAgents',[]),'deltas':sum(e['type']=='message_delta' for e in wire),'result':result,'events':wire})
  save();page.screenshot(path=str(out/(name+'.png')));print(name,result['verdict'],result.get('executedAgents',[]),flush=True)
  return result
 baseline=ask('inventory-baseline','查询当前全部库存')
 rows=baseline['facts']['inventory']['overview']['rows']
 multi=ask('warehouse-union','查询仓库WH-01、WH-02的库存')
 expected=[r for r in rows if r.get('warehouse_code') in ['WH-01','WH-02']]
 assert multi['facts']['inventory']['overview']['rows']==expected
 follow=ask('warehouse-followup','其中可用库存',False)
 assert follow['scope']['selectors']['warehouse_codes']=='WH-01,WH-02'
 assert follow['facts']['inventory']['overview']['rows']==[r for r in expected if r.get('quantity_available') is not None and Decimal(r['quantity_available'])>0]
 negative=ask('warehouse-exclusion','查询排除仓库WH-01的库存')
 assert negative['facts']['inventory']['overview']['rows']==[r for r in rows if r.get('warehouse_code') is not None and r['warehouse_code']!='WH-01']
 location=ask('location-exclusion','查询仓库WH-01的库存，排除库位A-01-01')
 assert location['facts']['inventory']['overview']['rows']==[r for r in rows if r.get('warehouse_code')=='WH-01' and r.get('location_code') is not None and r['location_code']!='A-01-01']
 orders=ask('orders-baseline','查询当前全部订单')['facts']['order']['overview']['rows']
 union=ask('orders-status-union','查询已审核和待审核订单')
 assert union['facts']['order']['overview']['rows']==[r for r in orders if r['status'] in ['APPROVED','PENDING']]
 narrowed=ask('orders-exclusion-followup','其中排除已审核的订单',False)
 assert narrowed['facts']['order']['overview']['rows']==[r for r in orders if r['status']=='PENDING']
 exclusion=ask('orders-status-exclusion','查询排除已审核的订单')
 assert exclusion['facts']['order']['overview']['rows']==[r for r in orders if r['status'] in ['PENDING','REJECTED','SHIPPED']]
 trend=ask('quality-trend','查询当前全部质检记录，按日分析质量趋势')
 assert trend['verdict']=='EVIDENCE_READY'
 quality=trend['facts']['quality'];groups=quality['qualityTrend'];assert groups
 assert sum(g['inspection_record_count'] for g in groups)==len(quality['overview']['rows'])
 for g in groups:
  if g['sample_pass_percent'] is not None:
   assert Decimal(g['sample_pass_percent'])==(Decimal(g['valid_qualified_quantity'])*100/Decimal(g['valid_sample_quantity'])).quantize(Decimal('.01'),rounding=ROUND_HALF_UP)
 assert page.locator('.workbench-message.assistant:last-child').inner_text().find('抽检合格比例')>=0
 delivery=ask('delivery-registry','分析订单ID100能否按时交付')
 assert delivery['executedAgents']==['ORDER','SUPPLY','PRODUCTION']
 parallel=ask('parallel-preserved','查询当前库存、当前生产工单和设备台账，三个都需要')
 assert set(parallel['executedAgents'])=={'SUPPLY','PRODUCTION','EQUIPMENT'}
 times={(e['agent_id'],e['type']):e['at'] for e in report['cases'][-1]['events'] if e['type'] in ['agent_started','agent_completed']}
 assert max(times[r,'agent_started'] for r in parallel['executedAgents'])<min(times[r,'agent_completed'] for r in parallel['executedAgents'])
 assert not report['errors'];report['status']='passed';save();browser.close()
