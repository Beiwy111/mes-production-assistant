"""Read-only real-service acceptance for scoped queries and maintenance intervals."""
import json,time,sys,subprocess
from pathlib import Path
from decimal import Decimal,ROUND_HALF_UP
from datetime import datetime
from playwright.sync_api import sync_playwright
out=Path('docs/ui-verification/p0-restart' if '--restart' in sys.argv else 'docs/ui-verification/p0-keyword' if '--keyword' in sys.argv else 'docs/ui-verification/p0-scopes');out.mkdir(parents=True,exist_ok=True)
report={'checks':[],'errors':[],'consoleErrors':[]}
with sync_playwright() as p:
 b=p.chromium.launch(headless=True);context=b.new_context(viewport={'width':1440,'height':900},reduced_motion='reduce')
 context.add_init_script(r'''window.__wire=[];const originalFetch=fetch.bind(window);window.fetch=async(...args)=>{const r=await originalFetch(...args);if(String(args[0]).endsWith('/runs/stream')&&r.ok)(async()=>{const reader=r.clone().body.getReader(),decoder=new TextDecoder();let buf='';while(true){const{value,done}=await reader.read();buf+=decoder.decode(value||new Uint8Array(),{stream:!done});let i;while((i=buf.indexOf('\n'))>=0){const line=buf.slice(0,i);buf=buf.slice(i+1);if(line.trim())window.__wire.push({...JSON.parse(line),at:performance.now()});}if(done)break;}})();return r;};''')
 page=context.new_page();page.on('pageerror',lambda e:report['errors'].append(str(e)))
 page.on('console',lambda e:report['consoleErrors'].append(e.text) if e.type=='error' else None)
 page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456');page.locator('button[type=submit]').click()
 page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
 page.goto('http://localhost:5173/assistant',wait_until='networkidle')
 page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
 report['sidebar']=page.locator('.history-bottom a').first.evaluate('(e)=>{let s=getComputedStyle(e);return{size:s.fontSize,weight:s.fontWeight,color:s.color}}')
 assert report['sidebar']=={'size':'14px','weight':'400','color':'rgb(36, 40, 51)'},report['sidebar']
 page.screenshot(path=str(out/'sidebar-1440.png'))
 def ask(text,name,fresh=True):
  if fresh:page.get_by_role('button',name='新对话',exact=True).click()
  page.evaluate('window.__wire=[]');page.get_by_label('输入问题',exact=True).fill(text);page.get_by_label('发送问题',exact=True).click()
  page.wait_for_function("window.__wire.some(e=>e.type==='done')",timeout=300000)
  wire=page.evaluate('window.__wire');errors=[e for e in wire if e['type']=='error'];assert not errors,errors
  result=next(e['data'] for e in reversed(wire) if e['type']=='result')
  page.wait_for_timeout(250)
  assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
  report['checks'].append({'name':name,'verdict':result['verdict'],'agents':result.get('executedAgents',[]),'deltaCount':sum(e['type']=='delta' for e in wire)})
  print(json.dumps(report['checks'][-1],ensure_ascii=True),flush=True)
  return result
 if '--restart' in sys.argv:
  first=ask('查询仓库WH-01的PCB库存','scope-before-restart')
  assert first['scope']['keyword']=='PCB' and first['scope']['selectors']=={'warehouse_code':'WH-01'}
  ref=first['runRef']
  for namespace in ['runtime','gateway']:
   envelope=json.loads(Path(f'backend/assistant/rag/data/conversation-context/{namespace}/{ref}.json').read_text(encoding='utf-8'))
   assert 'facts' not in envelope['body']['payload'] and 'reply' not in envelope['body']['payload']
  # File handles avoid waiting for inherited pipe EOF from Windows background JVMs.
  restart_log=Path('backend/assistant/rag/data/context-browser-restart.log')
  with restart_log.open('w',encoding='utf-8') as log:
   restart=subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(Path(__file__).with_name('restart-workbench-context.ps1'))],stdout=log,stderr=log,timeout=90)
  assert restart.returncode==0,'Independent restart failed; inspect private local logs'
  report['restart']=json.loads(next(line.split(':',1)[1] for line in restart_log.read_text(encoding='utf-8',errors='replace').splitlines() if line.startswith('CONTEXT_RESTART_RESULT:')))
  # The same browser, login token and session remain open across both independent JVM restarts.
  follow=ask('其中可用库存','scope-after-restart',False)
  assert follow['verdict']=='EVIDENCE_READY'
  assert follow['scope']['keyword']=='PCB' and follow['scope']['selectors']==first['scope']['selectors']
  assert follow['runRef']!=ref
  assert all(r['warehouse_code']=='WH-01' and 'PCB' in r['material_name'] and Decimal(r['quantity_available'])>0 for r in follow['facts']['inventory']['overview']['rows'])
  assert not report['errors'] and not report['consoleErrors'],report
  report['status']='passed';(out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
  page.screenshot(path=str(out/'after-restart-1440.png'))
  b.close();print('Real restart continuation passed',flush=True);raise SystemExit(0)
 if '--keyword' in sys.argv:
  first=ask('查询PCB库存','keyword-first')
  second=ask('其中可用库存','keyword-second',False)
  third=ask('其中今天更新的','keyword-third',False)
  for r in [first,second,third]:
   assert r['verdict']=='EVIDENCE_READY',r['verdict']
   assert r['scope']['keyword']=='PCB',r['scope']
  assert third['scope']['criteria']['INVENTORY']=='AVAILABLE'
  unrelated=ask('查询全部订单','new-topic-clears-keyword',False)
  assert unrelated['scope']['keyword'] is None
  assert not report['errors'] and not report['consoleErrors']
  report['status']='passed';(out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
  b.close();print('Keyword continuation passed',flush=True);raise SystemExit(0)
 allstock=ask('帮我查询全部库存','baseline-inventory')['facts']['inventory']['overview']['rows']
 scoped=ask('查询仓库WH-01、库位A-01-01的可用库存','warehouse-location-available')
 rows=scoped['facts']['inventory']['overview']['rows']
 expected=[r for r in allstock if r.get('warehouse_code')=='WH-01' and r.get('location_code')=='A-01-01' and r.get('quantity_available') is not None and Decimal(r['quantity_available'])>0]
 assert rows==expected,(rows,expected)
 assert scoped['scope']['selectors']=={'warehouse_code':'WH-01','location_code':'A-01-01'}
 assert page.locator('.inventory-answer tbody tr').count()==len(rows)
 page.screenshot(path=str(out/'scoped-stock-1440.png'))
 follow=ask('其中哪些库存不足','followup-keeps-warehouse',False)
 assert follow['scope']['selectors']==scoped['scope']['selectors']
 assert all(Decimal(r['quantity_available'])<=0 for r in follow['facts']['inventory']['overview']['rows'])
 orders=ask('查询订单列表','baseline-orders')['facts']['order']['overview']['rows']
 chosen=next(r for r in orders if r.get('customer_name') and r.get('event_at'))
 day=chosen['event_at'][:10];customer=chosen['customer_name']
 result=ask(f'查询客户名称为“{customer}”的订单，日期{day}','customer-date')
 matching=[r for r in orders if r.get('customer_name')==customer and r.get('event_at','')[:10]==day]
 assert result['facts']['order']['overview']['rows']==matching
 assert result['scope']['selectors']=={'customer_name':customer}
 assert page.locator('.business-answer tbody tr').count()==len(matching)
 page.screenshot(path=str(out/'customer-orders-1440.png'))
 maintenance=ask('统计所有设备维修时长，合并重叠维修区间','maintenance-interval-union')
 fact=maintenance['facts']['maintenance'];records=fact['overview']['rows'];summaries=fact['maintenanceSummary']
 for summary in summaries:
  group=[r for r in records if r.get('equipment_id')==summary.get('equipment_id')];intervals=[]
  for r in group:
   if not r.get('equipment_id'):continue
   try:
    start=datetime.fromisoformat(r['start_time']);end=datetime.fromisoformat(r['end_time'])
    if end>=start:intervals.append((start,end))
   except (ValueError,TypeError,KeyError):pass
  merged=[]
  for start,end in sorted(intervals):
   if merged and start<=merged[-1][1]:merged[-1]=(merged[-1][0],max(end,merged[-1][1]))
   else:merged.append((start,end))
  expected=sum((Decimal(str((end-start).total_seconds()))/60 for start,end in merged),Decimal(0)).quantize(Decimal('.01'),rounding=ROUND_HALF_UP) if intervals else None
  actual=Decimal(summary['interval_union_minutes']) if summary['interval_union_minutes'] is not None else None
  assert actual==expected,(actual,expected)
  assert summary['valid_interval_count']==len(intervals)
 page.screenshot(path=str(out/'maintenance-1440.png'))
 rejected=ask('查询仓库WH-01和FG-WH的库存','ambiguous-scope-clarifies')
 assert rejected['verdict']=='CLARIFY' and not rejected.get('executedAgents')
 assert not report['errors'] and not report['consoleErrors'],report
 report['actualInventoryCount']=len(allstock);report['selectedInventoryCount']=len(rows);report['actualOrderCount']=len(orders);report['selectedOrderCount']=len(matching);report['maintenanceCount']=len(records);report['status']='passed'
 (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');b.close()
 print(json.dumps({'status':'passed','scenarios':len(report['checks'])},ensure_ascii=True),flush=True)

