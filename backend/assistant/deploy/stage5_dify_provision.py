"""Worker executed inside the pinned Dify API image by Provision-Stage5Dify.py."""
import json
from copy import deepcopy
from pathlib import Path
import runpy
import tempfile
import uuid


class SetupError(Exception):
    """Operator-facing diagnostic with no private service response or credentials."""


def identity(tenant_id, kind, role):
    return str(uuid.uuid5(uuid.UUID(tenant_id), f'computer-mes:stage5:{kind}:{role}'))


def configure_model(dsl, provider, model):
    for node in dsl['workflow']['graph']['nodes']:
        data = node['data']
        if data['type'] == 'agent':
            config = data['agent_parameters']['model']['value']
            config['model'] = model
        elif data['type'] == 'llm':
            config = data['model']
            config['name'] = model
        else:
            continue
        if provider != config['provider']:
            config['completion_params'].pop('thinking', None)
        config['provider'] = provider


def build_specs(tenant_id, files, provider, model):
    import yaml
    # The generator reads its sibling prompts and node source relative to its own file.
    with tempfile.TemporaryDirectory(prefix='mes-stage5-') as directory:
        root = Path(directory)
        for rel, content in files.items():
            target = root/rel
            if not target.resolve().is_relative_to(root.resolve()):
                raise SetupError('Invalid generator file path.')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8')
        builder = runpy.run_path(str(root/'scripts/build_stage5.py'))
        tool_spec = runpy.run_path(str(root/'manage.py'))['tool_spec']
        tools = builder['TOOLS']
        roles = ('KNOWLEDGE', *tools, 'SUP')
        providers = {r: identity(tenant_id, 'tool', r) for r in roles if r != 'SUP'}
        specs = {}
        for role in roles:
            if role == 'KNOWLEDGE':
                dsl = yaml.safe_load(files['workflows/stage4-graph-rag.yml'])
                dsl['app']['name'] = 'MES 共享知识工具（第五阶段）'
            else:
                dsl = builder['build'](role, providers)
            configure_model(dsl, provider, model)
            specs[role] = dict(app_id=identity(tenant_id, 'app', role), tool_id=providers.get(role),
                               tool=tool_spec(role, dsl['app']['name'], tools), dsl=dsl)
        return specs


def matches(workflow, spec, key):
    if workflow is None:
        return False
    from core.app.apps.workflow.app_config_manager import WorkflowAppConfigManager
    target = spec['dsl']['workflow']
    # Dify's draft validator populates defaults (e.g. TTS voice and language)
    # in-place before storing the complete features object.
    features = deepcopy(target['features'])
    WorkflowAppConfigManager.config_validate(workflow.tenant_id, features, only_structure_validate=True)
    variables = workflow.environment_variables
    expected = target['environment_variables'][0]
    return (workflow.graph_dict == target['graph'] and workflow.features_dict == features
            and len(variables) == 1 and variables[0].value_type == 'secret' and variables[0].name == expected['name']
            and variables[0].id == expected['id'] and variables[0].value == key)


def check_dependencies(account, provider, model):
    from core.model_manager import ModelManager
    from core.plugin.impl.agent import PluginAgentClient
    from graphon.model_runtime.entities.model_entities import ModelType
    tenant_id = account.current_tenant_id
    try:
        agent = PluginAgentClient().fetch_agent_strategy_provider(tenant_id, 'langgenius/agent/agent')
        if 'function_calling' not in {s.identity.name for s in agent.declaration.strategies}:
            raise ValueError()
    except Exception:
        raise SetupError('Install the langgenius/agent Function Calling strategy in the local Dify workspace.') from None
    try:
        manager = ModelManager.for_tenant(tenant_id, account.id)
        configured = manager.get_model_instance(tenant_id, provider, ModelType.LLM, model)
        schema = configured.get_model_schema()
        if not {'tool-call', 'multi-tool-call'}.intersection(schema.features or []):
            raise ValueError()
    except Exception:
        raise SetupError('Configure the selected tool-calling LLM and its credentials in Dify. Check the provider and model IDs.') from None


def check_targets(session, tenant_id, specs):
    from models.model import App
    from models.tools import WorkflowToolProvider
    from sqlalchemy import or_, select
    for role, spec in specs.items():
        name = spec['dsl']['app']['name']
        apps = session.scalars(select(App).where(or_(App.id == spec['app_id'],
            (App.tenant_id == tenant_id) & (App.name == name))))
        for app in apps:
            if (app.id, app.tenant_id, app.name, app.mode) != (spec['app_id'], tenant_id, name, 'workflow'):
                raise SetupError(f'An existing app conflicts with {role}. Use a fresh local Dify workspace.')
        if spec['tool']:
            tool_name = spec['tool']['name']
            tools = session.scalars(select(WorkflowToolProvider).where(or_(
                WorkflowToolProvider.id == spec['tool_id'], (WorkflowToolProvider.tenant_id == tenant_id) &
                or_(WorkflowToolProvider.name == tool_name, WorkflowToolProvider.app_id == spec['app_id']))))
            for tool in tools:
                if (tool.id, tool.tenant_id, tool.app_id, tool.name) != (spec['tool_id'], tenant_id, spec['app_id'], tool_name):
                    raise SetupError(f'An existing workflow tool conflicts with {role}. Use a fresh local Dify workspace.')


