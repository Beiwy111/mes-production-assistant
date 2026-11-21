"""Pure boundary functions embedded verbatim into Dify Code nodes. No I/O."""
import json
import re
from datetime import date

ROLES = {'ORDER', 'SUPPLY', 'PRODUCTION', 'QUALITY', 'EQUIPMENT', 'AFTERSALES'}
INTENTS = {'INVENTORY','PRODUCTION','QUALITY','DELIVERY','ORDER','EQUIPMENT','AFTERSALES','KNOWLEDGE','MAINTENANCE','ALARMS','STOCK_MOVEMENT'}

INTAKE_LIMITS = {'productQuery':120, 'requiredDeliveryDate':10, 'receiverName':80,
                 'receiverPhone':30, 'receiverAddress':500, 'customerName':80, 'remark':500}


def order_intake_input(goal, context):
    if not isinstance(goal,str) or not 1<=len(goal.strip())<=2000:
        raise ValueError('Invalid intake text')
    if not isinstance(context,str) or len(context)>16000 or not isinstance(json.loads(context),dict):
        raise ValueError('Invalid intake context')
    return {'goal':goal.strip(),'context':context}


def intake_json(text):
    if not isinstance(text,str) or len(text)>8000: raise ValueError('Invalid intake output')
    cleaned=text.strip()
    if cleaned.startswith('```') and cleaned.endswith('```'):
        cleaned=re.sub(r'^```(?:json)?\s*','',cleaned,flags=re.I)[:-3].strip()
    def unique(pairs):
        result={}
        for key,value in pairs:
            if key in result: raise ValueError('Duplicate intake field')
            result[key]=value
        return result
    return json.loads(cleaned,object_pairs_hook=unique)


def order_intake_intent(text):
    result=intake_json(text)
    if not isinstance(result,dict) or set(result)!={'action'} or result['action'] not in {'ORDER','QUERY','CANCEL'}:
        raise ValueError('Invalid supervisor intake intent')
    return result


def order_intake_result(text, action=None):
    result=intake_json(text)
    if not isinstance(result,dict) or set(result)!={'action','fields','clearFields','question'}:
        raise ValueError('Unexpected intake fields')
    if result['action'] not in {'ORDER','QUERY','CANCEL'}: raise ValueError('Invalid intake action')
    if action is not None and result['action']!=action: raise ValueError('Intake action differs from supervisor intent')
    fields=result['fields']; allowed=set(INTAKE_LIMITS)|{'quantity'}
    if not isinstance(fields,dict) or not set(fields)<=allowed: raise ValueError('Invalid field patch')
    for key,value in fields.items():
        if key=='quantity':
            if type(value)!=int or not 1<=value<=2147483647: raise ValueError('Invalid quantity')
        elif not isinstance(value,str) or not value.strip() or len(value)>INTAKE_LIMITS[key] or any(ord(c)<32 or ord(c)==127 for c in value):
            raise ValueError('Invalid text field')
        elif key=='requiredDeliveryDate':
            if not re.fullmatch(r'\d{4}-\d{2}-\d{2}',value): raise ValueError('Invalid date')
            date.fromisoformat(value)
    clear=result['clearFields']
    if not isinstance(clear,list) or len(clear)>len(allowed) or any(not isinstance(f,str) or f not in allowed for f in clear):
        raise ValueError('Invalid clear fields')
    if len(set(clear))!=len(clear) or set(clear)&set(fields): raise ValueError('Conflicting field patch')
    if result['action']!='ORDER' and (fields or clear): raise ValueError('Only order action can change fields')
    if not isinstance(result['question'],str) or len(result['question'])>500 or any(ord(c)<32 or ord(c)==127 for c in result['question']):
        raise ValueError('Invalid question')
    return {'order_intake_json':json.dumps(result,ensure_ascii=False)}


