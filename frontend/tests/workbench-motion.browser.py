"""Animation acceptance: real MES/Dify first, labelled controlled component scenarios second."""
import json,time
from pathlib import Path
from playwright.sync_api import sync_playwright
out=Path('docs/ui-verification/stream-motion');out.mkdir(parents=True,exist_ok=True)
instrument=r"""
window.__frames=[];window.__wire=[];window.__longTasks=[];
new PerformanceObserver(list=>{window.__longTasks.push(...list.getEntries().map(e=>({at:e.startTime,duration:e.duration})))}).observe({type:'longtask',buffered:true});
let last=0;function sample(t){if(t-last>=16&&window.__frames.length<16000){last=t;
 const m=document.querySelector('.workbench-message.assistant:last-child')||document.querySelector('#controlled');
 if(m){const texts=[...m.querySelectorAll('.answer-content .workbench-markdown,.answer-analysis .workbench-markdown,.controlled-text')];
 const caret=texts.find(e=>e.classList.contains('is-streaming'))?.lastElementChild;
 window.__frames.push({at:t,text:texts.map(e=>e.innerText).join('\n'),caret:!!caret,opacity:caret?getComputedStyle(caret,'::after').opacity:null,
 rings:[...document.querySelectorAll('.inspector-role > summary .status-ring')].map(e=>({agent:e.closest('[data-agent]').dataset.agent,transform:getComputedStyle(e).transform,animation:getComputedStyle(e).animationName})),
 states:[...document.querySelectorAll('.inspector-role')].map(e=>[e.dataset.agent,e.querySelector('.agent-status-indicator')?.dataset.status])});}}
 requestAnimationFrame(sample)}requestAnimationFrame(sample);
const originalFetch=window.fetch.bind(window);window.fetch=async(...args)=>{const r=await originalFetch(...args);if(!String(args[0]).endsWith('/runs/stream')||!r.ok)return r;
 let buffer='';const decoder=new TextDecoder();return new Response(r.body.pipeThrough(new TransformStream({transform(chunk,c){buffer+=decoder.decode(chunk,{stream:true});let i;
 while((i=buffer.indexOf('\n\n'))>=0){const f=buffer.slice(0,i);buffer=buffer.slice(i+2);const data=f.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');if(data)window.__wire.push({...JSON.parse(data),at:performance.now()});}c.enqueue(chunk)}})),{status:r.status,headers:r.headers})};
"""
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True)
 context=browser.new_context(viewport={'width':1440,'height':1000},reduced_motion='no-preference',record_video_dir=str(out/'video'),record_video_size={'width':1440,'height':1000})
 context.add_init_script(instrument);page=context.new_page();errors=[]
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
 page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456');page.locator('button[type=submit]').click()
 page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
 page.goto('http://localhost:5173/assistant',wait_until='networkidle')
 page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
 report={'real':[],'controlled':[],'pageErrors':errors,'businessWrites':False}
 def save(): (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
 for name,query in [('greeting','你好，介绍一下你的功能。'),('inventory','帮我查询当前库存。'),('delivery','帮我分析订单ID100能否按时交付。'),('rag','根据知识库解释LCD面板Mura缺陷的检测方法和局限，给出资料依据。')]:
  page.get_by_role('button',name='新对话',exact=True).click();page.evaluate('window.__frames=[];window.__wire=[];window.__longTasks=[]')
  page.get_by_label('输入问题',exact=True).fill(query);page.get_by_label('发送问题',exact=True).click()
  page.wait_for_function("window.__wire.some(e=>e.type==='message_delta')",timeout=180000)
  page.screenshot(path=str(out/f'{name}-streaming.png'))
  page.wait_for_function("window.__wire.some(e=>['task_completed','task_failed'].includes(e.type))",timeout=180000)
  page.wait_for_function("!document.querySelector('.workbench-message.assistant:last-child .is-streaming')",timeout=3000)
  wire=page.evaluate('window.__wire');frames=page.evaluate('window.__frames')
  assert wire[-1]['type']=='task_completed',wire[-1]['type']
  result=next(e['payload']['legacy']['data'] for e in wire if e['type']=='message_completed')
  text=''.join(e['payload']['text'] for e in wire if e['type']=='message_delta');assert text==result['reply']
  values=list(dict.fromkeys(f['text'] for f in frames if f['text']))
  assert len(values)>=4,(name,len(values))
  assert page.locator('.workbench-message.assistant:last-child .workbench-markdown').first.inner_text().strip()
  rings={r['transform'] for f in frames for r in f['rings'] if r['agent']=='SUP'}
  assert len(rings)>5,(name,'spinner did not rotate',rings)
  assert any(f['caret'] for f in frames),'no streaming caret'
  assert not page.locator('.inspector-role > summary .rotating').count()
  if name=='inventory':assert page.locator('.workbench-message.assistant:last-child .inventory-answer tbody tr').count()==len(result['facts']['inventory']['overview']['rows'])
  final=wire[-1]['at'];settled=next((f['at'] for f in frames if f['at']>=final and not f['caret']),final)
  report['real'].append({'name':name,'deltas':sum(e['type']=='message_delta' for e in wire),'visibleStates':len(values),'spinnerTransforms':len(rings),'drainMs':round(settled-final),'longTasks':page.evaluate('window.__longTasks')})
  (out/f'{name}-frames.json').write_text(json.dumps(frames,ensure_ascii=False),encoding='utf-8')
  page.screenshot(path=str(out/f'{name}-complete.png'));save();print(name,report['real'][-1],flush=True)

 # Controlled only: use the actual Vue components, not test changes to production services/data.
 controlled=context.new_page();controlled.on('pageerror',lambda e:errors.append(str(e)))
 controlled.goto('http://localhost:5173/login',wait_until='networkidle')
 controlled.evaluate(r"""async()=>{
 const {createApp,reactive,h,ref}=await import('/node_modules/.vite/deps/vue.js');
 const [{default:Markdown},{default:Inspector},{default:Composer}]=await Promise.all([import('/src/components/assistant/WorkbenchMarkdown.vue'),import('/src/components/assistant/AgentInspector.vue'),import('/src/components/assistant/WorkbenchComposer.vue')]);
 const {applyWorkbenchEvent,createAssistantMessage,interruptMessage}=await import('/src/utils/agentWorkbench.js');
 document.body.innerHTML='<main id="controlled" style="display:grid;grid-template-columns:1fr 320px;gap:30px;padding:32px;background:white;color:#242833"><div id="fixture"></div></main>';
 const message=reactive(createAssistantMessage());message.runId='controlled';message.result={presentation:'compact_stream'};
 message.agents=[{agentId:'SUP',status:'RUNNING'},{agentId:'SUPPLY',status:'RUNNING',steps:[{id:'slow',label:'受控测试工具',status:'RUNNING',detail:'仅用于8秒连续帧测试，无业务写入'}]}];
 const input=ref('');window.__fixture={message,interruptMessage};
 createApp({setup:()=>()=>h('div',{style:'display:grid;grid-template-columns:780px 320px;gap:30px'},[
 h('div',[h(Markdown,{class:'controlled-text',content:message.text,streaming:message.status==='RUNNING',smooth:true,stopped:['CANCELLED','FAILED','INTERRUPTED'].includes(message.status)}),h(Composer,{modelValue:input.value,busy:message.status==='RUNNING',canSend:false,'onUpdate:modelValue':v=>input.value=v})]),h(Inspector,{message})])}).mount('#fixture');
 window.__frames=[];window.__longTasks=[];
 }""")
 controlled.get_by_label('输入问题',exact=True).fill('生成期间仍可编辑下一条问题')
 for i in range(8):controlled.wait_for_timeout(1000)
 frames=controlled.evaluate('window.__frames')
 for agent in ['SUP','SUPPLY']:
  for second in range(8):
   selected=[f for f in frames if frames[0]['at']+second*1000<=f['at']<frames[0]['at']+(second+1)*1000]
   assert len({r['transform'] for f in selected for r in f['rings'] if r['agent']==agent})>3,(agent,second)
 assert controlled.get_by_label('输入问题',exact=True).input_value()=='生成期间仍可编辑下一条问题'
 controlled.screenshot(path=str(out/'controlled-slow-tool.png'))
 report['controlled'].append({'name':'8-second-tool','eachSecondHasRotation':True,'inputEditable':True,'parallelRings':['SUP','SUPPLY']});save()
 # One large native-like delta: visual animation must be independent of data delivery.
 controlled.evaluate("window.__frames=[];window.__fixture.message.text='中文👩‍💻é正文'.repeat(50)")
 controlled.wait_for_timeout(120)
 controlled.evaluate("window.__fixture.message.status='COMPLETED';window.__fixture.message.agents.forEach(a=>{a.status='COMPLETED';a.steps?.forEach(s=>s.status='COMPLETED')});window.__end=performance.now()")
 controlled.wait_for_function("!document.querySelector('.controlled-text.is-streaming')",timeout=1500)
 frames=controlled.evaluate('window.__frames');assert len({f['text'] for f in frames})>=4
 assert controlled.locator('.controlled-text').inner_text()==controlled.evaluate('window.__fixture.message.text')
 assert not controlled.locator('.rotating').count()
 report['controlled'].append({'name':'large-delta','visibleStates':len({f['text'] for f in frames}),'drainMs':round(controlled.evaluate('performance.now()-window.__end')),'exactText':True});save()
 # A fresh component per run ensures a separate queue; cancellation stops visual ticks immediately.
 for status in ['CANCELLED','FAILED','INTERRUPTED']:
  controlled.reload(wait_until='networkidle')
  # Component isolation test through a standalone real Markdown leaf.
  controlled.evaluate(r"""async()=>{const {createApp,reactive,h}=await import('/node_modules/.vite/deps/vue.js');const {default:M}=await import('/src/components/assistant/WorkbenchMarkdown.vue');document.body.innerHTML='<div id="controlled"></div>';const s=reactive({text:'用于停止测试的流式文字'.repeat(100),status:'RUNNING'});window.__state=s;createApp({setup:()=>()=>h(M,{class:'controlled-text',content:s.text,smooth:true,streaming:s.status==='RUNNING',stopped:s.status!=='RUNNING'})}).mount('#controlled');window.__frames=[];}""")
  controlled.wait_for_timeout(100);controlled.evaluate('(s)=>window.__state.status=s',status);controlled.wait_for_timeout(30)
  prefix=controlled.locator('.controlled-text').inner_text();controlled.wait_for_timeout(350)
  assert controlled.locator('.controlled-text').inner_text()==prefix and not controlled.locator('.is-streaming').count()
  report['controlled'].append({'name':status,'visualQueueFrozen':True});save()
 controlled.emulate_media(reduced_motion='reduce');controlled.reload(wait_until='networkidle')
 controlled.evaluate(r"""async()=>{const {createApp,h}=await import('/node_modules/.vite/deps/vue.js');const [{default:M},{default:S}]=await Promise.all([import('/src/components/assistant/WorkbenchMarkdown.vue'),import('/src/components/assistant/AgentStatusIndicator.vue')]);document.body.innerHTML='<div id="controlled"></div>';createApp({render:()=>h('div',[h(M,{class:'controlled-text',content:'减少动态效果直接显示完整文本',smooth:true,streaming:true}),h(S,{status:'RUNNING'})])}).mount('#controlled')}""")
 assert controlled.locator('.controlled-text').inner_text()=='减少动态效果直接显示完整文本'
 assert controlled.locator('.status-ring').evaluate('(e)=>getComputedStyle(e).animationName')=='none'
 report['controlled'].append({'name':'reduced-motion','immediateText':True,'noRotation':True});assert not errors,errors
 report['status']='passed';save();context.close();browser.close()
