import copy
import json
import uuid
import pytest

from mes_knowledge.graph.business import validate_export, sync_business, trace_business


def packet(order='1'):
    return {'schemaVersion':'1','exportId':str(uuid.uuid4()),'scope':{'orderPk':order},
        'sourceObservedAt':'2026-11-08T00:00:00Z','readFinishedAt':'2026-11-08T00:00:01Z',
        'complete':True,'nodes':[
            {'id':'Order:'+order,'kind':'Order','source_table':'customer_order','source_id':order,'properties':{'status':'APPROVED'}},
            {'id':'OrderItem:2','kind':'OrderItem','source_table':'customer_order_item','source_id':'2','properties':{'quantity':5}},
            {'id':'Plan:3','kind':'Plan','source_table':'production_plan','source_id':'3','properties':{}}],
        'edges':[
            {'id':'HAS_ITEM:customer_order_item:2','source':'Order:'+order,'target':'OrderItem:2','type':'HAS_ITEM',
             'source_table':'customer_order_item','source_id':'2','reliability':'CONFIRMED'},
            {'id':'PLANNED_AS:production_plan_item:4','source':'OrderItem:2','target':'Plan:3','type':'PLANNED_AS',
             'source_table':'production_plan_item','source_id':'4','reliability':'CONFIRMED'}],
        'missingLinks':[]}


def test_export_rejects_scope_mismatch_dangling_edge_and_pii():
    value=packet()
    validate_export(value,'1')
    with pytest.raises(ValueError):validate_export(value,'2')
    bad=copy.deepcopy(value);bad['edges'][0]['target']='OrderItem:99'
    with pytest.raises(ValueError):validate_export(bad,'1')
    bad=copy.deepcopy(value);bad['nodes'][0]['properties']['customer_name']='Private'
    with pytest.raises(ValueError):validate_export(bad,'1')
    bad=copy.deepcopy(value);bad['complete']=False
    with pytest.raises(ValueError):validate_export(bad,'1')


class Repository:
    def __init__(self):self.records={}
    def write_business(self,g,order,data,domain):
        self.records[(g,order,domain)]=data
    def business_counts(self,g,order,domain):
        data=self.records[(g,order,domain)]
        return {'nodes':len(data['nodes']),'edges':len(data['edges'])}
    def read_business(self,g,order,domain,limit):
        data=self.records[(g,order,domain)]
        return {'nodes':data['nodes'][:limit+1],'edges':data['edges'][:limit+1]}


def test_generations_remove_deleted_current_edges_and_keep_orders_isolated(tmp_path):
    repo=Repository();one=packet();two=packet('9')
    first=sync_business(tmp_path,repo,one,'1',synthetic=True)
    assert sync_business(tmp_path,repo,one,'1',synthetic=True)['graph_snapshot_id']==first['graph_snapshot_id']
    sync_business(tmp_path,repo,two,'9',synthetic=True)
    updated=copy.deepcopy(one);updated['exportId']=str(uuid.uuid4());updated['edges']=updated['edges'][:1]
    second=sync_business(tmp_path,repo,updated,'1',synthetic=True)
    assert second['graph_snapshot_id']!=first['graph_snapshot_id']
    assert len(trace_business(tmp_path,repo,'1')['edges'])==1
    assert len(trace_business(tmp_path,repo,'9')['edges'])==2
    assert trace_business(tmp_path,repo,'1')['synthetic'] is True


def test_incomplete_or_failed_sync_never_publishes_ready_snapshot(tmp_path):
    repo=Repository();value=packet()
    sync_business(tmp_path,repo,value,'1',synthetic=True)
    value['exportId']=str(uuid.uuid4())
    def fail(*a):raise RuntimeError('private-failure')
    repo.write_business=fail
    with pytest.raises(RuntimeError):sync_business(tmp_path,repo,value,'1',synthetic=True)
    with pytest.raises(ValueError):trace_business(tmp_path,repo,'1')


def test_business_generation_change_during_read_is_stale(tmp_path):
    repo=Repository();one=packet()
    sync_business(tmp_path,repo,one,'1',synthetic=True)
    original=repo.read_business
    def switch(*args):
        result=original(*args)
        one['exportId']=str(uuid.uuid4())
        sync_business(tmp_path,repo,one,'1',synthetic=True)
        return result
    repo.read_business=switch
    with pytest.raises(ValueError):trace_business(tmp_path,repo,'1')


def test_rejected_replacement_invalidates_old_ready_snapshot(tmp_path):
    repo=Repository(); value=packet()
    sync_business(tmp_path,repo,value,'1',synthetic=True)
    value['complete']=False
    with pytest.raises(ValueError):sync_business(tmp_path,repo,value,'1',synthetic=True)
    with pytest.raises(ValueError):trace_business(tmp_path,repo,'1')