def planning_context(body, status_code, run_ref):
    data = trusted(body, status_code, run_ref)
    if data.get('queryMode') != 'NATURAL':
        return {'context':json.dumps({'queryMode':'LEGACY','workflowProfile':data.get('workflowProfile')})}
    result = {k:data.get(k) for k in ['queryMode','userQuery','currentDate','dateTimezone','previousContext','scope','availableTools','capabilities']}
    text = json.dumps(result, ensure_ascii=False)
    if len(text) > 24000: raise ValueError('Planning context exceeds budget')
    return {'context':text}


def planning_body(text, context):
    if json.loads(context).get('queryMode') == 'LEGACY':
        return {'payload':json.dumps({'action':'LEGACY'})}
    if not isinstance(text,str) or len(text)>8000: raise ValueError('Invalid planning result')
    cleaned = text.strip()
    if cleaned.startswith('```') and cleaned.endswith('```'):
        cleaned = re.sub(r'^```(?:json)?\s*', '', cleaned, flags=re.I)[:-3].strip()
    def unique(pairs):
        result={}
        for key,value in pairs:
            if key in result: raise ValueError('Duplicate plan field')
            result[key]=value
        return result
    plan=json.loads(cleaned,object_pairs_hook=unique)
    fields={'action','intents','orderPk','orderNo','keyword','period','question'}
    if not isinstance(plan,dict) or set(plan)!=fields: raise ValueError('Unexpected plan fields')
    if plan['action'] not in {'QUERY','CLARIFY','UNSUPPORTED'}: raise ValueError('Invalid plan action')
    if not isinstance(plan['intents'],list) or len(plan['intents'])>len(INTENTS) or any(i not in INTENTS for i in plan['intents']): raise ValueError('Invalid intents')
    if len(set(plan['intents']))!=len(plan['intents']): raise ValueError('Duplicate intent')
    if plan['period'] not in {'CURRENT','TODAY','LAST_7_DAYS','THIS_MONTH'}: raise ValueError('Invalid period')
    for key,maximum in [('orderPk',19),('orderNo',100),('keyword',100)]:
        if plan[key] is not None and (not isinstance(plan[key],str) or not 1<=len(plan[key])<=maximum): raise ValueError('Invalid scope value')
    if plan['orderPk'] is not None and not re.fullmatch(r'[1-9][0-9]{0,18}',plan['orderPk']): raise ValueError('Invalid order primary key')
    if not isinstance(plan['question'],str) or len(plan['question'])>500: raise ValueError('Invalid clarification')
    return {'payload':json.dumps(plan,ensure_ascii=False)}


def validate_input(run_ref, goal):
    if not isinstance(run_ref, str) or not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', run_ref):
        raise ValueError('Invalid run reference; create a scoped preview run first')
    if not isinstance(goal, str) or not 1 <= len(goal.strip()) <= 2000:
        raise ValueError('Goal must contain 1..2000 characters')
    return {'run_ref': run_ref, 'goal': goal.strip()}


def trusted(body, status_code, run_ref):
    if status_code != 200 or not isinstance(body, str) or len(body) > 2_000_000:
        raise ValueError('Read-only runtime unavailable or request rejected; no delivery conclusion')
    data = json.loads(body)
    if data.get('runRef') != run_ref or data.get('sourceMode') != 'LOCAL_READONLY_PREVIEW':
        raise ValueError('Runtime identity or scope mismatch')
    return data


def bounded(value, limit=30, depth=0):
    """Bound presentation only. Never use a display subset to compute totals."""
    if depth > 12:
        return None, True
    truncated = False
    if isinstance(value, list):
        result = []
        truncated = len(value) > limit
        for item in value[:limit]:
            reduced, cut = bounded(item, limit, depth + 1)
            result.append(reduced); truncated |= cut
        return result, truncated
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            reduced, cut = bounded(item, limit, depth + 1)
            result[key] = reduced; truncated |= cut
        return result, truncated
    if isinstance(value, str) and len(value) > 1000:
        return value[:1000] + ' [展示截断]', True
    return value, False


