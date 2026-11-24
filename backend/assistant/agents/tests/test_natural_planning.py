import importlib.util
import json
from pathlib import Path
import pytest

BASE = Path(__file__).resolve().parents[1]
def module(name):
    spec = importlib.util.spec_from_file_location(name, BASE / 'scripts' / (name + '.py'))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result

RUN = '12345678-1234-1234-1234-123456789abc'

def test_large_natural_result_uses_receipt_without_copying_or_truncating_authoritative_rows():
    m=module('stage5_nodes')
    rows=[{'record_id':i,'alarm_description':'实测记录'*100} for i in range(1000)]
    data=dict(runRef=RUN,sourceMode='LOCAL_READONLY_PREVIEW',queryMode='NATURAL',
              verdict='EVIDENCE_READY',reply='已查询到以下报警信息：',facts={'alarms':{'overview':{'rows':rows}}})
    result=m.final_answer(json.dumps(data,ensure_ascii=False),200,RUN)
    assert len(result['decision'])<1000
    assert json.loads(result['decision'])=={'runRef':RUN,'verdict':'EVIDENCE_READY','queryMode':'NATURAL'}
    assert len(data['facts']['alarms']['overview']['rows'])==1000
def context(**extra):
    return json.dumps(dict(runRef=RUN, sourceMode='LOCAL_READONLY_PREVIEW', queryMode='NATURAL', workflowProfile='AUTO', requiredAgents=[], currentDate='2026-11-09', **extra))

def test_planner_validates_json_and_keeps_legacy_scope_immutable():
    m=module('stage5_nodes')
    base=m.planning_context(context(userQuery='查库存'),200,RUN)['context']
    plan=dict(action='QUERY',intents=['INVENTORY'],orderPk=None,orderNo=None,keyword=None,period='CURRENT',question='')
    assert json.loads(m.planning_body(json.dumps(plan),base)['payload']) == plan
    for invalid in [dict(plan,sql='DELETE'),dict(plan,intents=['EXECUTE']),dict(plan,orderPk=100),dict(plan,action='WRITE')]:
        with pytest.raises(ValueError):m.planning_body(json.dumps(invalid),base)
    legacy=json.dumps(dict(runRef=RUN,sourceMode='LOCAL_READONLY_PREVIEW',workflowProfile='ORDER',requiredAgents=['ORDER']))
    before=m.planning_context(legacy,200,RUN)['context']
    assert json.loads(m.planning_body('{"action":"WRITE"}',before)['payload']) == {'action':'LEGACY'}

def test_natural_tasks_preserve_bounded_overview_and_clarification_is_not_a_delivery_failure():
    m=module('stage5_nodes')
    ctx=m.supervisor_context(context(required='unused',plan={'action':'QUERY'},scope={}),200,RUN)
    assert json.loads(ctx['context'])['queryMode']=='NATURAL'
    task=json.loads(context());task.update(agentId='SUPPLY',taskId='t',evidence=[{'data':{'overview':{'rows':[{'materialName':'显示屏','quantity':'7'}]}}}])
    parsed=json.loads(m.task_context(json.dumps(task),200,RUN,'SUPPLY')['context'])
    assert parsed['evidence'][0]['data']['overview']['rows'][0]['quantity']=='7'
    result=json.loads(context());result.update(verdict='CLARIFY',reply='请提供订单编号。',facts={},queryRefs=[],executedAgents=[])
    assert m.final_answer(json.dumps(result),200,RUN)['answer']=='请提供订单编号。'


def test_planner_accepts_grounded_trace_and_optional_graph():
    m=module('stage5_nodes')
    plan=dict(action='QUERY',intents=['INSPECTION_TRACE','GRAPH_KNOWLEDGE'],orderPk=None,orderNo=None,
              keyword='QI202607021',period='CURRENT',question='')
    ctx=m.planning_context(context(userQuery='QI202607021 涉及哪个环节'),200,RUN)['context']
    assert json.loads(m.planning_body(json.dumps(plan),ctx)['payload'])==plan


