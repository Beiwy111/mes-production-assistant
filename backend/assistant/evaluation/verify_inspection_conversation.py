"""Live browser regression. Set MES_TRACE_USER and MES_TRACE_PASSWORD to a quality reader."""
import argparse
import os
import re
from uuid import uuid4

import requests
from playwright.sync_api import expect, sync_playwright

from replay import MES


def verify(page, questions, expected):
    business_requests = []
    intake_requests = []
    page.on("request", lambda req: intake_requests.append(req.url)
            if req.method == "POST" and req.url.endswith("/assistant-v2/orders/turn") else None)
    page.on("request", lambda req: business_requests.append(req.post_data_json)
            if req.method == "POST" and req.url.endswith("/runs/stream") else None)
    for i, question in enumerate(questions):
        previous = len(business_requests)
        field = page.get_by_role("textbox", name="输入问题")
        field.fill(question)
        send = page.get_by_role("button", name="发送问题")
        send.click()
        messages = page.locator("article.workbench-message.assistant")
        expect(messages).to_have_count(i + 1, timeout=15000)
        expect(send).to_be_visible(timeout=150000)
        msg = messages.last
        assert not msg.locator(".message-error").count(), question
        expect(msg.locator(".message-actions")).to_be_visible(timeout=30000)
        assert not intake_requests, "Inspection and weather questions must skip order intake"
        ids = {cell.strip() for cell in msg.locator("tbody td").all_text_contents() if re.fullmatch(r"QI[0-9]+",cell.strip())}
        if i == 3:
            assert len(business_requests) == previous and not ids, question
        else:
            assert len(business_requests) == previous + 1, question
            target = expected[0 if i == 0 else 2 if i == 5 else 1]
            assert ids == target, (question, ids)
            if i == 2:
                # Wait for the existing progressive Markdown renderer.
                for number in expected[1]:
                    expect(msg.locator(".answer-content, .answer-analysis").filter(has_text=number).first).to_be_visible(timeout=30000)
            if i == 4:
                assert business_requests[-1].get("previousRunId"), question
        print(f"{question}: {len(ids)} inspection rows, {len(business_requests)-previous} business runs", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:5173")
    args = parser.parse_args()
    with requests.Session() as client, sync_playwright() as pw:
        client.trust_env = False
        login = client.post(MES + "/auth/login", json={"username": os.environ["MES_TRACE_USER"],
                            "password": os.environ["MES_TRACE_PASSWORD"]}, timeout=10).json()["data"]
        client.headers["Authorization"] = "Bearer " + login["token"]
        try:
            from inspection_live import turn
            _, run = turn(client, "列出全部质检记录。", str(uuid4()))
            table = run["answer"]["facts"]["quality"]["overview"]
            assert not table["truncated"] and len(table["rows"]) == table["totalRecords"]
            all_ids = {r["inspection_no"] for r in table["rows"]}
            rejected = {r["inspection_no"] for r in table["rows"] if r["status"] in ("UNQUALIFIED","FAIL")}
            failed = {r["inspection_no"] for r in table["rows"] if r["inspection_status"]=="FAILED" and r["inspection_no"] in rejected}
            assert 0 < len(rejected) <= 20 and rejected < all_ids, "Requires mixed inspection outcomes"
            user = client.get(MES + "/auth/userInfo", timeout=10).json()["data"]
            browser = pw.chromium.launch(channel="msedge", headless=True)
            for questions in [
                ["质检怎么样", "哪些没通过", "这几单先查哪个环节", "今天天气怎么样", "回到刚才那几单"],
                ["帮我看看检验结果怎么样？", "把判定不合格的挑出来", "这些单子优先排查哪道工序？", "北京天气如何", "接着看前面那批检验单","其中流程状态标为失败的是哪几张？"],
            ]:
                page = browser.new_page()
                page.goto(args.url + "/login")
                page.evaluate("""([token,user])=>{sessionStorage.setItem('token',token);
                              sessionStorage.setItem('userInfo',JSON.stringify(user))}""",[login["token"],user])
                page.goto(args.url + "/assistant")
                verify(page, questions, (all_ids,rejected,failed))
                page.close()
            browser.close()
        finally:
            client.post(MES + "/auth/logout", timeout=8)


if __name__ == "__main__":
    main()
