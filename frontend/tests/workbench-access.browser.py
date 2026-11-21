"""Real local read-only queries, signed audit persistence and original UI/streaming regression."""
import json, subprocess, time
from pathlib import Path
from collections import Counter
from playwright.sync_api import sync_playwright

out=Path('docs/ui-verification/p0-access-browser');out.mkdir(parents=True,exist_ok=True)
report={'cases':[],'errors':[],'businessWrites':False}
instrument=r"""window.__wire=[];const original=fetch.bind(window);window.fetch=async(...args)=>{const r=await original(...args);if(!String(args[0]).endsWith('/runs/stream')||!r.ok)return r;let b='';const d=new TextDecoder();return new Response(r.body.pipeThrough(new TransformStream({transform(chunk,c){b+=d.decode(chunk,{stream:true});let i;while((i=b.indexOf('\n\n'))>=0){const f=b.slice(0,i);b=b.slice(i+2);const s=f.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');if(s)window.__wire.push({...JSON.parse(s),at:performance.now()});}c.enqueue(chunk)}})),{status:r.status,headers:r.headers})};"""
def save(): (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True);context=browser.new_context(viewport={'width':1440,'height':1000});context.add_init_script(instrument)
    page=context.new_page();page.on('pageerror',lambda e:report['errors'].append(str(e)))
    page.goto('http://localhost:5173/assistant',wait_until='domcontentloaded')
    page.get_by_placeholder('用户名',exact=True).fill('admin');page.get_by_placeholder('密码',exact=True).fill('123456');page.locator('button[type=submit]').click()
    page.wait_for_function("!!sessionStorage.getItem('token')&&!location.pathname.includes('login')",timeout=30000)
    page.goto('http://localhost:5173/assistant',wait_until='networkidle')
    page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
    def audit(ref):
        return page.evaluate("""async ref=>{const r=await fetch('/api/assistant-workbench/runs/'+ref+'/audit',{headers:{Authorization:'Bearer '+sessionStorage.getItem('token')}});return{status:r.status,data:await r.json()}}""",ref)
    def ask(name,query,fresh=True):
        if fresh:page.get_by_role('button',name='新对话',exact=True).click()
        page.evaluate('window.__wire=[]');page.get_by_label('输入问题',exact=True).fill(query);page.get_by_label('发送问题',exact=True).click()
        page.wait_for_function("window.__wire.some(e=>['task_completed','task_failed'].includes(e.type))",timeout=240000)
        wire=page.evaluate('window.__wire');assert wire[-1]['type']=='task_completed',wire[-1]
        result=next(e['payload']['legacy']['data'] for e in wire if e['type']=='message_completed')
        assert ''.join(e['payload'].get('text','') for e in wire if e['type']=='message_delta')==result['reply']
        page.wait_for_function("!document.querySelector('.workbench-message.assistant:last-child .is-streaming')",timeout=10000)
        ref=wire[0]['task_id'];saved=None
        for _ in range(100):
            saved=audit(ref)
            if saved['status']==200 and saved['data']['status']=='COMPLETED' and saved['data']['persistence']=='PERSISTED':break
            page.wait_for_timeout(100)
        assert saved['status']==200 and saved['data']['persistence']=='PERSISTED',saved
        assert saved['data']['status']=='COMPLETED'
        assert not saved['data']['truncated']
        assert all(e['eventId'] in {w['event_id'] for w in wire} for e in saved['data']['events'])
        assert not any(e['type'] in ['message_delta','heartbeat'] for e in saved['data']['events'])
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        report['cases'].append({'name':name,'verdict':result['verdict'],'agents':result.get('executedAgents',[]),'result':result,'events':wire,'audit':saved['data']})
        save();page.screenshot(path=str(out/(name+'.png')));print(name,result['verdict'],flush=True)
        return result,ref
    result,ref=ask('work-order-suffix','7001这个订单现在派工了吗')
    assert result['scope']['workOrderNo']=='WO202607001',result.get('reply')
    assert 'dispatchRows' in result['facts']['production']
    result,_=ask('work-order-complete','WO202607001这个订单现在派工了吗')
    assert result['scope']['workOrderNo']=='WO202607001'
    result,_=ask('work-order-followup','你发给我的就是这个',False)
    assert result['scope']['workOrderNo']=='WO202607001',result.get('reply')
    risk,_=ask('cross-order-risk','分析订单交付风险')
    assert risk['facts']['order']['crossOrderRisk']
    assert page.locator('.workbench-message.assistant:last-child table').count()>=1
    parallel,_=ask('parallel-preserved','查询当前库存、生产工单和设备台账，三个都需要')
    assert set(parallel['executedAgents'])=={'SUPPLY','PRODUCTION','EQUIPMENT'}
    events=report['cases'][-1]['events'];times={(e['agent_id'],e['type']):e['at'] for e in events if e['type'] in ['agent_started','agent_completed']}
    assert max(times[r,'agent_started'] for r in parallel['executedAgents'])<min(times[r,'agent_completed'] for r in parallel['executedAgents'])
    assert all(sum(e['type']=='message_delta' for e in c['events'])>1 for c in report['cases'])
    assert not report['errors'];report['passed']=True;save();browser.close()
    print('P0_ACCESS_BROWSER_PASSED',flush=True)
