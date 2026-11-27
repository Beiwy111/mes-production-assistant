"""Live planning previews using seeded roles; never approve a business operation."""
import json
import os
from pathlib import Path
import requests
from playwright.sync_api import sync_playwright

out = Path('docs/ui-verification/planning-migration')
out.mkdir(parents=True, exist_ok=True)
report = {'cases': [], 'pageErrors': [], 'businessWrites': False}
password = os.environ['MES_TEST_LOGIN_PASSWORD']

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    try:
        for username, role in [('admin', 'ADMIN'), ('li_planner', 'PLANNER'), ('li_manager', 'MANAGER')]:
            login = requests.post('http://127.0.0.1:8088/auth/login', json={'username': username, 'password': password}, timeout=20).json()
            assert login.get('code') == 200, f'Existing {role} fixture unavailable'
            token = login['data']['token']
            identity = requests.get('http://127.0.0.1:8088/auth/userInfo', headers={'Authorization': 'Bearer ' + token}, timeout=20).json()['data']
            assert identity['roleCode'] == role
            context = browser.new_context(viewport={'width': 1440, 'height': 1000}, reduced_motion='reduce')
            context.add_init_script('sessionStorage.setItem("token",' + json.dumps(token) + ');sessionStorage.setItem("userInfo",' + json.dumps(json.dumps(identity, ensure_ascii=False)) + ');')
            page = context.new_page()
            page.on('pageerror', lambda e: report['pageErrors'].append(str(e)))
            decisions = []
            def block_decision(route):
                decisions.append(route.request.url)
                route.abort()
            page.route('**/operations/*/decision', block_decision)
            page.goto('http://localhost:5173/assistant', wait_until='networkidle')
            page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')", timeout=30000)
            prompts = ['你能帮我去排产吗', '帮我去制定生产计划呢'] if role != 'MANAGER' else ['你能帮我去排产吗']
            for index, prompt in enumerate(prompts):
                page.get_by_role('button', name='新对话', exact=True).click()
                page.get_by_label('输入问题', exact=True).fill(prompt)
                with page.expect_response('**/operations/preview', timeout=60000) as response:
                    page.get_by_label('发送问题', exact=True).click()
                flow = response.value.json()
                if isinstance(flow.get('data'), dict):
                    flow = flow['data']
                assert flow['status'] == 'NEEDS_INPUT', flow
                card = page.locator('.production-card.supplement').last
                card.wait_for()
                if 'planningTask' in flow.get('options', {}):
                    card.locator('select').select_option('PLAN')
                    with page.expect_response('**/operations/preview', timeout=60000) as response:
                        card.get_by_role('button', name='继续预览').click()
                    flow = response.value.json()
                    if isinstance(flow.get('data'), dict):
                        flow = flow['data']
                expected = 'confirmSmartDispatch' if role == 'MANAGER' else 'saveProductionPlan'
                assert flow['actionCode'] == expected, flow
                target = 'planId' if role == 'MANAGER' else 'orderId'
                options = flow.get('options', {}).get(target, [])
                assert options, f'No eligible {target} in live fixture'
                card = page.locator('.production-card.supplement').last
                assert card.locator('select option').count() > 1
                page.screenshot(path=str(out / f'{role.lower()}-{index}-choices.png'))
                entry = {'role': role, 'prompt': prompt, 'actionCode': expected, 'optionCount': len(options)}
                report['cases'].append(entry)
                # One full read-only preview per role is sufficient; both exact phrases are exercised above.
                if index:
                    continue
                card.locator('select').first.select_option(str(options[0]['value']))
                for date in card.locator('input[type=date]').all():
                    label = date.locator('..').inner_text()
                    date.fill('2026-12-31' if '结束' in label or '截止' in label else '2026-12-01')
                with page.expect_response('**/operations/preview', timeout=90000) as response:
                    card.get_by_role('button', name='继续预览').click()
                flow = response.value.json()
                if isinstance(flow.get('data'), dict):
                    flow = flow['data']
                for _ in range(4):
                    fields = flow.get('missingFields', [])
                    if flow['status'] != 'NEEDS_INPUT' or not fields or any(field['name'] not in ['planStart', 'planEnd'] for field in fields):
                        break
                    card = page.locator('.production-card.supplement').last
                    for field in fields:
                        card.get_by_label(field['label'], exact=True).fill('2026-12-31' if field['name'] == 'planEnd' else '2026-12-01')
                    with page.expect_response('**/operations/preview', timeout=90000) as response:
                        card.get_by_role('button', name='继续预览').click()
                    flow = response.value.json()
                    if isinstance(flow.get('data'), dict):
                        flow = flow['data']
                if role != 'MANAGER':
                    assert flow['status'] == 'NEEDS_INPUT' and len(flow.get('schemes', [])) == 3, flow
                    card = page.locator('.production-card.supplement').last
                    assert card.locator('.scheme').count() == 3
                    assert '返回 3 种排产方案' in page.get_by_label('业务服务记录', exact=True).inner_text()
                    page.screenshot(path=str(out / f'{role.lower()}-schemes.png'))
                    available = card.locator('input[type=radio]:enabled')
                    assert available.count(), 'Fixture has no viable scheduling strategy'
                    available.first.check()
                    with page.expect_response('**/operations/preview', timeout=90000) as response:
                        card.get_by_role('button', name='继续预览').click()
                    flow = response.value.json()
                    if isinstance(flow.get('data'), dict):
                        flow = flow['data']
                assert flow['status'] == 'PENDING', flow
                assert flow['confirmation']['params'].get('recommendations' if role == 'MANAGER' else 'schedules')
                page.get_by_role('button', name='确认执行', exact=True).wait_for()
                assert '未写入业务数据' in page.get_by_label('业务服务记录', exact=True).inner_text()
                assert '当前账号可办理订单' not in page.locator('body').inner_text()
                assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                page.screenshot(path=str(out / f'{role.lower()}-preview.png'))
                entry['previewReady'] = True
            assert not decisions, decisions
            context.close()
        assert not report['pageErrors'], report['pageErrors']
        report['passed'] = True
    finally:
        browser.close()
        (out / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'passed': report.get('passed', False), 'cases': len(report['cases'])}))
