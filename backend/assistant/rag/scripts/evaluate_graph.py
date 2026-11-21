"""Read-only live graph/API checks. Uses only public papers and labeled demo snapshots."""
import argparse
import json
import re
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx
from neo4j.exceptions import Neo4jError
from mes_knowledge.cli import settings_from_file
from mes_knowledge.config import RAG_ROOT
from mes_knowledge.graph.config import graph_settings, open_graph
from mes_knowledge.graph.business import trace_business
from mes_knowledge.graph.business_store import BusinessGraphStore


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    config=settings_from_file(RAG_ROOT.parent/'deploy/.env.knowledge.local')
    gc=graph_settings()
    graph=open_graph(gc)
    checks={}
    try:
        checks['database_read_only']=graph.readonly()
        with graph.driver.session(database='neo4j') as session:
            tx=session.begin_transaction()
            try:
                tx.run('CREATE (:WriteDenialProbe)').consume()
                checks['graph_write_rejected']=False
            except Neo4jError as exc:
                checks['graph_write_rejected']=exc.code=='Neo.ClientError.General.WriteOnReadOnlyAccessDatabase'
            finally:
                tx.rollback()  # No probe persists even if configuration is wrong.
    finally:
        graph.close()
    questions=[('Mura检测与分段伽马校正、APSO有什么关系？','CL_PGC_APSO'),
               ('串扰检测的显著颜色通道滤波使用什么技术？','CL_CT_FILTER'),
               ('YOLO-DEI如何使用DCNv2检测LCD缺陷？','CL_YOLO_DCN')]
    results=[]
    headers={'Authorization':'Bearer '+config.api_key.get_secret_value()}
    with httpx.Client(base_url='http://127.0.0.1:18081',headers=headers,trust_env=False,timeout=30) as client:
        for question, expected_claim in questions:
            start=time.perf_counter()
            res=client.post('/internal/knowledge/query',json={'query':question,'mode':'graph_assisted','top_k':5})
            res.raise_for_status(); data=res.json()
            found=expected_claim in {p['claim_id'] for p in data['graph_paths']}
            refs={h['evidence_ref'] for h in data['hits']}
            valid=all(set(p['evidence_refs'])<=refs for p in data['graph_paths'])
            for hit in data['hits']:
                returned=client.get(hit['evidence_path']); returned.raise_for_status()
                valid=valid and returned.json()['text']==hit['text']
            results.append({'query':question,'expected_claim':expected_claim,'found':found,
                'all_evidence_verified':valid,'seconds':round(time.perf_counter()-start,3),
                'mode_used':data['mode_used'],'graph_status':data['graph_status'],
                'graph_paths':data['graph_paths'],'complete':data['complete']})
        checks['private_template_rejected']=client.post('/internal/knowledge/graph/trace',
            json={'template_id':'ORDER_CHAIN','entity_id':'Order:900001'}).status_code==422
        checks['arbitrary_cypher_rejected']=client.post('/internal/knowledge/graph/trace',
            json={'entity_id':'DEF_MURA','cypher':'MATCH (n) RETURN n'}).status_code==422
        plain=client.post('/internal/knowledge/query',json={'query':'Mura如何检测？','top_k':5})
        checks['vector_contract_preserved']=plain.status_code==200 and 'graph_paths' not in plain.json()
        trace=client.post('/internal/knowledge/graph/trace',json={'entity_id':'DEF_MURA','max_paths':1})
        checks['trace_reports_truncation']=trace.status_code==200 and trace.json()['truncated'] is True
    business=BusinessGraphStore(gc.uri,gc.user,gc.password.get_secret_value())
    try:
        demo=trace_business(config.data_dir,business,'900001')
        checks['synthetic_order_chain']=demo['synthetic'] and len(demo['nodes'])==5 and len(demo['edges'])==4
    finally:
        business.close()
    record={'checked_at':datetime.now(timezone.utc).isoformat(),
        'scope':'Authored smoke questions, not a held-out accuracy benchmark. Business chain is explicitly synthetic.',
        'checks':checks,'results':results}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    passed=all(checks.values()) and all(r['found'] and r['all_evidence_verified'] for r in results)
    print(json.dumps({'passed':passed,'checks':checks,'queries':len(results)},ensure_ascii=False))
    raise SystemExit(0 if passed else 1)


if __name__=='__main__':
    main()
