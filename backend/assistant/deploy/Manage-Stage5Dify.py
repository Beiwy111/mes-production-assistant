"""Local administrator helper for only the explicitly mapped Stage5 Dify apps.

Uses the installed Dify service layer in its own container. Never changes account
credentials, login state, other applications or the MES database. Secrets travel
through stdin, not command arguments; repository DSL files stay secret-free.
"""
import json
import sys


def tool_spec(role, app_name, tool_names):
    if role == 'SUP':
        return None
    if role == 'KNOWLEDGE':
        return {'name': 'public_display_knowledge', 'label': '显示器公开知识 RAG+GraphRAG',
                'description': '公开论文与有出处关系；不提供订单或放行事实。',
                'parameters': [{'name': 'question', 'description': '公开研究问题，不含客户与订单标识', 'form': 'llm'},
                               {'name': 'history', 'description': '空历史', 'form': 'form'}]}
    return {'name': tool_names[role], 'label': app_name,
            'description': '只处理服务端校验的查询范围（概览或精确订单）；依赖和角色由后端验证，返回resultRef与证据。角色 '+role,
            'parameters': [{'name': 'run_ref', 'description': '固定绑定运行引用，不由模型生成', 'form': 'llm'},
                           {'name': 'task_goal', 'description': '本角色范围内的具体问题', 'form': 'llm'}]}


