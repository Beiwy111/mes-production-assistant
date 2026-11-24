"""Check local admin acceptance receipts against the same site's current read-only SQL snapshot.

Run shortly after inspection_live.py, while the test records and deployed workshop catalogue match.
Limited-role sessions have separate permission tests and different expected evidence.
"""
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
import re
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'backend/assistant/rag/src'))
from mes_knowledge.experience.source import reader_transaction


def require(value, reason):
    if not value:
        raise ValueError(reason)


def select(cursor, sql, params=()):
    cursor.execute(sql, params)
    return cursor.fetchall()


def keys(rows, *columns):
    return sorted(tuple('' if r.get(k) is None else str(r[k]) for k in columns) for r in rows)


def check_run(packet, case):
    answer = packet['answer']
    require(packet['deltas'] > 0 and answer.get('reply'), case+' empty stream')
    tools = {e['toolId'] for e in packet['events'] if e['status']=='COMPLETED'}
    return answer, answer.get('facts') or {}, tools


def recent(cursor, packet):
    a, facts, tools = check_run(packet, 'recent')
    require('OVERVIEW_QUALITY' in tools, 'recent quality tool missing')
    table = facts['quality']['overview']
    require(table.get('period') == 'LAST_7_DAYS' or a.get('scope',{}).get('period') == 'LAST_7_DAYS', 'recent time scope')
    day = datetime.fromisoformat(a['checkedAt'].replace('Z','+00:00')).astimezone(timezone(timedelta(hours=8))).date()
    start, end = day - timedelta(days=6), day + timedelta(days=1)
    require(str(table['startInclusive'])[:10] == str(start) and str(table['endExclusive'])[:10] == str(end), 'recent dates')
    expected=select(cursor,'SELECT inspection_id AS record_id,inspection_no FROM quality_inspection '
                    'WHERE inspected_at >= %s AND inspected_at < %s',(start,end))
    require(keys(table['rows'],'record_id','inspection_no')==keys(expected,'record_id','inspection_no'),'recent rows differ')
    if not expected:
        require(any(w in a['reply'] for w in ('未','无','没有','0','零')), 'recent empty result unexplained')
        vision=facts['quality']['visionEvidence']
        require(vision['status']=='NO_INSPECTIONS_IN_SCOPE' and vision['missingInspectionArchiveCount']==0,
                'empty result incorrectly classified as missing historical archives')
    return dict(inspections=len(expected), start=str(start), through=str(end-timedelta(days=1)))


def route(cursor, packet):
    a,facts,tools=check_run(packet,'route')
    require('PROCESS_ROUTE' in tools and 'KNOWLEDGE_RETRIEVAL' not in tools,'route used wrong tools')
    data=facts['process_route']
    stages=('主板装配','电源板装配','接口板装配','显示屏加工','面板贴附','外壳装配','整机组装','支架底座装配')
    require(data.get('stageSource')=='ProductionWorkshopCatalog.PRODUCTION_STAGES','workshop catalogue source missing')
    require([(int(row['step_no']),row['step_name']) for row in data.get('productionStages',[])]
            ==list(enumerate(stages,1)), 'workshop dispatch catalogue sequence differs')
    offsets=[a['reply'].find(stage) for stage in stages]
    require(all(i>=0 for i in offsets) and offsets==sorted(offsets),'answer omitted or reordered workshop stages')
    actual=data['rows']
    db=select(cursor,'SELECT r.route_id,r.route_code,r.version_no,s.step_id,s.step_no,s.step_name FROM process_route r '
              'LEFT JOIN process_step s ON s.route_id=r.route_id AND s.status=1 WHERE r.status=1')
    require(keys(actual,'route_id','route_code','version_no','step_id','step_no','step_name')
            ==keys(db,'route_id','route_code','version_no','step_id','step_no','step_name'),'route rows differ')
    counts=Counter(r['route_code'] for r in db if r.get('step_id') is not None)
    require(all(str(n) in (a['reply']+facts['process_route']['summary']) for n in set(counts.values())),'missing route counts')
    return dict(workshop_stages=stages,routes=len(counts),step_counts=dict(counts),eight_step_routes=[k for k,n in counts.items() if n==8])