def test_scoped_findings_survive_empty_and_failed_rag():
    m=module('stage5_nodes')
    base=json.loads(context());base.update(verdict='EVIDENCE_READY',intents=['INSPECTION_TRACE','GRAPH_KNOWLEDGE'],
        facts={'inspection_trace':{}},reply='记录状态与数量不一致。缺少工序依据。')
    failed=json.loads(m.compact_response_context(json.dumps({**base,'knowledgeEvidence':[{'status':'UNAVAILABLE'}]}),200,RUN)['context'])
    assert failed['required_reply'].startswith(base['reply']) and '暂不可用' in failed['required_reply']
    assert failed['retrieval_states']==[dict(status='UNAVAILABLE',graph_status=None,mode_used=None)]
    hits=json.loads(m.compact_response_context(json.dumps({**base,'knowledgeEvidence':[{'status':'MATCHES','hits':[{'evidence_ref':'e','title':'manual'}]}]}),200,RUN)['context'])
    assert hits['verified_reply']==base['reply'] and hits['sources'][0]['id']=='S1'
    stale=json.loads(m.compact_response_context(json.dumps({**base,'knowledgeEvidence':[{'status':'UNAVAILABLE','hits':[{'evidence_ref':'stale'}]}]}),200,RUN)['context'])
    assert stale['sources']==[] and stale['required_reply'].startswith(base['reply'])


def test_inspection_review_uses_trace_and_business_linked_checks_even_without_publications():
    m=module('stage5_nodes')
    base=json.loads(context())
    trace={'status':'FOUND','inspection':[{'inspection_status':'FAILED','batch_no':'B1'}],
           'items':[{'item_name':'亮点计数','result':'FAILED','standard_value':'2','measured_value':'4'}],
           'defects':[{'batch_no':'foreign','nonconforming_no':'NC-other','defect_description':'无信号'}],
           'reportRows':[{'report_no':'WR1','link_basis':'同工单背景'},{'report_no':'WR-other','link_basis':'检验工单与报工工单矛盾'}],
           'routeSteps':[{'step_name':'屏幕检测'}],'trainingHistory':[{'record_no':'DT1','record_kind':'DISPATCH'}]}
    case={'text':'需复核光学测试条件','verification':'重测同一位置','evidenceRefs':['business-ref']}
    base.update(verdict='EVIDENCE_READY',intents=['INSPECTION_TRACE'],facts={'inspection_trace':trace},
                queryRefs=['business-ref'],knowledgeEvidence=[{'status':'UNAVAILABLE'}],
                analyses=[{'agentId':'QUALITY','status':'PARTIAL_INVALID_REFERENCES','hypotheses':[
                    case,dict(case,evidenceRefs=['unrelated']),dict(case,verification='')]}])
    for mode in ('DIRECT','COMPOSE'):
        result=json.loads(m.compact_response_context(json.dumps(base),200,RUN,mode)['context'])
        assert result['answer_mode']=='inspection_review' and 'required_reply' not in result
        assert result['recorded_defects']==[] and result['training_archive'][0]['record_no']=='DT1'
        assert result['route_in_order']==['屏幕检测'] and [r['report_no'] for r in result['production_reports']]==['WR1']
        assert result['candidate_checks']==[{'hypothesis':case['text'],'verification':case['verification']}]
    trace['inspection'][0]['inspection_status']='PENDING'
    assert not m.inspection_review(base,trace)['candidate_checks']
    trace.update(visionStatus='RECORDED',boundImages=[{'recordId':'image-1','defect':True,'count':1,
                 'detections':[{'box':[1,2,4,8],'className':'scratch','confidence':0.9}],
                 'images':{'input':{'width':20,'height':40,'storage':'private'}}}],
                 similarDefects=[{'inspection_no':'QI-prior','batch_no':'B0','handle_method':'REWORK'}],
                 lotReports=[{'inspection_no':'QI-lot','report_no':'WR-lot','step_name':'贴附'}])
    review=m.inspection_review(base,trace)
    assert review['historical_matches'][0]['inspection_no']=='QI-prior'
    assert review['lot_reports'][0]['report_no']=='WR-lot'
    assert review['image_records'][0]['input_size']==[20,40] and 'private' not in str(review['image_records'])
    base['verdict']='DATA_CHANGED'
    assert 'inspection' not in json.loads(m.compact_response_context(json.dumps(base),200,RUN)['context'])


def test_general_explanation_and_document_fallback_are_separate_from_lookup_failure():
    m=module('stage5_nodes')
    base=json.loads(context());base.update(verdict='EVIDENCE_READY',facts={},reply='请说明原理')
    def packet(intents, **extra):
        return json.loads(m.compact_response_context(json.dumps({**base,'intents':intents,**extra}),200,RUN)['context'])
    assert packet(['GENERAL'])=={'answer_mode':'general_explanation'}
    fallback=packet(['KNOWLEDGE'],knowledgeEvidence=[{'status':'NO_MATCHES'}])
    assert fallback['answer_mode']=='no_verified_document' and fallback['retrieval_states'][0]['status']=='NO_MATCHES'
    assert packet(['GENERAL'],verdict='CLARIFY')['required_reply']==base['reply']