def container_main():
    sys.path.insert(0, '/app/api')
    from gevent import monkey
    monkey.patch_all()
    import uuid
    from app_factory import create_app
    from extensions.ext_database import db
    from sqlalchemy import select
    from sqlalchemy.orm import Session
    from models.model import App
    from models.account import Account
    from models.workflow import Workflow
    from models.tools import WorkflowToolProvider
    from services.workflow_service import WorkflowService
    from services.tools.workflow_tools_manage_service import WorkflowToolManageService
    from core.tools.entities.tool_entities import WorkflowToolParameterConfiguration
    from graphon.variables import SecretVariable
    data=json.load(sys.stdin)
    _,app=create_app()
    result=[]
    with app.app_context(), app.test_request_context('/'):
        with Session(db.engine,expire_on_commit=False) as session:
            for command in data['commands']:
                model=session.get(App,command['app_id'])
                if model is None or model.name!=command['expected_name'] or not model.name.endswith('（第五阶段）'):
                    raise ValueError('Target is not the explicitly mapped Stage5 app')
                account=session.get(Account,model.created_by)
                account.set_tenant_id_with_session(model.tenant_id,session=session)
                if str(account.current_role) not in ('owner','admin'):
                    raise ValueError('Stage5 app creator must be the existing workspace administrator')
                service=WorkflowService()
                draft=service.get_draft_workflow(app_model=model,session=session)
                action=command['action']
                if action=='deploy':
                    content=command.get('workflow')
                    if content:
                        role_variable=SecretVariable(id=str(uuid.uuid5(uuid.NAMESPACE_URL,'mes-stage5-role-key-'+command['role'])),
                            name='ROLE_API_KEY',value=command['role_key'],selector=['env','ROLE_API_KEY'])
                        draft=service.sync_draft_workflow(app_model=model,graph=content['graph'],features=content['features'],
                            unique_hash=draft.unique_hash,account=account,environment_variables=[role_variable],
                            conversation_variables=[],session=session)
                        model.description=command['description']
                        session.commit()
                    published=service.publish_workflow(session=session,app_model=model,account=account,
                        marked_name='Stage5 '+command['role'],marked_comment='Independent read-only preview; no MES entry switch.')
                    session.flush();model.workflow_id=published.id;model.updated_by=account.id;session.commit()
                    if command.get('tool'):
                        tool=command['tool']
                        existing=session.scalar(select(WorkflowToolProvider).where(WorkflowToolProvider.app_id==model.id,
                                                    WorkflowToolProvider.tenant_id==model.tenant_id))
                        params=[WorkflowToolParameterConfiguration(**p) for p in tool['parameters']]
                        kwargs=dict(user_id=account.id,tenant_id=model.tenant_id,name=tool['name'],label=tool['label'],
                            icon={'content':'🤖','background':'#E0F2FE'},description=tool['description'],parameters=params)
                        if existing:
                            WorkflowToolManageService.update_workflow_tool(workflow_tool_id=existing.id,**kwargs)
                        else:
                            WorkflowToolManageService.create_workflow_tool(workflow_app_id=model.id,**kwargs)
                    session.expire_all()
                elif action=='run':
                    from services.app_generate_service import AppGenerateService
                    from core.app.entities.app_invoke_entities import InvokeFrom
                    generated=AppGenerateService.generate(session=session,app_model=model,user=account,
                        args={'inputs':command['inputs'],'files':[]},invoke_from=InvokeFrom.DEBUGGER,streaming=False)
                    result.append({'role':command['role'],'app_id':model.id,'run':generated})
                    continue
                elif action=='node-test':
                    if command['role'] not in ('QUALITY','EQUIPMENT','AFTERSALES'):
                        raise ValueError('Isolated knowledge-only node test is limited to unsupported-source experts')
                    execution=service.run_draft_workflow_node(app_model=model,draft_workflow=draft,node_id='agent_node',
                        user_inputs=command['inputs'],account=account,files=[])
                    outputs=getattr(execution,'outputs_dict',None)
                    if outputs is None:
                        outputs=execution.outputs
                        if isinstance(outputs,str):outputs=json.loads(outputs)
                    result.append({'role':command['role'],'app_id':model.id,'run':{'data':{
                        'status':str(execution.status),'error':execution.error,'outputs':outputs or {},
                        'elapsed_time':execution.elapsed_time,'node_execution_id':execution.id,
                        'test_scope':'ISOLATED_AGENT_NODE_PUBLIC_KNOWLEDGE_ONLY_NO_BUSINESS_RUN'}}})
                    continue
                elif action!='inspect':
                    raise ValueError('Unsupported maintenance action')
                published=service.get_published_workflow(app_model=model,session=session)
                tool=session.scalar(select(WorkflowToolProvider).where(WorkflowToolProvider.app_id==model.id,
                                            WorkflowToolProvider.tenant_id==model.tenant_id))
                result.append({'role':command['role'],'app_id':model.id,'tool_id':tool.id if tool else None,
                    'published_version':published.version if published else None,
                    'node_count':len(published.graph_dict['nodes']) if published else 0,
                    'graph_matches_local':published.graph_dict==command['workflow']['graph'] if command.get('workflow') else None,
                    'secret_names':[v.name for v in published.environment_variables] if published else []})
    print('STAGE5_RESULT:'+json.dumps({'ok':True,'apps':result},ensure_ascii=True))


