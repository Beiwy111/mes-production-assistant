import json
import os
from pathlib import Path
import runpy
import subprocess
import uuid

import pytest


DEPLOY = Path(__file__).resolve().parents[2]/'deploy'
HOST = runpy.run_path(str(DEPLOY/'Provision-Stage5Dify.py'))
WORKER = runpy.run_path(str(DEPLOY/'stage5_dify_provision.py'))


def test_templates_use_target_workspaces_ids_and_selected_model():
    workspace = str(uuid.uuid4())
    files = HOST['read_templates'](DEPLOY)
    specs = WORKER['build_specs'](workspace, files, 'org/plugin/provider', 'test-model')
    assert list(specs) == list(HOST['ROLES'])
    legacy = json.loads((DEPLOY.parent/'agents/bindings.stage5.local.json').read_text(encoding='utf-8'))
    for role, spec in specs.items():
        assert spec['app_id'] != legacy['apps'][role]['app_id']
        assert spec['app_id'] == WORKER['identity'](workspace, 'app', role)
        assert spec['app_id'] != WORKER['identity'](str(uuid.uuid4()), 'app', role)
        assert spec['dsl']['workflow']['environment_variables'][0]['value'] == ''
        for node in spec['dsl']['workflow']['graph']['nodes']:
            data = node['data']
            if data['type'] == 'llm':
                assert (data['model']['provider'], data['model']['name']) == ('org/plugin/provider', 'test-model')
            if data['type'] == 'agent':
                params = data['agent_parameters']
                assert params['model']['value']['model'] == 'test-model'
                ids = {tool['provider_name'] for tool in params['tools']['value']}
                expected = {specs[r]['tool_id'] for r in HOST['ROLES'][1:-1]} if role == 'SUP' else {specs['KNOWLEDGE']['tool_id']}
                assert ids == expected
            if data['type'] == 'tool':
                expected = {s['tool']['name']: s['tool_id'] for s in specs.values() if s['tool']}
                assert data['provider_id'] == expected[data['tool_name']]


def test_private_setting_errors_never_include_values(tmp_path):
    path = tmp_path/'private.env'
    path.write_text('ROLE=sample-secret\nROLE=another-secret\n', encoding='utf-8')
    with pytest.raises(ValueError, match='duplicate ROLE') as error:
        HOST['read_keys'](path, ['ROLE'])
    assert 'sample-secret' not in str(error.value)


def test_workbench_configuration_writes_only_the_sup_api_key(tmp_path, monkeypatch, capsys):
    worker = tmp_path/'stage5_dify_provision.py'
    worker.write_text('# test worker', encoding='utf-8')
    provision = HOST['provision']
    monkeypatch.setitem(provision.__globals__, 'DEPLOY', tmp_path)
    secret = 'role-fixture-secret'
    monkeypatch.setitem(provision.__globals__, 'prepare', lambda *_: {'secrets': {'SUP': secret}})
    result = dict(api_key='app-WorkbenchTest', apps={r: {} for r in HOST['ROLES']})
    def docker(args, **kwargs):
        if args[1] == 'inspect':
            return subprocess.CompletedProcess(args, 0, b'mes-dify-local|api|langgenius/dify-api:1.19.0|true')
        assert secret not in str(args) and secret.encode() in kwargs['input']
        return subprocess.CompletedProcess(args, 0, ('MES_STAGE5_RESULT='+json.dumps(result)).encode())
    # Keep the actual file ACL calls active while replacing only the Docker boundary.
    original = subprocess.run
    monkeypatch.setattr(subprocess, 'run', lambda args, **kwargs: docker(args, **kwargs)
                        if args[0] == 'docker' else original(args, **kwargs))
    for _ in range(2):
        provision('provider', 'model')
    env = (tmp_path/'.env.workbench.local').read_text(encoding='utf-8')
    assert env.splitlines() == ['MES_WORKBENCH_DIFY_API_KEY=app-WorkbenchTest',
                                'MES_WORKBENCH_DIFY_BASE_URL=http://127.0.0.1:18080/v1']
    assert 'WorkbenchTest' not in (tmp_path/'.env.stage5-bindings.local').read_text(encoding='utf-8')
    assert 'WorkbenchTest' not in capsys.readouterr().out


