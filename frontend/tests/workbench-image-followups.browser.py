"""Live browser regression. Requires the local demo work order, real YOLO and an authorized account."""
import os
from pathlib import Path
from PIL import Image
import requests
from playwright.sync_api import sync_playwright

photo = Path(__file__).resolve().parents[2] / 'frontend/picture/显示器图片5.jpg'
with photo.open('rb') as file:
    truth = requests.post('http://127.0.0.1:8000/predict/json?include_image=false',
                          files={'file': file}, timeout=30).json()
assert truth['count'] and truth['count'] == len(truth['detections'])
with Image.open(photo) as image:
    width, height = image.size  # this fixture is smaller than the detector's resize threshold
x1, y1, x2, y2 = truth['detections'][0]['box']
expected = f'{x1/width:.1%}–{x2/width:.1%}、纵向 {y1/height:.1%}–{y2/height:.1%}'

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
    uploads, reads = [], []
    page.on('request', lambda r: uploads.append(1) if r.url.endswith('/images/ask') else None)
    page.on('request', lambda r: reads.append(r.post_data_json) if r.url.endswith('/runs/stream') else None)
    page.goto('http://localhost:5173/assistant')
    page.get_by_placeholder('用户名', exact=True).fill(os.environ['MES_TRACE_USER'])
    page.get_by_placeholder('密码', exact=True).fill(os.environ['MES_TRACE_PASSWORD'])
    page.locator('button[type=submit]').click()
    page.wait_for_function("!!sessionStorage.getItem('token') && !location.pathname.includes('login')")
    page.goto('http://localhost:5173/assistant')
    page.wait_for_function("""document.querySelector('[aria-label="刷新连接状态"]')?.textContent.includes('已连接')""")

    def ask(text):
        page.get_by_label('输入问题', exact=True).fill(text)
        page.get_by_label('发送问题', exact=True).click()

    ask('工单 WO202611001 派给谁了？')
    page.get_by_text('WO202611001', exact=True).first.wait_for(state='attached',timeout=180000)
    page.get_by_label('停止回答', exact=True).wait_for(state='hidden',timeout=180000)
    page.get_by_label('选择图片或文本资料').set_input_files(str(photo))
    page.get_by_label('图片分析方式').select_option('screen')
    page.get_by_label('发送问题', exact=True).click()
    page.get_by_text('查看 YOLO 标注图', exact=True).wait_for(timeout=90000)
    with page.expect_response(lambda r: r.url.endswith('/images/continue')) as location:
        ask('划痕在哪个位置？')
    assert location.value.headers['content-type'].startswith('application/x-ndjson')
    page.get_by_text(expected, exact=False).first.wait_for(timeout=30000)
    page.wait_for_function("window.imageDeltas>=2", timeout=30000)
    ask('这和刚才那张工单的质检记录一致吗？')
    page.get_by_text('QI202611001', exact=True).first.wait_for(state='attached',timeout=180000)
    page.get_by_text('现有类别和抽检数量不足以确认', exact=False).first.wait_for(timeout=30000)
    assert len(uploads) == 1 and len(reads) == 2 and reads[1].get('previousRunId')
    denied = page.evaluate(r"""async () => {
        const history=Object.keys(localStorage).filter(k=>/^mes-agent-workbench-v1-\d+$/.test(k))
            .flatMap(k=>JSON.parse(localStorage.getItem(k)));
        const imageRef=history.flatMap(s=>s.messages).find(m=>m.imageRef).imageRef;
        const r=await fetch('/api/assistant-v2/images/continue',{method:'POST',
            headers:{Authorization:'Bearer '+sessionStorage.getItem('token'),'Content-Type':'application/json'},
            body:JSON.stringify({imageRef,sessionId:crypto.randomUUID(),action:'location',question:'划痕在哪？'})});
        return (await r.json()).code
    }""")
    assert denied == 404
    print('PASS: one upload, real YOLO bounds', expected,
          'scoped comparison WO202611001 / QI202611001', flush=True)
    browser.close()