def inspection(cursor, packet, name):
    a, facts, tools=check_run(packet,name)
    require('INSPECTION_HEADER' in tools and 'INSPECTION_DETAILS' in tools and 'INSPECTION_BATCH' in tools
            and 'INSPECTION_PRODUCTION' in tools and 'INSPECTION_EQUIPMENT' in tools,'trace tools missing')
    trace=facts['inspection_trace']
    header=select(cursor,'SELECT inspection_id,inspection_no,work_order_id,work_report_id,material_id,batch_no,'
                    'inspection_status,inspection_result,sample_quantity,qualified_quantity,unqualified_quantity,inspection_category '
                    'FROM quality_inspection WHERE inspection_no=%s',('QI202607021',))[0]
    actual=trace['inspection'][0]
    for field in header:
        if field.endswith('quantity'):
            require(Decimal(str(actual[field]))==header[field], 'header quantity '+field)
        else:
            require(str(actual.get(field))==str(header[field]), 'header '+field)
    inspection_id,work=header['inspection_id'],header['work_order_id']
    items=select(cursor,'SELECT inspection_item_id,item_name,measured_value,result FROM quality_inspection_item WHERE inspection_id=%s',(inspection_id,))
    nc=select(cursor,'SELECT nonconforming_id,nonconforming_no,defect_type,quantity FROM nonconforming_product WHERE inspection_id=%s',(inspection_id,))
    same=select(cursor,'SELECT inspection_id,inspection_no,work_order_id FROM quality_inspection WHERE batch_no=%s AND material_id=%s '
                'AND inspection_category=%s',(header['batch_no'],header['material_id'],header['inspection_category']))
    for fields,source,shown in [(('inspection_item_id','item_name','measured_value','result'),items,trace['items']),
                               (('nonconforming_id','nonconforming_no','defect_type'),nc,trace['defects']),
                               (('inspection_id','inspection_no','work_order_id'),same,trace['sameBatchInspections'])]:
        require(keys(source,*fields)==keys(shown,*fields), 'trace child rows '+fields[0])
    require({r['nonconforming_id']: Decimal(str(r['quantity'])) for r in nc} ==
            {r['nonconforming_id']: Decimal(str(r['quantity'])) for r in trace['defects']}, 'defect quantities differ')
    batch=select(cursor,'SELECT batch_id FROM material_batch WHERE material_id=%s AND batch_no=%s',(header['material_id'],header['batch_no']))
    require(keys(batch,'batch_id')==keys(trace['batchRecords'],'batch_id'),'batch registry contamination')
    work_rows=select(cursor,'SELECT work_order_id,work_order_no FROM work_order WHERE work_order_id=%s',(work,))
    require(keys(work_rows,'work_order_id','work_order_no')==keys(trace['workOrder'],'work_order_id','work_order_no'),'work order mismatch')
    steps=select(cursor,'SELECT s.step_id,s.step_name,s.step_no FROM work_order w JOIN process_step s ON s.route_id=w.route_id '
                 'WHERE w.work_order_id=%s',(work,))
    require(keys(steps,'step_id','step_name','step_no')==keys(trace['routeSteps'],'step_id','step_name','step_no'),'incorrect candidate route')
    reports=select(cursor,'SELECT report_id FROM work_report WHERE work_order_id=%s OR report_id=%s',(work,header['work_report_id']))
    dispatch=select(cursor,'SELECT dispatch_id FROM dispatch_task WHERE work_order_id=%s',(work,))
    require(keys(reports,'report_id')==keys(trace['reportRows'],'report_id'),'report scope')
    require(keys(dispatch,'dispatch_id')==keys(trace['dispatchRows'],'dispatch_id'),'dispatch scope')
    alarms=select(cursor,'SELECT alarm_id FROM andon_alarm WHERE reported_at <= '
                 '(SELECT inspected_at FROM quality_inspection WHERE inspection_id=%s) AND (work_order_id=%s OR (work_order_id IS NULL '
                 'AND dispatch_id IN (SELECT dispatch_id FROM dispatch_task WHERE work_order_id=%s)))',(header['inspection_id'],work,work))
    require(keys(alarms,'alarm_id')==keys(trace['alarmRows'],'alarm_id'),'incorrect alarm linkage')
    if not reports and not dispatch:
        require(not trace['equipmentRows'] and '设备' in str(trace['missingData']), 'unsupported equipment assignment')
    require(all(str(p.get('inspection_id'))==str(inspection_id) for p in trace['graphPaths']), 'foreign graph evidence')
    require(trace['missingData'] and trace['possibleCauses'], 'missing data or hypothesis not marked')
    if name=='inspection':
        require('KNOWLEDGE_RETRIEVAL' in tools,'diagnosis omitted supplemental reference lookup')
        require('HDMI' in a['reply'] and '接口' in a['reply'] and '划伤' in a['reply'],'specific defects absent in answer')
    if name=='followup':
        require(a['scope'].get('inspectionNo')==header['inspection_no'] and '设备' in a['reply'],'followup entity lost')
    if name=='personnel':
        reply=a['reply']
        qc=select(cursor,'SELECT u.real_name FROM quality_inspection q JOIN `user` u ON u.user_id=q.inspector_id '
                  'WHERE q.inspection_id=%s',(inspection_id,))[0]['real_name']
        archive=select(cursor,'SELECT record_no,operator_name,step_name FROM assistant_training_work_history '
                       'WHERE work_order_no=%s',(work_rows[0]['work_order_no'],))
        require(qc==actual['inspector_name'] and qc in reply,'inspector missing from streamed answer')
        require(archive and keys(archive,'record_no','operator_name','step_name')==
                keys(trace['trainingHistory'],'record_no','operator_name','step_name'),'archive evidence mismatch')
        require(all(r['operator_name'] in reply for r in archive) and '实训' in reply,'training personnel/provenance missing')
        require('30%' in reply and 'HDMI' in reply and '划伤' in reply,'rate or defect analysis missing')
    anomalies=select(cursor,"SELECT inspection_no FROM quality_inspection WHERE (inspection_status='PASSED' OR inspection_result='QUALIFIED') AND unqualified_quantity>0")
    return dict(inspection_id=inspection_id,work_order_id=work,items=len(items),nonconforming=len(nc),
                same_batch=len(same),route_steps=len(steps),reports=len(reports),dispatches=len(dispatch),alarms=len(alarms),
                passed_with_rejects=[r['inspection_no'] for r in anomalies])


