"""Exercise the document workspace in Vite with intercepted, in-memory job responses."""
import os
from urllib.parse import urlsplit
from playwright.sync_api import expect, sync_playwright


base = os.environ.get("WORKBENCH_TEST_URL", "http://localhost:5173")
source = "/src/components/assistant/knowledge/ingestion/KnowledgeIngestionWorkspace.vue"
stages = ["upload", "parse", "chunk", "embedding", "vector", "entities", "relations", "graph", "complete"]
jobs = [
    dict(id="guide", filename="质检指南.pdf", status="completed", createdAt="2026-06-01T01:04:00Z",
         stages=[dict(key=key, status="success", durationMs=200) for key in stages],
         counts=dict(chunks=2, entities=0, relations=0), metadata=dict(ocrSections=1, sections=2, characters=25),
         chunks=[dict(id="a", location="page:1", text="检验流程"), dict(id="b", location="page:2", text="压力传感器")]),
    dict(id="failed", filename="旧版.xls", status="failed", error="文件头无效",
         stages=[dict(key="parse", status="failed", durationMs=0, error="文件头无效"),
                 dict(key="embedding", status="pending", durationMs=0)]),
]
mutations = []


def api(route):
    assert route.request.headers.get("authorization") == "Bearer fixture"
    path = urlsplit(route.request.url).path.split("/ingestion/jobs")[1]
    method = route.request.method
    if method == "GET":
        data = next(j for j in jobs if j["id"] == path[1:]) if path else dict(jobs=jobs)
    elif method == "POST" and path == "":
        assert route.request.headers.get("content-type").startswith("multipart/form-data;")
        assert b'name="file"; filename="sample.csv"' in route.request.post_data_buffer
        document = dict(id="upload", filename="sample.csv", status="queued", stages=[])
        jobs.insert(0, document)
        mutations.append("upload")
        data = document
    elif method == "DELETE" and path == "/guide/document":
        mutations.append("delete")
        jobs.pop(0)
        data = dict(deleted=True)
    elif method == "POST" and path == "/failed/retry":
        mutations.append("retry")
        jobs[0].update(status="queued", stages=[], error=None)
        data = jobs[0]
    elif method == "DELETE" and path == "/upload":
        jobs.remove(next(j for j in jobs if j["id"] == "upload"))
        mutations.append("delete-pending")
        data = dict(deleted=True)
    else:
        raise AssertionError((method, path))
    route.fulfill(json=dict(code=200, data=data))


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(channel=os.environ.get("PLAYWRIGHT_BROWSER_CHANNEL", "chrome"))
    page = browser.new_page(viewport=dict(width=1380, height=820))
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.route("**/__ingestion_fixture", lambda r: r.fulfill(content_type="text/html", body='<div id="app" style="height:97vh;display:flex"></div>'))
    page.route("**/api/**", api)
    page.goto(base + "/__ingestion_fixture")
    page.evaluate("""async source => {
      const compiled = await (await fetch(source)).text()
      const url = compiled.split('"').find(value => value.includes('/deps/vue.js'))
      const {createApp, h} = await import(url)
      const workspace = (await import(source)).default
      createApp({render: () => h(workspace, {token:'fixture', visible:true,
        onGraph:(...args) => {window.graphArgs = args}})}).mount('#app')
    }""", source)
    table = page.get_by_role("table", name="入库文档")
    expect(table).to_contain_text("所属知识库")
    for value in ["PDF", "XLS", "已向量化", "已抽取", "私有知识库"]:
        expect(table).to_contain_text(value)
    expect(page.get_by_label("入库阶段")).to_contain_text("OCR 识别 1 段")
    page.get_by_role("button", name="文档片段", exact=False).click()
    page.get_by_role("searchbox", name="搜索片段").fill("传感器")
    expect(page.get_by_text("第 2 页", exact=True).first).to_be_visible()
    page.get_by_role("button", name="在图谱中定位片段").click()
    assert page.evaluate("window.graphArgs") == ["guide", "b"]
    page.get_by_role("button", name="重新解析", exact=True).click()
    page.get_by_role("button", name="取消", exact=True).click()
    assert mutations == []
    page.get_by_role("button", name="删除", exact=True).click()
    page.get_by_role("button", name="确认删除", exact=True).click()
    expect(table).not_to_contain_text("质检指南.pdf")
    expect(page.get_by_label("入库阶段")).to_contain_text("文件头无效")
    page.get_by_role("button", name="重试", exact=True).click()
    page.get_by_role("button", name="确认重试", exact=True).click()
    expect(table).to_contain_text("已排队")
    page.get_by_role("searchbox", name="搜索文档").fill("missing")
    expect(page.get_by_text("没有匹配的文档", exact=True)).to_be_visible()
    picker = page.locator('input[type="file"]')
    picker.set_input_files(dict(name="sample.zip", mimeType="application/zip", buffer=b"fixture"))
    expect(page.get_by_label("上传进度")).to_contain_text("暂不支持此文件类型")
    page.get_by_role("button", name="移除上传提示 sample.zip").click()
    picker.set_input_files(dict(name="sample.csv", mimeType="text/csv", buffer=b"fixture,1"))
    expect(table).to_contain_text("sample.csv")
    expect(page.locator(".file-heading")).to_contain_text("sample.csv")
    page.set_viewport_size(dict(width=390, height=740))
    expect(page.get_by_role("button", name="选择文件")).to_be_visible()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    page.get_by_role("button", name="删除", exact=True).click()
    page.get_by_role("button", name="确认删除", exact=True).click()
    expect(table).not_to_contain_text("sample.csv")
    assert mutations == ["delete", "retry", "upload", "delete-pending"]
    assert not errors, errors
    browser.close()
