"""Real Vue + SSE acceptance. Set MES_TRACE_TOKEN to reuse a login, or MES_TRACE_USER/PASSWORD."""
import argparse
import json
import os
import re
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright


# Observe a clone of the actual server response. Vue reads its original Response unchanged.
OBSERVE = r"""
window.capture = {};
const originalFetch = window.fetch.bind(window);
window.fetch = async (...args) => {
  const response = await originalFetch(...args);
  const url = new URL(response.url || location.href);
  if (url.pathname.endsWith('/runs/stream')) {
    const capture = window.capture = {events:[], contentType:response.headers.get('content-type')};
    if (!response.ok) { capture.error = 'HTTP ' + response.status; return response; }
    (async () => {
      const reader = response.clone().body.getReader(), decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const {value,done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream:!done});
        let split;
        while ((split = /\r?\n\r?\n/.exec(buffer))) {
          const lines = buffer.slice(0,split.index).split(/\r?\n/);
          buffer = buffer.slice(split.index+split[0].length);
          const data = lines.filter(s=>s.startsWith('data:')).map(s=>s.slice(5).trimStart()).join('\n');
          if (data) capture.events.push({...JSON.parse(data), received:performance.now()});
        }
        if (done) break;
      }
    })().catch(()=>{capture.error='Invalid or interrupted SSE stream'});
  }
  return response;
};
"""


def open_workbench(page, base):
    # Reusing a token never calls login/logout. Hydrate actual user metadata in the same way as the Vue store.
    token = os.getenv('MES_TRACE_TOKEN')
    if token:
        page.goto(base + '/')
        page.evaluate("""async token=>{
            const r=await fetch('/api/auth/userInfo',{headers:{Authorization:'Bearer '+token}});
            const envelope=await r.json();
            if(!r.ok || !envelope.data?.userId) throw new Error('The supplied test session has expired');
            sessionStorage.setItem('token',token);
            const {useUserStore}=await import('/src/stores/user.js');
            const store=useUserStore(); store.token=token; await store.refreshUserInfo();
        }""", token)
    else:
        page.goto(base + '/assistant')
        page.get_by_placeholder('用户名', exact=True).fill(os.environ['MES_TRACE_USER'])
        page.get_by_placeholder('密码', exact=True).fill(os.environ['MES_TRACE_PASSWORD'])
        page.locator('button[type=submit]').click()
        page.wait_for_function("!!sessionStorage.getItem('token') && !location.pathname.includes('login')")
    page.goto(base + '/assistant')
    expect(page.get_by_label('刷新连接状态')).to_contain_text('已连接',timeout=30000)
    assert page.locator('.workbench-main').evaluate('e=>getComputedStyle(e).backgroundColor') == 'rgb(255, 255, 255)'
    toggle=page.get_by_label('展开 Agent 面板',exact=True)
    if toggle.count(): toggle.click()


def ask(page, query, role, required_tools):
    page.evaluate('window.capture={}')
    page.get_by_label('输入问题',exact=True).fill(query)
    page.get_by_label('发送问题',exact=True).click()
    page.wait_for_function("""()=>capture.error || capture.events?.some(e=>
        ['task_completed','task_failed','task_cancelled'].includes(e.type))""",timeout=300000)
    wire=page.evaluate('window.capture')
    assert not wire.get('error'),wire.get('error')
    assert (wire['contentType'] or '').startswith('text/event-stream')
    events=wire['events']
    assert events[-1]['type']=='task_completed',events[-1]['type']
    assert [e['sequence'] for e in events]==list(range(1,len(events)+1))
    assert any(e['type']=='agent_started' and e['agent_id']==role for e in events),role
    completed=[e['payload']['observation'] for e in events if e['type']=='tool_completed']
    ids={e['toolId'] for e in completed}
    assert required_tools <= ids, sorted(ids)
    result=next(e['payload']['legacy']['data'] for e in events if e['type']=='message_completed')
    deltas=[e for e in events if e['type']=='message_delta']
    assert len(deltas)>1 and deltas[0]['received']<deltas[-1]['received']
    assert ''.join(e['payload']['text'] for e in deltas)==result['reply']
    assert result['verdict']=='EVIDENCE_READY',result['verdict']
    expect(page.get_by_label('停止回答',exact=True)).to_have_count(0,timeout=30000)
    inspector=page.get_by_label('Agent 执行检查面板',exact=True)
    inspector.get_by_role('tab',name=re.compile('调用')).click()
    rendered=inspector.locator('.call-list li summary').all_inner_texts()
    for step in completed:
        if step['toolId'] in required_tools:
            assert step['label'] and any(step['label'] in s for s in rendered),step['toolId']
    print(f"{role}: {len(deltas)} text deltas; {len(rendered)} inspector entries",flush=True)
    return result,events[0]['conversation_id'],dict(deltaCount=len(deltas),tools=sorted(ids),loggedSteps=len(rendered))


