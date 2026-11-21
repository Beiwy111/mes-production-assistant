"""Read-only real service latency comparison; no synthetic deltas or business writes."""
import argparse,json,time
from pathlib import Path
from playwright.sync_api import sync_playwright

parser=argparse.ArgumentParser();parser.add_argument('--label',required=True);parser.add_argument('--cases',default='inventory,multi,delivery');args=parser.parse_args()
out=Path('docs/ui-verification/parallel-agents');out.mkdir(parents=True,exist_ok=True)
queries={'inventory':'帮我查询当前库存。','multi':'查询当前库存、当前生产工单和设备台账，三个都需要。','delivery':'帮我分析订单ID100能否按时交付。','quality':'查询订单ID100的质量检验记录和售后追溯情况。'}
queries['full']='查询当前全部订单、物料库存、生产工单、质检记录、设备台账和售后案件，六个领域都需要，不限定某张订单。'
queries['qualitytrend']='查询当前全部质检记录，按日分析质量趋势'
instrument=r"""window.__wire=[];const original=window.fetch.bind(window);window.fetch=async(...args)=>{const r=await original(...args);if(!String(args[0]).endsWith('/runs/stream')||!r.ok)return r;let b='';const d=new TextDecoder();return new Response(r.body.pipeThrough(new TransformStream({transform(chunk,c){b+=d.decode(chunk,{stream:true});let i;while((i=b.indexOf('\n\n'))>=0){const f=b.slice(0,i);b=b.slice(i+2);const s=f.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');if(s)window.__wire.push({...JSON.parse(s),at:performance.now()});}c.enqueue(chunk)}})),{status:r.status,headers:r.headers})};"""
report={'label':args.label,'cases':[],'errors':[],'businessWrites':False}
def save(): (out/(args.label+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1440,'height':1000},reduced_motion='no-preference');context.add_init_script(instrument);page=context.new_page()
 page.on('pageerror',lambda e:report['errors'].append(str(e)))
 page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456');page.locator('button[type=submit]').click()
 page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
 page.goto('http://localhost:5173/assistant',wait_until='networkidle')
 page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
 for name in args.cases.split(','):
  page.get_by_role('button',name='新对话',exact=True).click();page.evaluate('window.__wire=[];window.__started=performance.now()')
  page.get_by_label('输入问题',exact=True).fill(queries[name]);page.get_by_label('发送问题',exact=True).click()
  page.wait_for_function("window.__wire.some(e=>['task_completed','task_failed','task_cancelled'].includes(e.type))",timeout=240000)
  wire=page.evaluate('window.__wire');started=page.evaluate('window.__started');terminal=wire[-1]
  delta=next((e for e in wire if e['type']=='message_delta'),None)
  result=next((e['payload']['legacy']['data'] for e in wire if e['type']=='message_completed'),{})
  text=''.join(e['payload'].get('text','') for e in wire if e['type']=='message_delta')
  entry={'name':name,'firstTokenMs':round(delta['at']-started) if delta else None,'totalMs':round(terminal['at']-started),'status':terminal['type'],'deltaCount':sum(e['type']=='message_delta' for e in wire),'textMatches':text==result.get('reply'),'result':result,'events':wire}
  report['cases'].append(entry);save();print(name,{k:entry[k] for k in ['firstTokenMs','totalMs','status','deltaCount','textMatches']},flush=True)
  assert terminal['type']=='task_completed',terminal
  assert entry['textMatches']
  page.wait_for_function("!document.querySelector('.workbench-message.assistant:last-child .is-streaming')",timeout=4000)
  if name=='qualitytrend':page.get_by_label('按日抽检趋势，可左右滚动查看全部字段',exact=True).scroll_into_view_if_needed()
  page.screenshot(path=str(out/(args.label+'-'+name+'.png')))
 assert not report['errors'];report['passed']=True;save();browser.close()
