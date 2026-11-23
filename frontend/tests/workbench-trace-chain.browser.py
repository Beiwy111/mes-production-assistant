"""Read-only browser acceptance for the seeded trace pilot. Credentials are local only."""
import os, re, json
from playwright.sync_api import sync_playwright

QUESTIONS = ["WO202607003这个订单现在派工了吗", "哪道工序没做完", "同批有没有质检异常"]
with sync_playwright() as pw:
    browser = pw.chromium.launch(headless=True)
    page = browser.new_page()
    page.add_init_script("""window.events=[];const orig=fetch.bind(window);window.fetch=async(...args)=>{
      const r=await orig(...args);if(String(args[0]).endsWith('/runs/stream')&&r.ok)(async()=>{
        const reader=r.clone().body.getReader(),dec=new TextDecoder();let buf='';
        while(true){const {done,value}=await reader.read();buf+=dec.decode(value||new Uint8Array(),{stream:!done});
        let n;while((n=buf.indexOf('\\n'))>=0){const line=buf.slice(0,n).trim();buf=buf.slice(n+1);if(line.startsWith('data:'))window.events.push(JSON.parse(line.slice(5)))}if(done)break}
      })();return r}""")
    page.goto('http://localhost:5173/assistant')
    page.get_by_placeholder('用户名',exact=True).fill(os.environ['MES_TRACE_USER'])
    page.get_by_placeholder('密码',exact=True).fill(os.environ['MES_TRACE_PASSWORD'])
    page.locator('button[type=submit]').click()
    page.wait_for_function("!!sessionStorage.getItem('token') && !location.pathname.includes('login')")
    page.goto('http://localhost:5173/assistant')
    page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')")
    for ix,q in enumerate(QUESTIONS):
        page.evaluate('window.events=[]')
        page.get_by_label('输入问题',exact=True).fill(q)
        page.get_by_label('发送问题',exact=True).click()
        page.wait_for_function("window.events.some(e=>e.type==='task_completed'||e.type==='task_failed')",timeout=180000)
        d=page.evaluate("window.events.findLast(e=>e.type==='message_completed')?.payload.legacy.data")
        counts=page.evaluate("Object.fromEntries(['agent_started','message_delta'].map(t=>[t,window.events.filter(e=>e.type===t).length]))")
        assert d and d['scope']['workOrderNo']=='WO202607003' and d['verdict']=='EVIDENCE_READY' and all(counts.values()),(ix,counts)
        if ix==0: assert len(d['facts']['production']['dispatchRows'])==8
        if ix==1:
            steps=d['facts']['production']['stepCoverage']
            assert sum(s['trace_status']=='有未完派工' for s in steps)==6
            page.get_by_text('实际派工与当前路线不一致',exact=True).first.wait_for()
        if ix==2:
            f=d['facts']['quality'];assert f['businessGraph']['status']=='VERIFIED' and len(f['businessGraph']['paths'])==1
            assert [r['inspection_no'] for r in f['overview']['rows']]==['QI202607503']
            assert any(b.get('hits') for b in d['knowledgeEvidence'])
            page.get_by_text('QI202607503',exact=True).first.wait_for()
        print(json.dumps({'turn':ix+1,**counts}),flush=True)
    browser.close()
