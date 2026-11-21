"""Real-browser Markdown/security regression. Run with Vite available on localhost:5173.

Requires the existing Python Playwright installation; no application fixture is installed.
"""
import json
import os
from playwright.sync_api import sync_playwright

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True)
    page = browser.new_page()
    page.route("**/__markdown_browser_test__", lambda route: route.fulfill(
        status=200, content_type="text/html", body="<!doctype html><html><body></body></html>"))
    page.goto(os.environ.get("WORKBENCH_TEST_URL", "http://localhost:5173") + "/__markdown_browser_test__")
    result = page.evaluate(r"""async () => {
      const { renderWorkbenchMarkdown } = await import('/src/utils/workbenchMarkdown.ts');
      const source = [
        '# 查询结果', '', '正文 **加粗** 和 `code`。', '', '- 第一项', '- 第二项', '',
        '| 工单 | 数量 |', '| --- | --- |', '| TEST-1 | 0 |', '',
        '```js', 'const tag = "<script>";', '```', '',
        '[安全来源](https://example.com/a?q=1&x=2)', '',
        '[不安全](javascript:alert(1))', '[数据链接](data:text/html,evil)', '',
        '<script>window.__markdownXss = true</script>',
        '<img src=x onerror="window.__markdownXss=true">',
        '<svg onload="window.__markdownXss=true"><a href="javascript:alert(1)">x</a></svg>',
        '<iframe srcdoc="<script>alert(1)</script>"></iframe>',
        '<a href="https://example.com" onclick="window.__markdownXss=true">raw link</a>',
        '![remote image](https://example.com/tracker.png)',
      ].join('\n');
      const host = document.createElement('section');
      host.innerHTML = renderWorkbenchMarkdown(source);
      document.body.append(host);
      const check = (condition, message) => { if (!condition) throw new Error(message); };
      check(host.querySelector('h1')?.textContent === '查询结果', 'heading missing');
      check(host.querySelectorAll('li').length === 2, 'list missing');
      check(host.querySelectorAll('table tbody tr').length === 1, 'table missing');
      check(host.querySelector('table tbody td:last-child')?.textContent === '0', 'zero lost');
      check(host.querySelector('pre code')?.textContent.includes('<script>'), 'code was interpreted');
      check(host.querySelector('strong')?.textContent === '加粗', 'inline markdown missing');
      check(!host.querySelector('script,img,svg,iframe,object,embed,style,form,input'), 'active element survived');
      check(!host.querySelector('[onclick],[onerror],[onload],[style]'), 'active attribute survived');
      check(!window.__markdownXss, 'payload executed');
      check([...host.querySelectorAll('a')].every(a => /^https?:/.test(a.href)), 'unsafe URL survived');
      const anchor = host.querySelector('a');
      check(anchor?.target === '_blank' && anchor.rel.includes('noopener') && anchor.rel.includes('noreferrer'), 'external link not isolated');
      check(anchor.getAttribute('href') === 'https://example.com/a?q=1&x=2', 'URL changed');
      return { passed: true, checks: 12, links: host.querySelectorAll('a').length };
    }""")
    print(json.dumps(result))
    browser.close()
