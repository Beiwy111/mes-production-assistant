"""Use existing seeded accounts through real MES login; never alter accounts or permissions."""
from pathlib import Path
from urllib.parse import urlparse
import json, requests, pymysql
from playwright.sync_api import sync_playwright

cfg=dict(x.split('=',1) for x in Path('backend/assistant/deploy/.env.agents.local').read_text(encoding='utf-8-sig').splitlines() if x.strip() and not x.lstrip().startswith('#'))
u=urlparse(cfg['MES_AGENT_JDBC_URL'].removeprefix('jdbc:'))
db=pymysql.connect(host=u.hostname,port=u.port or 3306,user=cfg['MES_AGENT_JDBC_USER'],password=cfg['MES_AGENT_JDBC_PASSWORD'],database=u.path.strip('/'),charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor)
with db.cursor() as c:
    c.execute('SET TRANSACTION READ ONLY');c.execute('START TRANSACTION')
    c.execute("SELECT u.username,u.user_id,r.role_code,u.customer_name FROM user u JOIN role r ON r.role_id=u.role_id WHERE u.status=1 AND r.status=1 AND r.role_code IN ('CUSTOMER','QC','OPERATOR','ORDER','DEVICE','WAREHOUSE') ORDER BY u.user_id")
    users=c.fetchall()
selected=[]
for role in ('CUSTOMER','QC','OPERATOR','ORDER','DEVICE','WAREHOUSE'):
    selected.extend([x for x in users if x['role_code']==role][:2 if role=='CUSTOMER' else 1])
out=Path('docs/ui-verification/p0-access-roles');out.mkdir(parents=True,exist_ok=True)
instrument=r"""window.__wire=[];const original=fetch.bind(window);window.fetch=async(...args)=>{const r=await original(...args);if(!String(args[0]).endsWith('/runs/stream')||!r.ok)return r;let b='';const d=new TextDecoder();return new Response(r.body.pipeThrough(new TransformStream({transform(chunk,c){b+=d.decode(chunk,{stream:true});let i;while((i=b.indexOf('\n\n'))>=0){const f=b.slice(0,i);b=b.slice(i+2);const s=f.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');if(s)window.__wire.push({...JSON.parse(s),at:performance.now()});}c.enqueue(chunk)}})),{status:r.status,headers:r.headers})};"""
report={'checks':[],'consoleErrors':[],'businessDocumentWrites':False}
try:
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True)
        for index,user in enumerate(selected):
            # This password is the documented local fixture in sql/init/seed_business_roles_users.sql.
            login=requests.post('http://127.0.0.1:8088/auth/login',json={'username':user['username'],'password':'123456'},timeout=15).json()
            assert login.get('code')==200, ('Existing fixture login unavailable',user['role_code'])
            headers={'Authorization':'Bearer '+login['data']['token']}
            identity=requests.get('http://127.0.0.1:8088/auth/userInfo',headers=headers,timeout=15).json()['data']
            assert identity['roleCode']==user['role_code']
            identity['token']=login['data']['token']
            status=requests.get('http://127.0.0.1:18083/assistant-workbench/status',headers=headers,timeout=15)
            assert status.status_code==200
            capabilities=status.json()['capabilities'];assert status.json()['allowedOrderIds']==[]
            legacy=requests.post('http://127.0.0.1:18083/assistant-workbench/runs/stream',headers=headers,json={'text':'查询订单100','orderPk':'100','profile':'ORDER','sessionId':'scope-test','requestId':'scope-legacy'},timeout=15)
            assert legacy.status_code==403
            entry={'role':user['role_code'],'scope':capabilities['scope'],'domains':capabilities['domains'],'legacyDenied':True}
            if user['role_code']=='CUSTOMER':
                context=browser.new_context(viewport={'width':1440,'height':1000})
                context.add_init_script('sessionStorage.setItem("token",'+json.dumps(identity['token'])+');sessionStorage.setItem("userInfo",'+json.dumps(json.dumps(identity,ensure_ascii=False))+');')
                context.add_init_script(instrument)
                page=context.new_page();page.on('pageerror',lambda e:report['consoleErrors'].append(str(e)))
                page.goto('http://localhost:5173/assistant',wait_until='networkidle')
                page.wait_for_function("document.querySelector('[aria-label=\"刷新连接状态\"]')?.textContent.includes('已连接')",timeout=30000)
                page.get_by_label('输入问题',exact=True).fill('查询我的订单')
                page.get_by_label('发送问题',exact=True).click()
                page.wait_for_function("window.__wire.some(e=>['task_completed','task_failed'].includes(e.type))",timeout=240000)
                events=page.evaluate('window.__wire')
                assert events[-1]['type']=='task_completed'
                result=next(e['payload']['legacy']['data'] for e in events if e['type']=='message_completed')
                rows=result['facts']['order']['overview']['rows']
                with db.cursor() as c:
                    c.execute('SELECT order_id FROM customer_order WHERE customer_name=%s',(user['customer_name'].strip(),));expected={row['order_id'] for row in c.fetchall()}
                assert {int(row['record_id']) for row in rows}==expected
                assert all(row['customer_name']==user['customer_name'].strip() for row in rows)
                entry['orderRows']=len(rows);entry['streamDeltas']=sum(e['type']=='message_delta' for e in events)
                assert entry['streamDeltas']>1
                page.wait_for_timeout(1000);page.screenshot(path=str(out/f'customer-{index}.png'))
                context.close()
            report['checks'].append(entry)
        browser.close()
    assert not report['consoleErrors'];report['passed']=True
finally:
    db.rollback();db.close()
    (out/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'passed':report.get('passed',False),'roles':len(report['checks'])}))
