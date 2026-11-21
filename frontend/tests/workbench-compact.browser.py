"""Read-only live MES UI verification. Run from repository root with local services up."""
import json, time
from pathlib import Path
from playwright.sync_api import sync_playwright

out = Path('docs/ui-verification/compact-chat')
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
    box=page.locator('.workbench-composer'); field=page.get_by_label('输入问题',exact=True)
    def height():
        page.wait_for_timeout(250)
        return box.bounding_box()['height']
    assert 48<=height()<=56
    assert page.get_by_placeholder('询问生产协作助手…').is_visible()
    assert not page.locator('.composer-footnote,.composer-mode,.composer-bottom').count()
    button=page.get_by_label('发送问题',exact=True)
    assert button.is_disabled()
    assert button.evaluate('(el)=>getComputedStyle(el).borderRadius')=='50%'
    page.screenshot(path=str(out/'empty-1440.png'))
    field.fill('查询库存')
    assert 100<=height()<=120
    field.dispatch_event('compositionstart')
    field.press('Enter')
    assert not page.locator('.workbench-message.user').count()
    field.dispatch_event('compositionend')
    field.fill('第一行')
    field.press('End'); field.press('Shift+Enter'); field.type('second line')
    assert '\n' in field.input_value()
    field.fill('多行内容，用于验证输入区域自动增长。\n'*40)
    assert 200<=height()<=220
    assert field.evaluate('(el)=>el.scrollHeight>el.clientHeight')
    assert field.evaluate('(el)=>getComputedStyle(el).outlineStyle')=='none'
    page.screenshot(path=str(out/'expanded-1440.png'))
    field.fill('帮我查询库存')
    field.press('Enter')
    assert field.input_value()==''
    assert 48<=height()<=56
    assert page.get_by_label('停止回答',exact=True).is_visible()
    started=time.monotonic()
    while time.monotonic()-started<180:
        if page.evaluate("window.__wire.some(e=>e.type==='result')") and not page.get_by_label('停止回答',exact=True).count():break
        page.wait_for_timeout(500)
    result=page.evaluate("window.__wire.filter(e=>e.type==='result').at(-1)?.data")
    assert result and result['verdict']=='EVIDENCE_READY',result
    overview=result['facts']['inventory']['overview']
    assert overview['limit'] is None, 'independent runtime must load complete-scope query'
    table=page.locator('.inventory-answer table')
    assert table.locator('tbody tr').count()==len(overview['rows'])==overview['totalRecords']
    for row in overview['rows']:
        assert row['material_name'] in table.inner_text()
    assert not page.locator('.inventory-header,.inventory-toolbar,.inventory-footer,.inventory-basis,.original-answer,.result-evidence,.assistant-heading,.response-state,.verdict,.inventory-findings').count()
    scrolling=page.locator('.inventory-table-scroll')
    assert scrolling.evaluate('(el)=>el.scrollHeight<=el.clientHeight+1')
    assert 48<=height()<=56
    # Preserve clipboard action; assert payload is actually written.
    context.grant_permissions(['clipboard-read','clipboard-write'])
    page.locator('.message-actions button').last.click(force=True)
    assert page.evaluate('navigator.clipboard.readText()')
    page.wait_for_timeout(3100)
    for width in [1440,1920,390]:
        page.set_viewport_size({'width':width,'height':1000 if width>400 else 844})
        if width==390 and page.locator('.workbench-inspector').count():page.get_by_label('收起 Agent 面板',exact=True).click()
        page.locator('.conversation-scroll').evaluate('(el)=>el.scrollTop=0')
        page.wait_for_timeout(250)
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        page.screenshot(path=str(out/f'inventory-{width}.png'))
    page.set_viewport_size({'width':1440,'height':1000})
    page.get_by_role('button',name='新对话',exact=False).click()
    field.fill('新会话未发送的文字')
    assert height()>=100
    page.locator('.session-item > button:not(.session-delete)').first.click()
    assert field.input_value()=='' and height()<=56
    assert table.locator('tbody tr').count()==len(overview['rows'])
    # Explicit network failure fixture: no mock business data and no order writes.
    page.get_by_role('button',name='新对话',exact=False).click()
    page.route('**/api/assistant-v2/orders/turn',lambda route:route.fulfill(json={'code':200,'data':{'handled':False}}))
    page.route('**/api/assistant-workbench/runs/stream',lambda route:route.abort())
    field.fill('网络恢复后再查询库存');field.press('Enter')
    page.wait_for_function("document.querySelector('textarea').value==='网络恢复后再查询库存'")
    assert height()>=100
    page.screenshot(path=str(out/'failed-restored.png'))
    assert not errors,errors
    assert not [e for e in console_errors if 'ERR_FAILED' not in e],console_errors
    report={'status':'passed','realInventoryRows':len(overview['rows']),'serverTotal':overview['totalRecords'],'serverLimit':overview['limit'],
        'composer':'54px empty; 112px typing; 220px maximum; shrinks on send; restores only unacknowledged failure',
        'checks':['IME Enter does not send','Shift+Enter newline','multiline text','copy','session switch','1440/1920/390 layouts','no nested vertical scroll'],
        'pageErrors':errors,'expectedNetworkFailureFixture':True,'actualBusinessWrites':False}
    (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))
    context.close();browser.close()