def supervisor_context(body, status_code, run_ref):
    data = trusted(body, status_code, run_ref)
    if data.get('queryMode') == 'NATURAL':
        required=data.get('requiredAgents')
        if data.get('workflowProfile')!='AUTO' or not isinstance(required,list) or any(r not in ROLES for r in required):
            raise ValueError('Invalid automatic task plan')
        result={k:data.get(k) for k in ['runRef','queryMode','workflowProfile','scope','plan','requiredAgents','dependencies','sourceMode','userQuery']}
        return {'context':json.dumps(result,ensure_ascii=False)}
    profiles={'DELIVERY':['ORDER','SUPPLY','PRODUCTION'], 'ORDER':['ORDER'], 'SUPPLY':['ORDER','SUPPLY'],
              'PRODUCTION':['ORDER','SUPPLY','PRODUCTION'], 'QUALITY':['QUALITY'], 'EQUIPMENT':['EQUIPMENT'],
              'AFTERSALES':['AFTERSALES'], 'FULL_ASSESSMENT':['ORDER','SUPPLY','PRODUCTION','QUALITY','EQUIPMENT','AFTERSALES']}
    profile=data.get('workflowProfile','DELIVERY')
    if profile not in profiles or data.get('requiredAgents') != profiles[profile]:
        raise ValueError('Unsupported workflow profile')
    result = {k:data.get(k) for k in ['runRef', 'scope', 'requiredAgents', 'sourceMode']}
    result['workflowProfile']=profile
    result['coverage'] = 'Scoped delivery and live Q1/D1/A1 business evidence; empty recorded data is not an unavailable interface. Public knowledge is separate.'
    return {'context':json.dumps(result, ensure_ascii=False)}


def dispatch_waves(context):
    """Compile only the already authorized server plan; no model-invented roles/dependencies."""
    plan = json.loads(context)
    result = {'mode':'legacy', 'wave1':[], 'wave2':[], 'wave3':[]}
    if plan.get('queryMode') != 'NATURAL':
        return result
    required, dependencies = plan.get('requiredAgents'), plan.get('dependencies')
    if (not isinstance(required, list) or any(not isinstance(r,str) or r not in ROLES for r in required)
            or len(set(required)) != len(required) or not isinstance(dependencies,dict)
            or set(dependencies) != set(required)):
        raise ValueError('Invalid authorized dispatch plan')
    for role, predecessors in dependencies.items():
        if (not isinstance(predecessors,list) or any(not isinstance(p,str) or p not in required or p==role for p in predecessors)
                or len(set(predecessors)) != len(predecessors)):
            raise ValueError('Invalid authorized dependencies')
    completed = set()
    for wave in ('wave1','wave2','wave3'):
        ready = [r for r in required if r not in completed and set(dependencies[r]) <= completed]
        result[wave] = ready
        completed.update(ready)
    if completed != set(required):
        raise ValueError('Cyclic or unsupported dependency depth; no partial dispatch')
    result['mode'] = 'parallel'
    return result