# Optional integration test for a disposable Dify 1.19.0 container on its own
# --internal Docker network, with an empty migrated PostgreSQL and Redis.
# The actual import, secret encryption, publication, tool and API key services run twice.
CONTAINER = os.getenv('MES_STAGE5_DISPOSABLE_TEST_CONTAINER')


@pytest.mark.skipif(not CONTAINER, reason='requires an isolated Dify test database')
def test_disposable_workspace_provisions_once_and_survives_retry():
    assert CONTAINER.startswith('mes-stage5proof-')
    info = json.loads(subprocess.check_output(['docker', 'inspect', CONTAINER]))[0]
    networks = list(info['NetworkSettings']['Networks'])
    assert len(networks) == 1 and networks[0].startswith('mes-stage5proof-')
    network = json.loads(subprocess.check_output(['docker', 'network', 'inspect', networks[0]]))[0]
    assert network['Internal'] and not info['HostConfig']['Binds']
    request = dict(files=HOST['read_templates'](DEPLOY), model='fixture-model', provider='fixture/provider',
                   secrets={r: uuid.uuid4().hex for r in HOST['ROLES']}, expected_tenant=None)
    fixture = """
from gevent import monkey
monkey.patch_all()
import json, sys, uuid
from app_factory import create_app
from extensions.ext_application_services import application_services
from extensions.ext_database import db
from services.account.contracts import SetupInput
from models.account import Tenant
from models.model import App, ApiToken
from models.tools import WorkflowToolProvider
from models.workflow import Workflow
from sqlalchemy import func, select
from sqlalchemy.orm import Session
packet = json.load(sys.stdin)
scope = {}
exec(compile(packet['worker'], '<test-worker>', 'exec'), scope)
scope['check_dependencies'] = lambda *_: None  # Offline fixture only.
_, app = create_app()
with app.app_context(), app.test_request_context('/'), Session(db.engine, expire_on_commit=False) as session:
    assert not session.scalar(select(func.count()).select_from(Tenant)), 'Fixture must be an empty database'
    application_services().setup.initialize(
        SetupInput('fixture@example.org', 'MES Fixture', uuid.uuid4().hex, '127.0.0.1', 'en-US'),
        initialization_validated=True)
    first = scope['provision'](session, packet['request'])
    workflow_dates = dict(session.execute(select(Workflow.id, Workflow.updated_at)).all())
    session.expire_all()
    repeated = scope['provision'](session, packet['request'])
    assert first == repeated
    assert workflow_dates == dict(session.execute(select(Workflow.id, Workflow.updated_at)).all())
    counts = [session.scalar(select(func.count()).select_from(m)) for m in (App, WorkflowToolProvider, Workflow, ApiToken)]
    assert counts == [8, 7, 16, 1], counts
    request = packet['request']
    specs = scope['build_specs'](first['tenant_id'], request['files'], request['provider'], request['model'])
    for role, spec in specs.items():
        model = session.get(App, spec['app_id'])
        assert scope['matches'](session.get(Workflow, model.workflow_id), spec, request['secrets'][role])
    assert first['api_key'].startswith('app-') and len(first['api_key']) > 20
    response = app.test_client().get('/v1/info', headers={'Authorization': 'Bearer '+first['api_key']})
    assert response.status_code == 200 and response.json['name'] == first['apps']['SUP']['name']
print('STAGE5_DISPOSABLE_CHECK_PASSED')
"""
    packet = dict(worker=(DEPLOY/'stage5_dify_provision.py').read_text(encoding='utf-8'), request=request)
    database = os.getenv('MES_STAGE5_DISPOSABLE_TEST_DATABASE', 'dify')
    proc = subprocess.run(['docker', 'exec', '-i', '-e', 'DB_DATABASE='+database, '-w', '/app/api', CONTAINER, 'python', '-c', fixture],
                          input=json.dumps(packet).encode('ascii'), capture_output=True, timeout=900)
    assert proc.returncode == 0, proc.stderr.decode('utf-8', errors='replace')[-3500:]
    assert b'STAGE5_DISPOSABLE_CHECK_PASSED' in proc.stdout
