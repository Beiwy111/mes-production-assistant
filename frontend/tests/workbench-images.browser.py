"""Live chat attachment checks. Uses MES_TRACE_USER / MES_TRACE_PASSWORD and real vision services."""
import io
import os
from pathlib import Path
from PIL import Image, ImageDraw
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[2]
picture = Image.new("RGB", (200, 200), "white")
ImageDraw.Draw(picture).ellipse((30, 30, 170, 170), fill="red")
png = io.BytesIO()
picture.save(png, "PNG")
with sync_playwright() as pw:
    browser = pw.chromium.launch()
    page = browser.new_page()
    page.add_init_script("""window.imageDeltas=0; const originalFetch=window.fetch.bind(window);
        window.fetch=async(...args)=>{
          const response=await originalFetch(...args);
          if(String(args[0]).endsWith('/images/continue')) {
            response.clone().text().then(text=>{
              window.imageDeltas=text.trim().split('\\n').filter(line=>JSON.parse(line).type==='delta').length
            })
          }
          return response
        }""")
    page.goto("http://localhost:5173/assistant")
    page.get_by_placeholder("用户名", exact=True).fill(os.environ["MES_TRACE_USER"])
    page.get_by_placeholder("密码", exact=True).fill(os.environ["MES_TRACE_PASSWORD"])
    page.locator("button[type=submit]").click()
    page.wait_for_function("!!sessionStorage.getItem('token') && !location.pathname.includes('login')")
    page.goto("http://localhost:5173/assistant")
    page.wait_for_function("""document.querySelector('[aria-label="刷新连接状态"]')?.textContent.includes('已连接')""")
    upload = page.get_by_label("选择图片或文本资料")
    upload.set_input_files({"name": "photo.png", "mimeType": "image/png", "buffer": png.getvalue()})
    page.get_by_label("输入问题", exact=True).fill("描述图中的形状和颜色")
    page.get_by_label("发送问题", exact=True).click()
    page.wait_for_function("""/红/.test(document.querySelector('.answer-content')?.textContent)
        && /圆/.test(document.querySelector('.answer-content')?.textContent)""", timeout=150000)
    page.get_by_label("输入问题", exact=True).fill("这张图片中，圆形是什么颜色？")
    with page.expect_response(lambda response: response.url.endswith('/images/continue')) as follow:
        page.get_by_label("发送问题", exact=True).click()
    page.wait_for_function("window.imageDeltas>0", timeout=120000)
    page.wait_for_function("""() => {
        const replies=document.querySelectorAll('.answer-content');
        return replies.length===2 && replies[1].textContent.includes('红')
    }""",timeout=120000)
    upload.set_input_files(str(root / "frontend/picture/显示器图片8.jpg"))
    page.get_by_label("图片分析方式").select_option("screen")
    page.get_by_label("发送问题", exact=True).click()
    page.get_by_text("查看 YOLO 标注图", exact=True).click(timeout=120000)
    page.wait_for_function("""document.querySelector('.detected-image img')?.naturalWidth > 0""")
    assert "YOLO" in page.locator(".answer-content").last.inner_text()
    assert not page.evaluate("Object.values(localStorage).some(x => x.includes('data:image/jpeg;base64,'))")
    print("PASS: real visual Q&A, attachment-only YOLO query, rendered annotation, no image in saved history")
    browser.close()
