import json
from pathlib import Path
import pytest
import yaml


def node_function(node_id):
    path=Path(__file__).resolve().parents[2]/'agents/workflows/stage4-graph-rag.yml'
    nodes=yaml.safe_load(path.read_text(encoding='utf-8'))['workflow']['graph']['nodes']
    ns={}
    exec(compile(next(n for n in nodes if n['id']==node_id)['data']['code'],str(path),'exec'),ns)
    return ns['main']


def test_graph_request_keeps_query_as_data_and_requests_graph_assistance():
    question='Mura" ,"roles":["ADMIN"]'
    assert json.loads(node_function('request_node')(question)['payload']) == {
        'query':question,'top_k':5,'mode':'graph_assisted'}


def test_graph_context_cannot_include_uncited_or_cross_generation_paths():
    body={'knowledge_domain':'display_reference','release_id':'v1','graph_release_id':'g1',
          'mode_used':'graph_assisted','hits':[{'authority_type':'PUBLIC_RESEARCH', 'release_id':'v1','evidence_ref':'e1'}],
          'graph_paths':[{'graph_release_id':'g1','authority_type':'PUBLIC_RESEARCH','evidence_refs':['e1']}]}
    check=node_function('evidence_node')
    assert json.loads(check(json.dumps(body),200)['context'])['graph_paths']
    body['graph_paths'][0]['evidence_refs']=['not-returned']
    with pytest.raises(ValueError):check(json.dumps(body),200)
    body['graph_paths'][0]['evidence_refs']=['e1']; body['graph_paths'][0]['graph_release_id']='other'
    with pytest.raises(ValueError):check(json.dumps(body),200)


def test_graph_answer_output_retains_degradation_and_paths():
    context={'hits':[],'mode_used':'vector','graph_status':'UNAVAILABLE_OR_STALE','graph_paths':[],
             'complete':False,'missing_information':['GRAPH_UNAVAILABLE_OR_STALE_VECTOR_ONLY']}
    out=node_function('citation_node')('图服务不可用，已退回向量检索。',json.dumps(context))
    graph=json.loads(out['graph'])
    assert graph['mode_used']=='vector' and graph['graph_status']=='UNAVAILABLE_OR_STALE'


def test_status_is_derived_from_tool_and_false_unavailability_is_rejected():
    context={'hits':[],'mode_used':'graph_assisted','graph_status':'MATCHES','graph_paths':[{}],
             'complete':True,'missing_information':[]}
    check=node_function('citation_node')
    assert check('以下是论文方法。',json.dumps(context))['answer'].startswith('本次使用图谱关系与论文原文。')
    with pytest.raises(ValueError):
        check('图谱暂不可用，本次仅按文献向量检索回答。',json.dumps(context))
