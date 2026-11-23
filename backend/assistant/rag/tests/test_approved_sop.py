import hashlib
import pytest
from mes_knowledge.approved_sop import publish, withdraw, load


def test_only_reviewed_matching_factory_wide_version_can_be_published_and_revoked(tmp_path):
    file=tmp_path/'manual.txt'
    file.write_text('Reviewed example for the test.', encoding='utf-8')
    r=dict(document_id='SOP_QA',version='2.0',title='QA manual',domain='QUALITY',
           scope='FACTORY_WIDE',status='APPROVED',approved_by='test reviewer',approved_on='2020-01-01',
           effective_from='2020-02-01',valid_to=None,allow_model_processing=True,allowed_roles=['ADMIN'],
           keywords=['inspection'],sha256=hashlib.sha256(file.read_bytes()).hexdigest())
    root=tmp_path/'private'
    for change in ({'status':'DRAFT'},{'sha256':'stale'},{'allow_model_processing':False},{'allowed_roles':['CUSTOMER']}):
        with pytest.raises(ValueError):publish(root,file,{**r,**change})
    publish(root,file,r)
    publish(root,file,r)
    doc=load(root)['documents']['SOP_QA']
    assert doc['sections'][0]['evidence_ref'].startswith('sop:SOP_QA:2.0:')
    with pytest.raises(ValueError):withdraw(root,'SOP_QA','1.0')
    withdraw(root,'SOP_QA','2.0')
    assert not load(root)['documents']
    with pytest.raises(ValueError):publish(root,file,r)