def test_only_authorized_matched_enterprise_sop_counts_as_company_guidance():
    m=module('stage5_nodes')
    hit={'title':'QA','version':'2','evidence_ref':'sop:SOP_QA:2:abc:1','text':'local test','authority_type':'APPROVED_ENTERPRISE_SOP'}
    data=json.loads(context());data.update(verdict='EVIDENCE_READY',facts={},intents=['KNOWLEDGE'],
        knowledgeEvidence=[{'status':'NO_MATCHES'}],approvedSopEvidence=[{'status':'MATCHES','hits':[hit]}])
    ctx=json.loads(m.compact_response_context(json.dumps(data),200,RUN)['context'])
    assert ctx['company_sop_verified'] and ctx['approved_sops'][0]['id']=='SOP1' and 'required_reply' not in ctx
    data['approvedSopEvidence'][0]['status']='NOT_AUTHORIZED'
    ctx=json.loads(m.compact_response_context(json.dumps(data),200,RUN)['context'])
    assert not ctx['company_sop_verified'] and not ctx['approved_sops'] and ctx['answer_mode']=='no_verified_document'


def test_recent_quality_preserves_actual_window_even_when_no_inspections_match():
    m=module('stage5_nodes')
    message='2026-11-15 至 2026-11-21 未查到检验记录，不能判断质量改善。'
    data=json.loads(context());data.update(verdict='EVIDENCE_READY',intents=['QUALITY'],analyses=[
        {'findings':[{'text':'本厂没有归档'}]}],facts={
        'quality':{'overview':{'domain':'QUALITY','rows':[]},'traceConclusion':message}})
    ctx=json.loads(m.compact_response_context(json.dumps(data),200,RUN)['context'])
    assert ctx=={'required_reply':message}


def test_quality_overview_keeps_its_real_time_scope_and_sample_basis():
    m=module('stage5_nodes')
    overview={'domain':'QUALITY','dateField':'inspected_at','period':'CURRENT','timeZone':'Asia/Shanghai',
              'startInclusive':None,'endExclusive':None,'totalRecords':12,'returnedRecords':9,'truncated':True}
    data=json.loads(context());data.update(verdict='EVIDENCE_READY',intents=['QUALITY'],
        analyses=[{'findings':[{'text':'部分记录缺失'}]}],facts={'quality':{'overview':overview}})
    ctx=json.loads(m.compact_response_context(json.dumps(data),200,RUN)['context'])
    scope=ctx['quality_scope']
    assert scope['period']=='CURRENT' and scope['startInclusive'] is None and scope['endExclusive'] is None
    assert (scope['totalRecords'],scope['returnedRecords'],scope['truncated'])==(12,9,True)
    assert ctx['analyses'] and 'rows' not in scope and 'required_reply' not in ctx

def test_supervisor_has_planning_before_expert_dispatch_and_no_model_generated_run_reference():
    m=module('build_stage5'); providers={r:'11111111-1111-4111-8111-111111111111' for r in m.ROLE_NAMES}
    graph=m.build('SUP',providers)['workflow']['graph']; ids=[n['id'] for n in graph['nodes']]
    assert ids.index('planner_llm') < ids.index('plan_http') < ids.index('agent_node')
    m.validate_workflow('SUP',graph)


def test_deliverable_cannot_change_the_backend_tool_contract():
    m=module('stage5_nodes')
    plan=dict(action='QUERY',intents=['INVENTORY'],orderPk=None,orderNo=None,keyword=None,period='CURRENT',question='')
    ctx=m.planning_context(context(),200,RUN)['context']
    parsed=m.planning_body(json.dumps({**plan,'deliverable':'COMPOSE'}),ctx)
    assert parsed['deliverable']=='COMPOSE' and json.loads(parsed['payload'])==plan
    assert m.planning_body(json.dumps(plan),ctx)['deliverable']=='DIRECT'
    with pytest.raises(ValueError):m.planning_body(json.dumps({**plan,'deliverable':'WRITE'}),ctx)


def test_open_ended_composition_receives_only_authorized_prior_general_answer():
    m=module('stage5_nodes')
    prior={'userQuery':'原句','generalAnswer':'上轮已经显示的原文'}
    def read(previous):
        data=context(verdict='EVIDENCE_READY',intents=['GENERAL'],facts={},previousGeneralTurn=previous)
        return json.loads(m.compact_response_context(data,200,RUN)['context'])
    assert read(prior)['conversation_source']=={'request':'原句','answer':'上轮已经显示的原文'}
    assert 'conversation_source' not in read({**prior,'generalAnswer':None})
    assert read({**prior,'generalAnswer':'文'*5000})['conversation_source_partial']
    stale=context(verdict='DATA_CHANGED',intents=['GENERAL'],facts={},reply='失效',previousGeneralTurn=prior)
    assert 'conversation_source' not in m.compact_response_context(stale,200,RUN)['context']


