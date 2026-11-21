import json
import pytest
from test_stage5_nodes import load_nodes
from test_stage5_workflows import builder

def plan(required, dependencies):
    return json.dumps({'queryMode':'NATURAL','requiredAgents':required,'dependencies':dependencies})

def test_independent_domains_share_first_wave():
    result=load_nodes().dispatch_waves(plan(['SUPPLY','PRODUCTION','EQUIPMENT'],{'SUPPLY':[],'PRODUCTION':[],'EQUIPMENT':[]}))
    assert result=={'mode':'parallel','wave1':['SUPPLY','PRODUCTION','EQUIPMENT'],'wave2':[],'wave3':[]}

def test_delivery_and_quality_handoffs_are_respected():
    roles=['ORDER','SUPPLY','PRODUCTION','QUALITY','EQUIPMENT','AFTERSALES']
    deps={'ORDER':[],'SUPPLY':['ORDER'],'PRODUCTION':['ORDER','SUPPLY'],'QUALITY':[],'EQUIPMENT':[],'AFTERSALES':['QUALITY']}
    result=load_nodes().dispatch_waves(plan(roles,deps))
    assert result['wave1']==['ORDER','QUALITY','EQUIPMENT']
    assert result['wave2']==['SUPPLY','AFTERSALES']
    assert result['wave3']==['PRODUCTION']

@pytest.mark.parametrize('roles,deps',[
    (['SUPPLY'],{}),(['SUPPLY'],{'SUPPLY':['ORDER']}),
    (['ORDER','SUPPLY'],{'ORDER':['SUPPLY'],'SUPPLY':['ORDER']}),
    (['ORDER','ORDER'],{'ORDER':[]}),(['ADMIN'],{'ADMIN':[]}),
    (['ORDER'],{'ORDER':[],'SUPPLY':[]}),(['ORDER'],{'ORDER':'ORDER'}),
])
def test_invalid_dependency_plans_fail_closed(roles,deps):
    with pytest.raises(ValueError):load_nodes().dispatch_waves(plan(roles,deps))

def test_empty_clarification_does_not_invoke_experts_and_legacy_is_preserved():
    assert load_nodes().dispatch_waves(plan([],{}))['wave1']==[]
    assert load_nodes().dispatch_waves(json.dumps({'workflowProfile':'DELIVERY'}))['mode']=='legacy'

def test_six_independent_roles_are_all_retained_and_excess_depth_is_rejected():
    roles=['ORDER','SUPPLY','PRODUCTION','QUALITY','EQUIPMENT','AFTERSALES']
    result=load_nodes().dispatch_waves(plan(roles,{r:[] for r in roles}))
    assert result['wave1']==roles  # Dify's bounded executor, not list truncation, enforces max three workers.
    with pytest.raises(ValueError):
        load_nodes().dispatch_waves(plan(roles,{r:roles[:i] for i,r in enumerate(roles)}))

def test_parallel_tool_cannot_substitute_another_provider():
    m=builder();graph=m.build('SUP',{r:'provider-'+r for r in m.ROLE_NAMES})['workflow']['graph']
    tool=next(n['data'] for n in graph['nodes'] if n['data']['type']=='tool')
    tool['provider_id']='unrelated-app'
    with pytest.raises(ValueError):m.validate_workflow('SUP',graph)

def test_parallel_graph_uses_fixed_tools_and_waits_for_every_wave():
    m=builder();graph=m.build('SUP',{r:'provider-'+r for r in m.ROLE_NAMES})['workflow']['graph']
    nodes={n['id']:n['data'] for n in graph['nodes']}
    iterations=[n for n in nodes.values() if n['type']=='iteration']
    assert len(iterations)==3
    assert all(n['is_parallel'] and n['parallel_nums']==3 and n['error_handle_mode']=='terminated' for n in iterations)
    tools=[n for n in nodes.values() if n['type']=='tool']
    assert len(tools)==18
    assert {n['tool_name'] for n in tools}==set(m.TOOLS.values())
    assert all(n['tool_parameters']['run_ref']=={'type':'variable','value':['input_node','run_ref']} for n in tools)
    assert all(n['tool_parameters']['task_goal']=={'type':'variable','value':['input_node','goal']} for n in tools)
    pairs={(e['source'],e['target']) for e in graph['edges']}
    assert {('wave1','wave2'),('wave2','wave3'),('wave3','finalize_http')}<=pairs
    tools[0]['tool_parameters']['run_ref']['value']=['planner_llm','text']
    with pytest.raises(ValueError):m.validate_workflow('SUP',graph)
