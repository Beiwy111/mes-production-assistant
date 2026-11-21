"""Real local SELECT integration; uses existing private runtime keys, changes no business data.

Exercises the trusted gateway/runtime boundary directly, without pretending these are model runs.
Browser/Dify streaming is tested separately. Secrets and raw customer records are not printed.
"""
import json
from pathlib import Path
from urllib.parse import urlparse
import requests
import pymysql

root=Path(__file__).resolve().parents[2]
cfg=dict(line.split('=',1) for line in (root/'backend/assistant/deploy/.env.agents.local').read_text(encoding='utf-8-sig').splitlines() if line.strip() and not line.lstrip().startswith('#'))
url=urlparse(cfg['MES_AGENT_JDBC_URL'].removeprefix('jdbc:'))
db=pymysql.connect(host=url.hostname,port=url.port or 3306,user=cfg['MES_AGENT_JDBC_USER'],password=cfg['MES_AGENT_JDBC_PASSWORD'],database=url.path.strip('/'),charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor)
report={'businessWrites':False,'mode':'REAL_READONLY_RUNTIME_INTEGRATION','checks':[]}
def sql(query,args=()):
    with db.cursor() as c:c.execute(query,args);return c.fetchall()
sql('SET TRANSACTION READ ONLY');sql('START TRANSACTION')
users=sql('SELECT u.user_id,r.role_code,u.customer_name FROM user u JOIN role r ON r.role_id=u.role_id WHERE u.status=1 AND r.status=1 ORDER BY u.user_id')
def request(role,path,body=None):
    r=requests.request('GET' if body is None else 'POST','http://127.0.0.1:18082'+path,headers={'Authorization':'Bearer '+cfg['MES_AGENT_'+role+'_KEY']},json=body,timeout=60)
    assert r.ok, (path,r.status_code,r.text[:100])
    return r.json()
def run(user,query,domain,pk=None,number=None,keyword=None,previous=None):
    body={'ownerUserId':str(user['user_id']),'query':query}
    if previous:body['previousRunRef']=previous
    ref=request('BOOTSTRAP','/stage5/natural/runs',body)['runRef']
    base='/stage5/runs/'+ref
    plan=request('SUP',base+'/plan',dict(action='QUERY',intents=[domain],orderPk=pk,orderNo=number,keyword=keyword,period='CURRENT',question=''))
    for role in plan['requiredAgents']:
        request(role,base+'/tasks/'+role+'/begin',{})
        request(role,base+'/tasks/'+role+'/complete',{'summary':'只读接口测试：原始查询证据已取回；未调用模型分析。'})
    result=request('SUP',base+'/finalize',{})
    report['checks'].append({'role':user['role_code'],'query':query,'verdict':result['verdict'],'facts':list(result['facts'])})
    return result,ref
try:
    customers=[u for u in users if u['role_code']=='CUSTOMER' and u['customer_name']]
    assert len(customers)>=2
    for user in customers:
        result,_=run(user,'查询我的订单','ORDER')
        rows=result['facts']['order']['overview']['rows']
        expected=sql('SELECT order_id FROM customer_order WHERE customer_name=%s',(user['customer_name'].strip(),))
        assert {int(r['record_id']) for r in rows}=={r['order_id'] for r in expected}
        assert all(r['customer_name']==user['customer_name'].strip() for r in rows)
        foreign=sql('SELECT order_id FROM customer_order WHERE customer_name<>%s LIMIT 1',(user['customer_name'].strip(),))[0]['order_id']
        denied,_=run(user,f'查询订单ID{foreign}','ORDER',pk=str(foreign))
        assert denied['verdict']=='CLARIFY' and not denied['facts']
        denied,_=run(user,'查库存','INVENTORY');assert denied['verdict']=='UNSUPPORTED' and not denied['facts']
        risk,_=run(user,'分析订单交付风险','ORDER')
        assert {int(r['order_id']) for r in risk['facts']['order']['crossOrderRisk']}<={r['order_id'] for r in expected}
    operator=next(u for u in users if u['role_code']=='OPERATOR')
    result,_=run(operator,'查询生产工单','PRODUCTION')
    own=sql('SELECT DISTINCT work_order_id FROM dispatch_task WHERE operator_id=%s',(operator['user_id'],))
    assert {int(r['record_id']) for r in result['facts']['production']['overview']['rows']}=={r['work_order_id'] for r in own}
    if own:
        code=sql('SELECT work_order_no FROM work_order WHERE work_order_id=%s',(own[0]['work_order_id'],))[0]['work_order_no']
        result,_=run(operator,code+'这个工单派工了吗','PRODUCTION',keyword=code)
        assert all(int(r['operator_id'])==operator['user_id'] for r in result['facts']['production']['dispatchRows'])
    for role,domain,query in [('QC','QUALITY','查询质量记录'),('DEVICE','EQUIPMENT','查询设备台账'),('WAREHOUSE','INVENTORY','查询库存'),('ORDER','ORDER','查询订单'),('PLANNER','ORDER','查询订单')]:
        user=next(u for u in users if u['role_code']==role)
        result,_=run(user,query,domain);assert domain.lower() in result['facts']
        forbidden='INVENTORY' if domain!='INVENTORY' else 'ORDER'
        denied,_=run(user,'查询库存' if forbidden=='INVENTORY' else '查询订单',forbidden)
        assert denied['verdict']=='UNSUPPORTED' and not denied['facts']
    admin=next(u for u in users if u['role_code']=='ADMIN')
    result,ref=run(admin,'7001这个订单现在派工了吗','PRODUCTION',pk='7001')
    assert result['scope']['workOrderNo']=='WO202607001'
    expected=sql('SELECT COUNT(*) AS n FROM dispatch_task WHERE work_order_id=1')[0]['n']
    assert len(result['facts']['production']['dispatchRows'])==expected
    follow,_=run(admin,'你发给我的就是这个','PRODUCTION',previous=ref)
    assert follow['scope']['workOrderNo']=='WO202607001'
    risk,_=run(admin,'跨订单风险分析','ORDER')
    assert len(risk['facts']['order']['crossOrderRisk'])>=len(risk['facts']['order']['overview']['rows'])
    report['riskRows']=len(risk['facts']['order']['crossOrderRisk']);report['dispatchRows']=expected
    report['passed']=True
finally:
    db.rollback();db.close()
    out=root/'docs/ui-verification/p0-access';out.mkdir(parents=True,exist_ok=True)
    (out/'integration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'checks':len(report['checks']),'passed':report.get('passed',False),'riskRows':report.get('riskRows'),'dispatchRows':report.get('dispatchRows')},ensure_ascii=False))
