"""Read-only real MES + Dify acceptance; capture native SSE arrivals and actual DOM renders."""
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

out=Path('docs/ui-verification/dual-stream');out.mkdir(parents=True,exist_ok=True)
script=r"""
window.__wire=[];window.__renders=[];window.__cut=false;
setInterval(()=>{const m=document.querySelector('.workbench-message.assistant:last-child');
 if(m&&window.__renders.length<15000)window.__renders.push({at:performance.now(),text:[...m.querySelectorAll('.answer-content,.answer-analysis')].map(e=>e.innerText).join('\n'),running:!!document.querySelector('[aria-label="停止回答"]'),agent:document.querySelector('.agent-inspector')?.innerText||''});},40);
const originalFetch=window.fetch.bind(window);
window.fetch=async(...args)=>{const response=await originalFetch(...args);
 if(!String(args[0]).endsWith('/runs/stream')||!response.ok)return response;
 window.__contentType=response.headers.get('content-type');let buffer='';const decoder=new TextDecoder();
 const transform=new TransformStream({transform(chunk,controller){
   buffer+=decoder.decode(chunk,{stream:true});let i;
   while((i=buffer.indexOf('\n\n'))>=0){const frame=buffer.slice(0,i);buffer=buffer.slice(i+2);
     const data=frame.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');
     if(data){const event=JSON.parse(data);window.__wire.push({...event,receivedAt:performance.now()});
       if(window.__cut&&event.type==='message_delta'){window.__cut=false;controller.error(new TypeError('Acceptance: disconnected transport'));return;}}
   }controller.enqueue(chunk);
 }});
 return new Response(response.body.pipeThrough(transform),{status:response.status,headers:response.headers});
};
"""
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True)
 context=browser.new_context(viewport={'width':1440,'height':1000},reduced_motion='reduce')
 context.add_init_script(script);page=context.new_page();errors=[]
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456')
 page.locator('button[type=submit]').click()
 page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
 page.goto('http://localhost:5173/assistant',wait_until='networkidle')
 page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
 report={'cases':[],'pageErrors':errors,'businessWrites':False}
 def save(): (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 def start(query,new=True):
  if new:page.get_by_role('button',name='新对话',exact=True).click()
  page.evaluate('window.__wire=[];window.__renders=[]')
  page.get_by_label('输入问题',exact=True).fill(query);page.get_by_label('发送问题',exact=True).click()
 def run(name,query,new=True):
  start(query,new);begun=time.monotonic();captured=False
  while time.monotonic()-begun<240:
   wire=page.evaluate('window.__wire')
   if not captured and any(e['type']=='tool_completed' for e in wire):
    page.screenshot(path=str(out/f'{name}-working.png'));captured=True
   if any(e['type'] in ['task_completed','task_failed','task_cancelled'] for e in wire) and not page.get_by_label('停止回答',exact=True).count():break
   page.wait_for_timeout(100)
  assert wire and wire[-1]['type']=='task_completed',[(e['type'],e['payload'].get('code')) for e in wire]
  assert page.evaluate('window.__contentType').startswith('text/event-stream')
  assert [e['sequence'] for e in wire]==list(range(1,len(wire)+1))
  assert len({e['task_id'] for e in wire})==1 and len({e['conversation_id'] for e in wire})==1
  result=next(e['payload']['legacy']['data'] for e in wire if e['type']=='message_completed')
  deltas=[e for e in wire if e['type']=='message_delta']
  assert deltas and ''.join(e['payload']['text'] for e in deltas)==result['reply'], 'final text differs from native deltas'
  assert deltas[0]['receivedAt']<wire[-1]['receivedAt']
  renders=page.evaluate('window.__renders')
  states=list(dict.fromkeys(r['text'] for r in renders if r['running'] and r['text'].strip()))
  if name=='rag':
   assert len(states)>1 and len({round(e['receivedAt']/100) for e in deltas})>1
   assert any(b.get('hits') for b in result.get('knowledgeEvidence',[]))
  if name=='inventory':
   overview=result['facts']['inventory']['overview']
   assert len(overview['rows'])==int(overview['returnedRecords'])==int(overview['totalRecords'])
   page.screenshot(path=str(out/'inventory-final-check.png'))
   counts={'inventory':page.locator('.workbench-message.assistant:last-child .inventory-answer tbody tr').count(),'all':page.locator('.workbench-message.assistant:last-child tbody tr').count(),'expected':len(overview['rows'])}
   print('inventory rows',counts,flush=True)
   assert counts['inventory']==counts['expected'],counts
  assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
  page.screenshot(path=str(out/f'{name}-1440.png'))
  if name=='inventory':
   page.set_viewport_size({'width':1920,'height':1080});page.screenshot(path=str(out/'inventory-1920.png'));page.set_viewport_size({'width':1440,'height':1000})
  (out/f'{name}-events.json').write_text(json.dumps(wire,ensure_ascii=False),encoding='utf-8')
  report['cases'].append({'name':name,'verdict':result['verdict'],'seconds':round(time.monotonic()-begun,1),'events':len(wire),'deltas':len(deltas),'visibleTextStates':len(states),'executedAgents':result.get('executedAgents'),'toolStarts':sum(e['type']=='tool_started' for e in wire),'toolEnds':sum(e['type']=='tool_completed' for e in wire),'deltaFirstMs':round(deltas[0]['receivedAt']-wire[0]['receivedAt']),'deltaLastMs':round(deltas[-1]['receivedAt']-wire[0]['receivedAt'])})
  save();print(name,report['cases'][-1],flush=True)
  return result
 run('greeting','你好，介绍一下你的功能')
 run('inventory','帮我查询库存')
 run('delivery-clarify','帮我分析这个订单能不能按时交付')
 run('delivery','请分析订单ID100能不能按时交付',False)
 run('rag','请根据知识库解释LCD面板的Mura缺陷和表面缺陷如何检测，给出方法依据和局限')
 for name in ['cancel','switch','disconnect']:
  start('请根据知识库详细解释LCD面板Mura缺陷检测的方法、依据与局限')
  if name=='disconnect':page.evaluate('window.__cut=true')
  page.wait_for_function("window.__wire.some(e=>e.type==='message_delta')",timeout=180000)
  run_id=page.evaluate('window.__wire[0].task_id')
  if name=='cancel':page.get_by_label('停止回答',exact=True).click()
  if name=='switch':page.get_by_role('button',name='新对话',exact=True).click()
  page.wait_for_function("!document.querySelector('[aria-label=\"停止回答\"]')",timeout=30000)
  if name=='switch':
   page.wait_for_timeout(600);assert page.locator('.workbench-message').count()==0
  else:
   assert page.locator('.workbench-message.assistant:last-child .message-error').count()>0
  state=page.evaluate("""async id=>{const r=await fetch('/api/assistant-workbench/runs/'+id,{headers:{Authorization:'Bearer '+sessionStorage.getItem('token')}});const b=await r.json();return {status:b.status,partialLength:b.partialText?.length||0}}""",run_id)
  assert state['status'] in ['CANCELLED','FAILED','COMPLETED'],state
  page.screenshot(path=str(out/f'{name}.png'))
  report['cases'].append({'name':name,**state,'noInfiniteLoading':True});save();print(name,state,flush=True)
 assert not errors,errors
 report['status']='passed';save();context.close();browser.close()