def local_main():
    import argparse
    import subprocess
    from pathlib import Path
    import yaml
    root=Path(__file__).resolve().parents[3]
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=['deploy','inspect','run','node-test'])
    parser.add_argument('--roles',nargs='+',required=True)
    parser.add_argument('--inputs-file',type=Path)
    args=parser.parse_args()
    base=root/'backend/assistant/agents'
    private_bindings=root/'backend/assistant/deploy/.env.stage5-bindings.local'
    bindings_file=private_bindings if private_bindings.is_file() else base/'bindings.stage5.local.json'
    bindings=json.loads(bindings_file.read_text(encoding='utf-8'))
    env={}
    for line in (root/'backend/assistant/deploy/.env.agents.local').read_text(encoding='utf-8').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key,value=line.split('=',1);env[key]=value
    tools={'ORDER':'order_planning_v1','SUPPLY':'supply_inventory_v1','PRODUCTION':'production_dispatch_v1',
           'QUALITY':'quality_inspection_v1','EQUIPMENT':'equipment_support_v1','AFTERSALES':'aftersales_trace_v1'}
    commands=[]
    for role in args.roles:
        mapped=bindings['apps'][role]
        cmd={'action':args.action,'role':role,'app_id':mapped['app_id'],'expected_name':mapped['name']}
        if args.action in ('run','node-test'):
            if args.inputs_file is None or len(args.roles)!=1:raise ValueError('Run needs exactly one role and local inputs file')
            cmd['inputs']=json.loads(args.inputs_file.read_text(encoding='utf-8'))
        cmd['tool']=tool_spec(role,mapped['name'],tools)
        if role!='KNOWLEDGE':
            source=base/'workflows/stage5'/f'{role}.yml'
            if private_bindings.is_file():
                import runpy
                generator=runpy.run_path(str(base/'scripts/build_stage5.py'))
                providers={r:a['tool_id'] for r,a in bindings['apps'].items() if a.get('tool_id')}
                dsl=generator['build'](role,providers)
                configure=runpy.run_path(str(private_bindings.parent/'stage5_dify_provision.py'))['configure_model']
                configure(dsl,bindings['provider'],bindings['model'])
                cmd['workflow']=dsl['workflow']
                cmd['description']=dsl['app']['description']
            elif source.exists():
                dsl=yaml.safe_load(source.read_text(encoding='utf-8'));cmd['workflow']=dsl['workflow']
                cmd['description']=dsl['app']['description']
            if args.action=='deploy':
                if not source.exists():raise ValueError('Generate the role DSL first')
                cmd['role_key']=env[f'MES_AGENT_{role}_KEY']
        commands.append(cmd)
    copied=subprocess.run(['docker','cp',str(Path(__file__).resolve()),'mes-dify-local-api-1:/tmp/mes-stage5-admin.py'],capture_output=True)
    if copied.returncode:raise RuntimeError('Could not copy the scoped Dify maintenance helper')
    proc=subprocess.run(['docker','exec','-i','mes-dify-local-api-1','python','/tmp/mes-stage5-admin.py','--container'],
        input=json.dumps({'commands':commands},ensure_ascii=True),capture_output=True,text=True,encoding='utf-8',timeout=300)
    lines=[line for line in proc.stdout.splitlines() if line.startswith('STAGE5_RESULT:')]
    if not lines:
        # Keep diagnostics private; never echo config or generated request bodies.
        log=root/'backend/assistant/rag/logs/stage5-admin-error.log'
        error=proc.stdout+'\n'+proc.stderr
        for value in env.values():
            if len(value)>20:error=error.replace(value,'[redacted]')
        log.parent.mkdir(exist_ok=True);log.write_text(error,encoding='utf-8')
        raise RuntimeError('Dify maintenance failed; sanitized diagnostics saved locally')
    result=json.loads(lines[-1].split(':',1)[1])
    if not result.get('ok'):raise RuntimeError('Dify maintenance rejected request')
    if args.action in ('run','node-test'):
        role=args.roles[0]
        private=root/'backend/assistant/rag/data'/('stage5-dify-'+role+'-last.json')
        private.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        run=result['apps'][0]['run']
        summary=run.get('data',run) if isinstance(run,dict) else {}
        print(json.dumps({'ok':True,'role':role,'status':summary.get('status'),'error':summary.get('error'),
            'workflow_run_id':run.get('workflow_run_id') if isinstance(run,dict) else None,
            'output_keys':list(summary.get('outputs',{})), 'elapsed_time':summary.get('elapsed_time'),
            'private_result_file':str(private)},ensure_ascii=True))
        return
    for item in result['apps']:
        bindings['apps'][item['role']].update({k:v for k,v in item.items() if k!='role'})
    bindings_file.write_text(json.dumps(bindings,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=True))


if __name__=='__main__':
    if '--container' in sys.argv:container_main()
    else:local_main()
