"""Live acceptance at the workbench NDJSON endpoint. Use an authorized local account.
Raw evidence and conversation text are kept in a user-selected local report directory.
"""
import argparse
import json
import os
from pathlib import Path
from time import monotonic, sleep
from uuid import uuid4

import requests
from replay import MES, GATEWAY

CASES = {
    'defense': '列出全部质检记录。',
    'recent': '看一下最近的质检情况。',
    'route': '八道工序分别是什么？',
    'inspection': 'QI202607021 存在什么问题？主要可能涉及哪个生产环节？',
    'personnel': 'QI202607021这批工单都是由谁来生产和质检的，为什么不合格品这么多？',
    'optical_review': '请结合生产过程解释 QI202607013 这批产品的质检结果、可能原因和验证办法。',
    'incoming_review': 'QI202607301 这批质检产品情况怎么样？能从检验结果看出原因吗？',
    'reported_review': 'QI202611001 这批产品最可能是哪个环节出了问题？对照图片、同批报工和历史类似缺陷，按依据说明先查哪里、怎么验证。',
    'pixel_review': 'QI202607022 这批产品最可能是哪个环节出了问题，先查哪里，怎么验证？',
    'batch': '为什么近期某个批次的不良率升高？',
    'named_batch': '批号是 BATCH-WO202607002-3201，比较 2026-07-11 和 2026-07-12 的抽检不良率，能确定升高了吗？',
    'general': '抽检合格率和整批合格率有什么区别？请解释概念。',
    'status_mismatch': '请核对检验单 QI202611001 的判定状态与不合格数量，是否一致？',
}


def turn(client, question, sid, previous=None):
    payload = dict(text=question, sessionId=sid, requestId=str(uuid4()))
    if previous:
        payload['previousRunId'] = previous
    events, answer, run_id, deltas = {}, None, None, 0
    # The live service keeps prior runs for multi-turn context. Back off at admission
    # when a succession of suites fills that window; never replay an accepted stream.
    deadline=monotonic()+180
    while True:
        response=client.post(GATEWAY,json=payload,stream=True,timeout=(8,240))
        if response.status_code!=429 or monotonic()>=deadline:
            break
        response.close()
        sleep(min(15,max(0,deadline-monotonic())))
    with response:
        response.raise_for_status()
        for line in response.iter_lines():
            if not line:
                continue
            event = json.loads(line)
            if event['type']=='run':
                run_id=event['runId']
            elif event['type']=='workflow':
                for row in event.get('events',[]):
                    if row.get('toolId'):
                        events[row['seq']]={k:row.get(k) for k in ('toolId','status','kind')}
            elif event['type']=='result':
                answer=event['data']
            elif event['type']=='error':
                raise RuntimeError(f"Workbench stream failed ({event.get('message', 'unknown')})")
            elif event['type']=='delta' and event.get('text'):
                deltas+=1
    if answer is None:
        raise RuntimeError('Stream ended without result')
    return run_id, dict(answer=answer,events=list(events.values()),deltas=deltas)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--out',required=True,type=Path)
    parser.add_argument('--cases',choices=list(CASES),nargs='+',default=list(CASES))
    args=parser.parse_args()
    args.out.mkdir(exist_ok=True,parents=True)
    with requests.Session() as client:
        client.trust_env=False
        login=client.post(MES+'/auth/login',json=dict(username=os.environ['MES_TRACE_USER'],password=os.environ['MES_TRACE_PASSWORD']),timeout=(8,30))
        login.raise_for_status()
        client.headers['Authorization']='Bearer '+login.json()['data']['token']
        try:
            batch_context=None
            for case in args.cases:
                sid=str(uuid4())
                prompts=[(case,CASES[case])]
                if case=='defense':
                    prompts.extend([
                        ('defense_selected','我选 QI202607013。请结合生产过程解释这批产品的质检结果、可能原因和验证办法。'),
                        ('defense_followup','这批产品最值得先查哪个环节？为什么先查，怎么验证？'),
                        ('defense_next','换一批，分析检验单 QI202607022 的质检结果。有哪些原始依据？'),
                        ('defense_next_followup','这批产品先查哪个环节？工序前后有同一台样机的实测值吗？'),
                    ])
                if case=='inspection':
                    prompts.append(('followup','那这张单关联的设备能确定吗？'))
                if case=='optical_review':
                    prompts.append(('optical_followup','这批产品最可能是哪个环节出了问题？'))
                prior=None
                if case=='named_batch':
                    if batch_context:
                        sid,prior=batch_context
                    else:
                        prior,_=turn(client,CASES['batch'],sid)
                for name,q in prompts:
                    prior,result=turn(client,q,sid,prior)
                    if name=='batch':
                        batch_context=(sid,prior)
                    (args.out/(name+'.json')).write_text(json.dumps(result,ensure_ascii=False,default=str),'utf-8')
                    print(name,result['answer'].get('verdict'),list((result['answer'].get('facts') or {}).keys()),result['deltas'],flush=True)
        finally:
            client.post(MES+'/auth/logout',timeout=10)


if __name__=='__main__':
    main()