def verify(cursor, name, packet):
    if name=='recent':return recent(cursor,packet)
    if name=='route':return route(cursor,packet)
    if name in ('inspection','followup','personnel'):return inspection(cursor,packet,name)
    a,facts,tools=check_run(packet,name)
    if name.startswith('defense'):
        require(a['verdict']=='EVIDENCE_READY', 'defense did not return verified data')
        if name=='defense':
            observed=facts['quality']['overview']['rows']
            expected=select(cursor,'SELECT inspection_no FROM quality_inspection')
            require(keys(observed,'inspection_no')==keys(expected,'inspection_no'),'incomplete admin inspection list')
            return dict(visible_inspections=len(observed))
        number='QI202607022' if name.startswith('defense_next') else 'QI202607013'
        t=facts['inspection_trace']
        h=select(cursor,'SELECT inspection_id,sample_quantity,qualified_quantity,unqualified_quantity FROM quality_inspection WHERE inspection_no=%s',(number,))[0]
        require(a['scope']['inspectionNo']==number and str(t['inspection'][0]['inspection_id'])==str(h['inspection_id']),'lost selected inspection')
        for field in ('sample_quantity','qualified_quantity','unqualified_quantity'):
            require(Decimal(str(t['inspection'][0][field]))==h[field],field+' differs from source')
        for table,group in [('quality_inspection_round','inspectionEvents'),('quality_defect_sample','sampleBindings'),('quality_sample_measurement','sampleMeasurements')]:
            require(len(t[group])==select(cursor,f'SELECT COUNT(*) n FROM {table} WHERE inspection_id=%s',(h['inspection_id'],))[0]['n'],group+' differs from source')
        seed=t.get('legacyBaseline') or {}
        if number=='QI202607013':
            require(seed.get('kind')=='DEMO_SEED_REFERENCE' and seed.get('qualified_quantity')==8 and seed.get('unqualified_quantity')==2,'unlabelled or missing fixture reference')
            require(t['sampleRateStatus']=='WITHHELD_CONFLICT' and t.get('confirmedSampleRejectRate') is None,'conflicting counts yielded rate')
            require(not re.search(r'(?<![\d.])0(?:\.0+)?\s*[%％]',a['reply']),'zero percent appeared in conflict answer')
            require('复' in a['reply'] and ('核' in a['reply'] or '冲突' in a['reply']), 'missing reconciliation')
        else:
            require(t['confirmedSampleRejectRate']=='33.33%' and h['unqualified_quantity']==1 and h['sample_quantity']==3,'matched second lot incorrectly')
        return dict(inspection=number,sample_rate=t['sampleRateStatus'],events=len(t['inspectionEvents']),
                    bindings=len(t['sampleBindings']),measurements=len(t['sampleMeasurements']),deltas=packet['deltas'])
    if name in ('reported_review','pixel_review'):
        number,prior={'reported_review':('QI202611001','QI202607503'),
                      'pixel_review':('QI202607022','QI202607011')}[name]
        t=facts['inspection_trace']
        require(a['scope'].get('inspectionNo')==number and {'INSPECTION_VISION','INSPECTION_HISTORY','INSPECTION_PRODUCTION'}<=tools,'ranked trace scope or tool missing')
        matches=select(cursor,'SELECT n.nonconforming_no,n.defect_type,n.handle_method,b.batch_no FROM nonconforming_product n '
                       'JOIN quality_inspection b ON b.inspection_id=n.inspection_id JOIN quality_inspection q ON q.inspection_no=%s '
                       'WHERE b.inspection_no=%s AND b.inspected_at<q.inspected_at AND b.batch_no<>q.batch_no',(number,prior))
        require(bool(matches) and keys(matches,'nonconforming_no','defect_type','handle_method','batch_no')==
                keys([r for r in t['similarDefects'] if r['inspection_no']==prior],'nonconforming_no','defect_type','handle_method','batch_no'),'historical comparison differs from source')
        direct=select(cursor,'SELECT r.report_no FROM quality_inspection q JOIN work_report r ON r.report_id=q.work_report_id WHERE q.inspection_no=%s',(number,))
        require(keys(direct,'report_no')==keys([r for r in t['lotReports'] if r['inspection_no']==number],'report_no'),'direct report differs from source')
        require(prior in a['reply'] or any(r['nonconforming_no'] in a['reply'] for r in matches),'answer lacks historical provenance')
        return dict(inspection=number,history=prior,reports=len(direct),vision=t['visionStatus'],deltas=packet['deltas'])
    if name in ('optical_review','optical_followup','incoming_review'):
        number='QI202607301' if name=='incoming_review' else 'QI202607013'
        h=select(cursor,'SELECT inspection_id,inspection_no,inspection_status,inspection_result,work_order_id,'
                 'sample_quantity,qualified_quantity,unqualified_quantity FROM quality_inspection WHERE inspection_no=%s',(number,))[0]
        trace=facts['inspection_trace']
        require(a['scope'].get('inspectionNo')==number and 'INSPECTION_DETAILS' in tools,'inspection scope lost')
        actual=trace['inspection'][0]
        for k,v in h.items():
            if k.endswith('quantity'):
                require(Decimal(str(actual[k]))==v,'quantity mismatch '+k)
            else:
                require(actual.get(k)==v,'header mismatch '+k)
        reply=a['reply']
        if name=='incoming_review':
            require(h['inspection_status']=='PENDING' and not h['work_order_id'] and not trace['defects'],'expected pending incoming inspection')
            require('待检' in reply and '0%' not in reply and any(s in reply for s in ('来料','进料')),'pending diagnosis')
        else:
            nc=select(cursor,'SELECT nonconforming_no,defect_type FROM nonconforming_product WHERE inspection_id=%s',(h['inspection_id'],))
            require(keys(nc,'nonconforming_no','defect_type')==keys(trace['defects'],'nonconforming_no','defect_type'),'optical defect scope')
            require(all(row['defect_type'] in reply or row['nonconforming_no'] in reply or
                        row['defect_type'].replace('超标','') in reply for row in nc),'optical observations missing')
            require(bool(trace['conflicts']) and any(k in reply for k in ('矛盾','不一致','冲突','不符')),'conflict not surfaced')
        return {'inspection':number,'tool':'INSPECTION_DETAILS','deltas':packet['deltas']}
    if name=='status_mismatch':
        require('INSPECTION_HEADER' in tools,'mismatch example did not read header')
        record=select(cursor,'SELECT inspection_id,inspection_no,inspection_status,inspection_result,sample_quantity,'
                      'qualified_quantity,unqualified_quantity FROM quality_inspection WHERE inspection_no=%s',('QI202611001',))[0]
        trace=facts['inspection_trace']
        observed=trace['inspection'][0]
        require(keys([record],'inspection_id','inspection_no','inspection_status','inspection_result')==
                keys([observed],'inspection_id','inspection_no','inspection_status','inspection_result'),'mismatch inspection identity')
        for k in ('sample_quantity','qualified_quantity','unqualified_quantity'):
            require(Decimal(str(observed[k]))==record[k],'mismatch inspection quantities')
        require(record['inspection_result']=='QUALIFIED' and record['unqualified_quantity']>0,'update mismatch fixture')
        require(trace['conflicts'] and '合格' in a['reply'] and any(w in a['reply'] for w in ('准则','复核','复判','接受')),
                'mismatch needs explicit reconciliation')
        return {k:str(v) for k,v in record.items()}
    if name=='named_batch':
        batch='BATCH-WO202607002-3201'
        require(a['verdict']=='EVIDENCE_READY' and 'OVERVIEW_QUALITY' in tools,'named batch lacked real quality query')
        quality=facts['quality']
        report=quality['overview']
        require(report['period']=='COMPARISON' and report['selectors'].get('batch_no')==batch,'named batch scope')
        rows=select(cursor,'SELECT inspection_id record_id,inspection_no,inspection_type,batch_no,'
                    'sample_quantity,unqualified_quantity FROM quality_inspection WHERE batch_no=%s '
                    'AND inspected_at >= %s AND inspected_at < %s ORDER BY inspected_at,inspection_id',
                    (batch,'2026-07-11 00:00:00','2026-07-13 00:00:00'))
        require(keys(report['rows'],'record_id','inspection_no','inspection_type','batch_no')==
                keys(rows,'record_id','inspection_no','inspection_type','batch_no'),'two-period IDs differ from original source')
        require([r['inspection_type'] for r in rows]==['FINAL','RECHECK'],'expected initial/final and reinspection on separate dates')
        compare={item['inspection_type']:item for item in quality['batchComparison']}
        require(set(compare)=={'FINAL','RECHECK'},'original inspection pooled with reinspection')
        for r,side in zip(rows,('before','after')):
            n,bad=Decimal(r['sample_quantity']),Decimal(r['unqualified_quantity'])
            expected=format(100*bad/n,'.2f')+'%'
            v=compare[r['inspection_type']]
            require(expected in v[side] and v['difference_percentage_points'] is None,'uncomparable samples treated as a rise')
            require(expected in a['reply'],'streamed reply omitted actual sample percentage')
        require(any(x in a['reply'] for x in ('不能判定','不能确认','无法判断','无法判定','无法确认')),'missing incomparable periods warning')
        return dict(batch=batch,keys=keys(rows,'record_id','inspection_no','inspection_type'),comparison='separate sample denominators')
    if name=='batch':
        require(a['verdict']=='CLARIFY' and not facts and not a['executedAgents'] and not tools,'unanchored batch read')
        require('批' in a['reply'] and any(w in a['reply'] for w in ('时间','时期','期间','日期','周期')),'clarification lacks comparison scope')
    else:
        require('GENERAL' in a['intents'] and not facts and 'KNOWLEDGE_RETRIEVAL' not in tools and not (a.get('knowledgeEvidence') or []),'general used enterprise data')
        require('抽' in a['reply'] and '整批' in a['reply'],'missing general explanation')
    return {'tools': sorted(tools)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True,help='Read existing workbench result JSONs from this directory')
    parser.add_argument('--cases',nargs='+',choices=['recent','route','inspection','followup','personnel','optical_review','optical_followup','incoming_review','reported_review','pixel_review','batch','named_batch','general','status_mismatch',
                        'defense','defense_selected','defense_followup','defense_next','defense_next_followup'],
                        default=['recent','route','inspection','followup','batch','named_batch','general','status_mismatch'])
    args=parser.parse_args()
    failures=[]
    with reader_transaction('agent') as cursor:
        for name in args.cases:
            try:
                details=verify(cursor,name,json.loads((args.out/(name+'.json')).read_text('utf-8')))
                print(name,'PASS',json.dumps(details,ensure_ascii=False),flush=True)
            except (IndexError,KeyError,ValueError,FileNotFoundError) as exc:
                failures.append(name)
                print(name,'FAIL',str(exc),flush=True)
    return int(bool(failures))


if __name__=='__main__':
    sys.exit(main())