def task_context(body, status_code, run_ref, role):
    data = trusted(body, status_code, run_ref)
    if role not in ROLES or data.get('agentId') != role or not data.get('taskId'):
        raise ValueError('Task role mismatch')
    fields = ('runRef','taskId','agentId','status','sourceMode','upstreamResultRefs','queryMode','knowledgeOnly','knowledgeEvidence','upstreamFindings','steps')
    result = {k:data.get(k) for k in fields}
    result['evidence'] = []
    allowed = {
        'ORDER': {'order','lines','planRefs','workOrderRefs','plannedCoverageQty','unplannedQty','receiptEvidence','metDeadline'},
        'SUPPLY': {'materials','readiness','scenarioReadiness','returnCoverage','demandBuckets','allocationScenarioId','purchaseRecords','bomRecords'},
        'PRODUCTION': {'workOrders','savedSchedules','scheduleBasis','estimatedShipmentAt','calendarCoverage','recordCompleteness'},
        'QUALITY':{'status','complete','warnings','inspections','inspectionItems','nonconformingProducts','inspectionCount','inspectionItemCount','nonconformingRecordCount','batchReleaseStatus','visionEvidence'},
        'EQUIPMENT':{'status','complete','warnings','equipment','observedUsage','alarms','maintenanceRecords','equipmentCount','observedUsageCount','alarmCount','maintenanceRecordCount','currentStateBasis','historicalReliabilityStatus','failureProbabilityStatus'},
        'AFTERSALES':{'status','complete','warnings','cases','tasks','closures','caseRecordStatus','tracePaths','caseCount','taskCount','closureCount','tracePathLimit','tracePathsComplete','traceStatus','rootCauseStatus'},
    }[role]
    for envelope in data.get('evidence', []):
        item = {k:envelope.get(k) for k in ['queryRef','status','observedAt','missing','warnings','calculationVersion']}
        item['data'] = (envelope.get('data',{}) if data.get('queryMode')=='NATURAL' else
                        {k:v for k,v in envelope.get('data',{}).items() if k in allowed})
        if data.get('queryMode') == 'NATURAL' and envelope.get('kind') in ('RESOURCE_MATERIAL', 'RESOURCE_CAPACITY'):
            original = envelope.get('data', {})
            item['kind'] = envelope['kind']
            item['data'] = {k: original.get(k) for k in ('status','basis','scope','analysis_at')}
            item['data']['record_counts'] = {k:len(original.get(k, [])) for k in ('rows','coverage','demands')}
            item['data']['rows'] = original.get('rows', [])[:12]
            item['data']['coverage'] = original.get('coverage', [])[:12]
            item['data']['presentation_truncated'] = any(len(original.get(k, [])) > 12 for k in ('rows','coverage'))
            item['data']['source_evidence_location'] = '完整来源及全部计算行保留在服务端查询引用和最终表格；此处仅供专家阅读，不以展示子集推断完整计数。'
        result['evidence'].append(item)
    result, cut = bounded(result)
    result['presentation_truncated'] = cut or any(item.get('data', {}).get('presentation_truncated', False) for item in result.get('evidence', []))
    result['rules'] = '数字仅引用业务接口，null不是0；展示截断不代表完整范围；论文不证明订单质量放行或交期。'
    context = json.dumps(result, ensure_ascii=False)
    if len(context) > 50000:
        raise ValueError('Task facts exceed presentation budget; no partial success')
    return {'context':context}


def completion_body(summary):
    if not isinstance(summary, str) or not 1 <= len(summary.strip()) <= 6000:
        raise ValueError('Expert summary is empty or exceeds budget')
    return {'payload':json.dumps({'summary':summary.strip()}, ensure_ascii=False)}


def receipt_output(body, status_code, run_ref, role):
    data = trusted(body, status_code, run_ref)
    if data.get('agentId') != role or not data.get('resultRef'):
        raise ValueError('Unregistered expert output')
    context = json.loads(task_context(body,status_code,run_ref,role)['context'])
    context.update(resultRef=data['resultRef'], completedAt=data.get('completedAt'))
    # Model prose is deliberately not promoted to upstream numerical facts.
    context['modelCommentary'] = data.get('modelCommentary','')[:6000]
    context['modelCommentaryAuthority'] = 'UNVERIFIED_EXPLANATION_NOT_BUSINESS_FACT'
    return {'result':json.dumps(context, ensure_ascii=False)}


