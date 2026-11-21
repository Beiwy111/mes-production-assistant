"""Read-only live MES UI verification. Run from repository root with local services up."""
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

out = Path('docs/ui-verification/stream-live')
out.mkdir(parents=True, exist_ok=True)
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context(viewport={'width': 1440, 'height': 1000}, reduced_motion='reduce')
    context.add_init_script(r"""window.__wire=[];window.__renders=[];setInterval(()=>{const m=document.querySelector('.workbench-message.assistant:last-child');if(m && window.__renders.length<20000)window.__renders.push({at:performance.now(),text:[...m.querySelectorAll('.answer-content,.answer-analysis')].map(e=>e.innerText).join('\n'),running:!!document.querySelector('[aria-label="停止回答"]'),agentText:document.querySelector('.workbench-inspector')?.innerText||''});},50);
    const originalFetch=window.fetch.bind(window);
    window.fetch=async (...args)=>{
      const response=await originalFetch(...args);
      if(String(args[0]).endsWith('/runs/stream') && response.ok){
        (async()=>{let buffer=''; const reader=response.clone().body.getReader(), decoder=new TextDecoder();
          while(true){const {value,done}=await reader.read();buffer+=decoder.decode(value||new Uint8Array(),{stream:!done});
            let i;while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);if(line.trim())window.__wire.push({...JSON.parse(line), receivedAt:performance.now()});}if(done)break;}
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
    cases=[('inventory','查询所有库存',False),('knowledge','请根据知识库解释LCD面板的Mura缺陷和表面缺陷如何检测，给出方法依据和局限',True),('date-filter','查询2026-01-01至2026-11-15未完成工单',True),('maintenance','查询最近30天设备维修记录并汇总停机时长',True)]
    for name,query,new_session in cases:
        if new_session: page.get_by_role('button',name='新对话',exact=False).click()
        page.evaluate('window.__wire=[];window.__renders=[]')
        page.get_by_label('输入问题',exact=True).fill(query);page.get_by_label('发送问题',exact=True).click()
        started=time.monotonic()
        while time.monotonic()-started<240:
            wire=page.evaluate('window.__wire')
            result=next((e.get('data') for e in reversed(wire) if e['type']=='result'),None)
            if result and not page.get_by_label('停止回答',exact=True).count():break
            if any(e['type']=='error' for e in wire):break
            page.wait_for_timeout(50)
        assert result, [(e['type'],e.get('message')) for e in wire]
        (out/f'{name}-wire.json').write_text(json.dumps(wire,ensure_ascii=False),encoding='utf-8')
        print(name, result.get('verdict'), result.get('reply','')[:400],flush=True)
        assert result.get('verdict') in ['EVIDENCE_READY','INSUFFICIENT_EVIDENCE'],result.get('reply')
        if name=='followup':
            rows=result['facts']['inventory']['overview']['rows']
            assert all(float(row['quantity_available'])<=0 for row in rows)
            assert result['facts']['inventory']['overview']['filter']=='NO_AVAILABLE'
        if name=='knowledge':
            assert result['facts']=={}
            assert 'KNOWLEDGE' in result['plan']['intents']
            assert any(bundle.get('hits') for bundle in result['knowledgeEvidence'])
            assert page.locator('.knowledge-sources a').count()>0
        renders=page.evaluate('window.__renders')
        live_texts=list(dict.fromkeys(r['text'] for r in renders if r['running'] and r['text'].strip()))
        assert any(e['type']=='agent' and e.get('status')=='RUNNING' for e in wire)
        if name=='knowledge':
            assert len(live_texts)>=2, 'Answer never visibly changed while streaming'
            delta_times={round(e['receivedAt']/100) for e in wire if e['type']=='delta'}
            assert len(delta_times)>=2, 'All model tokens still arrived in one batch'
        if name=='date-filter':
            scope=result['facts']['production']['overview']
            assert scope['startInclusive']=='2026-01-01 00:00:00'
            assert scope['endExclusive']=='2026-11-16 00:00:00'
            assert scope['filter']=='UNFINISHED'
        if name=='maintenance':
            assert 'maintenanceSummary' in result['facts']['maintenance']
            assert any(s.get('label')=='查询设备维修与登记停机时长' for e in wire if e['type']=='agent' for s in e.get('steps',[]))
        if name=='inventory':
            with page.expect_download() as download:
                page.get_by_label('导出完整查询 CSV').last.click()
            path=download.value.path()
            assert 'quantity_available' in Path(path).read_text(encoding='utf-8-sig')
        assert not page.locator('.original-answer,.result-evidence,.business-result-header').count()
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        page.screenshot(path=str(out/f'{name}-1440.png'))
        report['queries'].append({'name':name,'verdict':result.get('verdict'),'intents':result['plan']['intents'],'executedAgents':result.get('executedAgents'),'deltas':sum(e['type']=='delta' for e in wire),'knowledgeHits':sum(len(b.get('hits',[])) for b in result.get('knowledgeEvidence',[])),'seconds':round(time.monotonic()-started,1),'visibleStreamingStates':len(live_texts),'deltaArrivalTimesMs':[round(e['receivedAt']-wire[0]['receivedAt']) for e in wire if e['type']=='delta']})
        (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    assert not errors,errors
    assert not console_errors,console_errors
    report['status']='passed';report['businessWrites']=False
    (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    context.close();browser.close()
