"""Read-only live MES UI verification. Run from repository root with local services up."""
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

out = Path('docs/ui-verification/stream-refinement')
out.mkdir(parents=True, exist_ok=True)
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context(viewport={'width': 1440, 'height': 1000}, reduced_motion='reduce')
    context.add_init_script(r"""window.__wire=[];
    const originalFetch=window.fetch.bind(window);
    window.fetch=async (...args)=>{
      const response=await originalFetch(...args);
      if(String(args[0]).endsWith('/runs/stream') && response.ok){
        (async()=>{let buffer=''; const reader=response.clone().body.getReader(), decoder=new TextDecoder();
          while(true){const {value,done}=await reader.read();buffer+=decoder.decode(value||new Uint8Array(),{stream:!done});
            let i;while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);if(line.trim())window.__wire.push(JSON.parse(line));}if(done)break;}
        })().catch(e=>window.__wireError=String(e));
      }return response;
    };""")
    page=context.new_page(); errors=[]; console_errors=[]
    page.on('pageerror',lambda e: errors.append(str(e)))
    page.on('console',lambda entry: console_errors.append(entry.text) if entry.type == 'error' else None)
    page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
    page.get_by_placeholder('用户名',exact=True).fill('admin')
    page.get_by_placeholder('密码',exact=True).fill('123456')
    page.locator('button[type=submit]').click()
    page.wait_for_function("!!sessionStorage.getItem('token') && !location.pathname.includes('login')",timeout=30000)
    page.goto('http://localhost:5173/assistant',wait_until='networkidle')
    page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
    report={'queries':[],'pageErrors':errors,'consoleErrors':console_errors}
    for name,query in [('production','查询当前生产工单'),('order','查询当前订单列表'),('equipment','查询当前设备情况'),('quality','查询当前质量检验情况')]:
        if report['queries']:page.get_by_role('button',name='新对话',exact=False).click()
        page.evaluate('window.__wire=[]')
        page.get_by_label('输入问题',exact=True).fill(query);page.get_by_label('发送问题',exact=True).click()
        started=time.monotonic(); live_text=False; live_tables=False
        while time.monotonic()-started<180:
            wire=page.evaluate('window.__wire')
            final=next((e.get('data') for e in reversed(wire) if e['type']=='result'),None)
            if not final and page.locator('.answer-content').count() and page.locator('.answer-content').last.inner_text():
                live_text=True
                if page.locator('.business-answer table').count():live_tables=True
                page.screenshot(path=str(out/f'{name}-streaming.png'))
            if final and not page.get_by_label('停止回答',exact=True).count():break
            page.wait_for_timeout(150)
        wire=page.evaluate('window.__wire');final=next((e.get('data') for e in reversed(wire) if e['type']=='result'),None)
        assert final and final.get('verdict') in ['EVIDENCE_READY','INSUFFICIENT_EVIDENCE'], [(e['type'],e.get('message')) for e in wire]
        deltas=[e for e in wire if e['type']=='delta']; snapshots=[e for e in wire if e['type']=='snapshot']
        assert len(deltas)>1 and len(snapshots)==1, {'types':[e['type'] for e in wire],'reply':final.get('reply')}
        assert ''.join(e['text'] for e in deltas)==final['reply']
        assert final.get('presentation')=='compact_stream'
        assert wire.index(snapshots[0])<wire.index(deltas[0])<next(i for i,e in enumerate(wire) if e['type']=='result')
        overview=final['facts'][name]['overview']
        assert page.locator('.business-answer tbody tr').count()==len(overview['rows'])
        assert not page.locator('.original-answer,.result-evidence,.business-result-header,.business-checked').count()
        assert not page.locator('.answer-content table').count(), 'LLM must not duplicate structured tables'
        assert page.locator('.business-table-scroll').first.evaluate('(el)=>el.scrollHeight<=el.clientHeight+1')
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        page.locator('.conversation-scroll').evaluate('(el)=>el.scrollTop=0')
        page.screenshot(path=str(out/f'{name}-1440.png'))
        report['queries'].append({'name':name,'records':len(overview['rows']),'deltaEvents':len(deltas),'snapshotBeforeDeltas':True,'liveTextObserved':live_text,'liveTablesObserved':live_tables,'seconds':round(time.monotonic()-started,1)})
        print(json.dumps(report['queries'][-1],ensure_ascii=False),flush=True)
    assert not errors,errors
    assert not console_errors,console_errors
    report['status']='passed';report['actualBusinessWrites']=False
    (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    context.close();browser.close()