def compact_response_context(body, status_code, run_ref):
    data = trusted(body, status_code, run_ref)
    if data.get('verdict') not in {'EVIDENCE_READY', 'INSUFFICIENT_EVIDENCE'}:
        return {'context': json.dumps({'verdict': data.get('verdict'), 'required_reply': data.get('reply', '')}, ensure_ascii=False),
                'presentation': 'compact_stream'}
    context = {'verdict': data.get('verdict'), 'domains': [], 'limitations': data.get('missing', []),
               'analyses': data.get('analyses', []), 'equipment_comments': []}
    resource_facts = data.get('facts') or {}
    if any(k in resource_facts for k in ('shared_material', 'capacity_conflicts')):
        statements = []
        material = resource_facts.get('shared_material')
        capacity = resource_facts.get('capacity_conflicts')
        if material is not None:
            orders = list(dict.fromkeys(str(r['order_no']) for r in material.get('rows', []) if r.get('risk_status') in ('SCENARIO_GAP','SHARED_POOL_SCENARIO_GAP') and r.get('order_no')))
            statements.append('供料情景存在缺口的订单：'+'、'.join(orders)+'。' if orders else '当前可核验计划的供料情景未发现缺口。')
            statements.append('共享库存按计划行交期分配一次；这不是已确认实际缺料，已领料与订单专属预留仍需核实。')
        if capacity is not None:
            statements.append('发现跨订单排程重叠，相关订单、资源及时间见表。' if capacity.get('rows') else '当前/未来可核验排程未发现跨订单重叠。')
        statements.append('结论仅覆盖本次授权及筛选范围；缺失或矛盾依据见表，不代表所有订单均无风险。')
        context['required_reply'] = '\n\n'.join(statements)
        return {'context': json.dumps(context, ensure_ascii=False), 'presentation': 'compact_stream'}
    for key, fact in (data.get('facts') or {}).items():
        if not isinstance(fact, dict):
            continue
        overview = fact.get('overview')
        context['domains'].append({'domain': overview.get('domain') if isinstance(overview, dict) else key,
                                   'incomplete': overview.get('truncated', False) if isinstance(overview, dict) else fact.get('complete') is False})
        context['limitations'].extend(fact.get('warnings', []))
    for item in data.get('expertExplanations', []):
        if item.get('agentId') == 'EQUIPMENT' and item.get('text'):
            context['equipment_comments'].append({'unverified': True, 'text': str(item['text'])[:1800]})
    if 'KNOWLEDGE' in data.get('intents', []):
        context['knowledge_only'] = data.get('intents') == ['KNOWLEDGE']
        unique = {}
        for bundle in data.get('knowledgeEvidence', []):
            for hit in bundle.get('hits', []):
                if hit.get('evidence_ref'):
                    unique.setdefault(hit['evidence_ref'], hit)
        context['sources'] = [{'id': f'S{i+1}', **{k:hit.get(k) for k in ('title','page_start','source_url','text','evidence_ref')}}
                              for i, hit in enumerate(unique.values())]
        context['limitations'].append('公开研究不是企业内部SOP或产品验收标准。')
        if not context['sources']:
            context['required_reply'] = '当前知识库未取得可核验的资料，无法确定该问题的答案；不能将通用建议当作企业 SOP。'
        return {'context': json.dumps(context, ensure_ascii=False), 'presentation': 'compact_stream'}
    if data.get('verdict') not in {'EVIDENCE_READY', 'INSUFFICIENT_EVIDENCE'} or not context['domains']:
        context['required_reply'] = data.get('reply', '')
    elif (data.get('verdict') == 'EVIDENCE_READY' and not context['limitations']
          and not any(item['incomplete'] for item in context['domains'])
          and not any(key in (data.get('facts') or {}) for key in ('E1', 'E2', 'E3', 'E4'))
          and not context['equipment_comments'] and not any(
              analysis.get(key) for analysis in context['analyses'] for key in ('findings', 'hypotheses', 'actions', 'missing'))):
        labels = {'INVENTORY': '库存', 'PRODUCTION': '生产工单', 'ORDER': '订单', 'QUALITY': '质量检验',
                  'EQUIPMENT': '设备', 'AFTERSALES': '售后', 'MAINTENANCE':'维修记录', 'ALARMS':'报警记录', 'STOCK_MOVEMENT':'库存流水', 'E1': '订单', 'E2': '物料', 'E3': '生产工单', 'E4': '交付'}
        names = list(dict.fromkeys(labels.get(str(item['domain']).upper(), '业务') for item in context['domains']))
        context['required_reply'] = '已查询到以下' + '、'.join(names) + '信息：'
    if any(key in (data.get('facts') or {}) for key in ['E1', 'E2', 'E3', 'E4']):
        context['delivery_caution'] = '交付、库存分配和物料缺口以表格口径为准，情景数量不是已预留数量，不能承诺交期。'
    return {'context': json.dumps(context, ensure_ascii=False), 'presentation': 'compact_stream'}


