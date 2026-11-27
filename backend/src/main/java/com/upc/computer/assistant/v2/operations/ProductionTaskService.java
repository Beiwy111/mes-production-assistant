package com.upc.computer.assistant.v2.operations;

import com.fasterxml.jackson.databind.*;
import com.upc.computer.ai.action.AgentActionPlanService;
import com.upc.computer.assistant.v2.orders.OrderSessionGuard;
import com.upc.computer.common.*;
import com.upc.computer.dto.LoginResponse;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Bounded observe/plan/act loop. Model output can call reads or prepare a proposal, never commit it. */
@Service
public class ProductionTaskService {
    public record Turn(String text,String sessionId,String actionCode,Map<String,Object> fields,List<Map<String,String>> history){}
    private final ProductionTaskModel model;
    private final ProductionTaskTools tools;
    private final ProductionOperationService operations;
    private final AgentActionPlanService plans;
    private final RedisUtil redis;
    private final OrderSessionGuard sessions;
    private final ObjectMapper json;
    private final ReentrantLock[] locks=new ReentrantLock[64];
    private final ExecutorService reads=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"production-task-read");t.setDaemon(true);return t;});
    public ProductionTaskService(ProductionTaskModel model,ProductionTaskTools tools,ProductionOperationService operations,
            AgentActionPlanService plans,RedisUtil redis,OrderSessionGuard sessions,ObjectMapper json){
        this.model=model;this.tools=tools;this.operations=operations;this.plans=plans;this.redis=redis;this.sessions=sessions;this.json=json;
        Arrays.setAll(locks,i->new ReentrantLock());
    }
    @jakarta.annotation.PreDestroy public void close(){reads.shutdownNow();}
    private String key(LoginResponse user,String session){
        if(session==null||!session.matches("[A-Za-z0-9_-]{1,160}"))throw new BusinessException(400,"无效会话编号");
        return "assistant:production-task:"+user.getUserId()+":"+user.getRoleCode()+":"+session;
    }
    private ReentrantLock lock(String key){var lock=locks[Math.floorMod(key.hashCode(),locks.length)];if(!lock.tryLock())throw new BusinessException(409,"当前任务正在处理，请等待返回后继续");return lock;}
    @SuppressWarnings("unchecked") private Map<String,Object> load(String key){Map<String,Object> value=redis.getJson(key,Map.class);return value==null?new LinkedHashMap<>():new LinkedHashMap<>(value);}
    private void save(String key,Map<String,Object> state){redis.setJson(key,state,86400);}
    private void active(String authorization,LoginResponse expected){var now=sessions.requireActive(authorization);if(!Objects.equals(now.getUserId(),expected.getUserId())||!Objects.equals(now.getRoleCode(),expected.getRoleCode()))throw new BusinessException(403,"账号权限已变化，请重新开始任务");}

    public Map<String,Object> run(Turn turn,String authorization,Consumer<Map<String,Object>> events){
        LoginResponse user=sessions.requireActive(authorization);
        String key=key(user,turn.sessionId());var lock=lock(key);
        try {
            if(turn.text()==null||turn.text().length()>4000)throw new BusinessException(400,"任务文字过长或为空");
            var state=load(key);
            // Old natural-language answers are not execution records (and may contain invented IDs).
            state.remove("lastAnswer");
            state.put("executionStatus",map(state.get("receipt")).getOrDefault("status","NOT_EXECUTED"));
            state.put("today",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());var emitter=new Emitter(events);
            List<Map<String,Object>> catalog=tools.catalog(user);
            if(catalog.isEmpty())throw new BusinessException(403,"当前角色没有生产办理工具权限");
            // Submitted forms may fill only the last server-issued request, never caller-invented action fields.
            if(turn.text().isBlank() && turn.actionCode()!=null){
                Map<String,Object> focused=map(state.get("focused"));
                if("confirmSmartDispatch".equals(turn.actionCode()) && turn.fields()!=null && turn.fields().containsKey("editProposalId")){
                    if(!"confirmSmartDispatch".equals(focused.get("actionCode")) ||
                       !Objects.equals(state.get("proposalId"),turn.fields().get("editProposalId")))
                        throw new BusinessException(409,"方案已变化，请使用最新派工表");
                    if(!turn.fields().keySet().equals(Set.of("editProposalId","assignmentOverrides")))
                        throw new BusinessException(400,"人员选择只能修改当前工序人员");
                    return preview(turn.sessionId(),turn.actionCode(),Map.of("assignmentOverrides",turn.fields().get("assignmentOverrides")),user,state,key,emitter,authorization);
                }
                if(!turn.actionCode().equals(focused.get("actionCode")))throw new BusinessException(409,"任务上下文已变化，请重新预览");
                Map<String,Object> fields=map(focused.get("fields"));
                Set<String> allowed=new HashSet<>();
                if(focused.get("missingFields") instanceof List<?> missing)for(Object item:missing)allowed.add(Objects.toString(map(item).get("name")));
                Map<String,Object> supplied=turn.fields()==null?Map.of():turn.fields();
                // Existing form sends the prior fields as well; forbid changes to fields not being requested.
                for(var field:supplied.entrySet())if(!allowed.contains(field.getKey())&&!Objects.equals(fields.get(field.getKey()),field.getValue()))throw new BusinessException(400,"只可补充当前请求的字段");
                fields.putAll(supplied);
                return preview(turn.sessionId(),turn.actionCode(),fields,user,state,key,emitter,authorization);
            }
            // A short, explicit fulfilment choice is a form answer, not a new open-ended task.
            // Resolve its entity and quantity from live authorized data; never from prior assistant prose.
            var focusedFields=map(map(state.get("focused")).get("fields"));
            String choiceOrder=Objects.toString(state.getOrDefault("selectedOrderId",focusedFields.get("orderId")),"");
            if(turn.text().strip().matches("^(?:那就|选择|选|我要|我选|按)?(?:全部|全量|重新)?(?:新生产|新投产|重新生产)(?:吧|[。！!])?$")
                    && choiceOrder.matches("CO[A-Za-z0-9_-]{1,78}")) {
                String choiceCall=emitter.start("PRODUCTION","orderContext","已选择新生产；重新核对当前订单与订购数量");
                Map<String,Object> context;
                try {
                    active(authorization,user);
                    context=map(tools.read("orderContext",Map.of("orderId",choiceOrder),user));
                    emitter.end("PRODUCTION",choiceCall,"orderContext","COMPLETED",summary(context));
                } catch(RuntimeException failure) {
                    emitter.end("PRODUCTION",choiceCall,"orderContext","FAILED",failure instanceof BusinessException?limit(failure.getMessage(),300):"订单核对暂不可用");
                    throw failure;
                }
                Object quantity=context.get("orderQuantity");
                if(quantity==null || !quantity.toString().matches("[1-9][0-9]{0,6}"))
                    throw new BusinessException(409,"订单缺少明确订购数量，请补充本次新生产数量");
                var selected=new LinkedHashMap<String,Object>();
                selected.put("orderId",choiceOrder);selected.put("plannedQty",quantity.toString());
                state.put("fulfillmentChoice","NEW_PRODUCTION");
                return preview(turn.sessionId(),"saveProductionPlan",selected,user,state,key,emitter,authorization);
            }
            List<Map<String,Object>> observations=new ArrayList<>();Set<String> called=new HashSet<>();
            List<Map<String,String>> history=turn.history()==null?List.of():turn.history().stream().skip(Math.max(0,turn.history().size()-6))
                .filter(h->"user".equals(h.get("role"))).map(h->Map.of("role",h.get("role"),"text",limit(h.get("text"),1200))).toList();
            for(int round=0;round<4;round++){
                active(authorization,user);
                String planning=emitter.start("SUP","task.plan","根据目标与已查询结果规划下一步");
                JsonNode plan;
                var visible=new StringBuilder();
                // Parameterless lookups are complete snapshots for this turn; do not offer
                // them again after success. Parameterized tools remain available for other entities.
                var available=(round == 3 ? List.<Map<String,Object>>of() : catalog).stream().filter(t->!Set.of("orders","plans","resources").contains(t.get("tool"))
                    || observations.stream().noneMatch(o->Objects.equals(o.get("tool"),t.get("tool"))&&"COMPLETED".equals(o.get("status")))).toList();
                try {plan=model.plan(authorization,json.convertValue(Map.of("question",turn.text(),"state",state,"observations",observations,"catalog",available,"history",history),Map.class),delta->{
                    if(visible.isEmpty()){
                        emitter.end("SUP",planning,"task.plan","COMPLETED","工具选择已结束，开始生成回答");
                        emitter.summarizing("RUNNING","根据已返回的工具结果整理回答或待补充事项");
                    }
                    visible.append(delta);events.accept(Map.of("type","delta","text",delta));});}
                catch(RuntimeException failure){if(visible.isEmpty())emitter.end("SUP",planning,"task.plan","FAILED","规划服务未返回有效结果");else emitter.summarizing("FAILED","回答生成中断，请核对已返回的查询结果");throw failure;}
                if(plan==null||!plan.path("calls").isArray()||plan.path("calls").size()>3)throw new BusinessException(502,"规划结果格式无效，未执行操作");
                String goal=limit(plan.path("goal").asText(),1000);if(!goal.isBlank())state.put("goal",goal);
                if(visible.isEmpty())emitter.end("SUP",planning,"task.plan","COMPLETED",plan.path("calls").isEmpty()?"已形成回答或待补充事项":"已选择 "+plan.path("calls").size()+" 个工具，按依赖执行");
                if(plan.path("calls").isEmpty()){
                    if(!visible.isEmpty())emitter.summarizing("COMPLETED","回答已生成，依据来自本轮查询及已核验任务记录");
                    String reply=limit(plan.path("reply").asText(),6000);
                    if(reply.isBlank())reply="请补充要处理的业务对象或调整要求。";
                    state.put("lastQuestion",turn.text());state.put("lastAnswer",reply);save(key,state);
                    return Map.of("status","COMPLETED","message",reply,"answerStreamed",!visible.isEmpty(),"taskManaged",true,"goal",state.getOrDefault("goal",""));
                }
                List<JsonNode> requests=new ArrayList<>();plan.path("calls").forEach(requests::add);
                for(JsonNode call:requests){
                    if(!call.isObject()||call.size()!=2||!call.path("tool").isTextual()||!call.path("args").isObject())throw new BusinessException(400,"无效的工具参数");
                    String name=call.path("tool").asText();
                    if(catalog.stream().noneMatch(t->name.equals(t.get("tool"))))throw new BusinessException(403,"模型选择了未授权工具");
                }
                emitter.planned(requests.stream().map(c->c.path("tool").asText()).toList());
                if(requests.stream().anyMatch(c->c.path("tool").asText().equals("preview"))){
                    if(requests.size()!=1){observations.add(Map.of("tool","preview","status","FAILED","message","一次只能准备一个方案；多个候选先让用户选择。缺少编号可以用 fields={} 获取选择表单。"));continue;}
                    JsonNode args=requests.get(0).path("args");
                    if(args.size()!=2||!args.path("actionCode").isTextual()||!args.path("fields").isObject()){
                        observations.add(Map.of("tool","preview","status","FAILED","message","参数错误：args 只能包含 actionCode 字符串和 fields 对象。fields 不能是数组；实际业务字段必须放在 fields 对象内。请按工具 parameters 重建参数。"));continue;
                    }
                    String signature=requests.get(0).toString();
                    if(!called.add(signature)){
                        observations.add(Map.of("tool","preview","status","ALREADY_ATTEMPTED","message","同一方案本轮已失败；必须说明实际冲突并询问用户，不得重复调用或自行放宽约束。"));
                        continue;
                    }
                    String action=args.path("actionCode").asText();
                    Map<String,Object> fields=json.convertValue(args.path("fields"),Map.class);
                    try {return preview(turn.sessionId(),action,fields,user,state,key,emitter,authorization);}
                    catch(BusinessException failure){observations.add(Map.of("tool","preview","status","FAILED","message",limit(failure.getMessage(),350)));}
                }else{
                    List<Future<Map<String,Object>>> pending=new ArrayList<>();
                    for(JsonNode call:requests){
                        String signature=call.toString();
                        if(!called.add(signature)){
                            observations.add(Map.of("tool",call.path("tool").asText(),"status","ALREADY_READ","message","相同条件已在本轮查询，请使用 observations 中的已有结果回答或选择不同参数；未重复调用工具"));
                            continue;
                        }
                        pending.add(reads.submit(()->{
                            active(authorization,user);String tool=call.path("tool").asText(),agent=agent(tool);
                            String id=emitter.start(agent,tool,ProductionTaskTools.LABELS.get(tool)+(call.path("args").isEmpty()?"":" · "+call.path("args").toString()));
                            try {
                                Object data=com.upc.computer.service.ProductionCalculationProgress.observe(emitter::calculation,()->tools.read(tool,json.convertValue(call.path("args"),Map.class),user));
                                emitter.end(agent,id,tool,"COMPLETED",summary(data));
                                emitter.handoff(agent,id,tool,"将本次查询结果交给总协调规划器，决定下一步");
                                return Map.<String,Object>of("tool",tool,"arguments",json.convertValue(call.path("args"),Map.class),"status","COMPLETED","data",data);
                            }catch(RuntimeException e){
                                String reason=e instanceof BusinessException?limit(e.getMessage(),350):"业务查询暂不可用，请重试";
                                emitter.end(agent,id,tool,"FAILED",reason);
                                return Map.<String,Object>of("tool",tool,"arguments",json.convertValue(call.path("args"),Map.class),"status","FAILED","message",reason);
                            }
                        }));
                    }
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                    try {for(var future:pending)observations.add(future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS));}
                    catch(Exception e){pending.forEach(f->f.cancel(true));if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new BusinessException(503,"业务查询超时或中断，请重试；未执行生产操作");}
                    var ordersThisRound=observations.stream().filter(o->"orderContext".equals(o.get("tool"))&&"COMPLETED".equals(o.get("status")))
                        .map(o->map(o.get("data")).get("orderId")).filter(Objects::nonNull).distinct().toList();
                    if(ordersThisRound.size()==1)state.put("selectedOrderId",ordersThisRound.get(0));
                    state.put("lastQuestion",turn.text());
                    // Persist only bounded recent observations; candidates are re-read before any operation.
                    String evidence=json.valueToTree(observations).toString();
                    if(evidence.length()<48000)state.put("observations",observations);
                }
            }
            save(key,state);
            return Map.of("status","COMPLETED","message","已完成本轮查询，但还未形成可执行方案。请明确优先交期、数量或人员限制，继续调整；未执行生产操作。","taskManaged",true);
        }finally{lock.unlock();}
    }

    private Map<String,Object> preview(String sessionId,String action,Map<String,Object> fields,LoginResponse user,
            Map<String,Object> state,String key,Emitter emitter,String authorization){
        active(authorization,user);
        String call=emitter.start("PRODUCTION","preview","检查业务状态、生成可确认方案");
        try {
            String old=Objects.toString(state.get("proposalId"),"");
            if(!old.isBlank()){
                try {plans.confirmProduction(old,"SKIP",user);}
                catch(BusinessException stale){if(stale.getCode()!=409&&stale.getCode()!=404)throw stale;}
                state.remove("proposalId");save(key,state);
            }
            if("saveProductionPlan".equals(action)) {
                var focused=map(state.get("focused"));var previous=map(focused.get("fields"));
                if(action.equals(focused.get("actionCode")) &&
                   (!fields.containsKey("orderIds") || Objects.equals(fields.get("orderIds"),previous.get("orderIds"))) &&
                   (!fields.containsKey("orderId") || !previous.containsKey("orderIds")) &&
                   (fields.get("orderId")==null || Objects.equals(fields.get("orderId"),previous.get("orderId")))) {
                    var merged=new LinkedHashMap<>(previous);if(fields.containsKey("fulfillment")&&!fields.containsKey("plannedQty"))merged.remove("plannedQty");merged.putAll(fields);fields=merged;
                }
            }
            // The planner has already selected an explicit action; the legacy UI's
            // PLAN/DISPATCH chooser is only needed when the action is ambiguous.
            if("saveProductionPlan".equals(action)&&!fields.containsKey("planningTask")){
                fields=new LinkedHashMap<>(fields);fields.put("planningTask","PLAN");
            }
            if ("confirmSmartDispatch".equals(action)) {
                Map<String,Object> focused=map(state.get("focused"));
                Map<String,Object> previous=map(focused.get("fields"));
                Map<String,Object> merged=new LinkedHashMap<>();
                if(action.equals(focused.get("actionCode")) && (fields.get("planId")==null || Objects.equals(fields.get("planId"),previous.get("planId"))))
                    merged.putAll(previous);
                // Each override is an incremental adjustment; {} explicitly resets the personnel overrides.
                if(fields.get("assignmentOverrides") instanceof String changes && !changes.strip().equals("{}") && merged.get("assignmentOverrides") instanceof String before){
                    try {
                        var combined=json.readTree(before).deepCopy();
                        var patch=json.readTree(changes);
                        if(combined.isObject() && patch.isObject()){
                            ((com.fasterxml.jackson.databind.node.ObjectNode)combined).setAll((com.fasterxml.jackson.databind.node.ObjectNode)patch);
                            fields=new LinkedHashMap<>(fields);fields.put("assignmentOverrides",combined.toString());
                        }
                    } catch(Exception invalid) { throw new BusinessException(400,"人员调整格式无效"); }
                }
                merged.putAll(fields);merged.putIfAbsent("assignmentOverrides","{}");fields=merged;
            }
            var checked = ProductionCommand.byCode(action).bind("",fields);
            state.put("focused",Map.of("actionCode",action,"fields",checked,"missingFields",List.of()));
            save(key,state);
            var previewTurn=new ProductionOperationService.Turn("",sessionId,action,fields);
            var result=com.upc.computer.service.ProductionCalculationProgress.observe(emitter::calculation,()->operations.preview(previewTurn,user));
            Map<String,Object> data=json.convertValue(result,Map.class);
            if("saveProductionPlan".equals(action) && result.fields().get("orderId") instanceof String selectedOrder)
                state.put("selectedOrderId",selectedOrder);
            state.put("focused",Map.of("actionCode",Objects.toString(result.actionCode(),""),"fields",result.fields(),"missingFields",result.missingFields()));
            state.remove("proposalId");
            if(result.confirmation()!=null)state.put("proposalId",result.confirmation().get("proposalId"));
            save(key,state);
            emitter.end("PRODUCTION",call,"preview","COMPLETED",result.status().equals("PENDING")?"方案已准备，等待用户确认；未执行写入":"已返回可选对象或待补充字段");
            data.put("taskManaged",true);data.put("goal",state.getOrDefault("goal",""));return data;
        }catch(RuntimeException e){emitter.end("PRODUCTION",call,"preview","FAILED",e instanceof BusinessException?limit(e.getMessage(),300):"方案准备失败");throw e;}
    }
    public Map<String,Object> decide(String sessionId,String proposalId,String decision,String authorization){
        var user=sessions.requireActive(authorization);String key=key(user,sessionId);var lock=lock(key);
        try {
            Map<String,Object> state=load(key);
            if(!proposalId.equals(state.get("proposalId"))&&!proposalId.equals(state.get("lastProposalId")))throw new BusinessException(409,"方案已被替换或不属于当前任务，请重新预览");
            if(!Set.of("APPROVE","SKIP").contains(decision))throw new BusinessException(400,"请使用方案确认按钮");
            Map<String,Object> result=plans.confirmProduction(proposalId,decision,user);
            // A lost-response retry for a completed prior step must not clear a newer proposal.
            if(!proposalId.equals(state.get("proposalId")))return result;
            Map<String,Object> anchors=map(state.get("anchors"));
            if("EXECUTED".equals(result.get("status"))){
                Map<String,Object> fields=map(map(state.get("focused")).get("fields"));
                for(var source:List.of(fields,map(result.get("result"))))for(String name:List.of("orderId","planId","workOrderNo","workOrderId","dispatchId")){
                    Object value=source.get(name);if(value instanceof String id && id.matches("(?:CO|PP|WO|DT)[A-Za-z0-9_-]{1,78}"))anchors.put(name,id);
                }
                state.put("anchors",anchors);
            }
            state.remove("proposalId");state.put("lastProposalId",proposalId);state.put("receipt",result);
            state.remove("focused");state.remove("observations");save(key,state);
            return result;
        }finally{lock.unlock();}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> m?new LinkedHashMap<>((Map<String,Object>)m):new LinkedHashMap<>();}
    private static String limit(String value,int max){return value==null?"":value.substring(0,Math.min(max,value.length()));}
    private static String agent(String tool){return tool.equals("orders")?"ORDER":tool.equals("resources")?"EQUIPMENT":"PRODUCTION";}
    private static String summary(Object data){
        if(data instanceof Collection<?> rows){
            var identifiers=rows.stream().filter(r->r instanceof Map<?,?>).map(r->(Map<?,?>)r)
                .map(r->r.containsKey("orderId")?r.get("orderId"):r.get("planId")).filter(Objects::nonNull).limit(3).map(Object::toString).toList();
            return "查到 "+rows.size()+" 条记录"+(identifiers.isEmpty()?"":"："+String.join("、",identifiers)+(rows.size()>3?"等":""))+"；已交回候选供选择";
        }
        if(data instanceof Map<?,?> values){
            var details=new ArrayList<String>();
            var labels=Map.of("orderId","订单","planId","计划","orderQuantity","订购数量","recommendedPlanQty","建议生产数量","status","状态");
            for(String k:List.of("orderId","planId","orderQuantity","recommendedPlanQty","status"))if(values.get(k)!=null)details.add(labels.get(k)+"："+values.get(k));
            var collections=Map.of("operators","在岗账号","equipment","设备","processRoute","工艺工序","materialGaps","物料核对","capacityRisks","产能提示","workOrders","工单");
            for(String k:List.of("operators","equipment","processRoute","materialGaps","capacityRisks","workOrders"))if(values.get(k) instanceof Collection<?> list)details.add(collections.get(k)+" "+list.size()+" 条");
            return details.isEmpty()?"查询完成，已返回业务上下文":limit(String.join("；",details),450);
        }
        return "查询完成，已返回结果";
    }
    static final class Emitter {
        final Consumer<Map<String,Object>> sink;long seq;
        Emitter(Consumer<Map<String,Object>> sink){this.sink=sink;}
        synchronized void calculation(Map<String,Object> stage){
            var event=new LinkedHashMap<String,Object>(stage);
            event.put("seq",++seq);event.put("kind","CALCULATION");event.put("agentId","PRODUCTION");
            event.put("at",java.time.Instant.now().toString());
            sink.accept(Map.of("type","workflow","invalidated",false,"events",List.of(event)));
        }
        synchronized void planned(List<String> tools){
            String detail=String.join("、",tools.stream().map(t->ProductionTaskTools.LABELS.getOrDefault(t,"准备操作方案")).toList());
            sink.accept(Map.of("type","workflow","invalidated",false,"events",List.of(Map.of("seq",++seq,"agentId","SUP","kind","PLAN_CONFIRMED","status","COMPLETED","label","本轮任务拆解","detail",detail+(tools.size()>1?"（独立读取并行执行）":""),"at",java.time.Instant.now().toString()))));
        }
        synchronized String start(String agent,String tool,String label){String id=UUID.randomUUID().toString();emit(agent,tool,id,"AGENT_STARTED","RUNNING",label);emit(agent,tool,id,"STEP_STARTED","RUNNING",label);return id;}
        synchronized void end(String agent,String id,String tool,String status,String detail){emit(agent,tool,id,"STEP_COMPLETED",status,detail);emit(agent,tool,id,"AGENT_COMPLETED",status,detail);}
        synchronized void handoff(String agent,String id,String tool,String detail){emit("SUP",tool,id,"HANDOFF","COMPLETED",detail);}
        synchronized void summarizing(String status,String detail){
            sink.accept(Map.of("type","workflow","invalidated",false,"events",List.of(Map.of("seq",++seq,"agentId","SUP","kind","SUMMARIZING","status",status,
                "label","汇总本轮结果","detail",detail,"at",java.time.Instant.now().toString()))));
        }
        void emit(String agent,String tool,String id,String kind,String status,String detail){
            Map<String,Object> event=new LinkedHashMap<>();event.put("seq",++seq);event.put("agentId",agent);event.put("taskId",id);
            event.put("toolId","TASK_"+tool.replace('.','_').toUpperCase(Locale.ROOT));event.put("toolCallId",id);event.put("kind",kind);event.put("status",status);
            if("HANDOFF".equals(kind)){event.put("fromAgent",ProductionTaskService.agent(tool));event.put("toAgent","SUP");}
            event.put("label",ProductionTaskTools.LABELS.getOrDefault(tool,tool.equals("preview")?"生成操作方案":"规划下一步"));event.put("detail",detail);event.put("at",java.time.Instant.now().toString());
            sink.accept(Map.of("type","workflow","invalidated",false,"events",List.of(event)));
        }
    }
}
