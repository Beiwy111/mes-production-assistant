import json
from test_natural_planning import module, context, RUN

def test_trace_conflict_cannot_publish_stale_rate_or_promote_later_save_to_recheck():
    m=module('stage5_nodes')
    trace={'status':'FOUND','inspection':[{'inspection_no':'QI-ABC','sample_quantity':10,'unqualified_quantity':0,
            'inspection_status':'RECHECK_REQUIRED','inspection_result':'UNQUALIFIED'}],
           'sampleRateStatus':'WITHHELD_CONFLICT','confirmedSampleRejectRate':'0%',
           'legacyBaseline':{'kind':'DEMO_SEED_REFERENCE','unqualified_quantity':2},
           'inspectionEvents':[{'event_type':'ITEMS_SAVED','recorded_at':'2026-08-01','items':[{'item_code':'FP-08','measured_value':'合格'}]}],
           'items':[{'item_code':'FP-08','result':'PASSED','updated_at':'2026-08-01'}],
           'defects':[{'nonconforming_no':'NC-C','quantity':1,'handle_method':'CONCESSION'}],
           'sampleBindings':[{'binding_id':4,'nonconforming_no':'NC-C','sample_label':'AX'}],
           'sampleMeasurements':[{'binding_id':4,'sample_label':'AX','step_name':'装配','metric_name':'色差','before_value':'1.3','after_value':None,'source_ref':'R1'}]}
    packet=m.inspection_review({},trace)
    assert packet['confirmed_sample_reject_rate'] is None and '不合格率待核实' in packet['first_verification']
    assert packet['inspection_events']==trace['inspectionEvents'] and packet['seed_reference']['kind']=='DEMO_SEED_REFERENCE'
    assert packet['recorded_checks'][0]['updated_at']=='2026-08-01' and packet['recorded_defects'][0]['handle_method']=='CONCESSION'
    assert packet['sample_bindings'][0]['nonconforming_no']=='NC-C' and 'after_value' not in packet['sample_measurements'][0]

def test_quality_and_aftersales_research_is_a_real_fixed_step():
    m=module('build_stage5'); providers={r:'11111111-1111-4111-8111-111111111111' for r in [*m.ROLE_NAMES,'KNOWLEDGE']}
    for role in ['QUALITY','AFTERSALES']:
        graph=m.build(role,providers)['workflow']['graph'];ids=[n['id'] for n in graph['nodes']]
        assert ids.index('context_http') < ids.index('research_http') < ids.index('context_node') < ids.index('agent_node')
        research=next(n['data'] for n in graph['nodes'] if n['id']=='research_http')
        assert research['url'].endswith('/tasks/'+role+'/research')
        assert research['body']['data'][0]['value']=='{}'

def test_task_context_preserves_research_and_upstream_receipt_and_mode():
    m=module('stage5_nodes');d=json.loads(context());d.update(agentId='AFTERSALES',taskId='t',evidence=[],
        knowledgeEvidence={'status':'NO_MATCHES','graph_status':'NO_MATCHES','hits':[]},upstreamFindings=[{
            'agentId':'QUALITY','resultRef':'q','authority':'BUSINESS_EVIDENCE_SNAPSHOT',
            'evidence':[{'queryRef':'quality-query','status':'PARTIAL','data':{
                'inspections':[{'unqualified_quantity':'2'}],
                'qualityMetrics':{'failedItemRecordCount':2},
                'visionEvidence':{'status':'RECORDED_SUMMARIES_ONLY'}}}]}])
    c=json.loads(m.task_context(json.dumps(d),200,RUN,'AFTERSALES')['context'])
    assert c['queryMode']=='NATURAL' and c['knowledgeEvidence']['status']=='NO_MATCHES'
    assert c['upstreamFindings'][0]['resultRef']=='q'
    handoff=c['upstreamFindings'][0]
    assert handoff['authority']=='BUSINESS_EVIDENCE_SNAPSHOT' and 'summary' not in handoff
    assert handoff['evidence'][0]['queryRef']=='quality-query'
    assert handoff['evidence'][0]['data']['inspections'][0]['unqualified_quantity']=='2'

def test_final_answer_uses_validated_analysis_not_untrusted_expert_prose():
    m=module('stage5_nodes');d=json.loads(context());d.update(verdict='EVIDENCE_READY',reply='已核对检验记录。',facts={},
      analyses=[{'agentId':'QUALITY','status':'VALIDATED_REFERENCES','summary':'检验存在待核实信息。',
      'findings':[{'text':'该检验项目待复核','evidenceRefs':['q1']}],
      'hypotheses':[{'text':'可能与搬运有关','evidenceRefs':['q1','r:p:c'],'verification':'核对搬运记录'}],
      'actions':[],'missing':['无历史原图']}],knowledgeEvidence=[],
      expertExplanations=[{'agentId':'QUALITY','text':'无依据：全部产品已放行'}])
    answer=m.final_answer(json.dumps(d),200,RUN)['answer']
    assert '该检验项目待复核' in answer and '核对搬运记录' in answer and 'q1' in answer
    assert '全部产品已放行' not in answer

def test_equipment_explanation_is_preserved_with_new_analysis_field():
    m=module('stage5_nodes');d=json.loads(context());d.update(verdict='EVIDENCE_READY',reply='设备记录已读取。',analyses=[],knowledgeEvidence=[],
        expertExplanations=[{'agentId':'EQUIPMENT','text':'设备报警需核对维护记录'}])
    assert '设备报警需核对维护记录' in m.final_answer(json.dumps(d),200,RUN)['answer']
