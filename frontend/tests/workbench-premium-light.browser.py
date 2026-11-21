"""Real local MES visual regression; creates no business order and never mocks query data."""
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright
out=Path('docs/ui-verification/premium-light');out.mkdir(parents=True,exist_ok=True)
report={'viewports':[],'errors':[],'consoleErrors':[],'checks':[]}
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True)
    context=browser.new_context(viewport={'width':1440,'height':900},reduced_motion='reduce')
    context.add_init_script(r"""window.__wire=[];window.__renders=[];
    const nativeFetch=fetch.bind(window);window.fetch=async(...args)=>{const response=await nativeFetch(...args);
      if(String(args[0]).endsWith('/runs/stream')&&response.ok)(async()=>{const reader=response.clone().body.getReader(),decoder=new TextDecoder();let buf='';while(true){const{value,done}=await reader.read();buf+=decoder.decode(value||new Uint8Array(),{stream:!done});let n;while((n=buf.indexOf('\n'))>=0){const line=buf.slice(0,n);buf=buf.slice(n+1);if(line.trim())window.__wire.push({...JSON.parse(line),at:performance.now()});}if(done)break;}})();return response;};
    setInterval(()=>{const m=document.querySelector('.workbench-message.assistant:last-child');if(m&&window.__renders.length<15000)window.__renders.push({at:performance.now(),text:[...m.querySelectorAll('.answer-content,.answer-analysis')].map(e=>e.innerText).join('\n'),live:!!document.querySelector('[aria-label="停止回答"]')});},50);
    """)
    page=context.new_page();page.on('pageerror',lambda e:report['errors'].append(str(e)))
    page.on('console',lambda e:report['consoleErrors'].append(e.text) if e.type=='error' else None)
    page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
    page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456')
    page.locator('button[type=submit]').click();page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
    page.goto('http://localhost:5173/assistant',wait_until='networkidle')
    page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
    page.evaluate('document.fonts.ready');field=page.get_by_label('输入问题',exact=True)
    for width,height in [(1440,900),(1920,1080)]:
        page.set_viewport_size({'width':width,'height':height});page.wait_for_timeout(250)
        assert not page.locator('.inspector-role').count(), 'Empty inspector must not invent executions'
        assert round(page.locator('.workbench-composer').bounding_box()['height'])==54
        page.screenshot(path=str(out/f'home-{width}.png'))
        field.fill('请帮我分析当前生产进度，\n重点查看尚未完成的工单和相关检验记录。')
        field.focus();page.wait_for_timeout(250)
        height_now=page.locator('.workbench-composer').bounding_box()['height'];assert 100<=height_now<=220
        page.screenshot(path=str(out/f'composer-{width}.png'))
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        report['viewports'].append({'width':width,'height':height,'expandedComposer':height_now})
        field.fill('');field.blur();page.wait_for_timeout(150)
    page.set_viewport_size({'width':1440,'height':900})
    cdp=context.new_cdp_session(page);cdp.send('DOM.enable');cdp.send('CSS.enable')
    doc=cdp.send('DOM.getDocument')['root']['nodeId']
    font_info={}
    for selector in ['.workbench-welcome h1','.welcome-eyebrow','.history-heading strong']:
        node=cdp.send('DOM.querySelector',{'nodeId':doc,'selector':selector})['nodeId']
        font_info[selector]=cdp.send('CSS.getPlatformFontsForNode',{'nodeId':node})['fonts']
    report['platformFonts']=font_info
    assert any('YaHei' in f['familyName'] for f in font_info['.workbench-welcome h1']),font_info
    report['computedStyles']=page.evaluate("""()=>Object.fromEntries(['.agent-workbench','.workbench-welcome h1','.workbench-welcome > p','.session-list','.inspector-idle p','.workbench-composer textarea'].map(sel=>{const s=getComputedStyle(document.querySelector(sel));return[sel,{fontFamily:s.fontFamily,fontSize:s.fontSize,fontWeight:s.fontWeight,color:s.color,background:s.backgroundColor,lineHeight:s.lineHeight,opacity:s.opacity,transform:s.transform}]}))""")
    report['fontResources']=page.evaluate("performance.getEntriesByType('resource').filter(r=>r.name.includes('.woff2')).map(r=>({name:r.name.split('/').at(-1),bytes:r.decodedBodySize}))")
    page.get_by_label('收起会话栏',exact=True).click();assert not page.get_by_label('新助手会话',exact=True).is_visible()
    page.get_by_label('打开会话列表',exact=True).click();assert page.get_by_label('新助手会话',exact=True).is_visible()
    page.get_by_label('收起 Agent 面板',exact=True).click();assert not page.get_by_label('Agent 执行检查面板',exact=True).count()
    page.get_by_label('展开 Agent 面板',exact=True).click();report['checks'].append('Both panel toggles')
    # Attachment remains an editable local text attachment, not an uploaded knowledge document.
    page.locator('input[type=file]').set_input_files({'name':'ui-verification.txt','mimeType':'text/plain','buffer':b'UI attachment verification'})
    assert 'UI attachment verification' in field.input_value();field.fill('');report['checks'].append('Local text attachment')
    field.fill('输入法测试');field.dispatch_event('compositionstart');field.press('Enter');assert not page.get_by_label('停止回答',exact=True).count()
    field.dispatch_event('compositionend');field.press('Shift+Enter');assert '\n' in field.input_value();field.fill('')
    report['checks'].append('IME Enter and Shift+Enter')
    field.fill('帮我查询全部库存');page.get_by_label('发送问题',exact=True).click()
    assert field.input_value()==''
    start=time.monotonic();captured=False;result=None
    while time.monotonic()-start<240:
        wire=page.evaluate('window.__wire')
        if not captured and any(e['type']=='agent' and e.get('agentId')=='SUPPLY' and e.get('status')=='RUNNING' and e.get('evidence') for e in wire):
            page.screenshot(path=str(out/'agent-running-1440.png'));captured=True
        result=next((e['data'] for e in reversed(wire) if e['type']=='result'),None)
        if result and not page.get_by_label('停止回答',exact=True).count():break
        assert not any(e['type']=='error' for e in wire),[(e['type'],e.get('message')) for e in wire]
        page.wait_for_timeout(80)
    assert result and result.get('verdict')=='EVIDENCE_READY',result
    rows=result['facts']['inventory']['overview']['rows'];assert len(rows)>0
    assert page.locator('.inventory-answer tbody tr').count()==len(rows)
    assert not page.locator('.original-answer,.result-evidence,.business-result-header').count()
    assert page.locator('.inspector-role.running').count()==0
    assert page.locator('.inspector-role[open]').count()==0
    report['inventory']={'rows':len(rows),'deltas':sum(e['type']=='delta' for e in wire),'actualAgents':result['executedAgents'],'capturedRunning':captured}
    for width,height in [(1440,900),(1920,1080)]:
        page.set_viewport_size({'width':width,'height':height});page.locator('.conversation-scroll').evaluate('(e)=>e.scrollTop=0');page.wait_for_timeout(200)
        page.screenshot(path=str(out/f'inventory-{width}.png'));assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
    # Copy/CSV are still available on hover, and the exported rows use the actual result.
    page.locator('.workbench-message.assistant').last.hover()
    with page.expect_download() as download:page.get_by_label('导出完整查询 CSV').last.click()
    assert 'quantity_available' in Path(download.value.path()).read_text(encoding='utf-8-sig')
    assert page.get_by_role('button',name='复制',exact=True).last.is_visible()
    report['checks'].append('Real inventory rows, actual Agent lifecycle, streaming, copy and CSV controls')
    page.get_by_role('button',name='新对话',exact=True).click();assert page.locator('.workbench-welcome').is_visible()
    page.locator('.session-item').filter(has_text='帮我查询全部库存').locator('button').first.click()
    assert page.locator('.inventory-answer tbody tr').count()==len(rows)
    report['checks'].append('New conversation and history switch')
    page.set_viewport_size({'width':390,'height':844});field.fill('这是一段长文本，检查输入框的自动增高。\n'*30);page.wait_for_timeout(200)
    assert page.locator('.workbench-composer').bounding_box()['height']<=220
    assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
    page.screenshot(path=str(out/'narrow-390.png'));field.fill('')
    page.get_by_label('打开会话列表',exact=True).click();assert page.get_by_label('新助手会话',exact=True).is_visible()
    page.get_by_label('关闭会话列表',exact=True).last.click()
    report['checks'].append('Narrow layout, long input and mobile navigation')
    assert not report['errors'],report['errors'];assert not report['consoleErrors'],report['consoleErrors']
    report['status']='passed';report['businessOrderSubmitted']=False
    (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'status':'passed','inventory':report['inventory'],'checks':report['checks']},ensure_ascii=True),flush=True)
    browser.close()
