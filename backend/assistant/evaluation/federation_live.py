"""Exercise the authenticated chat stream. Set MES_TRACE_USER and MES_TRACE_PASSWORD for a local admin."""
import argparse
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path

import requests


WORKBENCH = 'http://127.0.0.1:18083/assistant-workbench'
MES = 'http://127.0.0.1:8088'
CASES = {
    'knowledge': '公开文献中显示屏划痕常用哪些检测方法？',
    'current': '查询当前库存明细',
    'graph': '请查询公开文献中 Mura 缺陷与检测方法的关联路径。',
    'diagnosis': '分析本厂历史维修记录与质检记录的关联，给出故障诊断建议。',
}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def evaluate(session, case):
    started = time.monotonic()
    payload = {'sessionId': str(uuid.uuid4()), 'requestId': str(uuid.uuid4()), 'text': CASES[case]}
    observed, final, delta_count = {}, None, 0
    with session.post(WORKBENCH + '/runs/stream', json=payload, stream=True, timeout=(10, 300)) as stream:
        stream.raise_for_status()
        for line in stream.iter_lines():
            if not line:
                continue
            event = json.loads(line)
            if event['type'] == 'workflow':
                for item in event.get('events', []):
                    observed[item['seq']] = item
            elif event['type'] == 'result':
                final = event['data']
            elif event['type'] == 'delta' and event.get('text'):
                delta_count += 1
            elif event['type'] == 'error':
                code = str(event.get('message'))
                raise ValueError(code if re.fullmatch(r'[A-Z_]{1,64}', code) else 'WORKFLOW_ERROR')
    check(final and final.get('verdict') in ('EVIDENCE_READY','INSUFFICIENT_EVIDENCE'),
          'No completed evidence response: '+str(final.get('verdict') if final else 'NO_RESULT'))
    check(delta_count > 0 and final.get('reply'), 'No streamed answer')
    calls = {e['toolId'] for e in observed.values() if e.get('toolCallId') and e.get('toolId') and e.get('status') == 'COMPLETED'}
    facts = set(final.get('facts', {}))
    mysql = any(key.startswith('OVERVIEW_') for key in calls)
    public = final.get('knowledgeEvidence', [])
    history = final.get('experienceEvidence', [])
    public_hits = [h for b in public for h in b.get('hits', [])]
    public_paths = [p for b in public for p in b.get('graph_paths', [])]
    history_hits = [h for b in history for h in b.get('hits', [])]
    history_paths = [p for b in history for p in b.get('graph_paths', [])]
    refs = {h['evidence_ref'] for h in public_hits}
    check(all(p.get('evidence_refs') and set(p['evidence_refs']) <= refs for p in public_paths), 'Unbound public graph path')
    refs = {h['evidence_ref'] for h in history_hits}
    check(all(p.get('source_ref') in refs and p.get('target_ref') in refs for p in history_paths), 'Unbound private path')
    if case == 'knowledge':
        check(public_hits and not public_paths and not history and not facts and not mysql, 'Pure knowledge source selection')
        check(all(b.get('requested_mode') == 'vector' and b.get('graph_status') == 'NOT_REQUESTED' for b in public), 'Knowledge used graph')
        check('KNOWLEDGE_RETRIEVAL' in calls, 'Missing public source event')
    elif case == 'current':
        check(mysql and 'inventory' in facts and not history and not public, 'Current inventory source selection')
        check('KNOWLEDGE_RETRIEVAL' not in calls and 'EXPERIENCE_RETRIEVAL' not in calls, 'Unexpected supplemental call')
    elif case == 'graph':
        check(public_hits and public_paths and not history and not facts, 'No verified graph path for public concept')
        check(any(b.get('mode_used') == 'graph_assisted' for b in public), 'Graph mode missing')
    else:
        check(mysql and {'quality','maintenance'} <= facts, 'Missing scoped current evidence')
        check({'OVERVIEW_MAINTENANCE','OVERVIEW_QUALITY','EXPERIENCE_RETRIEVAL'} <= calls, 'Missing diagnostic tool receipts')
        check(any(b.get('domain') == 'DIAGNOSTIC' and b.get('vector_status') == 'MATCHES'
                  and b.get('graph_status') == 'MATCHES' for b in history), 'Missing scoped multi-source result')
        check(history_paths and len(refs) == len(history_hits) and len({h['kind'] for h in history_hits}) == 2,
              'Missing unique cross-domain history evidence')
        check(all(h.get('record_hash') and h.get('record_id') and h.get('source_table') for h in history_hits), 'Missing history provenance')
    return {'case':case,'passed':True,'verdict':final['verdict'],'seconds':round(time.monotonic()-started,1),
            'tools':sorted(calls),'fact_domains':sorted(facts),'stream_deltas':delta_count,
            'public_hits':len(public_hits),'public_paths':len(public_paths),
            'history_hits':len(history_hits),'history_paths':len(history_paths)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', nargs='+', choices=CASES, default=list(CASES))
    parser.add_argument('--out', type=Path, help='Optional JSON receipt containing counts, timings and tool names')
    args = parser.parse_args()
    records = []
    with requests.Session() as session:
        session.trust_env = False
        login = session.post(MES + '/auth/login', json={'username':os.environ['MES_TRACE_USER'],
                             'password':os.environ['MES_TRACE_PASSWORD']}, timeout=15)
        login.raise_for_status()
        check(login.json().get('code') == 200, 'Login failed')
        session.headers['Authorization'] = 'Bearer ' + login.json()['data']['token']
        try:
            for case in args.cases:
                try:
                    row = evaluate(session, case)
                except ValueError as exc:
                    row = {'case':case,'passed':False,'error':str(exc)[:100]}
                except requests.RequestException as exc:
                    row = {'case':case,'passed':False,'error':type(exc).__name__}
                records.append(row)
                print(json.dumps(row,ensure_ascii=False),flush=True)
                if args.out:
                    args.out.parent.mkdir(parents=True,exist_ok=True)
                    args.out.write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
        finally:
            session.post(MES + '/auth/logout',timeout=10)
    return 0 if all(r['passed'] for r in records) else 1


if __name__ == '__main__':
    sys.exit(main())
