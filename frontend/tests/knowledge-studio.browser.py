"""Graph canvas, evidence and authenticated-source interactions against Vite with fixture responses."""
import os
from urllib.parse import parse_qs, urlsplit
from playwright.sync_api import sync_playwright, expect


BASE = os.environ.get('WORKBENCH_TEST_URL', 'http://localhost:5173')
FOLDER = '/src/components/assistant/knowledge/'
hit = dict(release_id='r', evidence_ref='r:P:c', document_id='P', document_version='v1',
           chunk_id='c', title='Display guide', text='Optics detects Mura.', page_start=2, page_end=2,
           authority_type='PUBLIC_RESEARCH', source_url='https://example.org/guide.pdf')
claim = dict(claim_id='claim', subject_id='O', subject_name='Optics', object_id='M', object_name='Mura',
             relation='DETECTS', statement=hit['text'], quote='detects Mura', page=2, document_id='P',
             document_version='v1', graph_release_id='g', evidence_refs=[hit['evidence_ref']],
             scope='RESEARCH_ONLY', review_status='SOURCE_VERIFIED', authority_type='PUBLIC_RESEARCH')
bundle = dict(status='MATCHES', query='Mura', release_id='r', mode_used='graph_assisted',
              graph_status='MATCHES', graph_release_id='g', hits=[hit], graph_paths=[claim])