def test_mixed_business_request_gets_checked_numbers_but_never_hidden_fields_or_uncounted_rows():
    m=module('stage5_nodes')
    overview={'domain':'INVENTORY','totalRecords':22,'truncated':False,
              'rows':[{'material_name':f'料{i}','quantity_available':0,'unit':'件','secret':'private'} for i in range(22)]}
    base=dict(verdict='EVIDENCE_READY',reply='已核验',intents=['INVENTORY'],facts={'inventory':{'overview':overview}})
    packet=json.loads(m.compact_response_context(context(**base),200,RUN,'COMPOSE')['context'])
    table=packet['business_evidence'][0]
    assert packet['answer_mode']=='business_composition' and 'required_reply' not in packet
    assert table['preview_truncated'] and not table['source_truncated']
    assert 0<len(table['row_preview'])<22 and table['row_preview'][0]['quantity_available']==0
    assert 'private' not in str(packet)
    denied=context(**{**base,'verdict':'DATA_CHANGED','reply':'重新查询'})
    assert json.loads(m.compact_response_context(denied,200,RUN,'COMPOSE')['context'])=={'verdict':'DATA_CHANGED','required_reply':'重新查询'}


def test_other_specialists_partial_view_cannot_erase_nonempty_domain_receipts():
    m=module('stage5_nodes')
    facts={key:{'overview':{'domain':key.upper(),'totalRecords':length,'rows':[{'status':'active'}]*length}}
           for key,length in [('inventory',3),('equipment',2)]}
    data=context(verdict='EVIDENCE_READY',intents=['INVENTORY','EQUIPMENT'],facts=facts,
                 expertExplanations=[{'agentId':'EQUIPMENT','text':'未取得仓库的表'}])
    def packet(mode):return json.loads(m.compact_response_context(data,200,RUN,mode)['context'])
    direct=packet('DIRECT')
    assert direct['required_reply']=='已查询到以下库存、设备信息：'
    assert direct['equipment_comments']==[]
    composed=packet('COMPOSE')
    assert [(d['domain'],d['returned_records'],d['total_records']) for d in composed['business_evidence']]==[
        ('inventory',3,3),('equipment',2,2)]


def test_recap_keeps_prior_scope_resolution_server_side_and_requests_a_composed_reply():
    m=module('stage5_nodes')
    previous={'plan':{'action':'QUERY','intents':['INVENTORY']},'scope':{'confirmed':True,'keyword':'螺钉'}}
    ctx=m.planning_context(context(previousContext=previous,userQuery='把刚才的结论整理成两句话'),200,RUN)['context']
    plan=dict(action='QUERY',intents=['RECAP'],orderPk=None,orderNo=None,keyword=None,period='CURRENT',question='')
    compiled=m.planning_body(json.dumps(plan),ctx)
    assert json.loads(compiled['payload'])==plan and compiled['deliverable']=='COMPOSE'


def test_model_preview_and_business_table_coverage_are_independent_per_domain():
    m=module('stage5_nodes')
    facts={'inventory':{'overview':{'rows':[{'quantity_available':0}]*21,'totalRecords':21,'truncated':False}},
           'equipment':{'overview':{'rows':[{'status':'RUNNING'}]*5,'totalRecords':26,'truncated':True}}}
    packet=json.loads(m.compact_response_context(context(intents=['INVENTORY','EQUIPMENT'],facts=facts,verdict='EVIDENCE_READY'),
                                                 200,RUN,'COMPOSE')['context'])
    inventory,equipment=packet['business_evidence']
    assert inventory['returned_records']==21 and inventory['preview_truncated'] and not inventory['source_truncated']
    assert equipment['returned_records']==5 and not equipment['preview_truncated'] and equipment['source_truncated']
    assert packet['table_delivery']=='chat_tables_with_csv'
    facts['equipment']['overview'].update(rows=[],totalRecords=0,truncated=False)
    empty=json.loads(m.compact_response_context(context(intents=['EQUIPMENT'],facts={'equipment':facts['equipment']},
                                              verdict='EVIDENCE_READY'),200,RUN,'COMPOSE')['context'])
    assert empty['table_delivery']=='no_returned_rows'
