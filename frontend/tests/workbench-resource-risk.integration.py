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
    admin=next(u for u in users if u['role_code']=='ADMIN')
    result,ref=run(admin,'跨订单共享物料竞争和产能冲突分析','ORDER')
    assert result['verdict'] in ['EVIDENCE_READY','INSUFFICIENT_EVIDENCE'],result['verdict']
    assert set(result['executedAgents'])=={'ORDER','SUPPLY','PRODUCTION'}
    context=request('SUP','/stage5/runs/'+ref+'/context')
    assert all(not context['dependencies'].get(r) for r in result['executedAgents'])
    m=result['facts']['shared_material'];c=result['facts']['capacity_conflicts']
    from collections import Counter
    report['materialStates']=dict(Counter(r['risk_status'] for r in result['facts']['shared_material']['rows']))
    report['coverageStates']=dict(Counter(r['risk_status'] for k in ['shared_material','capacity_conflicts'] for r in result['facts'][k]['coverage']))
    from decimal import Decimal
    expected=sql("SELECT material_id,SUM(quantity_available) AS available FROM inventory WHERE inventory_status='NORMAL' GROUP BY material_id")
    pools={str(r['material_id']):r['available'] for r in expected}
    for material in {r['material_id'] for r in m['rows']}:
        rows=[r for r in m['rows'] if r['material_id']==material]
        for r in rows:
            if r['public_available_qty'] is not None:assert Decimal(r['public_available_qty'])==pools.get(material,0)
        assert sum(Decimal(r['scenario_allocated_qty']) for r in rows if r['scenario_allocated_qty'] is not None)<=pools.get(material,0)
        for r in rows:
            if r['scenario_gap_qty'] is not None:assert Decimal(r['scenario_demand_qty'])==Decimal(r['scenario_allocated_qty'])+Decimal(r['scenario_gap_qty'])
    from datetime import datetime
    at=datetime.fromisoformat(c['analysis_at'])
    expected_pairs=sql("""SELECT a.schedule_id AS l,b.schedule_id AS r FROM production_plan_schedule a JOIN production_plan_schedule b ON a.equipment_id=b.equipment_id AND a.schedule_id<b.schedule_id JOIN production_plan p ON p.plan_id=a.plan_id JOIN production_plan q ON q.plan_id=b.plan_id JOIN customer_order o ON o.order_id=p.source_order_id JOIN customer_order z ON z.order_id=q.source_order_id WHERE p.source_order_id<>q.source_order_id AND p.plan_status NOT IN ('CANCELLED','COMPLETED','CLOSED') AND q.plan_status NOT IN ('CANCELLED','COMPLETED','CLOSED') AND o.audit_status NOT IN ('CANCELLED','REJECTED') AND z.audit_status NOT IN ('CANCELLED','REJECTED') AND a.planned_start<a.planned_end AND b.planned_start<b.planned_end AND a.planned_end>%s AND b.planned_end>%s AND a.planned_start<b.planned_end AND b.planned_start<a.planned_end""",(at,at))
    actual={tuple(sorted((int(r['left_source_id']),int(r['right_source_id'])))) for r in c['rows'] if r['resource_type']=='EQUIPMENT'}
    assert actual=={(r['l'],r['r']) for r in expected_pairs}
    report.update(materialRows=len(m['rows']),demandRows=len(m['demands']),capacityConflicts=len(c['rows']),coverageRows=len(m['coverage'])+len(c['coverage']),scenarioGapOrders=sorted({r['order_no'] for r in m['rows'] if r['risk_status'] in ['SHARED_POOL_SCENARIO_GAP','SCENARIO_GAP']}))
    for user in [u for u in users if u['role_code']=='CUSTOMER' and u['customer_name']][:2]:
        scoped,_=run(user,'跨订单共享物料竞争和产能冲突分析','ORDER')
        assert set(scoped['executedAgents'])=={'ORDER'}
        assert 'shared_material' not in scoped['facts'] and 'capacity_conflicts' not in scoped['facts']
        assert scoped['facts']['order']['resourceLimitations']
        assert all(r['customer_name']==user['customer_name'].strip() for r in scoped['facts']['order']['overview']['rows'])
    customer=next(u for u in users if u['role_code']=='CUSTOMER' and u['customer_name'])
    scoped,_=run(admin,f"分析客户名称为‘{customer['customer_name']}’的跨订单共享物料竞争和产能冲突",'ORDER')
    allowed={str(o['order_id']) for o in sql('SELECT order_id FROM customer_order WHERE customer_name=%s',(customer['customer_name'],))}
    for key in ['shared_material','capacity_conflicts']:
        assert {str(o['record_id']) for o in scoped['facts'][key]['sourceEvidence']['orders']}<=allowed
        assert all(str(p['order_id']) in allowed for p in scoped['facts'][key]['sourceEvidence']['plans'])
    planner=next(u for u in users if u['role_code']=='PLANNER')
    limited,_=run(planner,'跨订单共享物料竞争和产能冲突分析','ORDER')
    assert 'shared_material' not in limited['facts']
    report['passed']=True
finally:
    db.rollback();db.close()
    out=root/'docs/ui-verification/shared-resource-risk';out.mkdir(parents=True,exist_ok=True)
    (out/'integration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
