"""Controlled real-component performance and recovery verification. No business/API writes."""
import json,time
from pathlib import Path
from playwright.sync_api import sync_playwright
out=Path('docs/ui-verification/stream-motion');out.mkdir(parents=True,exist_ok=True)
with sync_playwright() as p:
 browser=p.chromium.launch(headless=True);page=browser.new_page(viewport={'width':1440,'height':1000},reduced_motion='no-preference')
 page.goto('http://localhost:5173/login',wait_until='networkidle')
 page.evaluate(r"""async()=>{
 const {createApp,h,reactive,onUpdated}=await import('/node_modules/.vite/deps/vue.js');
 const [{default:M},{default:I},{default:T}]=await Promise.all([import('/src/components/assistant/WorkbenchMarkdown.vue'),import('/src/components/assistant/AgentInspector.vue'),import('/src/components/assistant/InventoryAnswer.vue')]);
 document.body.innerHTML='<main id="controlled" style="display:flex;background:white;color:#242833;padding:24px"></main>';
 const state=reactive({text:'',status:'RUNNING'}),message=reactive({status:'RUNNING',agents:[{agentId:'SUP',status:'RUNNING'}],result:{facts:{inventory:{overview:{domain:'INVENTORY',rows:[],totalRecords:0,returnedRecords:0}}}}});
 window.__state=state;window.__counts={parent:0,markdown:0,inspector:0,table:0};window.__long=[];
 new PerformanceObserver(l=>window.__long.push(...l.getEntries().map(e=>e.duration))).observe({type:'longtask'});
 const app=createApp({setup(){onUpdated(()=>window.__counts.parent++);return()=>h('div',{style:'width:780px;min-width:0'},[h(M,{class:'controlled-text',content:state.text,streaming:state.status==='RUNNING',smooth:true,stopped:['INTERRUPTED','FAILED','CANCELLED'].includes(state.status),onVnodeUpdated:()=>window.__counts.markdown++}),h(T,{message,onVnodeUpdated:()=>window.__counts.table++}),h(I,{message,onVnodeUpdated:()=>window.__counts.inspector++})])}});
 app.mount('#controlled');window.__app=app;
 }""")
 cdp=page.context.new_cdp_session(page);cdp.send('Performance.enable')
 def metrics():return {m['name']:m['value'] for m in cdp.send('Performance.getMetrics')['metrics']}
 before=metrics();begin=time.monotonic()
 page.evaluate("window.__state.text='长回复包含中文、English、👩‍💻组合字素。'.repeat(200)")
 page.wait_for_timeout(800)
 page.evaluate("window.__state.status='COMPLETED';window.__done=performance.now()")
 page.wait_for_function("!document.querySelector('.controlled-text.is-streaming')",timeout=1500)
 assert page.locator('.controlled-text').inner_text()==page.evaluate('window.__state.text')
 after=metrics();elapsed=time.monotonic()-begin;counts=page.evaluate('window.__counts')
 assert counts['markdown']>10 and counts['parent']<=3,counts
 assert counts['table']<=3 and counts['inspector']<=3,counts
 result={'controlled':True,'characters':page.evaluate('window.__state.text.length'),'wallSeconds':round(elapsed,3),'updates':counts,
 'mainThreadTaskSeconds':round(after['TaskDuration']-before['TaskDuration'],4),'scriptSeconds':round(after['ScriptDuration']-before['ScriptDuration'],4),
 'layoutSeconds':round(after['LayoutDuration']-before['LayoutDuration'],4),'longTasksMs':page.evaluate('window.__long')}
 # Real component recovery after cancelled display: same text prefix must not remain frozen.
 page.evaluate("window.__state.text='新的流式回复正在到达'.repeat(20);window.__state.status='RUNNING'")
 page.wait_for_timeout(60);page.evaluate("window.__state.status='INTERRUPTED'");page.wait_for_timeout(30)
 page.evaluate("window.__state.text+='恢复后的完整结论';window.__state.status='COMPLETED'")
 page.wait_for_function("document.querySelector('.controlled-text').innerText.endsWith('恢复后的完整结论')")
 result['recoveryDisplaysFinal']=True
 # Open Markdown constructs may span deltas; retain the same message root and sanitize final markup.
 page.evaluate("window.__state.text='';window.__state.status='RUNNING'")
 page.wait_for_timeout(30)
 markdown='# 标题\n\n这是 **加粗中文👩‍💻**。\n\n- 第一项\n- 第二项\n\n```js\nconst a = 1;\n```\n\n| 物料 | 数量 |\n| --- | --- |\n| PCB | 10 |\n\n<img src=x onerror=alert(1)>'
 rootStable=page.evaluate(r"""async text=>{const root=document.querySelector('.controlled-text');for(let i=0;i<text.length;i+=6){window.__state.text=text.slice(0,i+6);await new Promise(r=>setTimeout(r,25));if(root!==document.querySelector('.controlled-text'))return false}window.__state.status='COMPLETED';return true}""",markdown)
 page.wait_for_function("!document.querySelector('.controlled-text.is-streaming')",timeout=1500)
 assert rootStable
 assert page.locator('.controlled-text strong').inner_text()=='加粗中文👩‍💻'
 assert page.locator('.controlled-text pre code').inner_text().strip()=='const a = 1;'
 assert page.locator('.controlled-text tbody tr').count()==1
 assert not page.locator('.controlled-text img,.controlled-text script').count()
 result['markdown']={'stableMessageRoot':True,'boldEmoji':True,'listAndCodeAndTable':True,'htmlSanitized':True}
 page.evaluate("window.__app.unmount()")
 assert not page.locator('.controlled-text,.status-ring').count()
 result['unmountClean']=True;result['status']='passed'
 (out/'performance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps(result,ensure_ascii=False),flush=True);browser.close()