def final_answer(body, status_code, run_ref):
    data = trusted(body, status_code, run_ref)
    if data.get('queryMode')=='NATURAL':
        if data.get('verdict') not in {'CLARIFY','UNSUPPORTED','NOT_FOUND','EVIDENCE_READY','INSUFFICIENT_EVIDENCE','INCOMPLETE','DATA_CHANGED'}:
            raise ValueError('Unexpected natural query verdict')
        reply=data.get('reply')
        if not isinstance(reply,str) or not reply.strip(): raise ValueError('Missing authoritative answer')
        if data['verdict'] in {'EVIDENCE_READY','INSUFFICIENT_EVIDENCE'} and 'analyses' in data:
            for analysis in data.get('analyses',[]):
                role={'QUALITY':'质量分析','AFTERSALES':'售后追溯'}.get(analysis.get('agentId'),'业务分析')
                reply+='\n\n'+role+'（模型解读，引用已核验；不代表根因确认）：'
                if analysis.get('status')=='INVALID_FORMAT':
                    reply+='\n未取得格式有效的分析，仅以上方业务事实为准。'
                    continue
                for key,label in [('findings','记录发现'),('hypotheses','待验证原因'),('actions','建议核查')]:
                    for item in analysis.get(key,[])[:5]:
                        reply+='\n'+label+'：'+item.get('text','')+' ['+'；'.join(item.get('evidenceRefs',[]))+']'
                        if item.get('verification'): reply+='\n验证方法：'+item['verification']
                if analysis.get('missing'):reply+='\n证据缺口：'+'；'.join(analysis['missing'])
            for bundle in data.get('knowledgeEvidence',[]):
                if bundle.get('status')!='MATCHES':reply+='\n公开知识检索：本次未取得可引用资料（'+str(bundle.get('status','UNKNOWN'))+'），不能用论文补足业务证据。'
                elif bundle.get('mode_used')!='graph_assisted':reply+='\n知识依据来自向量检索；本次未取得有效图谱关系。'
            for item in data.get('expertExplanations',[]):
                if item.get('agentId')=='EQUIPMENT' and item.get('text'):
                    reply+='\n\n设备分析建议（需结合证据核验）：\n'+str(item['text'])[:1800]
        elif data['verdict'] in {'EVIDENCE_READY','INSUFFICIENT_EVIDENCE'}:
            # Explicitly separate model interpretation/public literature from server-computed facts.
            explanations=[]
            for item in data.get('expertExplanations',[]):
                if item.get('agentId') in {'QUALITY','AFTERSALES','EQUIPMENT'} and item.get('text'):
                    explanations.append(str(item['text'])[:1800])
            if explanations: reply += '\n\n分析建议（需结合证据核验）：\n'+'\n\n'.join(explanations)
        # The gateway independently reloads the finalized authoritative result and
        # checks runRef/verdict. Never duplicate full facts in Dify's bounded string
        # output: large queries must keep all rows at the authenticated backend.
        receipt={key:data[key] for key in ('runRef','verdict','queryMode')}
        return {'answer':reply[:20000],'decision':json.dumps(receipt,ensure_ascii=False)}
    labels = {
        'INSUFFICIENT_EVIDENCE':'证据不足，暂不能确认该订单能够按期交付。',
        'DATA_CHANGED':'查询期间业务数据发生变化，本次判断失效，需要重新查询。',
        'INCOMPLETE':'必需专家或业务证据未齐，本次交付判断未完成。',
        'EVIDENCE_READY':'已完成所选场景的只读证据查询；这不代表质量放行或交付承诺。',
    }
    verdict = data.get('verdict')
    if verdict not in labels:
        raise ValueError('Unexpected delivery verdict; positive promises are not supported')
    rows = [labels[verdict], '范围：本机只读预览，未执行采购、排产、放行、入库或发货。',
            '已启动的专家：' + '、'.join(data.get('executedAgents',[])),
            '数据复核时间：' + str(data.get('checkedAt') or '未知')]
    facts = data.get('facts') or {}
    def display(value):
        return '未知（不能按0处理）' if value is None else str(value)
    if verdict in {'INSUFFICIENT_EVIDENCE','EVIDENCE_READY'}:
        for line in (facts.get('fulfillmentLines') or [])[:30]:
            line_id = line.get('orderItemPk') or line.get('order_item_id') or '未知'
            rows.append('订单行 '+str(line_id)+'：剩余交付量 '+display(line.get('remainingDeliveryQty'))+
                        '；确认可发货量 '+display(line.get('shippableQty'))+'。')
            if 'candidateReadyQty' in line:
                rows.append('该行公共合格库存分配情景：'+display(line.get('candidateReadyQty'))+'；尚未预留，不等于确认可发货量。')
        for material in (facts.get('materials') or [])[:30]:
            if 'scenarioGapQty' in material:
                rows.append('物料 '+str(material.get('materialId','未知'))+'：当前BOM情景缺口 '+display(material.get('scenarioGapQty'))+
                            '；已确认精确缺口 '+display(material.get('confirmedShortageNow'))+'。情景假设见结构化证据。')
        for key,label,count in [('quality','质量检验','inspectionCount'),('equipment','关联设备','equipmentCount'),('aftersales','售后案件','caseCount')]:
            domain=facts.get(key)
            if isinstance(domain,dict):
                rows.append(label+'记录数：'+display(domain.get(count))+'；数据状态 '+str(domain.get('status','未知'))+'。')
        rows.append('情景数量仅用于备货分析；预计发货时间仍需完整产能、放行和物流证据，不作确定承诺。')
    if verdict == 'DATA_CHANGED':
        rows.append('旧查询数量仅留在审计记录，不作为当前判断依据。')
    rows.append('缺失/限制：' + '；'.join(str(x) for x in data.get('missing',[])))
    explanations=[]
    for item in (data.get('expertExplanations',data.get('knowledgeExplanations',[])) if verdict!='DATA_CHANGED' else []):
        if (item.get('agentId') not in ROLES or
                item.get('authority')!='UNVERIFIED_MODEL_EXPLANATION' or
                item.get('status') not in {'PARTIAL','UNSUPPORTED_BUSINESS_SOURCE'} or not item.get('resultRef')):
            raise ValueError('Invalid nonbusiness explanation boundary')
        text=item.get('text','')
        if not isinstance(text,str) or len(text)>6000:
            raise ValueError('Invalid expert explanation')
        explanations.append(item)
        prefix='未接入真实业务数据。' if item['status']=='UNSUPPORTED_BUSINESS_SOURCE' else '已读取本角色业务证据；具体数量以上方结构化事实为准。'
        rows.append(item['agentId']+'补充说明：'+prefix+'以下为模型解释，引用仍需复核，不作为交付依据。\n'+text)
    result = {k:data.get(k) for k in ['runRef','workflowProfile','verdict','sourceMode','executedAgents','missing','checkedAt','facts','queryRefs','resultRefs','revalidation']}
    result['knowledgeExplanations']=explanations
    result['expertExplanations']=explanations
    result, cut = bounded(result)
    result['presentation_truncated'] = cut
    if cut:
        rows.append('展示已截断；不能把显示的部分记录当作整单合计。')
    encoded = json.dumps(result, ensure_ascii=False)
    if len(encoded) > 70000:
        raise ValueError('Decision exceeds presentation budget')
    return {'answer':'\n\n'.join(rows), 'decision':encoded}
