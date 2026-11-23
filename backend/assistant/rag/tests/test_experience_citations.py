from mes_knowledge.experience.source import record


def test_source_recorded_cause_and_private_excerpts():
    raw = dict(record_id=1,record_no='M-1',description='异常。已确认故障原因：接口松动。',action='重新固定',
               note='联系测试 13800000000，api_key=demo-private-value；待核查')
    case = record(b'x','MAINTENANCE',raw)
    assert [v['label'] for v in case.entities.values() if v['type']=='CAUSE'] == ['接口松动']
    assert 'demo-private-value' not in case.text and '13800000000' not in case.text
    for uncertain in ('疑似短路', '未确认', '可能松动', '待核实'):
        case = record(b'x','MAINTENANCE',{**raw,'description':'已确认根因：'+uncertain})
        assert not any(v['type']=='CAUSE' for v in case.entities.values())

