"""Run real Vue, order-intent handoff, supervisor and model through a local logged-in workbench."""
import argparse
import json
from pathlib import Path
from runpy import run_path

from playwright.sync_api import expect, sync_playwright


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    chat = run_path(str(Path(__file__).with_name('workbench-inspection-routing.browser.py')))
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={'width': 1440, 'height': 1000})
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.add_init_script(chat['OBSERVE'])
        chat['open_workbench'](page, 'http://localhost:5173')
        page.get_by_role('button', name='新对话', exact=True).click()
        first, conversation, first_receipt = chat['ask'](
            page, '把这句话写成一条简短的英文留言，只给英文：我的紫色雨伞在图书馆。', 'SUP', set())
        assert 'purple' in first['reply'].lower() and 'library' in first['reply'].lower()
        second, same_conversation, second_receipt = chat['ask'](
            page, '现在把上面那条留言改成一个英文问句，保留颜色和地点。', 'SUP', set())
        assert same_conversation == conversation
        reply = second['reply'].strip()
        assert 'purple' in reply.lower() and 'library' in reply.lower() and '?' in reply
        for answer, receipt in ((first, first_receipt), (second, second_receipt)):
            assert answer['intents'] == ['GENERAL'] and not answer.get('facts') and not receipt['tools']
        last = page.locator('article.workbench-message.assistant').last
        expect(last.locator('.answer-content')).to_contain_text(reply, timeout=15000)
        expect(page.locator('.order-draft')).to_have_count(0)
        assert not errors, errors
        page.screenshot(path=str(args.out/'open-conversation.png'))
        browser.close()
    print(json.dumps({'first':first_receipt, 'rewrite':second_receipt}, ensure_ascii=False))


if __name__ == '__main__':
    main()
