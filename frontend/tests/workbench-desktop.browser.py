"""Read-only live MES UI verification. Run from repository root with local services up."""
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

out = Path('docs/ui-verification/light-inspector')
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
    page.screenshot(path=str(out/'welcome-1440.png'))
    report={'pageErrors':errors,'consoleErrors':console_errors,'queries':[], 'viewports':[]}
    for name,query in [('production','查询当前生产工单，列出工单编号、产品名称、计划数量、合格数量和运行状态。'),('inventory','帮我查一下当前库存，列出需要关注的物料。')]:
        if report['queries']: page.get_by_role('button',name='新对话',exact=False).click()
        page.evaluate('window.__wire=[]')
        page.get_by_label('输入问题',exact=True).fill(query)
        page.get_by_label('发送问题',exact=True).click()
        started=time.monotonic(); captured=False; captured_detail=False; active_agents=set()
        while time.monotonic()-started < 180:
            active_agents.update(page.locator('.inspector-role.running[open]').evaluate_all('(els)=>els.map(el=>el.dataset.agent)'))
            if not captured and page.locator('.inspector-role.running[open]').count():
                page.screenshot(path=str(out/f'{name}-running-1440.png'));captured=True
            if not captured_detail and page.locator('.inspector-role.running[open] .query-result').count():
                page.screenshot(path=str(out/f'{name}-live-evidence-1440.png'));captured_detail=True
            if page.evaluate("window.__wire.some(e=>e.type==='result')") and not page.get_by_label('停止回答',exact=True).count(): break
            page.wait_for_timeout(500)
        privacy_fallback=page.locator('.message-error').filter(has_text='订单信息识别暂不可用').count()>0
        wire=page.evaluate('window.__wire')
        results=[e['data'] for e in wire if e['type']=='result']
        assert results, f'{name}: no final result'
        result=results[-1]
        assert result.get('verdict')=='EVIDENCE_READY',f"{name}: {result.get('verdict')}"
        table=page.locator('.business-answer table').first if name=='production' else page.locator('.inventory-answer table').first
        assert table.count(), f'{name}: no real table'
        if name=='production':
            headers=table.locator('th').all_text_contents()
            for expected in ['工单编号','产品名称','计划数量','合格数量','运行状态']: assert expected in headers,(expected,headers)
            rows=result['facts']['production']['overview']['rows']
            assert table.locator('tbody tr').count()==len(rows)
            for row in rows: assert str(row['work_order_no']) in table.inner_text()
        assert not page.locator('.agent-flow').last.evaluate('(el)=>el.open')
        assert page.locator('.inspector-role[open]').count()==0, 'completed roles must collapse'
        page.get_by_role('tab',name='调用',exact=False).click()
        assert page.locator('.call-list > li').count()>0
        page.get_by_role('tab',name='任务',exact=True).click()
        role=page.locator(f'.inspector-role[data-agent={"PRODUCTION" if name=="production" else "SUPPLY"}]')
        role.locator(':scope > summary').click()
        assert role.locator('.query-result').first.is_visible()
        assert '返回' in role.inner_text()
        table.scroll_into_view_if_needed()
        for width,height in [(1440,1000),(1920,1080)]:
            page.set_viewport_size({'width':width,'height':height});page.wait_for_timeout(100)
            assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
            metrics=page.evaluate("""() => {const q=s=>document.querySelector(s);return {sidebar:q('.workbench-history').getBoundingClientRect().width,inspector:q('.workbench-inspector').getBoundingClientRect().width,canvas:getComputedStyle(q('.agent-workbench')).backgroundColor,main:getComputedStyle(q('.workbench-main')).backgroundColor,composer:getComputedStyle(q('.workbench-composer')).backgroundColor,title:getComputedStyle(q('.assistant-header-title')).color}}""")
            assert metrics['sidebar']==240 and metrics['inspector']==300,metrics
            assert metrics['main']=='rgb(255, 255, 255)' and metrics['composer']=='rgb(245, 245, 245)',metrics
            page.screenshot(path=str(out/f'{name}-{width}.png'))
            report['viewports'].append({'name':name,'width':width,**metrics})
        page.get_by_label('收起 Agent 面板',exact=True).click()
        assert not page.locator('.workbench-inspector').count()
        page.screenshot(path=str(out/f'{name}-inspector-collapsed.png'))
        page.get_by_label('展开 Agent 面板',exact=True).click()
        page.set_viewport_size({'width':1440,'height':1000})
        workflow=next((event for event in reversed(wire) if event['type']=='workflow' and event.get('plan')), {})
        page.locator('.agent-flow').last.locator(':scope > summary').click()
        page.locator('.agent-flow').last.scroll_into_view_if_needed()
        page.screenshot(path=str(out/f'{name}-execution-details.png'))
        page.locator('.agent-flow').last.locator(':scope > summary').click()
        report['queries'].append({'name':name,'seconds':round(time.monotonic()-started,1),'agents':workflow.get('plan',{}).get('requiredAgents'), 'tableRows':table.locator('tbody tr').count(),'streamEvents':len(wire),'capturedRunning':captured,'autoExpandedAgents':sorted(active_agents),'actualLiveEvidenceVisible':captured_detail,'intakeUnavailablePrivacyFallback':privacy_fallback})
        print(json.dumps(report['queries'][-1],ensure_ascii=False),flush=True)
    page.reload(wait_until='networkidle')
    page.locator('.session-item > button:not(.session-delete)').first.click()
    if report['queries'][-1]['intakeUnavailablePrivacyFallback']:
        page.get_by_text('订单会话已保存，请恢复草稿查看当前状态。',exact=True).wait_for()
        assert not page.locator('.inventory-answer').count(), 'unclassified intake history must retain existing privacy redaction'
        assert page.locator('.inspector-role[open]').count()==0
        report['history']='existing privacy redaction verified after intake-unavailable fallback'
    else:
        page.locator('.inventory-answer').wait_for()
        assert not page.locator('.agent-flow').last.evaluate('(el)=>el.open')
        report['history']='full inventory evidence restored and collapsed'
    page.set_viewport_size({'width':390,'height':844})
    page.get_by_label('收起 Agent 面板',exact=True).click()
    assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
    page.screenshot(path=str(out/'inventory-mobile.png'))
    assert not errors,errors
    assert not console_errors,console_errors
    report['mobile']='passed';report['actualBusinessWrites']=False
    (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'status':'passed','pageErrors':errors,'realQueries':len(report['queries'])}),flush=True)
    context.close();browser.close()
