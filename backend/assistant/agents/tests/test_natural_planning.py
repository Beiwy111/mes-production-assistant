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

def test_supervisor_has_planning_before_expert_dispatch_and_no_model_generated_run_reference():
    m=module('build_stage5'); providers={r:'11111111-1111-4111-8111-111111111111' for r in m.ROLE_NAMES}
    graph=m.build('SUP',providers)['workflow']['graph']; ids=[n['id'] for n in graph['nodes']]
    assert ids.index('planner_llm') < ids.index('plan_http') < ids.index('agent_node')
    m.validate_workflow('SUP',graph)