def check_table(article,label,records,field):
    assert records,label
    table=article.get_by_role('table',name=re.compile('^'+re.escape(label)+'，'))
    expect(table).to_be_visible()
    expect(table.locator('tbody tr')).to_have_count(len(records))
    for record,row in zip(records,table.locator('tbody tr').all(),strict=True):
        expect(row.get_by_role('cell',name=str(record[field]),exact=True).first).to_be_visible()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',default='http://localhost:5173')
    parser.add_argument('--out',type=Path,required=True,help='Private output directory for authenticated UI screenshots')
    args=parser.parse_args()
    assert urlsplit(args.url).hostname in ('localhost','127.0.0.1','::1'),'Local MES only'
    args.out.mkdir(parents=True,exist_ok=True)
    report={}
    with sync_playwright() as pw:
        browser=pw.chromium.launch()
        page=browser.new_page(viewport={'width':1440,'height':1000})
        errors=[]
        page.on('pageerror',lambda error:errors.append(str(error)))
        page.add_init_script(OBSERVE)
        open_workbench(page,args.url.rstrip('/'))
        page.get_by_role('button',name='新对话',exact=True).click()
        route,_,report['route']=ask(page,'八道工序分别是什么？','PRODUCTION',{'PROCESS_ROUTE'})
        article=page.locator('.workbench-message.assistant').last
        stages=route['facts']['process_route']['productionStages']
        assert len(stages)==8 and all(r['step_name'] in route['reply'] for r in stages)
        check_table(article,'车间标准派工工序',stages,'step_name')
        article.get_by_role('heading',name='车间标准派工工序').scroll_into_view_if_needed()
        page.screenshot(path=str(args.out/'route.png'))
        page.get_by_role('button',name='新对话',exact=True).click()
        trace_tools={'INSPECTION_HEADER','INSPECTION_DETAILS','INSPECTION_PRODUCTION','INSPECTION_EQUIPMENT'}
        result,conversation,report['inspection']=ask(page,'QI202607021 存在什么问题？主要可能涉及哪个生产环节？','QUALITY',trace_tools)
        trace=result['facts']['inspection_trace']
        assert result['scope']['inspectionNo']=='QI202607021'
        assert trace['inspection'][0]['work_order_id']==trace['workOrder'][0]['work_order_id']
        for label,key,field in [('检验单','inspection','inspection_no'),('检验项目与实测值','items','item_name'),
                                ('已登记不合格品','defects','nonconforming_no'),('来源工单','workOrder','work_order_no')]:
            check_table(article,label,trace[key],field)
        article.get_by_role('heading',name='已登记不合格品').scroll_into_view_if_needed()
        page.screenshot(path=str(args.out/'inspection.png'))
        follow,same,report['followup']=ask(page,'那这张单关联的设备能确定吗？','QUALITY',{'INSPECTION_HEADER','INSPECTION_EQUIPMENT'})
        assert same==conversation and follow['scope']['inspectionNo']==result['scope']['inspectionNo']
        assert follow['facts']['inspection_trace']['inspection']==trace['inspection'] and '设备' in follow['reply']
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        assert not errors,errors
        browser.close()
    (args.out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(report,ensure_ascii=True))


if __name__=='__main__':
    main()
