"""Browser regression for the defect detail. API responses are intercepted in this browser only."""
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright


def run():
    defect = dict(nonconformingId=5, inspectionId=12, nonconformingNo="TEST-NC", inspectionNo="TEST-QI",
                  inspectionCategory="FINISHED_PRODUCT", defectType="屏闪", handleStatus="PENDING", quantity=1)
    evidence = dict(inspectionNo="TEST-QI", items=[], steps=[dict(stepId=2, name="贴附")], bindings=[], measurements=[])
    received = []
    receipts = {}

    def respond(route):
        path = urlsplit(route.request.url).path
        data = []
        if path.endswith("/nonconforming/views"):
            data = [defect]
        elif path.endswith("/sample-evidence"):
            data = evidence
        elif path.endswith("/bindings"):
            body = route.request.post_data_json
            assert body["inspectionNo"] == "TEST-QI" and body["sampleLabel"] == "TEST-SN" and body["requestId"]
            received.append(body)
            evidence["bindings"].append(dict(bindingId=6, sampleLabel="TEST-SN", sampleOrigin="MANUAL_SOURCE",
                                            sourceRef=body["sourceRef"], recordedBy=1, recordedAt="2026-01-02"))
            data = 6
        elif path.endswith("/measurements"):
            body = route.request.post_data_json
            assert body["bindingId"] == 6 and body["stepId"] == 2 and body["unit"] == "V"
            received.append(body)
            if body["requestId"] in receipts:
                data = receipts[body["requestId"]]
            else:
                data = len(evidence["measurements"]) + 1
                receipts[body["requestId"]] = data
                evidence["measurements"].append(dict(**body, measurementId=data, stepName="贴附", sampleLabel="TEST-SN",
                                                     recordedBy=1, recordedAt="2026-01-02"))
                if data == 1:  # The server saved the first reading, but the response was lost.
                    route.abort()
                    return
        route.fulfill(json=dict(code=200, data=data))

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport=dict(width=1440, height=1000))
        page.set_default_timeout(12000)
        page.add_init_script("""sessionStorage.setItem('token','browser-only-test');
            sessionStorage.setItem('userInfo',JSON.stringify({userId:1,roleKey:'qc',roleCode:'QC'}));""")
        page.route("http://localhost:5173/api/**", respond)
        page.goto("http://localhost:5173/quality/fp/defect")
        page.get_by_text("TEST-NC", exact=True).first.click()
        panel = page.locator(".evidence")
        expect(panel.get_by_text("该缺陷尚未关联具体样本。")).to_be_visible()
        panel.get_by_text("关联缺陷样本", exact=True).click()
        panel.get_by_label("原始记录上的质检单号").fill("TEST-QI")
        panel.get_by_label("原始样本标识").fill("TEST-SN")
        panel.get_by_label("缺陷与该样本的关联依据").fill("抽样单 2")
        panel.get_by_role("button", name="保存关联").click()
        expect(panel.get_by_text("尚无工序实测读数。")).to_be_visible()
        panel.get_by_text("登记工序实测", exact=True).click()
        for label, name in [("缺陷样本", "TEST-SN"), ("工单工序", "贴附")]:
            panel.get_by_label(label, exact=True).press("Enter")
            page.get_by_role("option", name=name, exact=True).click()
        panel.get_by_label("实测指标").fill("电压")
        panel.get_by_label("计量单位").fill("V")

        def enter_side(side, value, ts):
            panel.get_by_label(side + "读数").fill(value)
            time = panel.get_by_label(side + "实测时间（北京时间）")
            time.fill(ts)
            time.press("Enter")
            panel.get_by_label("测量来源").fill("仪表单 " + side)
            panel.get_by_role("button", name="保存实测").click()

        enter_side("工序前", "0", "2026-01-01 08:10:00")
        expect(panel.get_by_role("alert")).to_be_visible()
        panel.get_by_role("button", name="保存实测").click()
        panel.get_by_role("button", name="补录另一侧读数").click()
        enter_side("工序后", "1.5", "2026-01-01 08:15:00")
        expect(panel.get_by_text("补录自 #1")).to_be_visible()
        assert received[1]["beforeValue"] == "0" and received[1]["afterValue"] is None
        assert received[2] == received[1]
        assert received[3]["priorMeasurementId"] == 1 and received[3]["beforeValue"] is None
        assert received[3]["afterValue"] == "1.5" and len({r["requestId"] for r in received}) == 3
        browser.close()
    print("Defect detail manual binding, idempotent retry, zero reading, and later linked reading passed")


if __name__ == "__main__":
    run()
