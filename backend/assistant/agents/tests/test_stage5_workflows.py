import importlib.util
from pathlib import Path
import pytest

BASE = Path(__file__).resolve().parents[1]

def builder():
    p=BASE/'scripts/build_stage5.py'
    assert p.exists(), 'Workflow builder not implemented'
    spec=importlib.util.spec_from_file_location('build_stage5',p)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    return m

def test_supervisor_does_not_allow_model_to_choose_run_or_call_raw_business_tools():
    m=builder()
    providers={role: '11111111-1111-4111-8111-111111111111' for role in m.ROLE_NAMES}
    graph=m.build('SUP',providers)['workflow']['graph']
    m.validate_workflow('SUP',graph)
    agent=next(n['data'] for n in graph['nodes'] if n['data']['type']=='agent')
    tools=agent['agent_parameters']['tools']['value']
    tools[0]['parameters']['run_ref']['auto']=1
    with pytest.raises(ValueError):m.validate_workflow('SUP',graph)

def test_expert_only_has_public_knowledge_tool_and_cannot_call_siblings():
    m=builder(); graph=m.build('QUALITY',{'KNOWLEDGE':'11111111-1111-4111-8111-111111111111'})['workflow']['graph']
    m.validate_workflow('QUALITY',graph)
    agent=next(n['data'] for n in graph['nodes'] if n['data']['type']=='agent')
    agent['agent_parameters']['tools']['value'][0]['tool_name']='order_planning_v1'
    with pytest.raises(ValueError):m.validate_workflow('QUALITY',graph)

def test_exported_dsl_has_no_runtime_key_and_secret_is_only_in_fixed_http_header():
    m=builder(); app=m.build('ORDER',{'KNOWLEDGE':'11111111-1111-4111-8111-111111111111'})
    assert all(v['value']=='' for v in app['workflow']['environment_variables'])
    nodes=app['workflow']['graph']['nodes']
    headers=[n['data']['authorization']['config']['api_key'] for n in nodes if n['data']['type']=='http-request']
    assert headers and all(v=='{{#env.ROLE_API_KEY#}}' for v in headers)
    agent=next(n['data'] for n in nodes if n['data']['type']=='agent')
    agent['agent_parameters']['query']['value']+=' {{#env.ROLE_API_KEY#}}'
    with pytest.raises(ValueError):m.validate_workflow('ORDER',app['workflow']['graph'])


def test_order_intake_branch_is_optional_isolated_and_keeps_existing_read_chain():
    m=builder()
    graph=m.build('SUP',{role:'test-provider' for role in m.ROLE_NAMES})['workflow']['graph']
    nodes={n['id']:n['data'] for n in graph['nodes']}
    variables={v['variable']:v for v in nodes['start_node']['variables']}
    assert variables['order_intake']['required'] is False
    assert variables['order_intake']['max_length']==16000
    edges=graph['edges']
    assert any(e['source']=='intake_route' and e['sourceHandle']=='false' and e['target']=='input_node' for e in edges)
    assert any(e['source']=='input_node' and e['target']=='context_http' for e in edges)
    read_chain=['input_node','context_http','planner_input','planner_llm','plan_body','plan_http',
                'context_node','dispatch_plan','dispatch_route']
    for source,target in zip(read_chain,read_chain[1:]):
        assert [e['target'] for e in edges if e['source']==source]==[target]
    reachable={'intake_input'}
    for _ in range(len(nodes)):
        reachable.update(e['target'] for e in edges if e['source'] in reachable)
    assert reachable=={'intake_input','intake_intent_llm','intake_intent_result','intake_llm','intake_result','intake_end'}
    assert all(nodes[n]['type'] not in {'http-request','agent'} for n in reachable)
    assert nodes['intake_llm']['model']['provider']==nodes['planner_llm']['model']['provider']
    assert nodes['intake_intent_llm']['model']['provider']==nodes['intake_llm']['model']['provider']
    assert nodes['intake_end']['outputs'][0]['variable']=='order_intake_json'
    edges.append({'source':'intake_result','target':'context_http'})
    with pytest.raises(ValueError):m.validate_workflow('SUP',graph)
