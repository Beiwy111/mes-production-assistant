"""Welcome positioning and a real read-only inventory turn; no mocked business data."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

out = Path('docs/ui-verification/centered-welcome')
out.mkdir(parents=True, exist_ok=True)
report = {'errors': [], 'checks': [], 'viewports': [], 'businessWrites': False}
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    context = browser.new_context(viewport={'width': 1440, 'height': 900})
    page = context.new_page()
    page.on('pageerror', lambda e: report['errors'].append(str(e)))
    page.goto('http://localhost:5173/assistant', wait_until='domcontentloaded')
    page.get_by_placeholder('用户名', exact=True).fill('admin')
    page.get_by_placeholder('密码', exact=True).fill('123456')
    page.locator('button[type=submit]').click()
    page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')", timeout=30000)
    page.goto('http://localhost:5173/assistant', wait_until='networkidle')
    page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')", timeout=30000)
    page.evaluate('document.fonts.ready')
    field = page.get_by_label('输入问题', exact=True)
    composer = page.locator('.workbench-composer')
    assert page.locator('.brand-mark .lucide-monitor').count() == 1
    assert page.locator('.brand-mark').inner_text() == ''
    page.evaluate('window.__composer = document.querySelector(".workbench-composer textarea")')
    for width, height in [(1440, 900), (1920, 1080), (390, 844), (844, 390)]:
        page.set_viewport_size({'width': width, 'height': height})
        # On a resized desktop session the inspector stays open by design; close
        # its narrow-screen overlay to inspect the actual welcome controls.
        if width < 1200 and page.get_by_label('收起 Agent 面板', exact=True).count():
            page.get_by_label('收起 Agent 面板', exact=True).click()
        page.wait_for_timeout(300)
        main = page.locator('.workbench-main').bounding_box()
        box = composer.bounding_box()
        title = page.locator('.workbench-welcome h1').bounding_box()
        assert abs(box['x'] + box['width']/2 - main['x'] - main['width']/2) < 2
        assert abs(title['x'] + title['width']/2 - main['x'] - main['width']/2) < 2
        assert round(box['height']) == 104
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        if height >= 800:
            assert height * .3 < box['y'] + box['height']/2 < height * .7
        page.screenshot(path=str(out / f'welcome-{width}.png'))
        report['viewports'].append({'width': width, 'height': height, 'composer': box})
    page.set_viewport_size({'width': 1440, 'height': 900})
    page.get_by_label('展开 Agent 面板', exact=True).click()
    page.get_by_role('button', name='查库存 物料、仓库与可用数量').click()
    assert '当前库存' in field.input_value()
    assert field.evaluate('(el) => document.activeElement === el')
    assert page.locator('.chat-stage.is-empty').count() == 1
    field.fill('输入法测试')
    field.dispatch_event('compositionstart')
    field.press('Enter')
    assert page.locator('.workbench-message').count() == 0
    field.dispatch_event('compositionend')
    field.press('Shift+Enter')
    assert '\n' in field.input_value()
    field.fill('多行输入与长文本检查。\n' * 40)
    page.wait_for_timeout(300)
    assert round(composer.bounding_box()['height']) == 220
    field.fill('帮我查询库存')
    page.wait_for_timeout(250)
    page.screenshot(path=str(out / 'welcome-typing.png'))
    field.press('Enter')
    page.wait_for_timeout(350)
    assert field.input_value() == ''
    assert page.locator('.workbench-welcome').count() == 0
    assert page.get_by_label('快捷提问').count() == 0
    assert round(composer.bounding_box()['height']) == 54
    assert composer.bounding_box()['y'] > 780
    assert page.evaluate('window.__composer === document.querySelector(".workbench-composer textarea")')
    page.screenshot(path=str(out / 'conversation-start.png'))
    page.wait_for_function("document.querySelectorAll('.inventory-answer tbody tr').length > 0 && !document.querySelector('[aria-label=\"停止回答\"]')", timeout=240000)
    report['inventoryRows'] = page.locator('.inventory-answer tbody tr').count()
    assert page.locator('.inspector-role').count() > 0
    page.screenshot(path=str(out / 'inventory.png'))
    page.get_by_role('button', name='新对话', exact=True).click()
    page.wait_for_timeout(300)
    assert round(composer.bounding_box()['height']) == 104
    assert page.locator('.chat-stage.is-empty').count() == 1
    assert field.input_value() == ''
    page.locator('.session-item').filter(has_text='帮我查询库存').locator('button').first.click()
    page.wait_for_timeout(300)
    assert round(composer.bounding_box()['height']) == 54
    assert page.locator('.inventory-answer tbody tr').count() == report['inventoryRows']
    assert page.evaluate('window.__composer === document.querySelector(".workbench-composer textarea")')
    assert not report['errors'], report['errors']
    report['checks'] = ['Centered welcome at desktop/mobile widths', 'Monitor logo', 'Shortcut fills and focuses existing input', 'IME Enter and Shift+Enter', 'Long input bounded at 220px', 'Same mounted composer, docked at 54px after send', 'Real inventory and Agent events', 'New chat and history positioning']
    report['passed'] = True
    (out / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True), flush=True)
    browser.close()
