package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.upc.computer.assistant.v2.tools.delivery.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Natural-language run state. Plans select only fixed queries; model prose is never authoritative. */
public final class NaturalLanguageRuntime {
    public interface Access { Map<String,Object> apply(long owner,Function<Data,Map<String,Object>> operation); }
    public interface Research {
        Map<String,Object> retrieve(String role,List<Map<String,Object>> evidence);
        default Map<String,Object> retrieveQuestion(String query) {return Map.of("status","NOT_CONFIGURED","hits",List.of(),"graph_paths",List.of());}
    }
    public record Data(NaturalBusinessReadMapper overview,AiDeliveryQueryService delivery,AssistantReadPolicy policy) {
        public Data(NaturalBusinessReadMapper overview,AiDeliveryQueryService delivery){this(overview,delivery,AssistantReadPolicy.admin());}
    }
    private static final String SOURCE="LOCAL_READONLY_PREVIEW";
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final Set<String> INTENTS=Set.of("INVENTORY","PRODUCTION","QUALITY","DELIVERY","ORDER","EQUIPMENT","AFTERSALES","KNOWLEDGE","MAINTENANCE","ALARMS","STOCK_MOVEMENT");
    private static final Set<String> FIELDS=Set.of("action","intents","orderPk","orderNo","keyword","period","question");
    private static final List<String> ROLE_ORDER=List.of("ORDER","SUPPLY","PRODUCTION","QUALITY","EQUIPMENT","AFTERSALES");
    static final int MAX_WORKFLOW_EVENTS=128;
    private static final Map<String,String> DOMAIN_EVIDENCE=Map.of("QUALITY","Q1","EQUIPMENT","D1","AFTERSALES","A1");
    private final Clock clock;
    private final Access access;
    private final Research research;
    private final ConversationScopeStore contextStore;
    private final Map<String,Run> runs=new LinkedHashMap<>();
    public NaturalLanguageRuntime(Clock clock,Access access) { this(clock,access,(role,evidence)->Map.of("status","NOT_CONFIGURED","hits",List.of(),"graph_paths",List.of())); }
    public NaturalLanguageRuntime(Clock clock,Access access,Research research) {this(clock,access,research,ConversationScopeStore.disabled());}
    public NaturalLanguageRuntime(Clock clock,Access access,Research research,ConversationScopeStore contextStore) { this.clock=clock;this.access=access;this.research=research;this.contextStore=Objects.requireNonNull(contextStore); }
    public synchronized boolean contains(String ref) { return runs.containsKey(ref); }
    public synchronized Map<String,Object> create(JsonNode input) {
        String query=text(input,"query",4000); long owner;
        try { owner=Long.parseLong(text(input,"ownerUserId",20)); if(owner<=0)throw new IllegalArgumentException(); }
        catch(RuntimeException invalid) { throw error(400,"INVALID_OWNER"); }
        var permission=access.apply(owner,d->Map.of("policy",d.policy));
        Map<String,Object> previous=null;
        if(input.has("previousRunRef")&&!input.get("previousRunRef").isNull()) {
            String previousRef=text(input,"previousRunRef",36);
            try {
                Run prior=require(previousRef);
                if(prior.owner!=owner)throw error(403,"PREVIOUS_RUN_FORBIDDEN");
                previous=object("userQuery",prior.query,"plan",prior.plan,"scope",scope(prior),"reply",prior.result==null?null:prior.result.get("reply"),
                    "awaitingOrderInput",prior.awaitingOrderInput&&prior.result!=null&&"CLARIFY".equals(prior.result.get("verdict")));
            } catch(ResponseStatusException absent) {
                if(absent.getStatusCode().value()!=404)throw absent;
                previous=contextStore.load(previousRef,owner);
                if(previous==null)throw error(404,"PREVIOUS_RUN_UNAVAILABLE");
                if(!Objects.equals(previous.get("authorizationStamp"),((AssistantReadPolicy)permission.get("policy")).fingerprint()))previous=null;
            }
        }
        runs.values().removeIf(r->!r.expires.isAfter(clock.instant()));
        if(runs.size()>=64)throw error(429,"CAPACITY_REACHED");
        Run run=new Run(UUID.randomUUID().toString(),query,owner,clock.instant().plusSeconds(600),previous);
        run.policy=(AssistantReadPolicy)permission.get("policy");
        runs.put(run.ref,run);return contextMap(run);
    }
    public synchronized Map<String,Object> context(String ref) { return contextMap(require(ref)); }
    public synchronized Map<String,Object> plan(String ref,JsonNode input) {
        Run run=require(ref);
        if(run.plan!=null) {
            if(run.planInput.equals(input))return contextMap(run);
            throw error(409,"PLAN_ALREADY_SET");
        }
        Set<String> actual=new HashSet<>(); input.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(FIELDS))throw error(400,"INVALID_PLAN");
        String action=text(input,"action",20),period=text(input,"period",20);
        if(!Set.of("QUERY","CLARIFY","UNSUPPORTED").contains(action)||!Set.of("CURRENT","TODAY","LAST_7_DAYS","THIS_MONTH").contains(period))throw error(400,"INVALID_PLAN");
        JsonNode intentNodes=input.get("intents");
        if(!intentNodes.isArray()||intentNodes.size()>INTENTS.size())throw error(400,"INVALID_PLAN");
        List<String> intents=new ArrayList<>();
        for(JsonNode node:intentNodes)if(!node.isTextual()||!INTENTS.contains(node.asText())||!intents.add(node.asText()))throw error(400,"INVALID_PLAN");
        if(new HashSet<>(intents).size()!=intents.size())throw error(400,"INVALID_PLAN");
        String pk=nullable(input,"orderPk",20),number=nullable(input,"orderNo",100),keyword=nullable(input,"keyword",100);
        if(pk!=null)try { AiDeliveryQueryService.id(pk); } catch(RuntimeException invalid) {throw error(400,"INVALID_PLAN");}
        if(!input.get("question").isTextual()||input.get("question").asText().length()>1000)throw error(400,"INVALID_PLAN");
        String question=input.get("question").asText().trim();
        if(action.equals("QUERY")&&intents.isEmpty()||!action.equals("QUERY")&&question.isBlank())throw error(400,"INVALID_PLAN");
        run.planInput=input.deepCopy();
        run.crossOrderRisk=Pattern.compile("跨订单.*(?:风险|共享|物料|产能|冲突)|订单.*(?:风险分析|交付风险|共享物料|物料竞争|产能冲突)|哪些订单.*风险").matcher(run.query).find()&&pk==null&&number==null;
        if(run.crossOrderRisk&&intents.stream().allMatch(Set.of("ORDER","DELIVERY","INVENTORY","PRODUCTION")::contains)&&!action.equals("UNSUPPORTED")){action="QUERY";intents=new ArrayList<>(List.of("ORDER"));question="";}
        String workReference=WorkOrderReference.extract(run.query,run.previous);
        if(workReference==null&&run.query.contains("派工")&&WorkOrderReference.multipleReferences(run.query)) {
            action="CLARIFY";question="请先选择一张生产工单的完整编号，再核对派工记录；不会忽略其他明确编号。";
        }
        if(workReference!=null&&!action.equals("UNSUPPORTED")) {
            if(WorkOrderReference.multipleReferences(run.query)||intents.stream().anyMatch(intent->!Set.of("ORDER","PRODUCTION").contains(intent))||(keyword!=null&&!keyword.equalsIgnoreCase(workReference)&&!keyword.matches("(?i)WO[A-Z0-9_-]+"))) {
                action="CLARIFY";question="请先明确一张生产工单及要查询的派工范围；多个编号或额外物料条件不能被省略。";
            } else if(!run.policy.allows("PRODUCTION")||!run.policy.allows("DISPATCH")) {
                action="UNSUPPORTED";question="当前账号没有生产工单与派工查询权限。";
            } else {
                var resolved=access.apply(run.owner,d->{
                    d.policy.require("PRODUCTION");d.policy.require("DISPATCH");
                    var candidates=d.overview.resolveWorkOrders(workReference,d.policy.operator());
                    boolean customerCollision=workReference.matches("[0-9]+")&&!run.query.contains("工单")&&d.policy.allows("ORDER")&&!d.overview.resolveCustomerOrder(Long.valueOf(workReference),null,d.policy.customer()).isEmpty();
                    return object("candidates",candidates,"collision",customerCollision);
                });
                @SuppressWarnings("unchecked") var candidates=(List<Map<String,Object>>)resolved.get("candidates");
                if(candidates.size()==1&&!Boolean.TRUE.equals(resolved.get("collision"))) {
                    run.workOrder=String.valueOf(candidates.get(0).get("work_order_id"));run.workOrderNo=String.valueOf(candidates.get(0).get("work_order_no"));
                    action="QUERY";question="";intents=new ArrayList<>(List.of("PRODUCTION"));pk=null;number=null;keyword=null;
                } else {
                    action="CLARIFY";question=candidates.isEmpty()?"当前授权范围内未找到该生产工单，请核对完整工单号。":"该编号存在多个业务候选，请使用完整生产工单号确认："+candidates.stream().limit(8).map(row->String.valueOf(row.get("work_order_no"))).toList()+(Boolean.TRUE.equals(resolved.get("collision"))?"；该数字也匹配客户订单ID。":"");
                }
            }
        }
        if(action.equals("QUERY")&&intents.stream().anyMatch(intent->!run.policy.allows(intent))) {
            action="UNSUPPORTED";question="当前账号没有本次查询所需的业务权限，请查询已授权的业务范围或联系管理员授权。";
        }
        if(action.equals("QUERY")) {
            String dateText=run.query;boolean invalidDates=false,invalidSelectors=false;
            try {var selected=BusinessSelectors.parse(run.query,intents,run.previous);run.selectors=selected.values();dateText=selected.remainingText();}
            catch(IllegalArgumentException invalid) {invalidSelectors=true;}
            try {run.dateRange=BusinessDateRange.parse(dateText,LocalDate.ofInstant(clock.instant(),ZONE));if(run.dateRange!=null)dateText=run.dateRange.remainingText();
                else if(!Pattern.compile("今天|今日|本月|这个月|最近七天").matcher(dateText).find()&&run.previous!=null&&Pattern.compile("其中|这些|上述|刚才|刚刚|上面|同一范围").matcher(run.query).find()
                    &&run.previous.get("scope") instanceof Map<?,?> prior&&Boolean.TRUE.equals(prior.get("confirmed"))
                    &&prior.get("startInclusive") instanceof String from&&prior.get("endExclusive") instanceof String to)
                    run.dateRange=new BusinessDateRange(LocalDate.parse(from.substring(0,10)),LocalDate.parse(to.substring(0,10)),run.query);
            }
            catch(RuntimeException invalid) {invalidDates=true;}
            Map<String,String> criteria=new LinkedHashMap<>();
            for(String intent:intents) {
                if(BusinessQueryFilter.ambiguous(intent,dateText))invalidSelectors=true;
                String criterion=BusinessQueryFilter.criterion(intent,dateText);
                if(criterion.equals("ALL")&&!Pattern.compile("不限|所有状态|全部状态|不筛选").matcher(dateText).find()
                    &&Pattern.compile("其中|这些|上述|刚才|刚刚|上面|同一范围").matcher(run.query).find()
                    &&run.previous!=null&&run.previous.get("scope") instanceof Map<?,?> prior&&Boolean.TRUE.equals(prior.get("confirmed"))
                    &&prior.get("criteria") instanceof Map<?,?> filters&&filters.get(intent) instanceof String previousCriterion)criterion=previousCriterion;
                if(Pattern.compile("其中|这些|上述|刚才|刚刚|上面|同一范围").matcher(run.query).find()
                    &&Pattern.compile("排除|不要|不含|除了|不看|不包括|剔除").matcher(dateText).find()
                    &&!Pattern.compile("改查|改为|换成|所有状态|全部状态|不限状态").matcher(dateText).find()
                    &&run.previous!=null&&run.previous.get("scope") instanceof Map<?,?> prior&&Boolean.TRUE.equals(prior.get("confirmed"))
                    &&prior.get("criteria") instanceof Map<?,?> filters&&filters.get(intent) instanceof String previousCriterion)
                    criterion=BusinessQueryFilter.intersect(intent,previousCriterion,criterion);
                criteria.put(intent,criterion);
            }
            run.criteria=Map.copyOf(criteria);
            String filterQuery=BusinessQueryFilter.supportedTextRemoved(intents,dateText);
            boolean keywordGrounded=keyword==null||dateText.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
            if(!keywordGrounded&&Pattern.compile("其中|这些|上述|刚才|刚刚|上面|同一范围").matcher(run.query).find()
                &&run.previous!=null&&run.previous.get("scope") instanceof Map<?,?> prior&&Boolean.TRUE.equals(prior.get("confirmed")))
                keywordGrounded=keyword.equals(prior.get("keyword"));
            if(run.workOrder!=null&&(!period.equals("CURRENT")||run.dateRange!=null||run.criteria.values().stream().anyMatch(value->!value.equals("ALL")))) {
                action="CLARIFY";question="当前工单派工查询展示完整派工记录，不会将工单更新时间当作派工时间。请确认查询该工单全部派工，暂不支持附加日期或状态筛选。";
            } else if(invalidDates) {
                action="CLARIFY";question="请提供一个有效且明确的日期范围，例如2026-11-01至2026-11-15；结束日期包含当天。";
            } else if(invalidSelectors||(pk!=null||number!=null)&&!run.selectors.isEmpty()) {
                action="CLARIFY";question="请明确仓库/库位编码（每类最多8个，可用逗号分隔，排除时请写‘排除仓库…’），或用客户名称为‘完整名称’查询订单。仓库/库位仅用于库存与流水，客户仅用于订单列表。状态需使用系统支持的明确状态；精确订单证据链不叠加这些筛选。";
            } else if(keyword!=null&&run.selectors.containsValue(keyword)) {
                action="CLARIFY";question="仓库、库位和客户已作为独立条件，不能再把它们当作物料关键词。请明确物料关键词或仅查询这些条件。";
            } else if(!grounded(run,pk,"orderPk",true,intents)||!grounded(run,number,"orderNo",false,intents)||!keywordGrounded) {
                action="CLARIFY";question="请明确要查询的订单编号或关键词；当前问题未提供可核验的查询范围。";
            } else if((run.workOrder==null&&!explicitOrderMatches(filterQuery,pk,number))||unsupportedExplicitFilter(filterQuery)) {
                action="CLARIFY";question="问题中包含明确订单或其他筛选条件，但当前计划未完整表达。请明确订单ID/订单号，并使用支持的时间范围和物料/单据关键词；请使用明确的仓库/库位编码或完整客户名称；其他未支持条件不能省略。";
            } else if((run.workOrder==null&&pk==null&&number==null)&&(intents.contains("DELIVERY")||Pattern.compile("这[张个笔].*订单|该订单|此订单").matcher(run.query).find())) {
                action="CLARIFY";question="请提供订单ID或完整订单号，以便核对交付和生产依据。";
            } else if((run.dateRange==null&&!periodGrounded(run,period,dateText))||Pattern.compile("昨天|前天|上周|上月|上个月|去年|截至|截止|大于|小于|低于|高于|超过|不少于|不超过|未完成|(?:仅|只|筛选|列出|查找).*不合格|缺货|库存不足|逾期|仅.*异常|只.*异常|\\d+月\\d+日|\\d{4}-\\d{2}-\\d{2}").matcher(filterQuery).find()) {
                action="CLARIFY";question="当前支持当前记录、今天、最近7天或本月，以及单个名称/编号关键词。请明确使用哪个支持范围；暂不能按您提出的其他条件精确筛选。";
            } else if((pk!=null||number!=null)&&intents.stream().anyMatch(Set.of("MAINTENANCE","ALARMS","STOCK_MOVEMENT")::contains)) {
                action="CLARIFY";question="历史明细工具目前查询全局明确范围；订单相关维修和报警请使用订单设备分析，库存变动请指定物料或流水编号。";
            } else if((pk!=null||number!=null)&&intents.equals(List.of("KNOWLEDGE"))) {
                action="CLARIFY";question="当前是知识问题。请确认只查询公开资料，还是同时核对该订单的业务记录；不会自动继承订单范围查询业务表。";
            } else if((pk!=null||number!=null)&&intents.stream().anyMatch(intent->!run.criteria.getOrDefault(intent,"ALL").equals("ALL"))) {
                action="CLARIFY";question="单张订单证据链目前按完整范围复核，不会忽略您要求的状态筛选。请查询该订单完整记录，或查询全局符合状态的明细。";
            } else if(pk!=null||number!=null) {
                try {
                    String exactPk=pk,exactNo=number;
                    Map<String,Object> e0=access.apply(run.owner,d->{
                        if(!d.policy.adminRole()) {
                            d.policy.require("ORDER");
                            var matches=d.overview.resolveCustomerOrder(exactPk==null?null:Long.valueOf(exactPk),exactNo,d.policy.customer());
                            if(matches.size()!=1)throw error(404,"ORDER_UNAVAILABLE");
                            var row=matches.get(0);
                            return object("scopeRef",run.scopeRef,"data",object("orderPk",row.get("order_id"),"orderNo",row.get("order_no")));
                        }
                        return EvidenceToolRegistry.resolve(d.delivery,run.owner,exactPk,exactNo,null,"FACTORY_SHIPMENT");
                    });
                    run.order=String.valueOf(data(e0).get("orderPk"));run.orderNo=String.valueOf(data(e0).get("orderNo"));
                    run.scopeRef=(String)e0.get("scopeRef");if(run.policy.adminRole())run.evidence.put("E0",e0);
                } catch(ResponseStatusException missing) {
                    if(missing.getStatusCode().value()!=404&&missing.getStatusCode().value()!=409)throw missing;
                    action="CLARIFY";question="未找到唯一匹配的现有订单，请核对订单ID或完整订单号。";
                }
            }
            if(action.equals("QUERY")&&run.order!=null&&(intents.contains("DELIVERY")||intents.contains("PRODUCTION")||intents.stream().anyMatch(DOMAIN_EVIDENCE::containsKey))&&(!period.equals("CURRENT")||keyword!=null||run.dateRange!=null)) {
                action="CLARIFY";question="精确订单的交付、生产、质量、设备及售后分析使用该订单当前完整证据。请确认查询当前完整订单，或改为带时间/关键词的全局业务记录概览。";
            }
        }
        run.plan=object("action",action,"intents",List.copyOf(intents),"orderPk",pk,"orderNo",number,"keyword",keyword,"period",period,"question",question);
        run.awaitingOrderInput=action.equals("CLARIFY")&&!intents.isEmpty()&&Pattern.compile("订单|交付|order",Pattern.CASE_INSENSITIVE).matcher(run.query).find()
            &&question.contains("订单")&&Pattern.compile("提供|输入|明确|核对").matcher(question).find();
        if(action.equals("QUERY")) {
            Set<String> required=new HashSet<>();
            for(String intent:intents)required.add(role(intent));
            if(run.crossOrderRisk&&run.order==null) {
                if(ResourceRiskQueries.allowed(run.policy,"RESOURCE_MATERIAL"))required.add("SUPPLY");
                if(ResourceRiskQueries.allowed(run.policy,"RESOURCE_CAPACITY"))required.add("PRODUCTION");
            }
            if(run.policy.adminRole()&&run.order!=null&&(intents.contains("DELIVERY")||intents.contains("PRODUCTION")))required.addAll(List.of("ORDER","SUPPLY","PRODUCTION"));
            if(run.policy.adminRole()&&run.order!=null&&intents.contains("AFTERSALES"))required.add("QUALITY");
            run.required=ROLE_ORDER.stream().filter(required::contains).toList();
        }
        run.workflowPlan=object("action",action,"requiredAgents",run.required,"dependencies",dependencies(run),
            "confirmedAt",clock.instant().toString());
        workflowEvent(run,null,"PLAN_CONFIRMED","COMPLETED","确认执行计划","已确定本次查询需要的业务角色");
        return contextMap(run);
    }
    public Map<String,Object> begin(String ref,String role) {
        Run run;Task task;Map<String,Object> started;boolean delivery;
        synchronized(this) {
        run=require(ref);requirePlan(run);
        if(!run.required.contains(role))throw error(403,"AGENT_NOT_REQUIRED");
        if(run.tasks.containsKey(role)) {
            Task existing=run.tasks.get(role);
            if(existing.reading||existing.failed)throw error(409,"BUSINESS_READ_INCOMPLETE");
            return existing.started;
        }
        if(run.result!=null)throw error(409,"RUN_FINALIZED");
        List<String> upstream=new ArrayList<>();
        delivery=usesDelivery(run);
        for(String dep:dependencies(run,role)) {
            Task upstreamTask=run.tasks.get(dep);if(upstreamTask==null||upstreamTask.receipt==null)throw error(409,"DEPENDENCY_INCOMPLETE");
            upstream.add((String)upstreamTask.receipt.get("resultRef"));
        }
        started=object("taskId",UUID.randomUUID().toString(),"agentId",role,"runRef",run.ref,"queryMode","NATURAL","status","PARTIAL",
            "upstreamResultRefs",upstream,"sourceMode",SOURCE,"knowledgeOnly",intents(run).equals(List.of("KNOWLEDGE")),"startedAt",clock.instant().toString());
        task=new Task(started);task.reading=true;started.put("evidence",List.of());run.tasks.put(role,task);
        workflowEvent(run,task,"AGENT_STARTED","RUNNING","开始角色任务","已接收本次业务任务");
        step(run,task,"BUSINESS_READ","读取业务证据","RUNNING","正在读取授权业务记录");
        }
        Map<String,Map<String,Object>> evidence=new LinkedHashMap<>();
        try {
            access.apply(run.owner,d->{
                if(run.crossOrderRisk&&run.order==null&&Set.of("SUPPLY","PRODUCTION").contains(role)) {
                    String kind=role.equals("SUPPLY")?"RESOURCE_MATERIAL":"RESOURCE_CAPACITY";
                    evidence.put(kind,readTool(run,task,kind,()->resourceEvidence(d,run,kind)));
                }
                if(delivery&&List.of("ORDER","SUPPLY","PRODUCTION").contains(role)) {
                    for(String kind:role.equals("ORDER")?List.of("E1","E4"):List.of(role.equals("SUPPLY")?"E2":"E3"))
                        evidence.put(kind,readTool(run,task,kind,()->EvidenceToolRegistry.query(d.delivery,run.owner,role,run.order,run.scopeRef,kind,qref(run,"E1"),qref(run,"E4"),qref(run,"E2"))));
                }
                if(run.policy.adminRole()&&run.order!=null&&DOMAIN_EVIDENCE.containsKey(role)) {
                    String kind=DOMAIN_EVIDENCE.get(role);
                    evidence.put(kind,readTool(run,task,kind,()->EvidenceToolRegistry.query(d.delivery,run.owner,role,run.order,run.scopeRef,kind,qref(run,"E1"),qref(run,"E4"),qref(run,"E2"))));
                }
                for(String intent:intents(run)) if(!intent.equals("KNOWLEDGE")&&role(intent).equals(role)&&(!delivery||!List.of("DELIVERY","ORDER","PRODUCTION").contains(intent))) {
                    if(run.policy.adminRole()&&run.order!=null&&DOMAIN_EVIDENCE.containsKey(intent))continue;
                    String domain=intent.equals("DELIVERY")?"ORDER":intent;
                    evidence.put("OVERVIEW_"+domain,readTool(run,task,"OVERVIEW_"+domain,()->overview(d,run,domain)));
                }
                return Map.of();
            });
        } catch(RuntimeException failure) {
            synchronized(this) {
            task.reading=false;task.failed=true;
            if(!run.cancelled) {
            for(var activeStep:List.copyOf(task.steps.values()))if("RUNNING".equals(activeStep.get("status")))
                step(run,task,(String)activeStep.get("id"),(String)activeStep.get("label"),"FAILED","业务证据读取失败，未生成成功结果");
            workflowEvent(run,task,"AGENT_COMPLETED","FAILED","角色任务结束","业务证据读取失败");
            }}
            throw failure;
        }
        synchronized(this) {
        require(ref);task.reading=false;
        started.put("evidence",List.copyOf(evidence.values()));
        step(run,task,"BUSINESS_READ","读取业务证据","COMPLETED","已读取 "+evidence.size()+" 组授权业务证据");
        if(role.equals("QUALITY")&&!intents(run).equals(List.of("KNOWLEDGE")))step(run,task,"VISION_REVIEW","核对视觉检测来源","COMPLETED","已核对可用检测记录；缺失、摘要和模拟结果分别标识");
        started.put("upstreamFindings",businessHandoff(run,role,task.upstreamBusinessRefs));
        for(String from:dependencies(run,role)) {
            Map<String,Object> event=workflowEvent(run,task,"HANDOFF","COMPLETED","交接业务证据","已接收前序角色的业务证据");
            if(event!=null){event.put("fromAgent",from);event.put("toAgent",role);}
        }
        if(!Set.of("QUALITY","AFTERSALES").contains(role))
            workflowEvent(run,task,"STEP_STARTED","RUNNING","结合证据形成分析","已提供授权业务证据供角色分析");
        run.evidence.putAll(evidence);return started;
        }
    }

    /** Emit only actual tool starts and returned evidence; never replay or fabricate progress. */
    private Map<String,Object> readTool(Run run,Task task,String tool,java.util.function.Supplier<Map<String,Object>> read) {
        synchronized(this) {
            require(run.ref);
            step(run,task,tool,toolLabel(tool),"RUNNING","查询范围："+(run.workOrder!=null?"工单 "+run.workOrderNo:run.order==null?"当前账号授权记录":"订单 "+run.order)+"；时间："+(run.dateRange==null?run.plan.get("period"):run.dateRange.start()+" 至 "+run.dateRange.endExclusive().minusDays(1)));
        }
        Map<String,Object> evidence=read.get();
        synchronized(this) {
            require(run.ref);
            Object overview=data(evidence).get("overview");
            String detail=data(evidence).get("rows") instanceof List<?> records&&tool.startsWith("RESOURCE_")?"已核对资源证据，返回 "+records.size()+" 条分析记录及待核实依据":overview instanceof Map<?,?> rows?"已返回 "+rows.get("returnedRecords")+" 条记录":"已取得 "+tool+" 业务证据";
            step(run,task,tool,toolLabel(tool),"COMPLETED",detail);
            @SuppressWarnings("unchecked") var prior=(List<Map<String,Object>>)task.started.get("evidence");
            var visible=new ArrayList<>(prior);visible.add(evidence);task.started.put("evidence",List.copyOf(visible));
        }
        return evidence;
    }
    private static String toolLabel(String tool) {
        return switch(tool) {
            case "RESOURCE_MATERIAL" -> "核对跨订单计划、BOM、库存及领料记录";
            case "RESOURCE_CAPACITY" -> "核对跨订单设备排程与人员派工重叠";
            case "OVERVIEW_INVENTORY" -> "查询物料现存、预留和可用库存";
            case "OVERVIEW_PRODUCTION" -> "查询工单数量和生产状态";
            case "OVERVIEW_QUALITY" -> "查询质检记录及抽检结果";
            case "OVERVIEW_ORDER" -> "查询订单及审核状态";
            case "OVERVIEW_EQUIPMENT" -> "查询设备台账和状态";
            case "OVERVIEW_MAINTENANCE" -> "查询设备维修与登记停机时长";
            case "OVERVIEW_ALARMS" -> "查询设备报警明细";
            case "OVERVIEW_STOCK_MOVEMENT" -> "查询库存出入库流水";
            case "OVERVIEW_AFTERSALES" -> "查询售后案件";
            case "E1" -> "核对订单与生产计划";case "E2" -> "核对物料供需";
            case "E3" -> "核对工序进度";case "E4" -> "核对交付与库存分配";
            case "Q1" -> "核对检验、缺陷和视觉归档";case "D1" -> "核对关联设备及维修报警";
            case "A1" -> "核对售后及追溯链路";default -> tool;
        };
    }

    /** Network I/O deliberately outside both runtime and HTTP routing locks so progress/cancel stays responsive. */
    public Map<String,Object> research(String ref,String role) {
        Task task;List<Map<String,Object>> evidence;String knowledgeQuestion;
        synchronized(this) {
            Run run=require(ref);if(!Set.of("QUALITY","AFTERSALES").contains(role)||!run.required.contains(role))throw error(403,"AGENT_NOT_REQUIRED");
            task=run.tasks.get(role);if(task==null)throw error(409,"TASK_NOT_BEGUN");
            if(task.reading||task.failed)throw error(409,"BUSINESS_READ_INCOMPLETE");
            if(task.knowledge!=null)return task.started;
            if(task.researchRunning||task.receipt!=null||run.result!=null)throw error(409,"RESEARCH_ALREADY_STARTED");
            knowledgeQuestion=intents(run).contains("KNOWLEDGE")?run.query:null;
            task.researchRunning=true;step(run,task,"KNOWLEDGE_RETRIEVAL","检索公开 RAG / GraphRAG","RUNNING","正在检索公开研究方法");
            @SuppressWarnings("unchecked") var records=(List<Map<String,Object>>)task.started.get("evidence");evidence=records;
        }
        Map<String,Object> bundle;
        try {bundle=knowledgeQuestion==null?research.retrieve(role,evidence):research.retrieveQuestion(knowledgeQuestion);}catch(Exception unavailable){bundle=object("status","UNAVAILABLE","hits",List.of(),"graph_paths",List.of(),"limitations",List.of("知识服务不可用，保留业务证据"));}
        synchronized(this) {
            Run run=require(ref);if(run.result!=null)throw error(409,"RUN_FINALIZED");
            task.knowledge=new LinkedHashMap<>(bundle);task.researchRunning=false;task.started.put("knowledgeEvidence",task.knowledge);
            String status=String.valueOf(bundle.get("status"));
            int hits=bundle.get("hits") instanceof List<?> list?list.size():0;int paths=bundle.get("graph_paths") instanceof List<?> list?list.size():0;
            boolean retrieved=Set.of("MATCHES","NO_MATCHES").contains(status);
            step(run,task,"KNOWLEDGE_RETRIEVAL","检索公开 RAG / GraphRAG",retrieved?"COMPLETED":"FAILED",
                retrieved?"可引用片段 "+hits+" 条，图谱关系 "+paths+" 条":"知识检索未成功，保留业务证据");
            step(run,task,"ANALYSIS","结合证据形成分析","RUNNING","区分记录发现、原因假设与验证建议");
            return task.started;
        }
    }
    public synchronized Map<String,Object> complete(String ref,String role,JsonNode input) {
        Run run=require(ref);String summary=text(input,"summary",6000);
        Task task=run.tasks.get(role);if(task==null)throw error(409,"TASK_NOT_BEGUN");
        if(task.reading||task.failed)throw error(409,"BUSINESS_READ_INCOMPLETE");
        if(task.researchRunning)throw error(409,"RESEARCH_INCOMPLETE");
        if(task.receipt!=null) {if(!summary.equals(task.rawSummary))throw error(409,"RESULT_CONFLICT");return task.receipt;}
        task.rawSummary=summary;
        if(Set.of("QUALITY","AFTERSALES").contains(role)) {
            Set<String> business=new HashSet<>(),knowledge=new HashSet<>();
            for(Object value:(List<?>)task.started.get("evidence"))if(value instanceof Map<?,?> record&&record.get("queryRef") instanceof String id)business.add(id);
            // These IDs were recorded server-side at begin, only for this run's actual dependency handoff.
            business.addAll(task.upstreamBusinessRefs);
            if(task.knowledge!=null&&task.knowledge.get("hits") instanceof List<?> hits)for(Object value:hits)if(value instanceof Map<?,?> hit&&hit.get("evidence_ref") instanceof String id)knowledge.add(id);
            task.analysis=GroundedAnalysis.parse(intents(run).equals(List.of("KNOWLEDGE"))?"KNOWLEDGE":role,summary,business,knowledge);
            summary=task.analysis.get("summary").toString();if(summary.isBlank())summary="结构化分析未通过校验，已保留业务证据与缺失说明。";
        }
        boolean invalidAnalysis=task.analysis!=null&&"INVALID_FORMAT".equals(task.analysis.get("status"));
        step(run,task,"ANALYSIS","结合证据形成分析",invalidAnalysis?"FAILED":"COMPLETED",
            invalidAnalysis?"结构化分析未通过校验，已保留业务证据":"已完成分析并校验本次引用；不等于确认根因");
        var receipt=new LinkedHashMap<>(task.started);
        receipt.put("resultRef",UUID.randomUUID().toString());receipt.put("modelCommentary",summary);
        receipt.put("commentaryTrust","UNTRUSTED_MODEL_COMMENTARY");receipt.put("completedAt",clock.instant().toString());
        if(task.analysis!=null)receipt.put("analysis",task.analysis);
        task.receipt=receipt;
        workflowEvent(run,task,"AGENT_COMPLETED","COMPLETED","角色任务结束","已记录本次任务结果");
        return receipt;
    }
    public synchronized Map<String,Object> finalizeRun(String ref) {
        Run run=require(ref);requirePlan(run);if(run.result!=null)return run.result;
        List<String> absent=run.required.stream().filter(r->!run.tasks.containsKey(r)||run.tasks.get(r).receipt==null).toList();
        if(absent.isEmpty()&&!run.summarizing) {
            workflowEvent(run,null,"SUMMARIZING","RUNNING","汇总业务证据","正在复核并汇总本次结果");
            run.summarizing=true;
        }
        Set<String> missing=new LinkedHashSet<>();absent.forEach(r->missing.add("REQUIRED_AGENT_"+r));
        Map<String,Object> facts=new LinkedHashMap<>();boolean[] changed={false};var revalidation=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
        if(run.plan.get("action").equals("QUERY"))access.apply(run.owner,d->{
            if(run.policy.adminRole()&&run.order!=null) {
                List<String> refs=run.evidence.entrySet().stream().filter(e->!e.getKey().startsWith("OVERVIEW_")).map(e->(String)e.getValue().get("queryRef")).toList();
                var validation=EvidenceToolRegistry.revalidate(d.delivery,run.owner,run.order,run.scopeRef,refs,"1",DeliveryEvidenceRegistry.VERSION);
                var value=new LinkedHashMap<>(data(validation));value.remove("replacementEvidence");
                validation=new LinkedHashMap<>(validation);validation.put("data",value);revalidation.set(validation);
                changed[0]="CHANGED".equals(value.get("comparedFactsStatus"));
            }
            for(var entry:run.evidence.entrySet()) {
                Map<String,Object> evidence=entry.getValue();
                if(entry.getKey().startsWith("OVERVIEW_")) {
                    String domain=entry.getKey().substring(9);
                    if(!data(evidence).equals(data(overview(d,run,domain))))changed[0]=true;
                    facts.put(domain.toLowerCase(Locale.ROOT),data(evidence));
                } else if(entry.getKey().startsWith("RESOURCE_")) {
                    if(!data(evidence).equals(data(resourceEvidence(d,run,entry.getKey()))))changed[0]=true;
                    facts.put(entry.getKey().equals("RESOURCE_MATERIAL")?"shared_material":"capacity_conflicts",data(evidence));
                } else if(!entry.getKey().equals("E0"))facts.put(switch(entry.getKey()){case "Q1"->"quality";case "D1"->"equipment";case "A1"->"aftersales";default->entry.getKey();},data(evidence));
                for(Object issue:(List<?>)evidence.getOrDefault("missing",List.of()))missing.add(String.valueOf(issue));
            }
            return Map.of();
        });
        String action=(String)run.plan.get("action");
        boolean unresolved=missing.stream().anyMatch(issue->issue.startsWith("CONTRADICTORY_")||issue.startsWith("CONFLICTING_")||issue.startsWith("UNRESOLVED_")||issue.startsWith("UNVERIFIED_")||issue.contains("TRUNCATED_")||issue.startsWith("SOURCE_RECORD_LIMIT_"));
        String verdict=!action.equals("QUERY")?action:changed[0]?"DATA_CHANGED":!absent.isEmpty()?"INCOMPLETE":usesDelivery(run)||unresolved?"INSUFFICIENT_EVIDENCE":"EVIDENCE_READY";
        if(changed[0]){facts.clear();missing.add("REFRESH_RUN_REQUIRED");}
        var explanations=changed[0]?List.of():run.tasks.entrySet().stream().filter(e->e.getValue().receipt!=null)
            .map(e->object("agentId",e.getKey(),"resultRef",e.getValue().receipt.get("resultRef"),"authority","UNVERIFIED_MODEL_EXPLANATION","text",e.getValue().receipt.get("modelCommentary"))).toList();
        Map<String,Object> result=object("runRef",run.ref,"queryMode","NATURAL","workflowProfile","AUTO","sourceMode",SOURCE,
            "verdict",verdict,"facts",facts,"scope",scope(run),"plan",run.plan,"intents",intents(run),
            "executedAgents",List.copyOf(run.tasks.keySet()),"missing",List.copyOf(missing),"checkedAt",clock.instant().toString(),
            "queryRefs",run.evidence.values().stream().map(e->e.get("queryRef")).toList(),
            "resultRefs",run.tasks.values().stream().filter(t->t.receipt!=null).map(t->t.receipt.get("resultRef")).toList(),
            "expertExplanations",explanations,"knowledgeExplanations",List.of(),"revalidation",revalidation.get(),"reply",reply(run,verdict,facts));
        result.put("analyses",changed[0]?List.of():run.tasks.values().stream().filter(t->t.analysis!=null).map(t->t.analysis).toList());
        result.put("knowledgeEvidence",changed[0]?List.of():run.tasks.entrySet().stream().filter(e->e.getValue().knowledge!=null).map(e->{var bundle=new LinkedHashMap<String,Object>(e.getValue().knowledge);bundle.put("agentId",e.getKey());return bundle;}).toList());
        if(changed[0]){
            result.put("queryRefs",List.of());result.put("resultRefs",List.of());
            run.workflowInvalidated=true;run.workflowPlan=null;run.workflowEvents.clear();run.workflowTruncated=false;
        }
        run.last=result;if(absent.isEmpty()||changed[0]) {
            run.result=result;
            Map<String,Object> retainedScope=new LinkedHashMap<>(scope(run));
            retainedScope.remove("scopeRef");retainedScope.remove("expiresAt");
            if(!Set.of("EVIDENCE_READY","INSUFFICIENT_EVIDENCE").contains(verdict))retainedScope=object("confirmed",false);
            else if(run.dateRange==null&&!"CURRENT".equals(run.plan.get("period"))) {
                LocalDate day=LocalDate.ofInstant(clock.instant(),ZONE);
                LocalDate from=switch((String)run.plan.get("period")){case "TODAY"->day;case "LAST_7_DAYS"->day.minusDays(6);default->day.withDayOfMonth(1);};
                retainedScope.put("startInclusive",from+" 00:00:00");retainedScope.put("endExclusive",day.plusDays(1)+" 00:00:00");
            }
            contextStore.save(run.ref,run.owner,object("userQuery",run.query,"plan",run.plan,"scope",retainedScope,
                "authorizationStamp",run.policy.fingerprint(),"awaitingOrderInput",run.awaitingOrderInput&&verdict.equals("CLARIFY")));
        }return result;
    }
    public synchronized Map<String,Object> observe(String ref) {
        Run run=lookup(ref);authorize(run);
        List<Map<String,Object>> tasks=new ArrayList<>();
        for(var entry:run.tasks.entrySet()) {
            Task task=entry.getValue();var item=object("agentId",entry.getKey(),"taskId",task.started.get("taskId"),"status",task.receipt!=null?"COMPLETED":run.cancelled?"CANCELLED":task.failed?"FAILED":"RUNNING","startedAt",task.started.get("startedAt"),"evidence",run.workflowInvalidated?List.of():task.started.get("evidence"),"steps",run.workflowInvalidated?List.of():List.copyOf(task.steps.values()));
            if(task.receipt!=null){item.put("summary",run.workflowInvalidated?"":task.receipt.get("modelCommentary"));item.put("completedAt",task.receipt.get("completedAt"));}tasks.add(item);
        }
        var observation=object("runRef",ref,"status",run.cancelled?"CANCELLED":run.result!=null?"COMPLETED":"RUNNING","tasks",tasks,"result",run.last);
        observation.put("workflow",object("plan",run.workflowPlan,"events",List.copyOf(run.workflowEvents),
            "invalidated",run.workflowInvalidated,"truncated",run.workflowTruncated));
        return observation;
    }
    public synchronized Map<String,Object> cancel(String ref) {
        Run run=lookup(ref);authorize(run);
        if(run.result!=null)return object("runRef",ref,"status","COMPLETED");
        if(!run.cancelled) {
            run.cancelled=true;
            workflowEvent(run,null,"CANCELLED","CANCELLED","执行已取消","已终止本次工作流");
        }
        return object("runRef",ref,"status","CANCELLED");
    }
    private Map<String,Object> resourceEvidence(Data d,Run run,String kind) {
        if(!run.crossOrderRisk||run.order!=null||!ResourceRiskQueries.allowed(d.policy,kind))throw error(403,"BUSINESS_PERMISSION_REQUIRED");
        // Reuse exactly the same customer/date/status/keyword scope as the ORDER agent, inside this transaction.
        String period=(String)run.plan.get("period");LocalDate today=LocalDate.ofInstant(run.expires.minusSeconds(600),ZONE);
        LocalDate start=switch(period){case "TODAY"->today;case "LAST_7_DAYS"->today.minusDays(6);case "THIS_MONTH"->today.withDayOfMonth(1);default->null;};
        String from=run.dateRange!=null?run.dateRange.fromTimestamp():start==null?null:start+" 00:00:00",to=run.dateRange!=null?run.dateRange.toTimestamp():start==null?null:today.plusDays(1)+" 00:00:00";
        var selected=BusinessToolRegistry.execute(d.overview,new BusinessToolRegistry.Request("ORDER",null,(String)run.plan.get("keyword"),from,to,run.selectors),d.policy);
        if(selected.total()!=selected.rows().size())throw error(409,"INCOMPLETE_ORDER_SCOPE");
        var scoped=BusinessQueryFilter.applyCriterion(run.criteria.getOrDefault("ORDER","ALL"),selected.rows());
        // A stable analysis instant prevents an ongoing overlap shrinking between read and final revalidation.
        var at=LocalDateTime.ofInstant(run.expires.minusSeconds(600),ZONE);
        var result=ResourceRiskQueries.execute(d.overview,d.policy,kind,scoped,at);result.put("analysis_at",at.toString());
        return object("queryRef",UUID.randomUUID().toString(),"kind",kind,"scopeRef",run.scopeRef,"status","PARTIAL","sourceMode",SOURCE,"data",result,"missing",List.of());
    }
    private Map<String,Object> overview(Data data,Run run,String domain) {
        if(run.policy.adminRole()&&run.order!=null&&DOMAIN_EVIDENCE.containsKey(domain))throw new IllegalStateException("Exact-order domains require registered detailed evidence");
        String period=(String)run.plan.get("period"),keyword=(String)run.plan.get("keyword");
        LocalDate today=LocalDate.ofInstant(clock.instant(),ZONE);
        LocalDate start=switch(period){case "TODAY"->today;case "LAST_7_DAYS"->today.minusDays(6);case "THIS_MONTH"->today.withDayOfMonth(1);default->null;};
        String from=run.dateRange!=null?run.dateRange.fromTimestamp():start==null?null:start+" 00:00:00",to=run.dateRange!=null?run.dateRange.toTimestamp():start==null?null:today.plusDays(1)+" 00:00:00";
        Long id=run.order==null?null:Long.valueOf(run.order);
        data.policy.require(domain);
        BusinessToolRegistry.Result queried;
        if(run.workOrder!=null&&domain.equals("PRODUCTION")) {
            var selected=data.overview.workOrderRows(Long.parseLong(run.workOrder),data.policy.operator(),from,to);
            queried=new BusinessToolRegistry.Result(selected.size(),selected);
        } else queried=BusinessToolRegistry.execute(data.overview,new BusinessToolRegistry.Request(domain,id,keyword,from,to,run.selectors),data.policy);
        var filtered=BusinessQueryFilter.applyCriterion(run.criteria.getOrDefault(domain,"ALL"),queried.rows());
        long total=queried.total()==queried.rows().size()?filtered.size():queried.total();
        var rows=filtered.stream().map(row->{Map<String,Object> out=new LinkedHashMap<>();row.forEach((k,v)->{if(!k.equals("search_text"))out.put(k,v==null?null:String.valueOf(v));});return out;}).toList();
        String dateField=switch(domain){case "QUALITY"->"inspected_at";case "ORDER"->"order_date";case "AFTERSALES"->"opened_at";case "MAINTENANCE"->"start_time";case "ALARMS"->"reported_at";case "STOCK_MOVEMENT"->"handled_at";default->"updated_at";};
        var overview=object("domain",domain,"rows",rows,"totalRecords",total,"returnedRecords",rows.size(),"truncated",total>rows.size(),"limit",null,
            "period",run.dateRange==null?period:"CUSTOM","dateField",dateField,"startInclusive",from,"endExclusive",to,"timeZone","Asia/Shanghai","keyword",keyword,
            "selectors",run.selectors,"filter",run.criteria.getOrDefault(domain,"ALL"),"countBasis","MATCHING_DATABASE_RECORDS","quantityBasis","PER_RECORD_PER_MATERIAL_UNIT; NO_CROSS_MATERIAL_TOTAL; CURRENT_RECORDS_NOT_HISTORICAL_SNAPSHOTS");
        var facts=object("overview",overview);
        if(run.workOrder!=null&&domain.equals("PRODUCTION")&&!rows.isEmpty()) {
            data.policy.require("DISPATCH");
            var dispatchRows=data.overview.workOrderDispatch(Long.parseLong(run.workOrder),data.policy.operator()).stream().map(row->{Map<String,Object> out=new LinkedHashMap<>();row.forEach((k,v)->out.put(k,v==null?null:String.valueOf(v)));return out;}).toList();
            facts.put("dispatchRows",dispatchRows);
            facts.put("dispatchScope",data.policy.operator()==null?"ALL_RECORDED_DISPATCHES":"OWN_DISPATCHES_ONLY");
            facts.put("dispatchConclusion",dispatchRows.isEmpty()?"当前授权范围内未查到派工记录。":"已查到派工记录，工序、人员和执行状态见派工明细；存在记录不代表已全部完成。");
        }
        if(domain.equals("ORDER")&&run.crossOrderRisk) {
            data.policy.require("ORDER");
            var lines=new ArrayList<Map<String,Object>>();var shipments=new ArrayList<Map<String,Object>>();
            // Bounded SQL IN batches, but no record truncation. IDs originate only in the scoped SELECT.
            for(int offset=0;offset<rows.size();offset+=100) {
                var ids=rows.subList(offset,Math.min(offset+100,rows.size())).stream().map(row->Long.parseLong(String.valueOf(row.get("record_id")))).toList();
                lines.addAll(data.overview.riskLines(ids,data.policy.customer()));shipments.addAll(data.overview.riskDeliveries(ids,data.policy.customer()));
            }
            facts.put("crossOrderRisk",CrossOrderRiskAnalysis.summarize(rows,lines,shipments,today));
            facts.put("riskBasis","RECORDED_SHIPMENTS_PER_ORDER_LINE; NO_SHARED_STOCK_ALLOCATION; NO_CAPACITY_PROMISE");
            var limits=new ArrayList<String>();
            if(!ResourceRiskQueries.allowed(data.policy,"RESOURCE_MATERIAL"))limits.add("当前授权不包含共享物料分析所需的订单、生产、库存及流水范围，未读取全局资源或他人竞争信息。");
            if(!ResourceRiskQueries.allowed(data.policy,"RESOURCE_CAPACITY"))limits.add("当前授权不包含跨订单排程和派工分析范围，未判断产能冲突。");
            facts.put("resourceLimitations",limits);
        }
        if(domain.equals("MAINTENANCE")&&Pattern.compile("停机.*(?:汇总|统计|时长|多久)|总停机|累计停机|维修.*(?:时长|区间|重叠|合并)").matcher(run.query).find())
            facts.put("maintenanceSummary",MaintenanceMetrics.summarize(rows));
        if("NO_AVAILABLE".equals(run.criteria.getOrDefault(domain,"ALL")))facts.put("warnings",List.of("本次筛选记录可用量≤0的库存行；不是安全库存预警或按订单BOM确认的物料缺口。"));
        if(domain.equals("QUALITY"))facts.putAll(com.upc.computer.assistant.v2.vision.QualityEvidenceAnalysis.overview(rows,total<=rows.size()));
        if(domain.equals("QUALITY")&&Pattern.compile("趋势|按日|每日|每天").matcher(run.query).find())
            facts.put("qualityTrend",QualityTrendMetrics.summarize(rows,total==rows.size()));
        if(domain.equals("ALARMS")&&Pattern.compile("趋势|按日|每日|每天").matcher(run.query).find())
            facts.put("alarmTrend",AlarmTrendMetrics.summarize(rows,total==rows.size()));
        return object("queryRef",UUID.randomUUID().toString(),"kind","OVERVIEW_"+domain,"scopeRef",run.scopeRef,"status","PARTIAL","sourceMode",SOURCE,
            "data",facts,"missing",total>rows.size()?List.of("DETAILS_TRUNCATED"):List.of());
    }
    private String reply(Run run,String verdict,Map<String,Object> facts) {
        if(!run.plan.get("action").equals("QUERY"))return (String)run.plan.get("question");
        if(verdict.equals("DATA_CHANGED"))return "查询期间业务记录已变化，请重新查询以获取一致的事实。";
        if(verdict.equals("INCOMPLETE"))return "查询尚未完成，仍有业务专家未返回结果。";
        List<String> parts=new ArrayList<>();
        if(facts.get("shared_material") instanceof Map<?,?> material && material.get("rows") instanceof List<?> rows) {
            var riskOrders=new LinkedHashSet<String>();
            for(Object value:rows)if(value instanceof Map<?,?> r && Set.of("SHARED_POOL_SCENARIO_GAP","SCENARIO_GAP").contains(r.get("risk_status")))riskOrders.add(String.valueOf(r.get("order_no")));
            parts.add(riskOrders.isEmpty()?"当前可核验计划的供料情景未发现缺口；未知需求不按零处理。":"供料情景存在缺口的订单："+String.join("、",riskOrders)+"。具体物料、缺口及计划/BOM/库存依据见表。");
            parts.add(String.valueOf(material.get("basis"))+"。"+material.get("scope")+"；不是已确认实际缺料。");
        }
        if(facts.get("capacity_conflicts") instanceof Map<?,?> capacity && capacity.get("rows") instanceof List<?> rows) {
            parts.add(rows.isEmpty()?"本次可核验的当前/未来排程未发现跨订单重叠；不代表未覆盖订单或缺失排程没有风险。":"发现"+rows.size()+"条跨订单设备/人员排程重叠，订单、时间和原始记录 ID 见表；需核实实际并发能力。");
        }
        Map<String,String> labels=Map.of("INVENTORY","库存","PRODUCTION","生产工单","QUALITY","质检","ORDER","订单","EQUIPMENT","设备","AFTERSALES","售后","MAINTENANCE","维修记录","ALARMS","报警记录","STOCK_MOVEMENT","库存流水");
        for(Object value:facts.values()) {
            @SuppressWarnings("unchecked") var fact=(Map<String,Object>)value;
            if(fact.get("overview") instanceof Map<?,?> o) {
                String dateLabel=switch(String.valueOf(o.get("dateField"))){case "inspected_at"->"检验时间";case "order_date"->"订单日期";case "opened_at"->"售后单建立时间";default->"记录更新时间";};
                parts.add(labels.get(String.valueOf(o.get("domain")))+"：符合范围的记录共"+o.get("totalRecords")+"条，取回"+o.get("returnedRecords")+"条"+(Boolean.TRUE.equals(o.get("truncated"))?"（明细已截断）":"")+"。时间按"+dateLabel+"筛选，属于当前记录，不是历史快照；数量按各物料原单位查看，不跨物料合计。");
                @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)o.get("rows");
                if(!rows.isEmpty())parts.add("以下展示"+Math.min(8,rows.size())+"条，其余已取回明细见引用证据：");
                rows.stream().limit(8).forEach(row->parts.add(rowSummary(row)));
            }
        }
        if(usesDelivery(run))parts.add("订单"+run.orderNo+"（ID "+run.order+"）已核对当前订单、交付、物料与生产记录；以下为记录事实，不能据此承诺交期。"+deliverySummary(facts)+productionSummary(facts));
        for(var entry:DOMAIN_EVIDENCE.entrySet())if(run.order!=null&&facts.get(entry.getKey().toLowerCase(Locale.ROOT)) instanceof Map<?,?> detail&&!detail.containsKey("overview")) {
            String counts=switch(entry.getKey()) {
                case "QUALITY"->"检验记录"+known(detail.get("inspectionCount"))+"条、检验项"+known(detail.get("inspectionItemCount"))+"条、不合格品记录"+known(detail.get("nonconformingRecordCount"))+"条";
                case "EQUIPMENT"->"设备记录"+known(detail.get("equipmentCount"))+"条、报警"+known(detail.get("alarmCount"))+"条、维修记录"+known(detail.get("maintenanceRecordCount"))+"条";
                default->"售后案例记录"+known(detail.get("caseCount"))+"条、任务"+known(detail.get("taskCount"))+"条、关闭记录"+known(detail.get("closureCount"))+"条";
            };
            parts.add("订单"+run.orderNo+"的"+labels.get(entry.getKey())+"候选关联证据："+counts+"。这些是已读取记录数，包含需核对归属的候选记录，不代表全部已确认归属；原始关联标识、详细记录与缺失项见引用证据。");
            if(detail.get("warnings") instanceof List<?> warnings&&warnings.stream().map(String::valueOf).anyMatch(w->w.startsWith("CONTRADICTORY_")||w.startsWith("UNRESOLVED_")||w.startsWith("UNVERIFIED_")))parts.add("存在归属矛盾或未核实关系，不能将这些候选记录作为该订单已确认的事实或根因。");
        }
        if(intents(run).contains("KNOWLEDGE"))parts.add("已检索本地公开研究知识库；文献不代表企业内部 SOP。具体结论以本次可核验来源为准。");
        return String.join("\n",parts);
    }
    private static String deliverySummary(Map<String,Object> facts) {
        Object e4=facts.get("E4");if(!(e4 instanceof Map<?,?> map)||!(map.get("lines") instanceof List<?> lines))return "";
        List<String> parts=new ArrayList<>();for(Object line:lines.stream().limit(8).toList())if(line instanceof Map<?,?> row)
            parts.add("订单行"+row.get("orderItemPk")+" / 物料"+row.get("materialId")+"：订购"+known(row.get("orderedQty"))+"，记录已发货"+known(row.get("recordedShippedQty"))+"，待交付"+known(row.get("remainingDeliveryQty"))+"（"+known(row.get("unit"))+"）。");
        if(lines.size()>8)parts.add("另有"+(lines.size()-8)+"条订单行，详见证据。");return "\n"+String.join("\n",parts);
    }
    private static String known(Object value) {return value==null?"未知":String.valueOf(value);}
    private static String productionSummary(Map<String,Object> facts) {
        if(!(facts.get("E3") instanceof Map<?,?> data)||!(data.get("workOrders") instanceof List<?> rows))return "";
        List<String> parts=new ArrayList<>();parts.add("关联生产工单共"+rows.size()+"条，以下最多展示8条（数量沿用各工单记录，单位需对照订单行）：");
        for(Object value:rows.stream().limit(8).toList())if(value instanceof Map<?,?> row)
            parts.add("工单"+known(row.get("work_order_no"))+"：计划"+known(row.get("planned_quantity"))+"，记录完成"+known(row.get("completed_quantity"))+"，记录合格"+known(row.get("qualified_quantity"))+"，记录状态"+known(row.get("status"))+"；末工序完成数量"+known(row.get("finalStageCompletedQty"))+"。");
        return "\n"+String.join("\n",parts);
    }
    private static String rowSummary(Map<String,Object> row) {
        List<String> values=new ArrayList<>();
        String[][] labels={{"material_name","物料"},{"work_order_no","工单"},{"inspection_no","检验单"},{"order_no","订单"},{"equipment_name","设备"},{"case_no","售后单"},{"status","记录状态"},{"quantity_on_hand","账面库存"},{"quantity_reserved","预留库存"},{"quantity_available","可用库存"},{"planned_quantity","计划数量"},{"completed_quantity","记录完成数量"},{"sample_quantity","抽检数量"},{"qualified_quantity","合格数量"},{"unqualified_quantity","不合格数量"}};
        for(String[] label:labels)if(row.get(label[0])!=null)values.add(label[1]+"："+row.get(label[0]));
        if(row.containsKey("material_id"))values.add("单位："+known(row.get("unit")));
        return String.join("，",values);
    }
    private static boolean periodGrounded(Run run,String period,String query) {
        String requested=query.contains("今天")||query.contains("今日")?"TODAY":Pattern.compile("(?:最近|近|过去)\\s*[7七]\\s*天").matcher(query).find()?"LAST_7_DAYS":query.contains("本月")||query.contains("这个月")?"THIS_MONTH":null;
        if(requested!=null)return requested.equals(period);
        if(period.equals("CURRENT"))return true;
        return run.previous!=null&&run.previous.get("scope") instanceof Map<?,?> previous&&Boolean.TRUE.equals(previous.get("confirmed"))&&period.equals(previous.get("period"));
    }
    private boolean grounded(Run run,String value,String field,boolean numeric,List<String> intents) {
        if(value==null)return true;
        if(run.query.matches("\\s*\\d+\\s*"))return run.query.trim().equals(value)&&run.previous!=null&&Boolean.TRUE.equals(run.previous.get("awaitingOrderInput"))
            &&run.previous.get("plan") instanceof Map<?,?> previous&&new HashSet<>(intents).equals(new HashSet<>((List<?>)previous.get("intents")));
        if(numeric?Pattern.compile("(?:订单|order)\\s*(?:ID|id|编号|主键|号)?\\s*[:：#为是]?\\s*"+Pattern.quote(value)+"(?![0-9])|(?<![0-9])"+Pattern.quote(value)+"\\s*号?订单",Pattern.CASE_INSENSITIVE).matcher(run.query).find():run.query.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)))return true;
        if(run.previous!=null&&run.previous.get("scope") instanceof Map<?,?> map&&Boolean.TRUE.equals(map.get("confirmed"))&&value.equals(map.get(field)))return true;
        return false;
    }
    private static boolean explicitOrderMatches(String query,String pk,String number) {
        Set<String> selectors=new HashSet<>();
        for(String expression:List.of("(?:订单|order)\\s*(?:ID|编号|主键|号)?\\s*[:：#为是]?\\s*([A-Za-z0-9][A-Za-z0-9_-]*)","(?<![A-Za-z0-9_-])([A-Za-z0-9][A-Za-z0-9_-]*)\\s*号?订单")) {
            var matcher=Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(query);
            while(matcher.find()){String selector=matcher.group(1);if(!Set.of("ID","id").contains(selector))selectors.add(selector.toLowerCase(Locale.ROOT));}
        }
        return selectors.isEmpty()||selectors.size()==1&&selectors.stream().allMatch(value->value.equals(pk)||number!=null&&value.equals(number.toLowerCase(Locale.ROOT)));
    }
    private static boolean unsupportedExplicitFilter(String query) {
        if(Pattern.compile("\\d{4}\\s*年|\\d{1,2}\\s*月|\\d{4}[/.-]\\d{1,2}|仓库|库位|warehouse|location|按客户|客户名|状态|金额|价格",Pattern.CASE_INSENSITIVE).matcher(query).find())return true;
        var days=Pattern.compile("(?:最近|近|过去)\\s*(\\d+)\\s*天").matcher(query);
        while(days.find())if(!days.group(1).equals("7"))return true;
        return false;
    }
    private Map<String,Object> contextMap(Run run) {return object("runRef",run.ref,"queryMode","NATURAL","userQuery",run.query,"workflowProfile","AUTO","sourceMode",SOURCE,"requiredAgents",run.required,"dependencies",dependencies(run),"currentDate",LocalDate.ofInstant(clock.instant(),ZONE).toString(),"timeZone","Asia/Shanghai","dateTimezone","Asia/Shanghai","previousContext",run.previous,"scope",scope(run),"plan",run.plan,"capabilities",run.policy.publicCapabilities(),"availableTools",BusinessToolRegistry.catalog().stream().filter(t->run.policy.allows(t.toolName().split("\\.")[1].toUpperCase(Locale.ROOT))).toList(),"availableEvidenceTools",run.policy.adminRole()?EvidenceToolRegistry.catalog():List.of(),"taskReceipts",run.tasks.values().stream().filter(t->t.receipt!=null).map(t->t.receipt).toList());}
    private static Map<String,List<String>> dependencies(Run run) {
        var dependencies=new LinkedHashMap<String,List<String>>();
        for(String role:run.required)dependencies.put(role,dependencies(run,role));
        return dependencies;
    }
    private static List<String> dependencies(Run run,String role) {
        if(role.equals("AFTERSALES")&&run.order!=null&&run.required.contains("QUALITY"))return List.of("QUALITY");
        return usesDelivery(run)?role.equals("PRODUCTION")?List.of("ORDER","SUPPLY"):role.equals("SUPPLY")?List.of("ORDER"):List.of():List.of();
    }
    private static List<Map<String,Object>> businessHandoff(Run run,String role,Set<String> handedRefs) {
        List<Map<String,Object>> findings=new ArrayList<>();
        for(String dependency:dependencies(run,role)) {
            Task upstream=run.tasks.get(dependency);
            List<Map<String,Object>> excerpts=new ArrayList<>();
            for(Object value:(List<?>)upstream.started.get("evidence")) {
                if(!(value instanceof Map<?,?> record)||!(record.get("queryRef") instanceof String queryRef))continue;
                Map<String,Object> excerpt=new LinkedHashMap<>();
                if(record.get("data") instanceof Map<?,?> source) {
                    // Keep recorded quantities, sample scope and visual provenance; do not duplicate all item rows.
                    for(String key:List.of("inspections","qualityMetrics","visionEvidence"))if(source.containsKey(key))excerpt.put(key,source.get(key));
                }
                excerpts.add(object("queryRef",queryRef,"status",record.get("status"),"data",excerpt));
                handedRefs.add(queryRef);
            }
            findings.add(object("agentId",dependency,"resultRef",upstream.receipt.get("resultRef"),
                "authority","BUSINESS_EVIDENCE_SNAPSHOT","evidence",excerpts));
        }
        return findings;
    }
    private void step(Run run,Task task,String id,String label,String status,String detail) {
        task.steps.put(id,object("id",id,"label",label,"status",status,"detail",detail,"at",clock.instant().toString()));task.started.put("steps",List.copyOf(task.steps.values()));
        var event=workflowEvent(run,task,"RUNNING".equals(status)?"STEP_STARTED":"STEP_COMPLETED",status,label,detail);
        if(event!=null&&(Set.of("E1","E2","E3","E4","Q1","D1","A1","KNOWLEDGE_RETRIEVAL").contains(id)||id.startsWith("OVERVIEW_")||id.startsWith("RESOURCE_"))) {
            String callId="RUNNING".equals(status)?UUID.randomUUID().toString():task.toolCalls.get(id);
            if(callId!=null) {task.toolCalls.put(id,callId);event.put("toolId",id);event.put("toolCallId",callId);}
        }
    }
    private Map<String,Object> workflowEvent(Run run,Task task,String kind,String status,String label,String detail) {
        if(run.workflowEvents.size()==MAX_WORKFLOW_EVENTS) {
            run.workflowTruncated=true;
            return null;
        }
        var event=object("eventId",UUID.randomUUID().toString(),"runRef",run.ref,"seq",run.workflowEvents.size()+1,"kind",kind,"agentId",task==null?"SUP":task.started.get("agentId"),
            "status",status,"label",label,"detail",detail,"at",clock.instant().toString());
        if(task!=null)event.put("taskId",task.started.get("taskId"));
        run.workflowEvents.add(event);
        return event;
    }
    private Map<String,Object> scope(Run run) {boolean confirmed=run.plan!=null&&"QUERY".equals(run.plan.get("action"));return object("workOrderPk",confirmed?run.workOrder:null,"workOrderNo",confirmed?run.workOrderNo:null,"orderPk",confirmed?run.order:null,"orderNo",confirmed?run.orderNo:null,"scopeRef",run.scopeRef,"confirmed",confirmed,"selectors",confirmed?run.selectors:Map.of(),"criteria",confirmed?run.criteria:Map.of(),"expiresAt",run.expires.toString(),"keyword",confirmed?run.plan.get("keyword"):null,"period",confirmed?run.plan.get("period"):"CURRENT","startInclusive",confirmed&&run.dateRange!=null?run.dateRange.fromTimestamp():null,"endExclusive",confirmed&&run.dateRange!=null?run.dateRange.toTimestamp():null);}
    private Run lookup(String ref) {Run run=runs.get(ref);if(run==null||!run.expires.isAfter(clock.instant()))throw error(404,"RUN_UNAVAILABLE");return run;}
    private Run require(String ref) {Run run=lookup(ref);if(run.cancelled)throw error(410,"RUN_UNAVAILABLE");authorize(run);return run;}
    private void authorize(Run run) {access.apply(run.owner,d->{if(!d.policy.fingerprint().equals(run.policy.fingerprint()))throw error(403,"PERMISSION_CHANGED");return Map.of();});}
    private static void requirePlan(Run run) {if(run.plan==null)throw error(409,"PLAN_REQUIRED");}
    private static boolean usesDelivery(Run run) {return run.policy.adminRole()&&run.order!=null&&(intents(run).contains("DELIVERY")||intents(run).contains("PRODUCTION"));}
    @SuppressWarnings("unchecked") private static List<String> intents(Run run) {return run.plan==null?List.of():(List<String>)run.plan.get("intents");}
    private static String role(String intent) {return switch(intent){case "INVENTORY"->"SUPPLY";case "DELIVERY"->"ORDER";case "KNOWLEDGE"->"QUALITY";case "MAINTENANCE","ALARMS"->"EQUIPMENT";case "STOCK_MOVEMENT"->"SUPPLY";default->intent;};}
    // Other independent roles may register evidence while this role reads its predecessors.
    // Only snapshot the reference under the same monitor used by registration; never hold it over SQL I/O.
    private synchronized String qref(Run run,String kind) {return run.evidence.containsKey(kind)?(String)run.evidence.get(kind).get("queryRef"):null;}
    @SuppressWarnings("unchecked") private static Map<String,Object> data(Map<String,Object> evidence) {return (Map<String,Object>)evidence.get("data");}
    private static String nullable(JsonNode node,String key,int limit) {if(node.get(key)==null)throw error(400,"INVALID_PLAN");return node.get(key).isNull()?null:text(node,key,limit);}
    private static String text(JsonNode node,String key,int limit) {JsonNode value=node.get(key);if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>limit)throw error(400,"INVALID_REQUEST");return value.asText().trim();}
    private static ResponseStatusException error(int code,String reason) {return new ResponseStatusException(HttpStatus.valueOf(code),reason);}
    private static Map<String,Object> object(Object... pairs) {Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;}
    private static final class Task {final Map<String,Object> started;Map<String,Object> receipt,knowledge,analysis;String rawSummary;boolean researchRunning,reading,failed;final Set<String> upstreamBusinessRefs=new HashSet<>();final Map<String,Map<String,Object>> steps=new LinkedHashMap<>();final Map<String,String> toolCalls=new HashMap<>();Task(Map<String,Object> started){this.started=started;}}
    private static final class Run {
        final String ref,query;final long owner;final Instant expires;final Map<String,Object> previous;
        AssistantReadPolicy policy;BusinessDateRange dateRange;Map<String,String> selectors=Map.of(),criteria=Map.of();
        String workOrder,workOrderNo,order,orderNo,scopeRef=UUID.randomUUID().toString();List<String> required=List.of();JsonNode planInput;Map<String,Object> plan,result,last;boolean crossOrderRisk,cancelled,awaitingOrderInput;
        final Map<String,Map<String,Object>> evidence=new LinkedHashMap<>();final Map<String,Task> tasks=new LinkedHashMap<>();
        Map<String,Object> workflowPlan;
        final List<Map<String,Object>> workflowEvents=new ArrayList<>();
        boolean workflowTruncated,workflowInvalidated,summarizing;
        Run(String ref,String query,long owner,Instant expires,Map<String,Object> previous){this.ref=ref;this.query=query;this.owner=owner;this.expires=expires;this.previous=previous;}
    }
}
