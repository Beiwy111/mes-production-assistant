"""Exercise the production build on an intercepted origin, with no live MES connection."""
import json
import mimetypes
from pathlib import Path
from urllib.parse import unquote, urlparse
from playwright.sync_api import sync_playwright, expect

dist = (Path(__file__).resolve().parents[1] / "dist").resolve()
origin = "http://assistant.test"
reply = ("## 检验结论\n\n**装配工序**需要复核。\n\n- 核对原始记录\n\n"
         "| 项目 | 数量 |\n| --- | --- |\n| 待检 | 0 |\n\n---\n\n"
         "```text\n<原始标记>\n```\n\n<img src=x onerror=alert(1)>")
rows = [{"record_id": index, "inspection_no": f"QI-{index}", "额外字段": f"字段-{index}"}
        for index in range(1, 166)]
quality = {"overview": {"domain": "QUALITY", "rows": rows, "totalRecords": 165, "returnedRecords": 165},
           "qualityStatistics": {"inspectionCount": 165, "pendingCount": 0,
                                 "defectDistribution": [{"defect_type": "划痕", "record_count": 2},
                                                        {"defect_type": "漏光", "record_count": 1}]},
           "qualityTrend": [{"inspection_day": f"2026-03-0{day}", "material_id": 1,
                             "material_name": "面板", "unit": "件", "inspection_type": "首检",
                             "sample_fail_percent": value} for day, value in [(1, "2"), (2, "0")]]}


def respond(route):
    path = unquote(urlparse(route.request.url).path)
    if path == "/api/assistant-workbench/runs/stream":
        question = route.request.post_data_json["text"]
        text = reply if "分析" in question else "已返回检验清单。"
        data = {"runRef": "run-1", "presentation": "compact_stream", "verdict": "EVIDENCE_READY",
                "queryMode": "NATURAL", "facts": {"quality": quality}, "userQuery": question}
        events = [{"type": "run", "runId": "run-1"},
                  {"type": "snapshot", "runId": "run-1", "seq": 1, "data": data}]
        for index in range(0, len(text), 6):
            events.append({"type": "delta", "runId": "run-1", "seq": 2 + index, "text": text[index:index + 6]})
        events.append({"type": "result", "runId": "run-1", "data": {**data, "reply": text}})
        route.fulfill(content_type="application/x-ndjson", body="\n".join(json.dumps(e) for e in events))
    elif path == "/api/assistant-workbench/status":
        route.fulfill(json={"configured": True, "naturalLanguage": True})
    elif path.startswith("/api/"):
        route.fulfill(json={"code": 200, "data": []})
    else:
        asset = (dist / ("index.html" if path == "/assistant" else path.lstrip("/"))).resolve()
        if asset.is_relative_to(dist) and asset.is_file():
            route.fulfill(path=asset, content_type=mimetypes.guess_type(asset)[0] or "application/octet-stream")
        else:
            route.fulfill(status=404, body="")


with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(viewport={"width": 1120, "height": 840}, reduced_motion="no-preference")
    errors = []
    page.on("pageerror", lambda err: errors.append(str(err)))
    page.route(origin + "/**", respond)
    page.add_init_script(r"""sessionStorage.setItem('userInfo', JSON.stringify({userId: 8,roleCode:'ADMIN',roleKey:'admin'}))
        sessionStorage.setItem('token', 'test-only')
        const read = window.fetch.bind(window)
        window.fetch = async (...args) => {
          const response = await read(...args)
          if (!String(args[0]).endsWith('/runs/stream')) return response
          const frames = (await response.text()).split('\n'), encoder = new TextEncoder()
          return new Response(new ReadableStream({async pull(controller) {
            if (!frames.length) {controller.close(); return}
            await new Promise(done => setTimeout(done, 52))
            controller.enqueue(encoder.encode(frames.shift() + '\n'))
          }}), {headers: response.headers})
        }""")
    page.goto(origin + "/assistant")
    for query in ["分析质量趋势和缺陷分布", "列出检验单"]:
        page.get_by_label("输入问题", exact=True).fill(query)
        page.get_by_label("发送问题", exact=True).click()
        message = page.locator(".workbench-message.assistant").last
        if "分析" in query:
            expect(message.locator(".answer-content .is-streaming")).to_be_visible()
            expect(message.locator(".answer-content code")).to_have_text("<原始标记>")
            expect(message.locator(".answer-content strong")).to_have_text("装配工序")
            for selector in ["h2", "li", "table", "hr", "pre code"]:
                expect(message.locator(".answer-content " + selector)).to_have_count(1)
            expect(message.locator(".answer-content img")).to_have_count(0)
            expect(message.locator(".chart-canvas canvas")).to_have_count(2)
            expect(message.locator(".answer-data")).not_to_have_attribute("open", "")
            expect(message.get_by_text("QI-165", exact=True)).to_have_count(0)
            message.locator(".answer-data > summary").click()
            expect(message.get_by_text("QI-165", exact=True)).to_be_visible()
            expect(message.get_by_text("字段-165", exact=True)).to_be_visible()
            with page.expect_download() as download:
                message.get_by_label("导出完整查询 CSV").click()
            content = Path(download.value.path()).read_text(encoding="utf-8-sig")
            assert "字段-165" in content and "quality.qualityStatistics.defectDistribution" in content
        else:
            expect(message.get_by_text("QI-165", exact=True)).to_be_visible()
            expect(message.locator(".chart-canvas,.answer-data")).to_have_count(0)
    assert not errors, errors
    print("Passed: streamed Markdown, real chart rendering, evidence disclosure, direct list, complete CSV.")
    browser.close()
