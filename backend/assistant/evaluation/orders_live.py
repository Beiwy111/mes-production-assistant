"""Three real read-only workbench conversations; reconcile membership with local MySQL.
Run from the repository root using the knowledge service venv and MES_TRACE_USER/PASSWORD.
"""
import json
import os
import sys
import uuid
from pathlib import Path

import requests
from replay import MES, query

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'backend/assistant/rag/src'))
from mes_knowledge.experience.source import reader_transaction

CASES = [(1,'WO202607001'), (100,'WO202607003'), (101,'WO202609001')]


def expected(order, work):
    # The fixture operator must separately authorize each graph export.
    receipt = json.loads((ROOT/f'backend/assistant/rag/data/business-graphs/{order}.json').read_text('utf-8'))
    assert receipt['status']=='READY' and not receipt['synthetic']
    with reader_transaction('agent') as cur:
        cur.execute('SELECT w.work_order_id FROM work_order w JOIN production_plan p ON p.plan_id=w.plan_id '
                    'WHERE w.work_order_no=%s AND p.source_order_id=%s',(work,order))
        rows = cur.fetchall()
        assert len(rows)==1
        wid = rows[0]['work_order_id']
        cur.execute('SELECT COUNT(*) AS n FROM dispatch_task WHERE work_order_id=%s',(wid,))
        dispatches = cur.fetchone()['n']
        cur.execute('SELECT inspection_id FROM quality_inspection WHERE work_order_id=%s',(wid,))
        attached = {str(r['inspection_id']) for r in cur.fetchall()}
        cur.execute("SELECT DISTINCT q.inspection_id FROM quality_inspection q JOIN quality_inspection seed "
                    "ON q.batch_no=seed.batch_no AND q.material_id=seed.material_id AND "
                    "q.inspection_category=seed.inspection_category WHERE seed.work_order_id=%s AND TRIM(seed.batch_no)<>''",(wid,))
        return dispatches, attached, len(cur.fetchall())


def main():
    with requests.Session() as client:
        client.trust_env=False
        response=client.post(MES+'/auth/login',json={'username':os.environ['MES_TRACE_USER'],
            'password':os.environ['MES_TRACE_PASSWORD']},timeout=10)
        response.raise_for_status()
        client.headers['Authorization']='Bearer '+response.json()['data']['token']
        try:
            for order, work in CASES:
                dispatches, inspections, same_batch = expected(order,work)
                previous=None
                sid=str(uuid.uuid4())
                results=[]
                for question in [work+'这个工单派给谁了','哪道工序没做完','同批有没有质检异常']:
                    previous, result = query(client,question,sid,previous)
                    assert result['verdict']=='EVIDENCE_READY' and result['scope']['workOrderNo']==work, (order,result['verdict'])
                    assert result['events'].get('agent') and result['events'].get('delta')
                    results.append(result)
                dispatch,steps,quality=results
                assert dispatch['dispatches']==dispatches and 'production' in dispatch['facts']
                assert steps['steps']>0
                assert quality['rowCounts']['quality']==same_batch
                assert quality['graph']=='VERIFIED' and quality['paths']
                assert all(str(p['order_id'])==str(order) for p in quality['paths'])
                assert {p['inspection_id'] for p in quality['paths'] if 'inspection_id' in p}==inspections
                print(json.dumps({'order':order,'work':work,'dispatches':dispatches,'steps':steps['steps'],
                      'same_batch_inspections':same_batch,'graph_paths':len(quality['paths']),'passed':True}),flush=True)
        finally:
            client.post(MES+'/auth/logout',timeout=10)


if __name__=='__main__':
    main()

