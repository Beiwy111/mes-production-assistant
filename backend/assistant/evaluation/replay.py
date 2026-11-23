"""Live regression through the workbench and draft endpoints. No order confirmation requests."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import uuid

import requests

BASE = Path(__file__).resolve().parent
MES = 'http://127.0.0.1:8088'
GATEWAY = 'http://127.0.0.1:18083/assistant-workbench/runs/stream'


def summarize(data):
    scope = data.get('scope') or {}
    facts = data.get('facts') or {}
    rows = {key: (fact.get('overview') or {}).get('rows', []) for key, fact in facts.items() if isinstance(fact, dict)}
    production = facts.get('production', {})
    graph = facts.get('quality', {}).get('businessGraph') or {}
    return {
        'verdict': data.get('verdict'), 'scope': {k:scope.get(k) for k in ('workOrderNo','orderPk','keyword','criteria','selectors')},
        'agents': data.get('executedAgents') or [], 'facts': list(facts), 'rowCounts': {k:len(v) for k,v in rows.items()},
        'pcbRows': sum('PCB' in str(row.get('material_name','')).upper() for row in rows.get('inventory', [])),
        'dispatches': len(production.get('dispatchRows', [])), 'steps': len(production.get('stepCoverage', [])),
        'graph': graph.get('status'), 'paths': graph.get('paths', []),
        'reply': data.get('reply','')[:1000],
    }


def query(session, text, sid, previous=None):
    req = {'sessionId':sid, 'requestId':str(uuid.uuid4()), 'text':text}
    if previous:
        req['previousRunId'] = previous
    result = None
    event_counts = Counter()
    current = None
    with session.post(GATEWAY,json=req,stream=True,timeout=(10,180)) as response:
        response.raise_for_status()
        for line in response.iter_lines():
            if not line:
                continue
            event = json.loads(line)
            event_counts[event['type']] += 1
            if event['type']=='run':
                current = event['runId']
            elif event['type']=='result':
                result = summarize(event['data'])
    if result is None or not current:
        raise RuntimeError('Incomplete stream')
    return current, {**result,'events':dict(event_counts)}


def check(cid, r):
    if 'error' in r:
        return False
    if cid.startswith('order-'):
        return (r['handled'] and r['state'] in ('COLLECTING','READY') and
                r['quantity'] == ('100' if cid == 'order-1' else '200') and not r['hasAddress'])
    clarify = cid.startswith('boundary') or cid in ('production-4','quality-2','quality-3','quality-4')
    if clarify:
        return r['verdict'] in ('UNSUPPORTED','CLARIFY') and not r['agents'] and not r['facts']
    if r['verdict'] != 'EVIDENCE_READY':
        return False
    if cid.startswith('dispatch-'):
        expected = 'WO202607001' if cid in ('dispatch-1','dispatch-2','dispatch-3') else 'WO202607003'
        return (r['scope']['workOrderNo']==expected and
                ('quality' in r['facts'] and r['graph']=='VERIFIED' if cid=='dispatch-7' else
                 'production' in r['facts'] and (r['steps']>0 if cid=='dispatch-6' else
                  r['dispatches']==0 if expected.endswith('7001') else r['dispatches']>0)))
    if cid.startswith('inventory-'):
        exclusion = (r['scope']['selectors'] or {}).get('excluded_material_keyword')
        if cid=='inventory-2':
            return r['pcbRows']>0 and r['pcbRows']==r['rowCounts'].get('inventory')
        if cid in ('inventory-3','inventory-4'):
            return (bool(exclusion) and 'PCB' in exclusion.upper() and r['pcbRows']==0 and
                    r['rowCounts'].get('inventory',0)>0 and (cid!='inventory-4' or r['scope']['criteria'].get('INVENTORY')=='AVAILABLE'))
        return 'inventory' in r['facts']
    required = {'risk-1':{'shared_material','capacity_conflicts'},'risk-2':{'shared_material'},'risk-3':{'capacity_conflicts'}}
    if cid in required:
        return required[cid].issubset(r['facts'])
    return ('production' if cid.startswith('production') else 'quality') in r['facts']


def replay(group, cases, headers, out):
    previous = None
    sid = str(uuid.uuid4())
    report = []
    with requests.Session() as session:
        session.trust_env = False
        session.headers.update(headers)
        for case in cases:
            cid = case['id']
            if cid in ('dispatch-4','inventory-2') or group in ('risk','boundary','production','quality'):
                previous, sid = None, str(uuid.uuid4())
            try:
                if group == 'order':
                    response = session.post(MES+'/assistant-v2/orders/turn',json={
                        'sessionId':sid,'requestId':str(uuid.uuid4()),'text':case['question']},timeout=130)
                    response.raise_for_status()
                    body = response.json()
                    if body['code'] != 200:
                        result_code = str(body.get('code'))
                        raise ValueError(result_code if result_code.isdigit() else 'DRAFT_FAILED')
                    data = body['data']
                    draft = data.get('orderDraft') or {}
                    fields = draft.get('fields') or {}
                    result = {'handled':data['handled'],'state':draft.get('status'),'quantity':str(fields.get('quantity')),
                              'hasAddress':bool(fields.get('receiverAddress') or fields.get('shippingAddress')),'reply':data.get('reply','')[:180]}
                else:
                    previous,result = query(session,case['question'],sid,previous)
            except Exception as exc:
                result = {'error':type(exc).__name__}
                if isinstance(exc,requests.HTTPError) and exc.response is not None:
                    result['http'] = exc.response.status_code
                if isinstance(exc,ValueError) and str(exc).isdigit():
                    result['code'] = int(str(exc))
                previous = None
            row = {'id':cid, **result, 'passed':check(cid,result)}
            (out/(cid+'.json')).write_text(json.dumps(row,ensure_ascii=False),'utf-8')
            print(cid, 'PASS' if row['passed'] else 'FAIL',flush=True)
            report.append(row)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--groups',nargs='*')
    args = parser.parse_args()
    args.out.mkdir(exist_ok=True,parents=True)
    groups = {}
    for case in json.loads((BASE/'trace-baseline.json').read_text('utf-8'))['cases']:
        groups.setdefault(case['id'].split('-')[0],[]).append(case)
    with requests.Session() as session:
        session.trust_env = False
        response = session.post(MES+'/auth/login',json={'username':os.environ['MES_TRACE_USER'],
            'password':os.environ['MES_TRACE_PASSWORD']},timeout=15)
        response.raise_for_status()
        headers = {'Authorization':'Bearer '+response.json()['data']['token']}
        try:
            with ThreadPoolExecutor(max_workers=2) as pool:
                jobs = [pool.submit(replay,g,cs,headers,args.out) for g,cs in groups.items() if not args.groups or g in args.groups]
                results = [r for job in jobs for r in job.result()]
            print(f'{sum(r["passed"] for r in results)}/{len(results)} passed',flush=True)
            return int(not all(r['passed'] for r in results))
        finally:
            session.post(MES+'/auth/logout',headers=headers,timeout=10)


if __name__=='__main__':
    raise SystemExit(main())

