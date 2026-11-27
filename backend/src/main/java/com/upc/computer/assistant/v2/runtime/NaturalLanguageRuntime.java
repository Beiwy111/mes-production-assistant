package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.upc.computer.assistant.v2.tools.delivery.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Natural-language run state. Plans select only fixed queries; model prose is never authoritative. */
public final class NaturalLanguageRuntime {
    public interface Access { Map<String,Object> apply(long owner,Function<Data,Map<String,Object>> operation); }
    public interface Research {
        Map<String,Object> retrieve(String role,List<Map<String,Object>> evidence);
        default Map<String,Object> retrieveQuestion(String query) {return Map.of("status","NOT_CONFIGURED","hits",List.of(),"graph_paths",List.of());}
        default Map<String,Object> retrievePlanned(String query,boolean graph) {return retrieveQuestion(query);}
        default Map<String,Object> retrieveExperience(String query,String domain) {return Map.of("status","NOT_CONFIGURED","hits",List.of(),"graph_paths",List.of());}
    }
    public record Data(NaturalBusinessReadMapper overview,AiDeliveryQueryService delivery,AssistantReadPolicy policy) {
        public Data(NaturalBusinessReadMapper overview,AiDeliveryQueryService delivery){this(overview,delivery,AssistantReadPolicy.admin());}
    }
    private static final String SOURCE="LOCAL_READONLY_PREVIEW";
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final Set<String> METHODOLOGY=Set.of("KNOWLEDGE","GRAPH_KNOWLEDGE","GENERAL");
    private static final Set<String> PLANNED_TOOLS=Set.of("PROCESS_ROUTE","INSPECTION_TRACE","GRAPH_KNOWLEDGE","GENERAL");
    private static final Set<String> SET_INTENTS=Arrays.stream(InspectionSelection.Operation.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Pattern INSPECTION_NO=BusinessReadIntent.INSPECTION_ID;
    private static final Set<String> INTENTS=Set.of("INVENTORY","PRODUCTION","QUALITY","DELIVERY","ORDER","EQUIPMENT","AFTERSALES","KNOWLEDGE","MAINTENANCE","ALARMS","STOCK_MOVEMENT","PROCESS_ROUTE","INSPECTION_TRACE","GENERAL","GRAPH_KNOWLEDGE","RECAP");
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
        ReadReplay replay=null;
        if(input.has("previousRunRef")&&!input.get("previousRunRef").isNull()) {
            String previousRef=text(input,"previousRunRef",36);
            try {
                Run prior=require(previousRef);
                if(prior.owner!=owner)throw error(403,"PREVIOUS_RUN_FORBIDDEN");
                if(Objects.equals(prior.policy.fingerprint(),((AssistantReadPolicy)permission.get("policy")).fingerprint())) previous=object("userQuery",prior.userQuery,"plan",prior.plan,"scope",scope(prior),"reply",prior.result==null?null:prior.result.get("reply"),
                    "referenceDay",prior.referenceDay.toString(),"businessSelection",prior.displayedBusiness,"displayedInspections",prior.displayedInspections,"displayedOrders",prior.displayedOrders,"displayedWorkOrders",prior.displayedWorkOrders,"completedGeneral",prior.result!=null&&"EVIDENCE_READY".equals(prior.result.get("verdict"))&&intents(prior).equals(List.of("GENERAL")),
                    "awaitingOrderInput",prior.awaitingOrderInput&&prior.result!=null&&"CLARIFY".equals(prior.result.get("verdict")));
                if(previous!=null)replay=prior.completedRead;
            } catch(ResponseStatusException absent) {
                if(absent.getStatusCode().value()!=404)throw absent;
                previous=contextStore.load(previousRef,owner);
                if(previous==null)throw error(404,"PREVIOUS_RUN_UNAVAILABLE");
                if(!Objects.equals(previous.get("authorizationStamp"),((AssistantReadPolicy)permission.get("policy")).fingerprint()))previous=null;
                if(previous!=null){previous=new LinkedHashMap<>(previous);replay=ReadReplay.load(previous.remove("readReplay"));}
            }
            if(input.hasNonNull("previousGeneralAnswer") && previous!=null && Boolean.TRUE.equals(previous.get("completedGeneral"))) {
                previous=new LinkedHashMap<>(previous);
                previous.put("generalAnswer",text(input,"previousGeneralAnswer",4000));
            }
        }
        runs.values().removeIf(r->!r.expires.isAfter(clock.instant()));
        if(runs.size()>=64)throw error(429,"CAPACITY_REACHED");
        Run run=new Run(UUID.randomUUID().toString(),query,owner,clock.instant().plusSeconds(600),previous);
        run.policy=(AssistantReadPolicy)permission.get("policy");run.replay=replay;
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
        for(JsonNode node:intentNodes)if(!node.isTextual()||!(INTENTS.contains(node.asText())||BusinessSelection.INTENTS.contains(node.asText())||SET_INTENTS.contains(node.asText()))||!intents.add(node.asText()))throw error(400,"INVALID_PLAN");
        if(new HashSet<>(intents).size()!=intents.size())throw error(400,"INVALID_PLAN");
        String pk=nullable(input,"orderPk",20),number=nullable(input,"orderNo",100),keyword=nullable(input,"keyword",100);
        if(pk!=null)try { AiDeliveryQueryService.id(pk); } catch(RuntimeException invalid) {throw error(400,"INVALID_PLAN");}
        if(!input.get("question").isTextual()||input.get("question").asText().length()>1000)throw error(400,"INVALID_PLAN");
        String question=input.get("question").asText().trim();
        if(action.equals("QUERY")&&intents.isEmpty()||!action.equals("QUERY")&&question.isBlank())throw error(400,"INVALID_PLAN");
        run.planInput=input.deepCopy();
        // Resolve document type before any set/overview shortcut. A QI number is an inspection
        // even if the user calls it an order. Keep every independently stated filter and permission.
        var documentIds=INSPECTION_NO.matcher(run.query).results().map(m->m.group().toUpperCase(Locale.ROOT)).distinct().toList();
        String priorInspection=run.previous!=null && run.previous.get("scope") instanceof Map<?,?> prior
            && Boolean.TRUE.equals(prior.get("confirmed")) && prior.get("inspectionNo") instanceof String no ? no : null;
        boolean singleFollowup=priorInspection!=null && run.query.matches("(?s).*(?:这张单|该单|这张检验单|这批产品|这份检验|这个订单|该检验单).*");
        String anchor=documentIds.size()==1 ? documentIds.get(0) : documentIds.isEmpty() && singleFollowup ? priorInspection : null;
        if(anchor!=null && BusinessReadIntent.inspectionRead(run.query) && !action.equals("UNSUPPORTED")
                && (intents.isEmpty() || intents.stream().allMatch(Set.of("QUALITY","INSPECTION_TRACE","ORDER","GENERAL","KNOWLEDGE","GRAPH_KNOWLEDGE","RECAP")::contains))
                && (keyword==null || anchor.equalsIgnoreCase(keyword) || !run.query.contains(keyword)
                    || Set.of("订单","检验单","质检单").contains(keyword))
                && explicitOrderMatches(INSPECTION_NO.matcher(run.query).replaceAll(" "),null,null)) {
            var resolved=new ArrayList<String>();resolved.add("INSPECTION_TRACE");
            if(intents.contains("GRAPH_KNOWLEDGE"))resolved.add("GRAPH_KNOWLEDGE");
            else if(intents.contains("KNOWLEDGE"))resolved.add("KNOWLEDGE");
            intents=resolved; action="QUERY"; pk=null; number=null; keyword=anchor; question="";
        }
        var cohort=BusinessSelection.previous(run.previous);
        if(InventoryCategory.switchesCategory(run.query)&&BusinessSelectors.previousInventory(run.previous)
                &&pk==null&&number==null&&!action.equals("UNSUPPORTED")
                &&intents.stream().allMatch(Set.of("INVENTORY","ROWS_FILTER")::contains)){
            action="QUERY";intents=List.of("INVENTORY");question="";keyword=null;
            var prior=(Map<?,?>)run.previous.get("scope");
            if(prior.get("period") instanceof String previousPeriod)period=previousPeriod;
        }
        boolean semanticRows=intents.stream().anyMatch(BusinessSelection.INTENTS::contains);
        boolean refers=BusinessSelection.refers(run.query);
        boolean drill=BusinessSelection.dispatch(run.query)||BusinessSelection.material(run.query);
        if((intents.contains("INVENTORY") || cohort!=null && cohort.domain().equals("INVENTORY") && semanticRows)
                && InventoryCategory.genericKeyword(keyword) && InventoryCategory.hasCategoryTerms(run.query))keyword=null;
        boolean selectorFollowup=cohort!=null && refers && cohort.domain().equals("INVENTORY")
                && run.query.matches("(?is).*(?:仓库|库位|warehouse|location).*") && !BusinessSelectors.otherMaterials(run.query);
        boolean resume=BusinessSelection.resume(run.query);
        boolean ellipticalFilter=cohort!=null && run.query.matches("(?:还有|那|那还有)?哪些(?:还|已经|是|尚|仍|没有|没|有|已|在|未|待).*")
                && !run.query.matches(".*(?:检验|质检|订单|工单|库存|设备|售后).*")
                && !"ALL".equals(BusinessQueryFilter.criterion(cohort.domain(),run.query));
        if(semanticRows || ellipticalFilter || cohort!=null && refers && (drill||resume||selectorFollowup||!"ALL".equals(BusinessQueryFilter.criterion(cohort.domain(),run.query))) && !DisplayedWorkOrders.qualityFollowup(run.query)
                || cohort==null && refers && (drill||resume) && !(run.previous!=null&&run.previous.get("displayedInspections") instanceof List<?>)
                    && !run.query.matches("(?is).*(?:检验|质检|WO[0-9]|SO[0-9]).*")){
            run.sourcePlanInput=input.deepCopy();
            return planSelection(run,cohort,intents,pk,number,period,keyword);
        }
        var explicitSet=InspectionSelection.explicitFollowup(run.query);
        if(explicitSet!=null && run.previous!=null && run.previous.get("displayedInspections") instanceof List<?>){
            intents=List.of(explicitSet.name());action="QUERY";question="";pk=null;number=null;keyword=null;period="CURRENT";
        }
        if(intents.stream().anyMatch(SET_INTENTS::contains)){
            boolean bound=run.previous!=null && run.previous.get("displayedInspections") instanceof List<?>
                    && run.previous.get("scope") instanceof Map<?,?> prior && Boolean.TRUE.equals(prior.get("confirmed"));
            boolean allowed=run.policy.allows("QUALITY") && run.policy.customer()==null && run.policy.operator()==null;
            if(!allowed){action="UNSUPPORTED";question="当前账号没有查看这组检验单的权限。";}
            else if(!bound || intents.size()!=1 || pk!=null || number!=null || keyword!=null || !period.equals("CURRENT")
                    || INSPECTION_NO.matcher(run.query).find()) {
                action="CLARIFY";question="请说明要继续查看的检验单范围或编号。";
            }else if(action.equals("QUERY")){
                var ids=DisplayedWorkOrders.storedIds(run.previous,"displayedInspections");
                var op=InspectionSelection.Operation.valueOf(intents.get(0));
                if(op==InspectionSelection.Operation.SET_TRIAGE && ids.size()>20){
                    action="CLARIFY";question="这组有 "+ids.size()+" 张检验单，请先缩小到最多 20 张再逐单排查。";
                }else{
                    run.inspectionSelection=ids;run.inspectionOperation=op;
                    run.criteria=Map.of("QUALITY",switch(op){case SET_UNQUALIFIED->"UNQUALIFIED";case SET_FAILED_STATUS->"WORKFLOW_FAILED";default->"ALL";});
                    run.required=List.of("QUALITY");
                }
            }
            run.sourcePlanInput=input.deepCopy();
            run.plan=object("action",action,"intents",List.of("QUALITY"),"orderPk",null,"orderNo",null,"keyword",null,"period","CURRENT","question",question);
            return confirmPlan(run,action);
        }
        // Generic nouns such as “质检情况” describe the view, not a literal database
        // search term. Only normalize a complete, unqualified overview request.
        if (QUALITY_OVERVIEW.matcher(run.query.strip()).matches()) {
            action="QUERY"; intents=List.of("QUALITY"); question="";
            pk=null; number=null; keyword=null; period="CURRENT";
        }
        if(DisplayedWorkOrders.qualityFollowup(run.query)) {
            run.sourcePlanInput=input.deepCopy();
            run.qualityWorkOrders=DisplayedWorkOrders.previous(run.previous);
            if(run.query.contains("订单"))run.qualityOrders=DisplayedWorkOrders.storedIds(run.previous,"displayedOrders");
            if(!run.qualityOrders.isEmpty())run.qualityWorkOrders=List.of();
            if(!run.qualityOrders.isEmpty()&&!run.policy.allows("ORDER") || !run.policy.allows("PRODUCTION") || !run.policy.allows("QUALITY") || run.policy.customer()!=null || run.policy.operator()!=null) {
                action="UNSUPPORTED";question="当前账号没有查看这些工单质检记录的权限。";
            } else if(!run.cohortQuality()) {
                action="CLARIFY";question="上一条回答没有可唯一确定范围的生产工单表，请提供工单编号。";
            } else {
                action="QUERY";question="";run.required=List.of(role("QUALITY"));
            }
            run.plan=object("action",action,"intents",List.of("QUALITY"),"orderPk",null,"orderNo",null,"keyword",null,"period","CURRENT","question",question);
            return confirmPlan(run,action);
        }
        if(action.equals("QUERY")&&intents.contains("RECAP")){
            var recipe=run.replay;
            if(intents.size()==1&&recipe!=null&&pk==null&&number==null&&keyword==null&&period.equals("CURRENT")
                    &&!BusinessReadIntent.crossCustomerDisclosure(run.userQuery)){
                run.query=recipe.query();run.previous=recipe.prior().isEmpty()?null:recipe.prior();
                run.referenceDay=LocalDate.parse(recipe.referenceDay());
                plan(ref,recipe.plan());
                run.planInput=input.deepCopy();
                if("QUERY".equals(run.plan.get("action"))&&recipe.scope().equals(ReadReplay.binding(scope(run))))return contextMap(run);
                // Resolution or permissions evolved. Never substitute a different order or a broader record set.
                run.evidence.clear();run.required=List.of();run.workflowEvents.clear();
            }
            run.plan=object("action","CLARIFY","intents",List.of("RECAP"),"orderPk",null,"orderNo",null,"keyword",null,
                "period","CURRENT","question","无法在当前权限下还原上一轮的查询范围，请说明要复查的对象和条件。");
            return confirmPlan(run,"CLARIFY");
        }
        run.sourcePlanInput=input.deepCopy();
        // Explanations alongside business reads are composed by SUP from the selected tools.
        // GENERAL alone has no business selectors: dates, prices and quantities can be input text.
        if(action.equals("QUERY") && intents.equals(List.of("GENERAL")) && pk==null && number==null && keyword==null
                && period.equals("CURRENT") && !INSPECTION_NO.matcher(run.query).find() && BusinessReadIntent.needsTechnicalGuidance(run.query))
            intents=List.of(BusinessReadIntent.processMechanism(run.query)?"GRAPH_KNOWLEDGE":"KNOWLEDGE");
        if(action.equals("QUERY")&&intents.equals(List.of("KNOWLEDGE"))&&BusinessReadIntent.processMechanism(run.query))intents=List.of("GRAPH_KNOWLEDGE");
        if(action.equals("QUERY")&&intents.contains("GENERAL")) {
            if(intents.size()>1) intents=intents.stream().filter(i->!i.equals("GENERAL")).toList();
            else {
                run.policy.require("GENERAL");
                if(pk!=null||number!=null||keyword!=null||INSPECTION_NO.matcher(run.query).find()) {
                    action="CLARIFY";question="请明确要读取的业务对象及所需数据，或提供希望处理的文本。";
                } else if(BusinessReadIntent.crossCustomerDisclosure(run.query)) {
                    action="UNSUPPORTED";question="当前账号只能查看已授权的订单。";
                }
                run.plan=object("action",action,"intents",intents,"orderPk",null,"orderNo",null,"keyword",null,"period","CURRENT","question",question);
                return confirmPlan(run,action);
            }
        }
        boolean materialRisk=Pattern.compile("(?:订单|跨单).*(?:共享物料|物料竞争|(?:竞争|争用|共用|共享).{0,9}(?:物料|库存))").matcher(run.query).find();
        boolean capacityRisk=Pattern.compile("(?:订单|派工|排程|排产).*(?:产能冲突|资源冲突|设备冲突|人员冲突|排程重叠)").matcher(run.query).find();
        run.crossOrderRisk=(materialRisk||capacityRisk||Pattern.compile("跨订单.*(?:风险|共享|物料|产能|冲突)|订单.*(?:风险分析|交付风险)|哪些订单.*风险").matcher(run.query).find())&&pk==null&&number==null;
        if(run.crossOrderRisk&&intents.stream().allMatch(Set.of("ORDER","DELIVERY","INVENTORY","PRODUCTION")::contains)&&!action.equals("UNSUPPORTED")){action="QUERY";intents=new ArrayList<>(List.of("ORDER"));question="";}
        else run.crossOrderRisk=false;
        boolean simpleOtherStock=run.query.strip().matches("(?:那|那么)?(?:其他|其它|其余|剩余)(?:的)?物料(?:库存|的库存)?(?:有多少|还有多少|呢)?[？?。]?");
        boolean stockFollowup=run.query.strip().matches("(?:其中|这些|上述)(?:的)?可用库存(?:有多少)?[？?。]?");
        if((simpleOtherStock||stockFollowup)&&!action.equals("UNSUPPORTED")&&pk==null&&number==null
            &&intents.stream().allMatch("INVENTORY"::equals)) {
            action="QUERY";intents=List.of("INVENTORY");question="";keyword=null;
        }
        if(action.equals("QUERY")&&INSPECTION_NO.matcher(run.query).find()&&!intents.contains("INSPECTION_TRACE")){
            if(intents.contains("QUALITY")&&intents.stream().allMatch(Set.of("QUALITY","KNOWLEDGE","GRAPH_KNOWLEDGE")::contains)){
                intents=intents.stream().map(i->i.equals("QUALITY")?"INSPECTION_TRACE":i).toList();
            }else{
                action="CLARIFY";question="问题含有具体检验单号。请明确要查询该单的检验明细、同批记录，还是关联工序和设备。";
            }
        }
        boolean explicitTools=intents.stream().anyMatch(PLANNED_TOOLS::contains);
        if(action.equals("QUERY")&&pk==null&&number==null&&!explicitTools&&intents.size()==1)
            intents=BusinessReadIntent.specialists(run.query,intents);
        if(action.equals("QUERY")&&period.equals("CURRENT")&&intents.contains("QUALITY")&&unspecifiedRecent(run.query))
            period="LAST_7_DAYS";
        if(action.equals("QUERY")&&intents.contains("INSPECTION_TRACE")) {
            var ids=INSPECTION_NO.matcher(run.query).results().map(m->m.group().toUpperCase(Locale.ROOT)).distinct().toList();
            String prior=run.previous!=null&&run.previous.get("scope") instanceof Map<?,?> s&&Boolean.TRUE.equals(s.get("confirmed"))
                &&s.get("inspectionNo") instanceof String no?no:null;
            String selected=ids.size()==1?ids.get(0):ids.isEmpty()?prior:null;
            boolean linkedOnly=InspectionTrace.asksForLinkedRecords(run.query);
            if(selected==null || keyword!=null&&!selected.equalsIgnoreCase(keyword) || pk!=null||number!=null
                    ||InspectionTrace.asksForAdditionalScope(run.query)
                    ||intents.stream().anyMatch(i->!METHODOLOGY.contains(i)&&!i.equals("INSPECTION_TRACE")&&!i.equals("QUALITY")
                        &&!(linkedOnly&&Set.of("PRODUCTION","EQUIPMENT","ALARMS").contains(i)))) {
                action="CLARIFY";question="请指定一张检验单的完整编号，以便沿其实际关联核对批次、工单和设备。";
            } else {
                run.inspectionNo=selected;keyword=selected;
                // In a question about the inspection's linked equipment or production records, the trace
                // supplies the permitted joins. Running another domain overview would lose the inspection scope.
                intents=intents.stream().filter(i->i.equals("INSPECTION_TRACE")||METHODOLOGY.contains(i)).toList();
            }
        }
        if(action.equals("QUERY")&&intents.contains("PROCESS_ROUTE")&&(intents.stream().anyMatch(i->!i.equals("PROCESS_ROUTE")&&!METHODOLOGY.contains(i))||pk!=null||number!=null||!period.equals("CURRENT"))){
            action="CLARIFY";question="请指定产品或当前工艺路线名称；订单对应的实际生产过程需要按工单查询。";
        }
        String workReference=explicitTools?null:WorkOrderReference.extract(run.query,run.previous);
        String focus=WorkOrderReference.focus(run.query);
        String batchReply=WorkOrderReference.batchClarificationAnswer(run.query,run.previous);
        if(batchReply!=null){workReference=batchReply;focus="BATCH";}
        boolean batch="BATCH".equals(focus);
        if(BusinessReadIntent.crossCustomerDisclosure(run.query)){
            workReference=null;action="UNSUPPORTED";question="当前工作台仅供当前账号查询已授权的订单，无法向其他客户展示或转发订单。";
        } else if(BusinessReadIntent.unresolvedEquipment(run.query,keyword)){
            workReference=null;action="CLARIFY";question="请提供设备编号或名称，我再核对这台设备的相关记录。";
        } else if((WorkOrderReference.needsBatchIdentity(run.query)||run.query.matches(".*(?:某个|某一|某)批次.*(?:不良率|不合格率).*"))&&!batch&&run.inspectionNo==null&&!(intents.contains("QUALITY")&&BusinessSelectors.BATCH_CODE.matcher(run.query).find())){
            workReference=null;action="CLARIFY";question=run.query.matches(".*(?:不良率|不合格率).*")?
                "请提供具体批号或检验单号，以及要比较的两个时间段。需要核对各期抽检数、不合格数和复检记录才能判断变化。":WorkOrderReference.BATCH_QUESTION;
        } else if(WorkOrderReference.needsAnchor(run.query)&&run.inspectionNo==null&&(workReference==null||focus==null)){
            workReference=null;action="CLARIFY";question="请明确一张工单及查询条件；不会用其他工单或全厂质检记录代替。";
        }
        if(!action.equals("UNSUPPORTED")&&workReference==null&&run.query.contains("派工")&&WorkOrderReference.multipleReferences(run.query)) {
            action="CLARIFY";question="请先选择一张生产工单的完整编号，再核对派工记录；不会忽略其他明确编号。";
        }
        if(workReference!=null&&(focus!=null||!action.equals("UNSUPPORTED"))) {
            if(WorkOrderReference.multipleReferences(run.query)||focus==null&&(intents.stream().anyMatch(intent->!Set.of("ORDER","PRODUCTION").contains(intent))||(keyword!=null&&!keyword.equalsIgnoreCase(workReference)&&!keyword.matches("(?i)WO[A-Z0-9_-]+")))) {
                action="CLARIFY";question="请先明确一张生产工单及要查询的派工范围；多个编号或额外物料条件不能被省略。";
            } else if(!run.policy.allows("PRODUCTION")||!run.policy.allows("DISPATCH")||batch&&!run.policy.allows("QUALITY")) {
                action="UNSUPPORTED";question="当前账号没有本次工单查询所需的业务权限。";
            } else {
                final String lookup=workReference;
                var resolved=access.apply(run.owner,d->{
                    d.policy.require("PRODUCTION");d.policy.require("DISPATCH");
                    var candidates=d.overview.resolveWorkOrders(lookup,d.policy.operator());
                    boolean customerCollision=lookup.matches("[0-9]+")&&!run.query.contains("工单")&&d.policy.allows("ORDER")&&!d.overview.resolveCustomerOrder(Long.valueOf(lookup),null,d.policy.customer()).isEmpty();
                    return object("candidates",candidates,"collision",customerCollision);
                });
                @SuppressWarnings("unchecked") var candidates=(List<Map<String,Object>>)resolved.get("candidates");
                if(candidates.size()==1&&!Boolean.TRUE.equals(resolved.get("collision"))) {
                    run.workOrder=String.valueOf(candidates.get(0).get("work_order_id"));run.workOrderNo=String.valueOf(candidates.get(0).get("work_order_no"));
                    action="QUERY";question="";intents=new ArrayList<>(List.of(batch?"QUALITY":"PRODUCTION"));if(focus!=null)period="CURRENT";pk=null;number=null;keyword=null;
                } else {
                    action="CLARIFY";question=candidates.isEmpty()?"当前授权范围内未找到该生产工单，请核对完整工单号。":"该编号存在多个业务候选，请使用完整生产工单号确认："+candidates.stream().limit(8).map(row->String.valueOf(row.get("work_order_no"))).toList()+(Boolean.TRUE.equals(resolved.get("collision"))?"；该数字也匹配客户订单ID。":"");
                }
            }
        }
        if(action.equals("QUERY")&&intents.stream().anyMatch(intent->!run.policy.allows(intent))) {
            action="UNSUPPORTED";question="当前账号没有本次查询所需的业务权限，请查询已授权的业务范围或联系管理员授权。";
        }
        if(action.equals("QUERY")) {
            String dateText=run.workOrder!=null&&focus!=null?"":run.query;boolean invalidDates=false,invalidSelectors=false;
            try {var selected=BusinessSelectors.parse(run.query,intents,run.previous);run.selectors=selected.values();dateText=selected.remainingText();}
            catch(IllegalArgumentException invalid) {invalidSelectors=true;}
            if(run.selectors.containsKey("batch_no"))dateText=BusinessSelectors.BATCH_CODE.matcher(dateText).replaceAll(" ");
            if(keyword!=null&&(keyword.equalsIgnoreCase(run.selectors.get("equipment_code"))||keyword.equalsIgnoreCase(run.selectors.get("batch_no"))))keyword=null;
            boolean otherStock=BusinessSelectors.otherMaterials(run.query)&&intents.contains("INVENTORY");
            if(otherStock||stockFollowup) {
                if(run.previous==null||!(run.previous.get("scope") instanceof Map<?,?> prior)||!Boolean.TRUE.equals(prior.get("confirmed"))
                    ||!(prior.get("criteria") instanceof Map<?,?> c)||!c.keySet().equals(Set.of("INVENTORY"))||prior.get("orderPk")!=null
                    ||!intents.equals(List.of("INVENTORY"))||pk!=null||number!=null)invalidSelectors=true;
                else {
                    if(stockFollowup)keyword=(String)prior.get("keyword");
                    else if(Objects.equals(keyword,prior.get("keyword"))||Set.of("其他物料","其它物料","其余物料","剩余物料").contains(Objects.toString(keyword,"")))keyword=null;
                    if(period.equals("CURRENT")&&prior.get("period") instanceof String previousPeriod)period=previousPeriod;
                    if(stockFollowup&&"NO_AVAILABLE".equals(c.get("INVENTORY")))invalidSelectors=true;
                }
            }
            try {LocalDate today=run.referenceDay;
                if(intents.equals(List.of("QUALITY"))&&run.selectors.keySet().equals(Set.of("batch_no"))&&pk==null&&number==null
                    &&(BatchQualityComparison.requested(run.query)||run.previous!=null&&run.previous.get("userQuery") instanceof String priorQuestion
                    &&BatchQualityComparison.requested(priorQuestion)))
                    run.comparison=BusinessDateRange.comparison(dateText,today);
                if(!run.comparison.isEmpty()){dateText=run.comparison.get(0).remainingText();period="CURRENT";}
                else run.dateRange=BusinessDateRange.parse(dateText,today);
                if(run.dateRange!=null)dateText=run.dateRange.remainingText();
                else if(run.comparison.isEmpty()&&BusinessSelectors.followsScope(run.query)&&run.previous!=null&&
                        run.previous.get("scope") instanceof Map<?,?> prior&&prior.get("comparisonPeriods") instanceof List<?> windows&&!windows.isEmpty())
                    throw new IllegalArgumentException("Repeat the two comparison periods");
                else if(!Pattern.compile("今天|今日|本月|这个月|最近七天").matcher(dateText).find()&&run.previous!=null&&BusinessSelectors.followsScope(run.query,run.previous)
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
                    &&BusinessSelectors.followsScope(run.query,run.previous)
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
            if(run.inspectionNo!=null)filterQuery=INSPECTION_NO.matcher(filterQuery).replaceAll(" ");
            if(run.inspectionNo!=null)filterQuery=filterQuery.replaceAll("(?:判定|设备|检验|质检|工单|报工|派工)(?:的)?状态(?!\\s*(?:为|是(?!否)|等于|[:：=]|不?合格))","关联记录");
            boolean keywordGrounded=keyword==null||keyword.equals(run.inspectionNo)||dateText.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
            if(!keywordGrounded&&Pattern.compile("其中|这些|上述|刚才|刚刚|上面|同一范围").matcher(run.query).find()
                &&run.previous!=null&&run.previous.get("scope") instanceof Map<?,?> prior&&Boolean.TRUE.equals(prior.get("confirmed")))
                keywordGrounded=keyword.equals(prior.get("keyword"));
            if(run.workOrder!=null&&(!period.equals("CURRENT")||run.dateRange!=null||run.criteria.values().stream().anyMatch(value->!value.equals("ALL")))) {
                action="CLARIFY";question="当前工单派工查询展示完整派工记录，不会将工单更新时间当作派工时间。请确认查询该工单全部派工，暂不支持附加日期或状态筛选。";
            } else if(run.inspectionNo!=null&&(run.dateRange!=null||!period.equals("CURRENT")||!run.selectors.isEmpty())){
                action="CLARIFY";question="检验单追溯按单号和关联记录核对。请确认查询该单全部关联，或改用带日期或批号的质检记录查询。";
            } else if(run.selectors.containsKey("batch_no")&&BatchQualityComparison.requested(run.query)&&run.comparison.isEmpty()){
                action="CLARIFY";question="请给出要比较的两个独立时间段，例如“2026-07-01 至 2026-07-07 与 2026-07-08 至 2026-07-14”，以便按相同批次核对前后抽检分母。";
            } else if(!run.comparison.isEmpty()&&(keyword!=null||!run.criteria.values().stream().allMatch("ALL"::equals))){
                action="CLARIFY";question="不良率比较需要该批全部检验样本。请确认单一批号与两个日期范围，不能仅取不合格记录作为分母。";
            } else if(invalidDates) {
                action="CLARIFY";question="请提供一个有效且明确的日期范围，例如2026-11-01至2026-11-15；结束日期包含当天。";
            } else if(invalidSelectors&&(otherStock||stockFollowup)) {
                action="CLARIFY";question="请明确库存查询范围，包括要排除的物料名称或编码，以及要保留的仓库或库存条件。";
            } else if(invalidSelectors&&intents.contains("INVENTORY")&&InventoryCategory.hasCategoryTerms(run.query)){
                action="CLARIFY";question="请明确要查的库存范围，例如全部库存、物料库存或成品库存，以及需要保留的筛选条件。";
            } else if(invalidSelectors&&(BusinessSelectors.EQUIPMENT_CODE.matcher(run.query).find()||BusinessSelectors.BATCH_CODE.matcher(run.query).find())) {
                action="CLARIFY";question="请指定一个设备编码查询其设备、维修或报警记录，或指定一个批号查询该批质检记录。当前明细工具无法直接按设备筛选质检记录。";
            } else if(invalidSelectors||(pk!=null||number!=null)&&!run.selectors.isEmpty()) {
                action="CLARIFY";question="请明确仓库/库位编码（每类最多8个，可用逗号分隔，排除时请写‘排除仓库…’），或用客户名称为‘完整名称’查询订单。仓库/库位仅用于库存与流水，客户仅用于订单列表。状态需使用系统支持的明确状态；精确订单证据链不叠加这些筛选。";
            } else if(keyword!=null&&run.selectors.containsValue(keyword)) {
                action="CLARIFY";question="仓库、库位和客户已作为独立条件，不能再把它们当作物料关键词。请明确物料关键词或仅查询这些条件。";
            } else if(!grounded(run,pk,"orderPk",true,intents)||!grounded(run,number,"orderNo",false,intents)||!keywordGrounded) {
                action="CLARIFY";question="请明确要查询的订单编号或关键词；当前问题未提供可核验的查询范围。";
            } else if((run.workOrder==null&&!explicitOrderMatches(filterQuery,pk,number))||unsupportedExplicitFilter(filterQuery)) {
                action="CLARIFY";question="问题中包含明确订单或其他筛选条件，但当前计划未完整表达。请明确订单ID/订单号，并使用支持的时间范围和物料/单据关键词；请使用明确的仓库/库位编码或完整客户名称；其他未支持条件不能省略。";
            } else if((run.workOrder==null&&run.inspectionNo==null&&pk==null&&number==null)&&(intents.contains("DELIVERY")||Pattern.compile("这[张个笔].*订单|该订单|此订单").matcher(run.query).find())) {
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
        // Legacy single-domain follow-ups (e.g. a new time window) still use the normal
        // selector/date parser. Intersect its predicates with the previously displayed IDs.
        if(action.equals("QUERY") && cohort!=null && refers && intents.equals(List.of(cohort.domain()))
                && run.order==null && run.workOrder==null && !run.crossOrderRisk && !BusinessSelectors.otherMaterials(run.query))
            run.selection=cohort;
        run.awaitingOrderInput=action.equals("CLARIFY")&&!intents.isEmpty()&&Pattern.compile("订单|交付|order",Pattern.CASE_INSENSITIVE).matcher(run.query).find()
            &&question.contains("订单")&&Pattern.compile("提供|输入|明确|核对").matcher(question).find();
        if(action.equals("QUERY")) {
            Set<String> required=new HashSet<>();
            for(String intent:intents)required.add(role(intent));
            if(run.crossOrderRisk&&run.order==null) {
                if((materialRisk||!capacityRisk)&&ResourceRiskQueries.allowed(run.policy,"RESOURCE_MATERIAL"))required.add("SUPPLY");
                if((capacityRisk||!materialRisk)&&ResourceRiskQueries.allowed(run.policy,"RESOURCE_CAPACITY"))required.add("PRODUCTION");
            }
            if(run.policy.adminRole()&&run.order!=null&&(intents.contains("DELIVERY")||intents.contains("PRODUCTION")))required.addAll(List.of("ORDER","SUPPLY","PRODUCTION"));
            if(run.policy.adminRole()&&run.order!=null&&intents.contains("AFTERSALES"))required.add("QUALITY");
            run.required=ROLE_ORDER.stream().filter(required::contains).toList();
        }
        return confirmPlan(run,action);
    }
    private Map<String,Object> planSelection(Run run,BusinessSelection selected,List<String> intents,String pk,String number,String period,String keyword) {
        String q=run.query;
        boolean dispatch=BusinessSelection.dispatch(q)||intents.contains("ROWS_DISPATCH");
        boolean materials=BusinessSelection.material(q)||intents.contains("ROWS_MATERIAL");
        var domains=new LinkedHashSet<String>();
        String message="";
        boolean forbidden=false;
        if(selected==null)message="请先列出要继续查看的订单、工单或库存记录，才能确定这几条的范围。";
        else {
            String domain=selected.domain();domains.add(domain);
            String criterion=BusinessQueryFilter.criterion(domain,q);
            var selector=Map.<String,String>of();String rest=q;
            try {
                var parsed=BusinessSelectors.parse(q,List.of(domain),run.previous);
                selector=parsed.values();rest=parsed.remainingText();
            } catch(IllegalArgumentException invalid){message="请明确要在这组记录中筛选的条件。";}
            var prior=(Map<?,?>)run.previous.get("scope");
            if(pk!=null||number!=null||(!period.equals("CURRENT")&&!period.equals(prior.get("period")))||BusinessReadIntent.crossCustomerDisclosure(q)||
                    unsupportedExplicitFilter(rest)||rest.matches("(?is).*(?:[0-9]|今天|昨天|上周|本月|最近|大于|小于|超过|排除|除了|不要|不看|仅看某|只看某|不是|不在).*")||
                    keyword!=null&&!keyword.equals(prior.get("keyword"))&&(!q.contains(keyword)||!selector.containsValue(keyword))||BusinessQueryFilter.ambiguous(domain,q))
                message="请明确这组记录中要复查的对象和条件，当前组合无法精确核对。";
            if(q.matches("(?s).*(?:没(?:有)?派工|尚未派工|未派工|请安排|请派|指派给|重新派工|(?:派给|分配给)(?!谁|了谁)[一-龥]{2}|调拨|领用|提交|创建|删除|取消这|只看.{1,7}(?:负责|接单)|只看.{1,7}物料).*"))
                message="请明确要查询的人员或物料范围；此处只复查已发生的派工与库存。";
            if(!domain.equals("ORDER")&&q.contains("订单")&&!q.contains("工单")||domain.equals("INVENTORY")&&(dispatch||materials)
                    ||domain.equals("ORDER")&&q.contains("工单")&&!dispatch&&!materials)
                message="上一轮的表格与这次描述的对象不同，请明确要沿用哪组记录。";
            if(criterion.equals("ALL")&&!dispatch&&!materials&&selector.isEmpty()&&!BusinessSelection.resume(q)&&!intents.contains("ROWS_RESUME"))
                message="希望在刚才的记录中按什么条件筛选？";
            if(dispatch||materials)domains.add("PRODUCTION");
            if(materials)domains.add("INVENTORY");
            forbidden=domains.stream().anyMatch(d->!run.policy.allows(d))||dispatch&&!run.policy.allows("DISPATCH")
                    ||materials&&(run.policy.customer()!=null||run.policy.operator()!=null);
            if(forbidden)message="当前账号没有查看这组明细所需的权限。";
            if(message.isEmpty()){
                if(prior.get("startInclusive") instanceof String start && prior.get("endExclusive") instanceof String end)
                    run.dateRange=new BusinessDateRange(LocalDate.parse(start.substring(0,10)),LocalDate.parse(end.substring(0,10)),q);
                else if(prior.get("period") instanceof String previousPeriod && !previousPeriod.equals("CURRENT")){
                    var day=LocalDate.parse(String.valueOf(run.previous.getOrDefault("referenceDay",run.referenceDay.toString())));
                    var start=switch(previousPeriod){case "TODAY"->day;case "LAST_7_DAYS"->day.minusDays(6);default->day.withDayOfMonth(1);};
                    run.dateRange=new BusinessDateRange(start,day.plusDays(1),q);
                }
                run.selection=selected;run.selectors=selector;run.criteria=Map.of(domain,criterion);
                run.selectedDispatch=dispatch;run.selectedMaterial=materials;
                run.required=ROLE_ORDER.stream().filter(r->domains.stream().anyMatch(d->role(d).equals(r))).toList();
            }
        }
        String action=message.isEmpty()?"QUERY":forbidden?"UNSUPPORTED":"CLARIFY";
        run.plan=object("action",action,"intents",List.copyOf(domains),"orderPk",null,"orderNo",null,"keyword",run.selection==null?null:((Map<?,?>)run.previous.get("scope")).get("keyword"),"period","CURRENT","question",message);
        return confirmPlan(run,action);
    }
    private Map<String,Object> confirmPlan(Run run,String action) {
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
            "upstreamResultRefs",upstream,"sourceMode",SOURCE,"knowledgeOnly",intents(run).stream().allMatch(METHODOLOGY::contains),"startedAt",clock.instant().toString());
        task=new Task(started);task.reading=true;started.put("evidence",List.of());run.tasks.put(role,task);
        workflowEvent(run,task,"AGENT_STARTED","RUNNING","开始角色任务","已接收本次业务任务");
        if(!Boolean.TRUE.equals(started.get("knowledgeOnly")))step(run,task,"BUSINESS_READ","读取业务证据","RUNNING","正在读取授权 MySQL 业务记录");
        }
        Map<String,Map<String,Object>> evidence=new LinkedHashMap<>();
        var experience=experienceLookup(run,task,role);
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
                for(String intent:intents(run)) if(!METHODOLOGY.contains(intent)&&role(intent).equals(role)&&(!delivery||!List.of("DELIVERY","ORDER","PRODUCTION").contains(intent))) {
                    if(run.policy.adminRole()&&run.order!=null&&DOMAIN_EVIDENCE.containsKey(intent))continue;
                    if(intent.equals("PROCESS_ROUTE")||intent.equals("INSPECTION_TRACE")){
                        evidence.put(intent,readTool(run,task,intent,()->specialEvidence(d,run,intent,task)));
                        continue;
                    }
                    String domain=intent.equals("DELIVERY")?"ORDER":intent;
                    evidence.put("OVERVIEW_"+domain,readTool(run,task,"OVERVIEW_"+domain,()->overview(d,run,domain)));
                }
                return Map.of();
            });
            if(experience!=null)experience.join();
        } catch(RuntimeException failure) {
            synchronized(this) {
            task.reading=false;task.failed=true;
            task.experience=null;task.started.remove("experienceEvidence");
            if(experience!=null)experience.cancel(false);
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
        if(!Boolean.TRUE.equals(started.get("knowledgeOnly")))step(run,task,"BUSINESS_READ","读取业务证据","COMPLETED","已读取 "+evidence.size()+" 组授权 MySQL 业务证据");
        if(role.equals("QUALITY")&&run.comparison.isEmpty()&&evidence.keySet().stream().anyMatch(Set.of("OVERVIEW_QUALITY","Q1")::contains))step(run,task,"VISION_REVIEW","核对视觉检测来源","COMPLETED","已核对可用检测记录；缺失、摘要和模拟结果分别标识");
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

    private static final Pattern EXPERIENCE_REQUEST=Pattern.compile("经验|案例|怎么(?:处理|维修|修复|排查)|如何(?:处理|维修|修复|排查)|故障诊断|诊断.*故障|根因|原因分析|关联路径|图谱|历史.*(?:处理|处置|故障|缺陷|关联)|(?:类似|相似).*(?:维修|缺陷|故障)|(?:设备|工序|缺陷).*关联");
    // A corpus-wide semantic search cannot enforce an arbitrary row predicate. Unknown words or
    // identifiers may name a customer, order or shift. Only positively recognized global topics qualify.
    private static final Pattern GLOBAL_EXPERIENCE_TERMS=Pattern.compile(
        "请问|请帮我|帮忙|帮我|请|一下|有哪些|哪些|有没有|是否有|什么|为什么|怎么样|怎么|怎样|如何|能否|能不能|可以|可能|一下|"
        +"全厂|全局|工厂|本厂|厂内|企业|历史上|历史|历次|过往|以前|曾经|以往|典型|常见|类似|相似|相关|过去|结合|根据|查找|查一查|检索|查看|查询|参考|给出|列出|分析|总结|建议|"
        +"排查|维修|维护|检修|修复|处置|处理|保养|措施|经验|案例|故障|停机|原因|根因|诊断|质量|设备|异常|报警|工序|缺陷|质检|生产|记录|图谱|知识|关联|关系|路径|证据|流程|方案|步骤|方法|"
        +"综合|关联性|对照|比对|检查|追溯|复核|还原|诊断|频繁|反复|重复|相同|类似于|涉及|推荐|还需|进一步|历史数据|线索|详情|模式|原因是什么|"
        +"专业知识|操作规范|公开资料|公开文献|公开手册|公开标准|技术手册|说明书|论文|原文|规范|标准|文献|手册|资料|"
        +"操作|发生|出现|显示器|显示屏|面板|色斑|划痕|亮点|暗点|漏光|坏点|黑屏|屏幕|检测|不良|问题|以及|同时|之间|有关|有过|有没有|有|的|和|与|跟|并|或|要|我|看|查|做|是|吗|呢|了|过|[，。？、；：！\\s,.?!]+|(?i:LCD|MURA|Qdrant|Neo4j|MySQL)");

    private CompletableFuture<Void> experienceLookup(Run run,Task task,String role) {
        if(intents(run).stream().anyMatch(Set.of("PROCESS_ROUTE","INSPECTION_TRACE","GENERAL")::contains))return null;
        if(!Set.of("QUALITY","EQUIPMENT").contains(role)||!EXPERIENCE_REQUEST.matcher(run.query).find())return null;
        boolean knowledgeOnly=intents(run).stream().allMatch(METHODOLOGY::contains);
        if(knowledgeOnly&&!Pattern.compile("本厂|厂内|全厂|历史|维修经验|维修案例").matcher(run.query).find())return null;
        String domain=role.equals("EQUIPMENT") || knowledgeOnly&&run.query.matches(".*(?:设备|维修|故障|维护).*")?"MAINTENANCE":"QUALITY";
        boolean both=run.required.contains("QUALITY") && run.required.contains("EQUIPMENT")
            && intents(run).stream().anyMatch(Set.of("EQUIPMENT","MAINTENANCE","ALARMS")::contains) && intents(run).contains("QUALITY");
        if(both&&role.equals("QUALITY"))return null;
        final String lookupDomain=both?"DIAGNOSTIC":domain;
        boolean global=!run.cohortQuality() && run.policy.adminRole() && run.policy.allows(domain) && (!both||run.policy.allows("QUALITY")&&run.policy.allows("MAINTENANCE")) && run.order==null && run.workOrder==null
            && run.dateRange==null && run.selectors.isEmpty() && run.criteria.values().stream().allMatch("ALL"::equals)
            && run.plan.get("keyword")==null && "CURRENT".equals(run.plan.get("period"))
            && (!previouslyNarrowed(run)||Pattern.compile("全厂|全局").matcher(run.query).find())
            && GLOBAL_EXPERIENCE_TERMS.matcher(run.query).replaceAll("").isEmpty();
        if(!global) {
            synchronized(this) {
                task.experience=object("status","SCOPE_NOT_COVERED","domain",lookupDomain,"hits",List.of(),"graph_paths",List.of());
                task.started.put("experienceEvidence",task.experience);
            }
            return null;
        }
        return CompletableFuture.runAsync(()->{
            synchronized(this) {
                require(run.ref);
                if(task.failed)return;
                step(run,task,"EXPERIENCE_RETRIEVAL","查询 Qdrant 历史案例与 Neo4j 路径","RUNNING","正在核对历史案例与来源记录");
            }
            Map<String,Object> result;
            try {result=research.retrieveExperience(run.query,lookupDomain);}
            catch(RuntimeException failure) {result=object("status","UNAVAILABLE","hits",List.of(),"graph_paths",List.of());}
            synchronized(this) {
                require(run.ref);
                if(task.failed)return;
                task.experience=new LinkedHashMap<>(result);task.experience.put("domain",lookupDomain);
                task.started.put("experienceEvidence",task.experience);
                boolean ok=Set.of("MATCHES","NO_MATCHES").contains(result.get("status"));
                int hits=result.get("hits") instanceof List<?> h?h.size():0;
                int paths=result.get("graph_paths") instanceof List<?> p?p.size():0;
                step(run,task,"EXPERIENCE_RETRIEVAL","查询 Qdrant 历史案例与 Neo4j 路径",ok?"COMPLETED":"FAILED",
                    ok?"已按原始记录去重并复核 "+hits+" 条；图谱路径 "+paths+" 条"+("UNAVAILABLE_OR_STALE".equals(result.get("graph_status"))?"，图谱未能核验":""):"历史检索暂不可用，保留本次业务查询依据");
            }
        });
    }

    private static boolean previouslyNarrowed(Run run) {
        if(run.previous==null||!(run.previous.get("scope") instanceof Map<?,?> prior))return false;
        if(!Boolean.TRUE.equals(prior.get("confirmed")))return true;
        return List.of("orderPk","orderNo","workOrderPk","workOrderNo","keyword","startInclusive","endExclusive").stream().anyMatch(key->prior.get(key)!=null)
            || !"CURRENT".equals(prior.get("period")) || prior.get("selectors") instanceof Map<?,?> selectors&&!selectors.isEmpty()
            || prior.get("criteria") instanceof Map<?,?> criteria&&criteria.values().stream().anyMatch(v->!"ALL".equals(v));
    }

    /** Emit only actual tool starts and returned evidence; never replay or fabricate progress. */
    private Map<String,Object> specialEvidence(Data d,Run run,String intent,Task task) {
        Map<String,Object> facts=intent.equals("PROCESS_ROUTE")
            ?ProcessRouteRead.read(d,(String)run.plan.get("keyword"))
            :InspectionTrace.read(d,run.inspectionNo,(tool,read)->{
                if(task==null)return read.get();
                synchronized(this){step(run,task,tool,toolLabel(tool),"RUNNING","按检验单 "+run.inspectionNo+" 的已核验关联读取");}
                var result=read.get();
                long count=result.values().stream().filter(List.class::isInstance).mapToLong(v->((List<?>)v).size()).sum();
                String status=String.valueOf(result.getOrDefault("graphStatus",""));
                String detail=switch(status) {
                    case "NO_SNAPSHOT" -> "该工单没有已授权业务图快照";
                    case "UNAVAILABLE" -> "业务图未能核验，保留实时业务记录";
                    case "VERIFIED" -> "已核验 "+count+" 条检验路径";
                    case "" -> "按上述检验单范围返回 "+count+" 条关联记录";
                    default -> "业务图状态："+status+"，已核验路径 "+count+" 条";
                };
                if(result.get("visionStatus") instanceof String vision)detail=switch(vision){
                    case "PARTIAL" -> "归档不完整，本单已核验 "+count+" 条检测记录";
                    case "NO_USABLE_RECORD" -> "归档中没有符合本单绑定条件的真实检测记录";
                    case "SOURCE_RECORD_MISSING" -> "本检验单没有绑定的原始视觉记录";
                    default -> "本单已核验 "+count+" 条检测记录";
                };
                synchronized(this){step(run,task,tool,toolLabel(tool),status.equals("UNAVAILABLE")?"FAILED":"COMPLETED",detail);}
                return result;
            });
        return object("queryRef",UUID.randomUUID().toString(),"kind",intent,"scopeRef",run.scopeRef,
            "sourceMode",SOURCE,"status","PARTIAL","data",facts,"missing",List.of());
    }

    private Map<String,Object> readTool(Run run,Task task,String tool,java.util.function.Supplier<Map<String,Object>> read) {
        synchronized(this) {
            require(run.ref);
            String detail=run.comparison.isEmpty()?"查询范围："+(run.cohortQuality()?run.cohortDescription():run.workOrder!=null?"工单 "+run.workOrderNo:run.order==null?"当前账号授权记录":"订单 "+run.order)+"；时间："+(run.dateRange==null?run.plan.get("period"):run.dateRange.start()+" 至 "+run.dateRange.endExclusive().minusDays(1))
                :"核对批号 "+run.selectors.get("batch_no")+" 的两个检验期间："+String.join(" 与 ",run.comparison.stream().map(p->p.start()+" 至 "+p.endExclusive().minusDays(1)).toList());
            step(run,task,tool,toolLabel(tool),"RUNNING",detail);
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
            case "PROCESS_ROUTE" -> "读取启用工艺路线和工序顺序";
            case "INSPECTION_TRACE" -> "追溯指定检验单";
            case "INSPECTION_HEADER" -> "检索检验单并核对判定与抽检数量";
            case "INSPECTION_DETAILS" -> "核对检验项和已登记缺陷";
            case "INSPECTION_VISION" -> "核验本检验单绑定的 YOLO 图片与检测结果";
            case "INSPECTION_HISTORY" -> "对照此前其他批次的同类缺陷及处置";
            case "INSPECTION_BATCH" -> "核对物料与同批检验记录";
            case "INSPECTION_PRODUCTION" -> "沿工单查询报工、派工和工序";
            case "INSPECTION_EQUIPMENT" -> "核查生产记录关联的设备及异常";
            case "INSPECTION_GRAPH" -> "核验业务图快照和检验路径";
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

    private static String sopQuestion(Run run) {
        var record=run.evidence.get("INSPECTION_TRACE");
        return record==null ? run.query : BusinessReadIntent.inspectionResearchQuestion(data(record))+" "+run.query;
    }

    /** Network I/O deliberately outside both runtime and HTTP routing locks so progress/cancel stays responsive. */
    public Map<String,Object> research(String ref,String role) {
        Run run;Task task;List<Map<String,Object>> evidence;String knowledgeQuestion,researchLabel;boolean explicitGraph;
        synchronized(this) {
            run=require(ref);if(!Set.of("QUALITY","AFTERSALES").contains(role)||!run.required.contains(role))throw error(403,"AGENT_NOT_REQUIRED");
            task=run.tasks.get(role);if(task==null)throw error(409,"TASK_NOT_BEGUN");
            if(task.reading||task.failed)throw error(409,"BUSINESS_READ_INCOMPLETE");
            if(task.knowledge!=null||task.sop!=null&&!task.researchRunning)return task.started;
            if(task.researchRunning||task.receipt!=null||run.result!=null)throw error(409,"RESEARCH_ALREADY_STARTED");
            if(intents(run).contains("GENERAL"))return task.started;
            explicitGraph=intents(run).contains("GRAPH_KNOWLEDGE");
            boolean planned=explicitGraph||intents(run).contains("KNOWLEDGE");
            knowledgeQuestion=planned?run.query:null;
            @SuppressWarnings("unchecked") var records=(List<Map<String,Object>>)task.started.get("evidence");evidence=records;
            boolean locatedAnomaly=run.workOrder!=null && "BATCH".equals(WorkOrderReference.focus(run.query))
                && records.stream().anyMatch(record->record.get("data") instanceof Map<?,?> facts
                    && facts.get("overview") instanceof Map<?,?> overview && overview.get("rows") instanceof List<?> rows
                    && rows.stream().anyMatch(row->row instanceof Map<?,?> r && "异常线索".equals(r.get("trace_status"))));
            if(intents(run).contains("INSPECTION_TRACE")){
                var trace=records.stream().filter(r->"INSPECTION_TRACE".equals(r.get("kind"))).findFirst().map(NaturalLanguageRuntime::data).orElse(Map.of());
                if(!"FOUND".equals(trace.get("status")) || !planned && !BusinessReadIntent.inspectionDiagnosis(run.query))return task.started;
                knowledgeQuestion=BusinessReadIntent.inspectionResearchQuestion(trace);explicitGraph=true;
            } else if(!planned && !locatedAnomaly && !Pattern.compile("分析|问题|原因|诊断|排查|方法|规范|经验|案例|建议|追溯|图谱|关联").matcher(run.query).find())return task.started;
            researchLabel=knowledgeQuestion!=null&&!explicitGraph?"检索 Qdrant 公开资料":"检索公开 RAG / GraphRAG";
            task.researchRunning=true;step(run,task,"SOP_LOOKUP","核对已批准的企业 SOP","RUNNING","按账号权限、有效版本及适用范围核对");
        }
        var sop=ApprovedSopStore.inspect(sopQuestion(run),run.policy,LocalDate.now(clock.withZone(ZONE)));
        synchronized(this) {
            require(ref);
            task.sop=sop;task.started.put("approvedSopEvidence",sop);
            boolean matched="MATCHES".equals(sop.get("status"));
            step(run,task,"SOP_LOOKUP","核对已批准的企业 SOP","UNAVAILABLE".equals(sop.get("status"))?"FAILED":"COMPLETED",
                matched?"已找到有权引用的有效文件段落":"未取得可引用的企业 SOP 段落");
            if(matched&&intents(run).equals(List.of("KNOWLEDGE"))){
                task.researchRunning=false;
                return task.started;
            }
            step(run,task,"KNOWLEDGE_RETRIEVAL",researchLabel,"RUNNING","正在检索公开资料");
        }
        Map<String,Object> bundle;
        try {bundle=knowledgeQuestion==null?research.retrieve(role,evidence):research.retrievePlanned(knowledgeQuestion,explicitGraph);}catch(Exception unavailable){bundle=object("status","UNAVAILABLE","hits",List.of(),"graph_paths",List.of(),"limitations",List.of("知识服务不可用，保留业务证据"));}
        synchronized(this) {
            require(ref);if(run.result!=null)throw error(409,"RUN_FINALIZED");
            task.knowledge=new LinkedHashMap<>(bundle);task.researchRunning=false;task.started.put("knowledgeEvidence",task.knowledge);
            String status=String.valueOf(bundle.get("status"));
            int hits=bundle.get("hits") instanceof List<?> list?list.size():0;int paths=bundle.get("graph_paths") instanceof List<?> list?list.size():0;
            boolean retrieved=Set.of("MATCHES","NO_MATCHES").contains(status);
            step(run,task,"KNOWLEDGE_RETRIEVAL",researchLabel,retrieved?"COMPLETED":"FAILED",
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
            if(task.sop!=null&&task.sop.get("hits") instanceof List<?> hits)for(Object value:hits)if(value instanceof Map<?,?> hit&&hit.get("evidence_ref") instanceof String id)knowledge.add(id);
            if(task.experience!=null&&task.experience.get("hits") instanceof List<?> hits)for(Object value:hits)if(value instanceof Map<?,?> hit&&hit.get("evidence_ref") instanceof String id)knowledge.add(id);
            task.analysis=GroundedAnalysis.parse(intents(run).stream().allMatch(METHODOLOGY::contains)?"KNOWLEDGE":role,summary,business,knowledge);
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
                if(Set.of("PROCESS_ROUTE","INSPECTION_TRACE").contains(entry.getKey())){
                    if(!data(evidence).equals(data(specialEvidence(d,run,entry.getKey(),null))))changed[0]=true;
                    facts.put(entry.getKey().toLowerCase(Locale.ROOT),data(evidence));
                }else if(entry.getKey().startsWith("OVERVIEW_")) {
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
        if(run.tasks.values().stream().anyMatch(task->task.sop!=null&&!task.sop.equals(
                ApprovedSopStore.inspect(sopQuestion(run),run.policy,LocalDate.now(clock.withZone(ZONE))))))changed[0]=true;
        boolean unresolved=missing.stream().anyMatch(issue->issue.startsWith("CONTRADICTORY_")||issue.startsWith("CONFLICTING_")||issue.startsWith("UNRESOLVED_")||issue.startsWith("UNVERIFIED_")||issue.contains("TRUNCATED_")||issue.startsWith("SOURCE_RECORD_LIMIT_"));
        String verdict=!action.equals("QUERY")?action:changed[0]?"DATA_CHANGED":!absent.isEmpty()?"INCOMPLETE":usesDelivery(run)||unresolved?"INSUFFICIENT_EVIDENCE":"EVIDENCE_READY";
        if(changed[0]){facts.clear();missing.add("REFRESH_RUN_REQUIRED");}
        var explanations=changed[0]?List.of():run.tasks.entrySet().stream().filter(e->e.getValue().receipt!=null)
            .map(e->object("agentId",e.getKey(),"resultRef",e.getValue().receipt.get("resultRef"),"authority","UNVERIFIED_MODEL_EXPLANATION","text",e.getValue().receipt.get("modelCommentary"))).toList();
        Map<String,Object> result=object("runRef",run.ref,"queryMode","NATURAL","workflowProfile","AUTO","sourceMode",SOURCE,
            "verdict",verdict,"facts",facts,"scope",scope(run),"plan",run.plan,"intents",intents(run),"userQuery",run.userQuery,
            "executedAgents",List.copyOf(run.tasks.keySet()),"missing",List.copyOf(missing),"checkedAt",clock.instant().toString(),
            "queryRefs",run.evidence.values().stream().map(e->e.get("queryRef")).toList(),
            "resultRefs",run.tasks.values().stream().filter(t->t.receipt!=null).map(t->t.receipt.get("resultRef")).toList(),
            "expertExplanations",explanations,"knowledgeExplanations",List.of(),"revalidation",revalidation.get(),"reply",reply(run,verdict,facts));
        result.put("analyses",changed[0]?List.of():run.tasks.values().stream().filter(t->t.analysis!=null).map(t->t.analysis).toList());
        result.put("approvedSopEvidence",changed[0]?List.of():run.tasks.values().stream().filter(t->t.sop!=null).map(t->t.sop).toList());
        result.put("experienceEvidence",changed[0]?List.of():run.tasks.entrySet().stream().filter(e->e.getValue().experience!=null)
            .map(e->{var item=new LinkedHashMap<>(e.getValue().experience);item.put("agentId",e.getKey());return item;}).toList());
        result.put("knowledgeEvidence",changed[0]?List.of():run.tasks.entrySet().stream().filter(e->e.getValue().knowledge!=null).map(e->{var bundle=new LinkedHashMap<String,Object>(e.getValue().knowledge);bundle.put("agentId",e.getKey());return bundle;}).toList());
        if(intents(run).equals(List.of("GENERAL")) && "EVIDENCE_READY".equals(verdict) && run.previous!=null
                && run.previous.get("generalAnswer") instanceof String priorAnswer && run.previous.get("userQuery") instanceof String priorQuery) {
            result.put("previousGeneralTurn",Map.of("userQuery",priorQuery,"generalAnswer",priorAnswer));
        }
        if(changed[0]){
            result.put("queryRefs",List.of());result.put("resultRefs",List.of());
            run.workflowInvalidated=true;run.workflowPlan=null;run.workflowEvents.clear();run.workflowTruncated=false;
        }
        run.last=result;if(absent.isEmpty()||changed[0]) {
            run.result=result;
            if(Set.of("EVIDENCE_READY","INSUFFICIENT_EVIDENCE").contains(verdict)) {
                run.displayedOrders=run.qualityOrders.isEmpty()?DisplayedWorkOrders.fromFacts(facts,"order","order_no"):run.qualityOrders;
                run.displayedWorkOrders=run.qualityWorkOrders.isEmpty()?DisplayedWorkOrders.fromFacts(facts):run.qualityWorkOrders;
                run.displayedInspections=InspectionSelection.displayed(facts);
                var shown=run.selection==null?BusinessSelection.displayed(facts):BusinessSelection.displayed(
                    Map.of(run.selection.domain().toLowerCase(Locale.ROOT),facts.get(run.selection.domain().toLowerCase(Locale.ROOT))));
                run.displayedBusiness=shown==null?null:shown.stored();
            }
            Map<String,Object> retainedScope=new LinkedHashMap<>(scope(run));
            retainedScope.remove("scopeRef");retainedScope.remove("expiresAt");
            if(!Set.of("EVIDENCE_READY","INSUFFICIENT_EVIDENCE").contains(verdict))retainedScope=object("confirmed",false);
            else if(run.dateRange==null&&!"CURRENT".equals(run.plan.get("period"))) {
                LocalDate day=run.referenceDay;
                LocalDate from=switch((String)run.plan.get("period")){case "TODAY"->day;case "LAST_7_DAYS"->day.minusDays(6);default->day.withDayOfMonth(1);};
                retainedScope.put("startInclusive",from+" 00:00:00");retainedScope.put("endExclusive",day.plusDays(1)+" 00:00:00");
            }
            if(Set.of("EVIDENCE_READY","INSUFFICIENT_EVIDENCE").contains(verdict)&&!facts.isEmpty())
                run.completedRead=ReadReplay.capture(run.query,run.sourcePlanInput,run.previous,run.referenceDay,scope(run));
            contextStore.save(run.ref,run.owner,object("userQuery",run.userQuery,"plan",run.plan,"scope",retainedScope,"businessSelection",run.displayedBusiness,"displayedInspections",run.displayedInspections,"displayedOrders",run.displayedOrders,"displayedWorkOrders",run.displayedWorkOrders,"readReplay",run.completedRead,
                "completedGeneral","EVIDENCE_READY".equals(verdict)&&intents(run).equals(List.of("GENERAL")),
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
        String period=(String)run.plan.get("period");LocalDate today=run.referenceDay;
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
    private List<Map<String,Object>> selectionRows(Data data,Run run,String from,String to){
        var selection=run.selection;data.policy.require(selection.domain());
        if(selection.ids().isEmpty())return List.of();
        return BusinessQueryFilter.applyCriterion(run.criteria.getOrDefault(selection.domain(),"ALL"),
                data.overview.selectedRows(selection.domain(),selection.ids(),data.policy.customer(),data.policy.operator(),run.selectors,(String)run.plan.get("keyword"),from,to));
    }
    private List<Map<String,Object>> selectedWorks(Data data,Run run,List<Map<String,Object>> selected){
        data.policy.require("PRODUCTION");
        if(run.selection.domain().equals("PRODUCTION")||selected.isEmpty())return selected;
        if(!run.selection.domain().equals("ORDER")||data.policy.customer()!=null||data.policy.operator()!=null)throw error(403,"SCOPE_FORBIDDEN");
        var ids=selected.stream().map(r->Long.valueOf(r.get("record_id").toString())).toList();
        var workIds=data.overview.resourceWorks(ids).stream().filter(w->"LINKED".equals(w.get("mapping_status")))
                .map(w->Long.valueOf(w.get("work_order_id").toString())).distinct().toList();
        return workIds.isEmpty()?List.of():data.overview.selectedRows("PRODUCTION",workIds,null,null,Map.of(),null,null,null);
    }
    private Map<String,Object> overview(Data data,Run run,String domain) {
        if(run.policy.adminRole()&&run.order!=null&&DOMAIN_EVIDENCE.containsKey(domain))throw new IllegalStateException("Exact-order domains require registered detailed evidence");
        if(domain.equals("QUALITY")&&!run.comparison.isEmpty())
            return object("queryRef",UUID.randomUUID().toString(),"kind","OVERVIEW_QUALITY","scopeRef",run.scopeRef,"status","PARTIAL",
                "sourceMode",SOURCE,"data",BatchQualityComparison.read(data,run.selectors.get("batch_no"),run.comparison),"missing",List.of());
        String period=(String)run.plan.get("period"),keyword=(String)run.plan.get("keyword");
        LocalDate today=run.referenceDay;
        LocalDate start=switch(period){case "TODAY"->today;case "LAST_7_DAYS"->today.minusDays(6);case "THIS_MONTH"->today.withDayOfMonth(1);default->null;};
        String from=run.dateRange!=null?run.dateRange.fromTimestamp():start==null?null:start+" 00:00:00",to=run.dateRange!=null?run.dateRange.toTimestamp():start==null?null:today.plusDays(1)+" 00:00:00";
        Long id=run.order==null?null:Long.valueOf(run.order);
        data.policy.require(domain);
        var domainSelectors=InventoryCategory.forDomain(domain,run.selectors);
        BusinessToolRegistry.Result queried;
        if(run.selection!=null){
            var selected=selectionRows(data,run,from,to);
            if(run.selectedMaterial&&domain.equals("INVENTORY")){
                var coverage=WorkMaterialCoverage.read(data.overview,selectedWorks(data,run,selected),run.referenceDay);
                return object("queryRef",UUID.randomUUID().toString(),"kind","OVERVIEW_INVENTORY","scopeRef",run.scopeRef,"status","PARTIAL","sourceMode",SOURCE,"data",coverage,"missing",List.of());
            }
            if(domain.equals("PRODUCTION")&&!run.selection.domain().equals(domain))selected=selectedWorks(data,run,selected);
            queried=new BusinessToolRegistry.Result(selected.size(),selected);
        } else if(run.inspectionOperation!=null&&domain.equals("QUALITY")) {
            if(data.policy.customer()!=null||data.policy.operator()!=null)throw error(403,"SCOPE_FORBIDDEN");
            var selected=run.inspectionSelection.isEmpty()?List.<Map<String,Object>>of():
                    data.overview.displayedInspections(run.inspectionSelection,data.policy.customer(),data.policy.operator());
            queried=new BusinessToolRegistry.Result(selected.size(),selected);
        } else if(run.cohortQuality()&&domain.equals("QUALITY")) {
            data.policy.require("PRODUCTION");
            if(data.policy.customer()!=null||data.policy.operator()!=null)throw error(403,"SCOPE_FORBIDDEN");
            boolean salesOrders=!run.qualityOrders.isEmpty();
            if(salesOrders)data.policy.require("ORDER");
            var selected=data.overview.inspectionsForWorkOrders(salesOrders?run.qualityOrders:run.qualityWorkOrders,salesOrders);
            queried=new BusinessToolRegistry.Result(selected.size(),selected);
        } else if(run.workOrder!=null&&domain.equals("PRODUCTION")) {
            var selected=data.overview.workOrderRows(Long.parseLong(run.workOrder),data.policy.operator(),from,to);
            queried=new BusinessToolRegistry.Result(selected.size(),selected);
        } else if(run.workOrder!=null&&domain.equals("QUALITY")){
            data.policy.require("PRODUCTION");data.policy.require("DISPATCH");
            var selected=data.overview.batchInspections(Long.parseLong(run.workOrder));
            queried=new BusinessToolRegistry.Result(selected.size(),selected);
        } else queried=BusinessToolRegistry.execute(data.overview,new BusinessToolRegistry.Request(domain,id,keyword,from,to,domainSelectors),data.policy);
        var filtered=BusinessQueryFilter.applyCriterion(run.criteria.getOrDefault(domain,"ALL"),queried.rows());
        long total=queried.total()==queried.rows().size()?filtered.size():queried.total();
        var rows=filtered.stream().map(row->{Map<String,Object> out=new LinkedHashMap<>();row.forEach((k,v)->{if(!k.equals("search_text"))out.put(k,v==null?null:String.valueOf(v));});return out;}).toList();
        String dateField=switch(domain){case "QUALITY"->"inspected_at";case "ORDER"->"order_date";case "AFTERSALES"->"opened_at";case "MAINTENANCE"->"start_time";case "ALARMS"->"reported_at";case "STOCK_MOVEMENT"->"handled_at";default->"updated_at";};
        var overview=object("domain",domain,"rows",rows,"totalRecords",total,"returnedRecords",rows.size(),"truncated",total>rows.size(),"limit",null,
            "period",run.dateRange==null?period:"CUSTOM","dateField",dateField,"startInclusive",from,"endExclusive",to,"timeZone","Asia/Shanghai","keyword",keyword,
            "selectors",domainSelectors,"filter",run.criteria.getOrDefault(domain,"ALL"),"countBasis","MATCHING_DATABASE_RECORDS","quantityBasis","PER_RECORD_PER_MATERIAL_UNIT; NO_CROSS_MATERIAL_TOTAL; CURRENT_RECORDS_NOT_HISTORICAL_SNAPSHOTS");
        if(domainSelectors.containsKey(InventoryCategory.KEY))
            overview.put("inventoryCategory",InventoryCategory.fromTypes(domainSelectors.get(InventoryCategory.KEY)).label);
        var facts=object("overview",overview);
        if(run.selection!=null){
            overview.put("selectedDomain",run.selection.domain());
            overview.put("countBasis","CURRENT_ROWS_OF_PREVIOUSLY_DISPLAYED_RECORDS");
            if(run.selectedDispatch&&domain.equals("PRODUCTION")){
                data.policy.require("DISPATCH");
                var ids=rows.stream().map(w->Long.valueOf(w.get("record_id").toString())).toList();
                var assignments=ids.isEmpty()?List.<Map<String,Object>>of():data.overview.selectedDispatches(ids,data.policy.operator());
                facts.put("dispatchRows",assignments);
                var assigned=assignments.stream().map(a->String.valueOf(a.get("work_order_id"))).collect(java.util.stream.Collectors.toSet());
                var missing=rows.stream().filter(w->!assigned.contains(String.valueOf(w.get("record_id")))).map(w->String.valueOf(w.get("work_order_no"))).toList();
                facts.put("traceConclusion","所选范围关联 "+ids.size()+" 张可见工单、"+assignments.size()+" 条可见派工记录。"+(missing.isEmpty()?"":"未查到派工记录："+String.join("、",missing)+"。"));
            }
        }
        if(run.inspectionOperation!=null) {
            overview.put("countBasis","CURRENT_ROWS_OF_PREVIOUSLY_DISPLAYED_INSPECTIONS");
            String conclusion=InspectionSelection.conclusion(run.inspectionOperation,rows,data.overview);
            if(queried.total()!=run.inspectionSelection.size()) conclusion+=" 上轮有部分单据当前未能查到。";
            facts.put("traceConclusion",conclusion);
        }
        if(run.cohortQuality()&&domain.equals("QUALITY"))
            facts.put("traceConclusion","按"+run.cohortDescription()+"关联到 "+rows.size()+" 条检验记录。缺少检验记录的工单不能据此判为合格。");
        if(run.workOrder!=null&&domain.equals("PRODUCTION")&&!rows.isEmpty()) {
            data.policy.require("DISPATCH");
            var dispatchRows=data.overview.workOrderDispatch(Long.parseLong(run.workOrder),data.policy.operator()).stream().map(row->{Map<String,Object> out=new LinkedHashMap<>();row.forEach((k,v)->out.put(k,v==null?null:String.valueOf(v)));return out;}).toList();
            facts.put("dispatchRows",dispatchRows);
            facts.put("dispatchScope",data.policy.operator()==null?"ALL_RECORDED_DISPATCHES":"OWN_DISPATCHES_ONLY");
            facts.put("dispatchConclusion",dispatchRows.isEmpty()?"当前授权范围内未查到派工记录。":"已查到派工记录，工序、人员和执行状态见派工明细；存在记录不代表已全部完成。");
        }
        if(run.workOrder!=null&&domain.equals("PRODUCTION")&&"STEPS".equals(WorkOrderReference.focus(run.query))){
            data.policy.require("DISPATCH");
            var steps=data.overview.stepCoverage(Long.parseLong(run.workOrder),data.policy.operator());
            facts.put("stepCoverage",steps);
            var open=steps.stream().filter(r->"有未完派工".equals(r.get("trace_status"))).map(r->String.valueOf(r.get("step_name"))).toList();
            boolean mismatch=steps.stream().anyMatch(r->"实际派工与当前路线不一致".equals(r.get("step_origin")));
            facts.put("traceConclusion",steps.isEmpty()?"没有可核验的授权工序记录。":("当前可见记录中"+(open.isEmpty()?"没有状态仍在执行的派工":"有未完派工的工序："+String.join("、",open))+"。无派工记录不能据此断言未开工。"+(mismatch?"实际派工与当前路线不一致，请核对路线版本。":"")));
        }
        if(run.workOrder!=null&&domain.equals("QUALITY")){
            String conclusion=rows.isEmpty()?"没有可核验的工单检验批次，不能判断同批有无异常。":
                "按本工单检验单的物料、批号及检验类别匹配；"+rows.size()+" 条可见记录，"+rows.stream().filter(r->"异常线索".equals(r.get("trace_status"))).count()+" 条异常线索。未记录的检验不在结论内；抽检不合格数不代表整批不合格。";
            facts.put("traceConclusion",conclusion);
            facts.put("businessGraph",WorkTraceGraph.read(data,Long.parseLong(run.workOrder)));
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
        if(domain.equals("QUALITY")){
            facts.putAll(com.upc.computer.assistant.v2.vision.QualityEvidenceAnalysis.overview(rows,total<=rows.size()));
            boolean fullVerdicts="ALL".equals(run.criteria.getOrDefault(domain,"ALL"));
            boolean analytic=run.inspectionOperation==null&&QualityOverviewAnswer.analytical(run.query);
            var statisticsScope=!analytic||run.selection!=null||run.cohortQuality()||run.workOrder!=null?null:
                    new BusinessToolRegistry.Request("QUALITY",id,keyword,from,to,domainSelectors);
            var statistics=QualityOverviewStatistics.read(data,rows,total==rows.size(),statisticsScope,fullVerdicts,today);
            @SuppressWarnings("unchecked") var metrics=(Map<String,Object>)facts.get("qualityMetrics");
            QualityOverviewStatistics.alignLegacyMetrics(metrics,rows,statistics);
            facts.put("qualityDetailScope","CURRENT_INSPECTION_HEADS_AND_SCOPE_MATCHED_DEFECT_REGISTRATION_COUNTS");
            facts.put("qualityStatistics",statistics);
            var trend=QualityTrendMetrics.summarize(rows,total==rows.size()&&fullVerdicts);
            facts.put("qualityTrend",trend);
            facts.put("presentationMode",analytic?"ANALYSIS":"LIST");
            if(analytic)facts.put("analysisConclusion",QualityOverviewAnswer.describe(overview,statistics,trend,run.query));
            if(run.inspectionOperation==InspectionSelection.Operation.SET_TRIAGE && !rows.isEmpty()){
                facts.put("qualityDetailScope","SELECTED_INSPECTION_ITEMS_AND_LINKED_DEFECTS_READ_FOR_TRIAGE");
                metrics.put("detailStatus","TRIAGE_DETAILS_CHECKED_LINKED_DEFECT_REGISTRATIONS_COUNTED");
            }
        }
        if(domain.equals("QUALITY")&&"LAST_7_DAYS".equals(period)&&run.dateRange==null)
            facts.put("traceConclusion","按检验时间统计，最近七个自然日（"+start+" 至 "+today+"，上海时区）可见 "+total+" 条检验记录。"+(total==0?"本时段缺少检验样本，无法判断质量状况。":""));
        if(domain.equals("ALARMS")&&Pattern.compile("趋势|按日|每日|每天").matcher(run.query).find())
            facts.put("alarmTrend",AlarmTrendMetrics.summarize(rows,total==rows.size()));
        return object("queryRef",UUID.randomUUID().toString(),"kind","OVERVIEW_"+domain,"scopeRef",run.scopeRef,"status","PARTIAL","sourceMode",SOURCE,
            "data",facts,"missing",total>rows.size()?List.of("DETAILS_TRUNCATED"):List.of());
    }
    private String reply(Run run,String verdict,Map<String,Object> facts) {
        if(!run.plan.get("action").equals("QUERY"))return (String)run.plan.get("question");
        if(verdict.equals("DATA_CHANGED"))return "查询期间业务记录已变化，请重新查询以获取一致的事实。";
        if(run.inspectionOperation!=null && verdict.equals("EVIDENCE_READY") && facts.get("quality") instanceof Map<?,?> quality
                && quality.get("traceConclusion") instanceof String conclusion)return conclusion;
        if(verdict.equals("INCOMPLETE"))return "查询尚未完成，仍有业务专家未返回结果。";
        List<String> parts=new ArrayList<>();
        if(intents(run).equals(List.of("GENERAL")))return "根据用户提供的内容和一般知识作答。";
        for(String tool:List.of("process_route","inspection_trace"))
            if(facts.get(tool) instanceof Map<?,?> found && found.get("summary") instanceof String summary)parts.add(summary);
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
            if("EVIDENCE_READY".equals(verdict)&&"ANALYSIS".equals(fact.get("presentationMode"))&&fact.get("analysisConclusion") instanceof String conclusion){
                if(fact.get("traceConclusion") instanceof String trace&&!trace.equals(conclusion)&&!trace.startsWith("按检验时间统计"))parts.add(trace);
                parts.add(conclusion);
                continue;
            }
            if(fact.get("traceConclusion") instanceof String trace)parts.add(trace);
            if(fact.get("overview") instanceof Map<?,?> o) {
                String dateLabel=switch(String.valueOf(o.get("dateField"))){case "inspected_at"->"检验时间";case "order_date"->"订单日期";case "opened_at"->"售后单建立时间";default->"记录更新时间";};
                parts.add((o.get("inventoryCategory") instanceof String category?category+"库存":labels.get(String.valueOf(o.get("domain"))))+"：符合范围的记录共"+o.get("totalRecords")+"条，取回"+o.get("returnedRecords")+"条"+(Boolean.TRUE.equals(o.get("truncated"))?"（明细已截断）":"")+"。时间按"+dateLabel+"筛选，属于当前记录，不是历史快照；数量按各物料原单位查看，不跨物料合计。");
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
        if(intents(run).contains("KNOWLEDGE")||intents(run).contains("GRAPH_KNOWLEDGE")){
            boolean sop=run.tasks.values().stream().anyMatch(t->t.sop!=null&&"MATCHES".equals(t.sop.get("status")));
            boolean publicHit=run.tasks.values().stream().anyMatch(t->t.knowledge!=null&&"MATCHES".equals(t.knowledge.get("status")));
            parts.add(sop?"已检索到当前账号可引用的有效企业 SOP，来源与版本见引用。":publicHit?"已取得公开资料，可作专业参考；当前未取得相应企业 SOP。":"当前未取得可引用的企业 SOP 或公开知识。");
        }
        for(var task:run.tasks.values())if(task.experience!=null)parts.add(experienceSummary(task.experience));
        return String.join("\n",parts);
    }
    private static String experienceSummary(Map<String,Object> bundle) {
        if("SCOPE_NOT_COVERED".equals(bundle.get("status")))return "本次权限或筛选条件无法覆盖全厂历史案例，已跳过该检索。";
        if(!Set.of("MATCHES","NO_MATCHES").contains(bundle.get("status")))return "历史经验检索暂不可用，可核验的业务记录仍见本次查询。";
        int stale=bundle.get("stale_record_count") instanceof Number n?n.intValue():0;
        String conflict=stale>0?"与 MySQL 不一致或已撤除的 "+stale+" 条候选记录已排除。":"";
        if(!(bundle.get("hits") instanceof List<?> hits)||hits.isEmpty())return "历史案例索引未返回可核验记录。"+conflict;
        List<String> excerpts=new ArrayList<>();
        for(var item:hits.stream().limit(3).toList())if(item instanceof Map<?,?> hit)
            excerpts.add(hit.get("title")+" — "+hit.get("text")+" ["+("graph".equals(hit.get("retrieval_source"))?"Neo4j":"Qdrant")+" → "+hit.get("source_table")+" #"+hit.get("record_id")+"]");
        if(bundle.get("graph_paths") instanceof List<?> paths&&!paths.isEmpty()&&paths.get(0) instanceof Map<?,?> path)
            excerpts.add("Neo4j 已核对 "+paths.size()+" 条路径，其中一条涉及 "+path.get("entity_label")+"。点击历史依据可查看路径及其记录。");
        return "历史参考（已核对 MySQL 来源）：\n"+String.join("\n",excerpts)
            +("UNAVAILABLE".equals(bundle.get("vector_status"))?"\nQdrant 暂不可用，本次仅使用可核验的图谱记录。":"")
            +("UNAVAILABLE_OR_STALE".equals(bundle.get("graph_status"))?"\nNeo4j 路径暂不可用，关系需另行核实。":"")
            +"\n"+conflict+"当前状态以本次 MySQL 记录为准；相似历史及关联路径仅供核查，不能确认当前故障的根因。";
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
        if(requested==null && unspecifiedRecent(query))requested="LAST_7_DAYS";
        if(requested!=null)return requested.equals(period);
        if(period.equals("CURRENT"))return true;
        return run.previous!=null&&run.previous.get("scope") instanceof Map<?,?> previous&&Boolean.TRUE.equals(previous.get("confirmed"))&&period.equals(previous.get("period"));
    }
    private static final Pattern QUALITY_OVERVIEW=Pattern.compile(
            "(?:请|麻烦)?(?:帮我)?(?:想要|想)?(?:了解|查看|查询|查|看看|看|列出|汇总)?(?:一?下)?"
            +"(?:全部|所有|整体|总体|当前)?(?:的)?(?:质检|质量检验|质量检测|检验)(?:的)?"
            +"(?:情况|概况|概览|记录|结果|单据|单)?(?:如何|怎么样|怎样|有哪些|呢)?[。？！?!]?");

    private static boolean unspecifiedRecent(String query) {
        return Pattern.compile("(?:最近|近期)(?!\\s*[零一二三四五六七八九十0-9])").matcher(query).find();
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
    private static List<Object> availableTools(AssistantReadPolicy policy) {
        List<Object> tools=new ArrayList<>();
        for(var tool:BusinessToolRegistry.catalog())
            if(policy.allows(tool.toolName().split("\\.")[1].toUpperCase(Locale.ROOT)))tools.add(tool);
        var descriptions=Map.of("PROCESS_ROUTE","读取启用工艺路线、实际步骤数及顺序，keyword 可填原文产品/路线名称或编码",
            "INSPECTION_TRACE","从准确检验单号 keyword 出发核对明细、同批记录、工单、工序、设备、异常及授权业务图",
            "GENERAL","根据用户输入与一般知识解释、计算、起草、归纳、翻译和改写，可承接上轮通用回答。无实时或私有数据来源。",
            "DELIVERY","核对单个指定订单的交付、成品与供料风险，需要已核验的订单编号或主键",
            "KNOWLEDGE","用 Qdrant 检索可验证的公开规范、手册与研究片段",
            "GRAPH_KNOWLEDGE","用 Neo4j 关系路径及 Qdrant 原文证据核对方法和缺陷知识");
        for(String intent:List.of("PROCESS_ROUTE","INSPECTION_TRACE","GENERAL","KNOWLEDGE","GRAPH_KNOWLEDGE","DELIVERY"))
            if(policy.allows(intent))tools.add(Map.of("toolName",intent,"description",descriptions.get(intent),"readOrWrite","READ"));
        return tools;
    }
    private Map<String,Object> contextMap(Run run) {
        var tools=availableTools(run.policy);
        var bound=BusinessSelection.previous(run.previous);
        if(bound!=null){
            for(String name:BusinessSelection.INTENTS.stream().sorted().toList()){
                if(name.equals("ROWS_DISPATCH")&&(!run.policy.allows("DISPATCH")||bound.domain().equals("INVENTORY")))continue;
                if(name.equals("ROWS_MATERIAL")&&(!run.policy.allows("INVENTORY")||!run.policy.allows("PRODUCTION")||bound.domain().equals("INVENTORY")))continue;
                tools.add(Map.of("toolName",name,"description","仅针对上轮已展示的 "+bound.domain()+" 记录","readOrWrite","READ"));
            }
        }
        if(run.policy.allows("QUALITY") && run.previous!=null && run.previous.get("displayedInspections") instanceof List<?>)
            for(var op:InspectionSelection.Operation.values())tools.add(Map.of("toolName",op.name(),"description","沿用上轮已展示的检验单集合","readOrWrite","READ"));
        if(run.replay!=null)tools.add(Map.of("toolName","RECAP","description","使用上轮已确认的相同对象、筛选及日期范围，重新读取当前可见数据，以用户要求的方式解读。","readOrWrite","READ"));
        return object("runRef",run.ref,"queryMode","NATURAL","userQuery",run.userQuery,"workflowProfile","AUTO","sourceMode",SOURCE,"requiredAgents",run.required,
            "dependencies",dependencies(run),"currentDate",LocalDate.ofInstant(clock.instant(),ZONE).toString(),"timeZone","Asia/Shanghai","dateTimezone","Asia/Shanghai",
            "previousContext",run.userPrevious,"scope",scope(run),"plan",run.plan,"capabilities",run.policy.publicCapabilities(),"availableTools",tools,
            "availableEvidenceTools",run.policy.adminRole()?EvidenceToolRegistry.catalog():List.of(),"taskReceipts",run.tasks.values().stream().filter(t->t.receipt!=null).map(t->t.receipt).toList());
    }
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
        if(event!=null&&(Set.of("E1","E2","E3","E4","Q1","D1","A1","SOP_LOOKUP","KNOWLEDGE_RETRIEVAL","EXPERIENCE_RETRIEVAL").contains(id)||id.startsWith("INSPECTION_")||id.equals("PROCESS_ROUTE")||id.startsWith("OVERVIEW_")||id.startsWith("RESOURCE_"))) {
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
    private Map<String,Object> scope(Run run) {boolean confirmed=run.plan!=null&&"QUERY".equals(run.plan.get("action"));return object("qualityOrders",run.qualityOrders,"qualityWorkOrders",run.qualityWorkOrders,"comparisonPeriods",confirmed?run.comparison.stream().map(p->Map.of("startInclusive",p.fromTimestamp(),"endExclusive",p.toTimestamp())).toList():List.of(),"inspectionNo",confirmed?run.inspectionNo:null,"workOrderPk",confirmed?run.workOrder:null,"workOrderNo",confirmed?run.workOrderNo:null,"orderPk",confirmed?run.order:null,"orderNo",confirmed?run.orderNo:null,"scopeRef",run.scopeRef,"confirmed",confirmed,"selectors",confirmed?run.selectors:Map.of(),"criteria",confirmed?run.criteria:Map.of(),"expiresAt",run.expires.toString(),"keyword",confirmed?run.plan.get("keyword"):null,"period",confirmed?run.plan.get("period"):"CURRENT","startInclusive",confirmed&&run.dateRange!=null?run.dateRange.fromTimestamp():null,"endExclusive",confirmed&&run.dateRange!=null?run.dateRange.toTimestamp():null);}
    private Run lookup(String ref) {Run run=runs.get(ref);if(run==null||!run.expires.isAfter(clock.instant()))throw error(404,"RUN_UNAVAILABLE");return run;}
    private Run require(String ref) {Run run=lookup(ref);if(run.cancelled)throw error(410,"RUN_UNAVAILABLE");authorize(run);return run;}
    private void authorize(Run run) {access.apply(run.owner,d->{if(!d.policy.fingerprint().equals(run.policy.fingerprint()))throw error(403,"PERMISSION_CHANGED");return Map.of();});}
    private static void requirePlan(Run run) {if(run.plan==null)throw error(409,"PLAN_REQUIRED");}
    private static boolean usesDelivery(Run run) {return run.policy.adminRole()&&run.order!=null&&(intents(run).contains("DELIVERY")||intents(run).contains("PRODUCTION"));}
    @SuppressWarnings("unchecked") private static List<String> intents(Run run) {return run.plan==null?List.of():(List<String>)run.plan.get("intents");}
    private static String role(String intent) {return switch(intent){case "INVENTORY"->"SUPPLY";case "DELIVERY"->"ORDER";case "KNOWLEDGE","GRAPH_KNOWLEDGE","GENERAL","INSPECTION_TRACE"->"QUALITY";case "PROCESS_ROUTE"->"PRODUCTION";case "MAINTENANCE","ALARMS"->"EQUIPMENT";case "STOCK_MOVEMENT"->"SUPPLY";default->intent;};}
    // Other independent roles may register evidence while this role reads its predecessors.
    // Only snapshot the reference under the same monitor used by registration; never hold it over SQL I/O.
    private synchronized String qref(Run run,String kind) {return run.evidence.containsKey(kind)?(String)run.evidence.get(kind).get("queryRef"):null;}
    @SuppressWarnings("unchecked") private static Map<String,Object> data(Map<String,Object> evidence) {return (Map<String,Object>)evidence.get("data");}
    private static String nullable(JsonNode node,String key,int limit) {if(node.get(key)==null)throw error(400,"INVALID_PLAN");return node.get(key).isNull()?null:text(node,key,limit);}
    private static String text(JsonNode node,String key,int limit) {JsonNode value=node.get(key);if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>limit)throw error(400,"INVALID_REQUEST");return value.asText().trim();}
    private static ResponseStatusException error(int code,String reason) {return new ResponseStatusException(HttpStatus.valueOf(code),reason);}
    private static Map<String,Object> object(Object... pairs) {Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;}
    private static final class Task {final Map<String,Object> started;Map<String,Object> receipt,knowledge,experience,analysis,sop;String rawSummary;boolean researchRunning,reading,failed;final Set<String> upstreamBusinessRefs=new HashSet<>();final Map<String,Map<String,Object>> steps=new LinkedHashMap<>();final Map<String,String> toolCalls=new HashMap<>();Task(Map<String,Object> started){this.started=started;}}
    private static final class Run {
        final String ref,userQuery;String query;final long owner;final Instant expires;
        final Map<String,Object> userPrevious;Map<String,Object> previous;ReadReplay replay,completedRead;LocalDate referenceDay;JsonNode sourcePlanInput;
        AssistantReadPolicy policy;BusinessDateRange dateRange;List<BusinessDateRange> comparison=List.of();Map<String,String> selectors=Map.of(),criteria=Map.of();
        List<Long> displayedWorkOrders=List.of(),qualityWorkOrders=List.of(),displayedOrders=List.of(),qualityOrders=List.of();
        Map<String,Object> displayedBusiness; BusinessSelection selection; boolean selectedDispatch,selectedMaterial;
        List<Long> displayedInspections,inspectionSelection; InspectionSelection.Operation inspectionOperation;
        boolean cohortQuality(){return !qualityWorkOrders.isEmpty()||!qualityOrders.isEmpty();}
        String cohortDescription(){return qualityOrders.isEmpty()?"上一轮的 "+qualityWorkOrders.size()+" 张生产工单":"上一轮的 "+qualityOrders.size()+" 张客户订单";}
        String inspectionNo,workOrder,workOrderNo,order,orderNo,scopeRef=UUID.randomUUID().toString();List<String> required=List.of();JsonNode planInput;Map<String,Object> plan,result,last;boolean crossOrderRisk,cancelled,awaitingOrderInput;
        final Map<String,Map<String,Object>> evidence=new LinkedHashMap<>();final Map<String,Task> tasks=new LinkedHashMap<>();
        Map<String,Object> workflowPlan;
        final List<Map<String,Object>> workflowEvents=new ArrayList<>();
        boolean workflowTruncated,workflowInvalidated,summarizing;
        Run(String ref,String query,long owner,Instant expires,Map<String,Object> previous){
            this.ref=ref;this.query=query;this.userQuery=query;this.owner=owner;this.expires=expires;this.previous=previous;this.userPrevious=previous;
            this.referenceDay=LocalDate.ofInstant(expires.minusSeconds(600),ZONE);
        }
    }
}
