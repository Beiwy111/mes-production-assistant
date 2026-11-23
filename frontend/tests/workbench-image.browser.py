"""Live Edge regression. Pass a zero-box and a positive photo, set MES_TRACE_USER/PASSWORD."""
import argparse
import base64
import os
from pathlib import Path

import requests
from playwright.sync_api import expect, sync_playwright


def verify(page, zero, positive, local_yolo=False):
    prompt = "看下这个屏幕，缺陷情况怎么样"
    field = page.get_by_role("textbox", name="输入问题")
    page.context.grant_permissions(["clipboard-read", "clipboard-write"], origin="http://localhost:5173")
    pasted_text = "请检查屏幕"
    page.evaluate("s => navigator.clipboard.writeText(s)", pasted_text)
    field.press("Control+v")
    expect(field).to_have_value(pasted_text)
    field.evaluate("""el => {
        const clipboardData = new DataTransfer();
        clipboardData.items.add(new File(['工单,数量'], 'records.csv', {type:'text/csv'}));
        el.dispatchEvent(new ClipboardEvent('paste', {clipboardData, bubbles:true, cancelable:true}));
    }""")
    expect(field).to_have_value(pasted_text + "\n\n资料：records.csv\n工单,数量")
    for image, should_detect in [(zero, False), (positive, True)]:
        if should_detect:
            encoded = base64.b64encode(image.read_bytes()).decode("ascii")
            page.evaluate("""async encoded => {
                const photo = new Image();
                photo.src = 'data:image/jpeg;base64,' + encoded;
                await photo.decode();
                const canvas = document.createElement('canvas');
                canvas.width = photo.width; canvas.height = photo.height;
                canvas.getContext('2d').drawImage(photo, 0, 0);
                const png = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
                await navigator.clipboard.write([new ClipboardItem({'image/png': png})]);
            }""", encoded)
            field.fill(prompt)
            field.press("Control+v")
            expect(field).to_have_value(prompt)
        else:
            page.locator("input[type=file]").set_input_files(image)
        expect(page.get_by_alt_text("待发送的图片")).to_be_visible()
        if local_yolo:
            page.get_by_label("图片分析方式").select_option("screen")
        field.fill(prompt)
        with page.expect_response(lambda r: r.url.endswith("/assistant-v2/images/ask"), timeout=150000) as received:
            page.get_by_role("button", name="发送问题").click()
        payload = received.value.json()
        assert payload["code"] == 200, payload.get("message")
        data = payload["data"]
        count = int(data["detectionCount"])
        assert (count > 0) == should_detect
        assert "怎么验证" in data["answer"]
        if not local_yolo:
            assert all(section in data["answer"] for section in ("画面观察", "可能原因"))
        expect(page.locator(".user-bubble").last).to_have_text(prompt)
        frame = page.locator("figure.detection-frame").last
        expect(frame).to_be_visible()
        print(image.name, count, frame.locator("figcaption").inner_text(), flush=True)
    page.reload(wait_until="domcontentloaded")
    page.get_by_role("button", name=prompt, exact=True).click(timeout=30000)
    frames = page.locator("figure.detection-frame img")
    expect(frames).to_have_count(2)
    for frame in frames.all():
        expect(frame).to_be_visible()
        assert frame.evaluate("img => img.decode().then(() => img.naturalWidth > 0)")
    field.fill("划痕在哪里？")
    page.get_by_role("button", name="发送问题").click()
    expect(page.locator(".answer-content").last).to_contain_text("检测框横向", timeout=30000)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zero", type=Path, required=True)
    parser.add_argument("--positive", type=Path, required=True)
    parser.add_argument("--local-yolo", action="store_true", help="Verify clipboard and annotations using only the local detector.")
    args = parser.parse_args()
    with requests.Session() as client, sync_playwright() as pw:
        client.trust_env = False
        root = "http://127.0.0.1:8088"
        auth = client.post(root + "/auth/login", json={"username": os.environ["MES_TRACE_USER"],
                           "password": os.environ["MES_TRACE_PASSWORD"]}, timeout=15).json()["data"]
        client.headers["Authorization"] = "Bearer " + auth["token"]
        try:
            user = client.get(root + "/auth/userInfo", timeout=10).json()["data"]
            browser = pw.chromium.launch(channel="msedge")
            page = browser.new_page()
            page.goto("http://localhost:5173/login")
            page.evaluate("([t,u])=>{sessionStorage.setItem('token',t);sessionStorage.setItem('userInfo',JSON.stringify(u))}",
                          [auth["token"], user])
            page.goto("http://localhost:5173/assistant")
            verify(page, args.zero, args.positive, args.local_yolo)
            print("Both annotated frames restored and the follow-up used the latest photo.")
            browser.close()
        finally:
            client.post(root + "/auth/logout", timeout=10)