def ensure_app(session, account, role, spec, key):
    from models.model import App
    from services.app_dsl_service import AppDslService
    from services.workflow_service import WorkflowService
    from graphon.variables import SecretVariable
    dsl = spec['dsl']
    workflow = dsl['workflow']
    app = session.get(App, spec['app_id'])
    if app is None:
        # Only the empty secret placeholder is submitted to the DSL importer.
        imported = AppDslService(session).import_app(account=account, import_mode='yaml-content',
            yaml_content=json.dumps(dsl, ensure_ascii=False), import_app_id=spec['app_id'])
        if imported.status not in {'completed', 'completed-with-warnings'} or imported.app_id != spec['app_id']:
            raise SetupError(f'Dify rejected the {role} DSL. Verify Dify 1.19.0 and the installed plugins.')
        session.commit()
        app = session.get(App, spec['app_id'])
    service = WorkflowService()
    draft = service.get_draft_workflow(app_model=app, session=session)
    if not matches(draft, spec, key):
        env = workflow['environment_variables'][0]
        variable = SecretVariable(id=env['id'], name=env['name'], selector=env['selector'],
                                  description=env['description'], value=key)
        service.sync_draft_workflow(app_model=app, graph=workflow['graph'], features=workflow['features'],
            unique_hash=draft.unique_hash if draft else None, account=account,
            environment_variables=[variable], conversation_variables=[], session=session)
    published = service.get_published_workflow(app_model=app, session=session)
    if not matches(published, spec, key):
        published = service.publish_workflow(session=session, app_model=app, account=account,
            marked_name='Stage5 '+role, marked_comment='MES local setup')
        session.flush()
        app.workflow_id = published.id
        app.updated_by = account.id
        session.commit()
    return published


def ensure_tool(session, account, spec, published):
    from core.tools.entities.tool_entities import WorkflowToolParameterConfiguration
    from models.tools import WorkflowToolProvider
    from services.tools.workflow_tools_manage_service import WorkflowToolManageService
    tool = spec['tool']
    existing = session.get(WorkflowToolProvider, spec['tool_id'])
    parameters = [WorkflowToolParameterConfiguration(**p) for p in tool['parameters']]
    if (existing and existing.version == published.version and existing.label == tool['label']
            and existing.description == tool['description'] and
            json.loads(existing.parameter_configuration) == [p.model_dump(mode='json') for p in parameters]):
        return
    params = dict(user_id=account.id, tenant_id=account.current_tenant_id, name=tool['name'],
                  label=tool['label'], description=tool['description'], icon={'content':'🤖','background':'#E0F2FE'},
                  parameters=parameters)
    if existing:
        WorkflowToolManageService.update_workflow_tool(workflow_tool_id=existing.id, **params)
    else:
        WorkflowToolManageService.create_workflow_tool(workflow_app_id=spec['app_id'], import_id=spec['tool_id'], **params)


def workbench_token(session, tenant_id, sup_app_id):
    from models.enums import ApiTokenType
    from models.model import ApiToken
    token_id = identity(tenant_id, 'api-key', 'SUP')
    token = session.get(ApiToken, token_id)
    if token is None:
        token = ApiToken(id=token_id, type=ApiTokenType.APP, app_id=sup_app_id, tenant_id=tenant_id,
                         token=ApiToken.generate_api_key('app-', 32, session=session))
        session.add(token)
        session.commit()
    elif token.app_id != sup_app_id or token.type != ApiTokenType.APP or token.tenant_id != tenant_id:
        raise SetupError('The existing Stage5 API key is bound to a different app.')
    return token.token


def provision(session, request):
    from models.account import Account, TenantAccountJoin
    from sqlalchemy import select
    owners = session.scalars(select(TenantAccountJoin).where(TenantAccountJoin.role == 'owner')).all()
    if len(owners) != 1:
        raise SetupError('Open http://127.0.0.1:18080/install and set up one local workspace owner before provisioning.')
    tenant_id = owners[0].tenant_id
    if request['expected_tenant'] and request['expected_tenant'] != tenant_id:
        raise SetupError('This checkout is already bound to a different local Dify workspace.')
    account = session.get(Account, owners[0].account_id)
    account.set_tenant_id_with_session(tenant_id, session=session)
    provider, model = request['provider'], request['model']
    check_dependencies(account, provider, model)
    specs = build_specs(tenant_id, request['files'], provider, model)
    check_targets(session, tenant_id, specs)
    apps = {}
    for role, spec in specs.items():
        try:
            published = ensure_app(session, account, role, spec, request['secrets'][role])
            if spec['tool']:
                ensure_tool(session, account, spec, published)
            apps[role] = dict(app_id=spec['app_id'], name=spec['dsl']['app']['name'],
                              tool_id=spec['tool_id'], published_version=published.version)
        except SetupError:
            raise
        except Exception as exc:
            raise SetupError(f'Could not publish {role} ({type(exc).__name__}). Check Dify readiness and rerun setup.') from None
    token = workbench_token(session, tenant_id, specs['SUP']['app_id'])
    return dict(tenant_id=tenant_id, provider=provider, model=model, apps=apps, api_key=token)


def main(request):
    try:
        from gevent import monkey
        monkey.patch_all()
        from app_factory import create_app
        from extensions.ext_database import db
        from sqlalchemy import text
        from sqlalchemy.orm import Session
        _, app = create_app()
        with app.app_context(), app.test_request_context('/'), db.engine.connect() as lock:
            # A transaction on a separate connection spans Dify service-managed commits.
            if not lock.scalar(text('select pg_try_advisory_xact_lock(593172530326)')):
                raise SetupError('Another Stage5 provisioning command is running. Retry when it finishes.')
            with Session(db.engine, expire_on_commit=False) as session:
                result = provision(session, request)
    except SetupError as exc:
        result = {'error': str(exc)}
    except Exception as exc:
        result = {'error': f'Dify local provisioning failed ({type(exc).__name__}). Check Docker services and Dify 1.19.0.'}
    print('MES_STAGE5_RESULT=' + json.dumps(result, ensure_ascii=True))
