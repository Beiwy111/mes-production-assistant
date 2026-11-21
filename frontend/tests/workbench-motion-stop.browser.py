"""Real Dify generation cancellation/session/disconnect with visual smoothing enabled."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright
out=Path('docs/ui-verification/stream-motion');out.mkdir(parents=True,exist_ok=True)
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1440,'height':1000},reduced_motion='no-preference')
 context.add_init_script(r"""window.__task=null;window.__cut=false;const original=window.fetch.bind(window);window.fetch=async(...args)=>{const r=await original(...args);if(!String(args[0]).endsWith('/runs/stream')||!r.ok)return r;let buffer='',count=0;const d=new TextDecoder();return new Response(r.body.pipeThrough(new TransformStream({transform(chunk,c){buffer+=d.decode(chunk,{stream:true});let i;while((i=buffer.indexOf('\n\n'))>=0){const f=buffer.slice(0,i);buffer=buffer.slice(i+2);const data=f.split('\n').find(s=>s.startsWith('data: '));if(data){const e=JSON.parse(data.slice(6));window.__task=e.task_id;if(e.type==='message_delta'&&++count>20&&window.__cut){c.error(new TypeError('Controlled transport disconnect'));return}}}c.enqueue(chunk)}})),{status:r.status,headers:r.headers})};""")
 page=context.new_page();page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456');page.locator('button[type=submit]').click()
 page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
 page.goto('http://localhost:5173/assistant',wait_until='networkidle');page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
 results=[]
 for name in ['parallel-stop','stop','switch','disconnect']:
  page.get_by_role('button',name='新对话',exact=True).click();page.evaluate('(cut)=>{window.__task=null;window.__cut=cut}',name=='disconnect')
  query='查询当前库存、当前生产工单和设备台账，三个都需要。' if name=='parallel-stop' else '请根据知识库详细解释LCD面板Mura缺陷检测方法、依据与局限。'
  page.get_by_label('输入问题',exact=True).fill(query);page.get_by_label('发送问题',exact=True).click()
  if name!='disconnect':
   if name=='parallel-stop':
    page.wait_for_function("[...document.querySelectorAll('.inspector-role')].filter(e=>e.dataset.agent!=='SUP'&&e.querySelector('.agent-status-indicator')?.dataset.status==='RUNNING').length>=3",timeout=180000)
   else:page.wait_for_function("document.querySelector('.answer-content .workbench-markdown.is-streaming')?.innerText.length>1",timeout=180000)
   if name in ['parallel-stop','stop']:page.get_by_label('停止回答',exact=True).click()
   else:page.get_by_role('button',name='新对话',exact=True).click()
  page.wait_for_function("window.__task&&!document.querySelector('[aria-label=\"停止回答\"]')",timeout=180000)
  page.wait_for_timeout(40)
  content=page.locator('.workbench-message.assistant:last-child .answer-content')
  visible=content.inner_text() if content.count() and name!='switch' else ''
  page.wait_for_timeout(500)
  assert not page.locator('.is-streaming,.inspector-role > summary .status-ring.rotating').count()
  if name=='switch':assert not page.locator('.workbench-message').count()
  else:assert (content.inner_text() if content.count() else '')==visible
  state=page.evaluate("""async()=>{const r=await fetch('/api/assistant-workbench/runs/'+window.__task,{headers:{Authorization:'Bearer '+sessionStorage.getItem('token')}});const b=await r.json();return b.status}""")
  assert state in ['CANCELLED','COMPLETED','FAILED'],state
  if name=='parallel-stop':assert state=='CANCELLED'
  results.append({'name':name,'serverStatus':state,'noAnimationRemaining':True,'visibleTextStable':True});print(name,state,flush=True)
 (out/'stop-verification.json').write_text(json.dumps({'status':'passed','realModel':True,'cases':results},ensure_ascii=False,indent=2),encoding='utf-8');context.close();browser.close()