job = dict(id='sample', filename='检验.md', status='completed',
           counts={'chunks':3},
           chunks=[dict(id=f'part{i}', text=f'Panel reference {i}.', location=f'line:{i}', metadata={'headings':['检验']})
                   for i in range(1,4)],
           entities=[], relations=[])

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(channel=os.environ.get('PLAYWRIGHT_BROWSER_CHANNEL', 'chrome'))
    page = browser.new_page(viewport={'width':1360, 'height':820}, reduced_motion='reduce')
    errors = []
    page.on('pageerror', lambda exc: errors.append(str(exc)))
    page.route('**/__graph_fixture', lambda r: r.fulfill(content_type='text/html', body='<div id="app" style="height:80vh;display:flex"></div>'))
    def respond(route, data):
        assert route.request.headers.get('authorization') == 'Bearer sample-session'
        route.fulfill(json={'code':200, 'data':data})
    catalog_hits = [dict(hit, document_id='Q', title='Panel guide', chunk_id=str(i), evidence_ref=f'r:Q:{i}')
                    for i in range(114)] + [hit]
    def catalog(route):
        params = parse_qs(urlsplit(route.request.url).query)
        start = int(params['offset'][0])
        assert params['limit'] == ['100'] and params.get('releaseId') == (['r'] if start else None)
        respond(route, dict(release_id='r', graph_release_id='g', graph_status='READY',
                            counts={'chunks':len(catalog_hits),'documents':2},
                            hits=catalog_hits[start:start+100], graph_paths=[] if start else [claim],
                            next_offset=start+100 if start+100 < len(catalog_hits) else None))
    page.route('**/api/assistant-v2/knowledge/catalog?*', catalog)
    def explore(route):
        if route.request.post_data_json['query'] == '资料对照':
            second = dict(hit, document_id='Z', title='Factory notes', evidence_ref='r:Z:c',
                          source_url='https://example.org/factory.txt')
            respond(route, dict(bundle, hits=[hit, second]))
        else:
            respond(route, bundle)
    page.route('**/api/assistant-v2/knowledge/explore', explore)
    page.route('**/ingestion/jobs', lambda r: respond(r, {'jobs':[job]}))
    page.route('**/ingestion/jobs/sample', lambda r: respond(r, job))
    page.goto(BASE + '/__graph_fixture')
    page.evaluate("""async (folder) => {
      const canvasSource = await (await fetch(folder+'DynamicKnowledgeGraphCanvas.vue')).text()
      // The tested component and fixture must share Vite's optimized Vue/ECharts instances.
      const dependency = name => canvasSource.split('"').find(url=>url.includes('/deps/'+name+'.js'))
      const { createApp, h } = await import(dependency('vue'))
      const { getInstanceByDom } = await import(dependency('echarts_core'))
      const Studio = (await import(folder+'KnowledgeStudio.vue')).default
      createApp({render:()=>h(Studio,{canIngest:true,token:'sample-session'})}).mount('#app')
      window.pointOf = id => {
        const container = document.querySelector('.kg-chart')
        const chart = getInstanceByDom(container)
        const node = chart.getModel().getSeriesByIndex(0).getGraph().getNodeById(id)
        const [x,y] = chart.convertToPixel({seriesIndex:0},node.getLayout())
        const box = container.getBoundingClientRect()
        return [box.x+x,box.y+y]
      }
    }""", FOLDER)
    expect(page.locator('.kg-scope')).to_contain_text('全库 2 份文档 · 115 个片段 · 120 个节点')
    expect(page.locator('.kg-pages')).to_contain_text('第 1 / 5 页')
    page.get_by_role('button',name='下一页节点').click()
    expect(page.locator('.kg-pages')).to_contain_text('第 2 / 5 页')
    catalog_hits.append(dict(catalog_hits[0], chunk_id='new', evidence_ref='r:Q:new'))
    page.get_by_role('button',name='刷新目录').click()
    expect(page.locator('.kg-scope')).to_contain_text('全库 2 份文档 · 116 个片段 · 121 个节点')
    page.get_by_label('浏览文档').select_option('P')
    expect(page.locator('.kg-overview')).to_contain_text('5 节点')
    page.get_by_label('图谱布局', exact=True).select_option('layered')
    document = 'doc:P:v1'
    position = page.evaluate('id => pointOf(id)', document)
    page.mouse.click(*position)
    expect(page.locator('.node-head h3')).to_have_text('Display guide')
    page.get_by_role('button', name='加入分析', exact=True).click()
    page.get_by_label('定位节点', exact=True).select_option('entity:g:M')
    page.get_by_label('到分析节点的最短路径').select_option(document)
    expect(page.get_by_text('沿 3 条关系相连')).to_be_visible()
    expect(page.locator('.path-list li')).to_have_count(4)
    page.get_by_role('checkbox', name='支持', exact=True).uncheck()
    expect(page.get_by_text('当前筛选下无相连路径')).to_be_visible()
    page.get_by_role('checkbox', name='支持', exact=True).check()
    expect(page.get_by_text('沿 3 条关系相连')).to_be_visible()
    page.locator('.metadata summary').click()
    expect(page.locator('.metadata dl')).to_contain_text('实体编号')
    page.get_by_role('button', name='加入分析', exact=True).click()
    page.get_by_role('tab', name='分析面板').click()
    expect(page.locator('.shared-list')).to_contain_text('Display guide')
    page.get_by_role('tab', name='分析面板').press('ArrowLeft')
    expect(page.get_by_role('tab', name='节点详情')).to_have_attribute('aria-selected', 'true')
    page.get_by_label('放大图', exact=True).click()
    expect(page.locator('.kg-zoom')).to_contain_text('125%')
    page.get_by_label('重置图谱布局与视图').click()
    position = page.evaluate('id => pointOf(id)', document)
    page.mouse.move(*position)
    page.mouse.down()
    page.mouse.move(position[0]+24, position[1]+18, steps=8)
    page.mouse.up()
    moved = page.evaluate('id => pointOf(id)', document)
    assert abs(moved[0]-position[0])+abs(moved[1]-position[1])>10, (position,moved)
    page.mouse.dblclick(*moved)
    box = page.locator('.kg-chart').bounding_box()
    centered = page.evaluate('id => pointOf(id)', document)
    assert abs(centered[0]-(box['x']+box['width']/2))<6 and abs(centered[1]-(box['y']+box['height']/2))<6
    page.evaluate("document.querySelector('#app').style.display='none'")
    page.wait_for_timeout(80)
    page.evaluate("document.querySelector('#app').style.display='flex'")
    page.get_by_label('图谱布局', exact=True).select_option('force')
    page.get_by_label('搜索图谱节点').fill('Optics')
    expect(page.locator('.node-head h3')).to_have_text('Optics')
    page.get_by_role('button',name='GraphRAG',exact=True).click()
    expect(page.locator('.kg-overview')).to_contain_text('3 节点')
    page.get_by_role('button',name='公开资料检索',exact=True).click()
    expect(page.get_by_text('输入关键词检索公开资料。')).to_be_visible()
    page.get_by_label('公开知识检索词').fill('Mura')
    page.get_by_role('button',name='检索资料',exact=True).click()
    expect(page.locator('.kg-scope')).to_contain_text('当前范围 1 个片段 · 5 个节点')
    page.get_by_label('公开知识检索词').fill('资料对照')
    page.get_by_role('button',name='检索资料',exact=True).click()
    page.get_by_label('来源文档', exact=True).select_option(label='Factory notes')
    expect(page.get_by_label('定位节点', exact=True).locator('option')).to_have_count(3)
    expect(page.get_by_label('文件类型', exact=True).locator('option')).to_have_text(['全部格式', 'TXT'])
    page.get_by_label('文件类型', exact=True).select_option('TXT')
    page.get_by_label('节点类别', exact=True).select_option('chunk')
    expect(page.get_by_label('定位节点', exact=True).locator('option')).to_have_count(2)
    page.get_by_role('button',name='入库资料',exact=True).click()
    page.get_by_label('已入库文件').select_option('sample')
    expect(page.locator('.kg-scope')).to_contain_text('当前范围 3 个片段 · 5 个节点')
    page.get_by_label('定位节点', exact=True).select_option(label='索引片段 · line:3')
    expect(page.locator('.node-head p')).to_have_text('Panel reference 3.')
    page.get_by_label('定位节点', exact=True).select_option(label='文档 · 检验.md')
    page.locator('.evidence summary').first.click()
    expect(page.get_by_role('button',name='下载原文件')).to_be_visible()
    assert not errors, errors
    print('Knowledge Studio browser checks passed')
    browser.close()
