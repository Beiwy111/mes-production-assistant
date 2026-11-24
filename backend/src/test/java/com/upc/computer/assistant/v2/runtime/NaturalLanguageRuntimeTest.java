package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.*;
import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper;
import com.upc.computer.assistant.v2.tools.delivery.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaturalLanguageRuntimeTest {
    @Test void selectedDispatchRetainsOperatorPredicateAndCannotReadInventory() throws Exception {
        var actor=AssistantReadPolicy.of(42,"OPERATOR",null,List.of("production:myDispatch"));
        var scoped=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,service,actor)));
        var work=List.<Map<String,Object>>of(Map.of("record_id",77,"work_order_no","WO77","status","RUNNING"));
        when(overview.countAuthorized("PRODUCTION",null,null,null,null,Map.of(),null,42L)).thenReturn(1L);
        when(overview.rowsAuthorized("PRODUCTION",null,null,null,null,Map.of(),null,42L)).thenReturn(work);
        var start=(String)scoped.create(json.valueToTree(Map.of("ownerUserId","42","query","哪些工单还没做完"))).get("runRef");
        scoped.plan(start,plan("PRODUCTION",null,null,null,"CURRENT"));scoped.begin(start,"PRODUCTION");
        scoped.complete(start,"PRODUCTION",node("{\"summary\":\"ok\"}"));scoped.finalizeRun(start);
        var next=(String)scoped.create(json.valueToTree(Map.of("ownerUserId","42","query","这几单派给谁了","previousRunRef",start))).get("runRef");
        scoped.plan(next,plan("ROWS_DISPATCH",null,null,null,"CURRENT"));
        when(overview.selectedRows("PRODUCTION",List.of(77L),null,42L,Map.of(),null,null,null)).thenReturn(work);
        scoped.begin(next,"PRODUCTION");
        verify(overview).selectedDispatches(List.of(77L),42L);
        verify(overview,never()).selectedDispatches(any(),isNull());
        var denied=(String)scoped.create(json.valueToTree(Map.of("ownerUserId","42","query","这几单缺哪些物料","previousRunRef",start))).get("runRef");
        assertEquals("UNSUPPORTED",json.valueToTree(scoped.plan(denied,plan("ROWS_MATERIAL",null,null,null,"CURRENT"))).at("/plan/action").asText());
        verify(overview,never()).resourceStock(any());
    }
    @Test void emptyOrderSelectionCannotExpandToAllOrdersOrAnotherCustomer() throws Exception {
        var actor=AssistantReadPolicy.of(42,"CUSTOMER","客户甲",List.of("customer:orders"));
        var scoped=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,service,actor)));
        var start=(String)scoped.create(json.valueToTree(Map.of("ownerUserId","42","query","查询全部订单"))).get("runRef");
        scoped.plan(start,plan("ORDER",null,null,null,"CURRENT"));scoped.begin(start,"ORDER");
        scoped.complete(start,"ORDER",node("{\"summary\":\"ok\"}"));scoped.finalizeRun(start);
        var next=(String)scoped.create(json.valueToTree(Map.of("ownerUserId","42","query","回到刚才那几单","previousRunRef",start))).get("runRef");
        scoped.plan(next,plan("ROWS_RESUME",null,null,null,"CURRENT"));
        var read=json.valueToTree(scoped.begin(next,"ORDER")).at("/evidence/0/data/overview");
        assertEquals(0,read.path("totalRecords").asInt(-1));
        verify(overview,times(2)).rowsAuthorized("ORDER",null,null,null,null,Map.of(),"客户甲",null);
        verify(overview,never()).selectedRows(any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void verifiedEmptyInspectionListStaysEmptyAcrossFollowup() throws Exception {
        String parent=create("查看质检记录");
        runtime.plan(parent,plan("QUALITY",null,null,null,"CURRENT"));runtime.begin(parent,"QUALITY");
        runtime.complete(parent,"QUALITY",node("{\"summary\":\"空清单\"}"));runtime.finalizeRun(parent);
        String next=(String)runtime.create(json.valueToTree(Map.of("query","回到这几张","ownerUserId","42","previousRunRef",parent))).get("runRef");
        assertEquals(List.of("QUALITY"),runtime.plan(next,plan("SET_RESUME",null,null,null,"CURRENT")).get("requiredAgents"));
        var read=json.valueToTree(runtime.begin(next,"QUALITY"));
        assertEquals(0,read.at("/evidence/0/data/overview/totalRecords").asInt(-1));
        verify(overview,never()).displayedInspections(anyList(),any(),any());
    }
    @Test void followupsUseDisplayedMembershipAndDistinguishVerdictFromWorkflowStatus() throws Exception {
        var pending=Map.<String,Object>of("record_id",10,"inspection_no","QI010","status","PENDING","inspection_status","PENDING");
        var recheck=Map.<String,Object>of("record_id",11,"inspection_no","QI011","status","UNQUALIFIED","inspection_status","RECHECK_REQUIRED");
        var failed=Map.<String,Object>of("record_id",12,"inspection_no","QI012","status","UNQUALIFIED","inspection_status","FAILED");
        var all=List.of(pending,recheck,failed);
        when(overview.count("QUALITY",null,null,null,null)).thenReturn(3L);
        when(overview.rows("QUALITY",null,null,null,null)).thenReturn(all);
        when(overview.displayedInspections(List.of(10L,11L,12L),null,null)).thenReturn(all);
        when(overview.displayedInspections(List.of(11L,12L),null,null)).thenReturn(List.of(recheck,failed));
        String ref=create("质检怎么样");
        for(String intent:List.of("QUALITY","SET_UNQUALIFIED","SET_TRIAGE","SET_RESUME","SET_FAILED_STATUS")){
            String phrase=switch(intent){case "SET_UNQUALIFIED"->"哪些没通过";case "SET_RESUME"->"回到刚才那几单";
                case "SET_TRIAGE"->"这些单子优先排查哪道工序？";default->"筛选流程状态为失败的";};
            if(!intent.equals("QUALITY")) ref=(String)runtime.create(json.valueToTree(Map.of("query",phrase,"ownerUserId","42","previousRunRef",ref))).get("runRef");
            // Even an unfiltered model proposal must not widen an explicit reference.
            String proposed=intent.equals("SET_FAILED_STATUS")?intent:"QUALITY";
            assertEquals(List.of("QUALITY"),runtime.plan(ref,plan(proposed,null,null,null,"CURRENT")).get("requiredAgents"));
            runtime.begin(ref,"QUALITY");
            runtime.complete(ref,"QUALITY",node("{\"summary\":\"核对本次清单\"}"));
            var result=json.valueToTree(runtime.finalizeRun(ref));
            assertEquals("EVIDENCE_READY",result.get("verdict").asText());
            assertEquals(switch(intent){case "QUALITY"->3;case "SET_FAILED_STATUS"->1;default->2;}, result.at("/facts/quality/overview/rows").size());
        }
        assertEquals("CLARIFY",json.valueToTree(runtime.plan(create("这些先查哪里"),plan("SET_TRIAGE",null,null,null,"CURRENT"))).at("/plan/action").asText());
    }
    @Test void qualityOverviewNounsNeverBecomeKeywordDateOrInheritedInspection() throws Exception {
        for(String question:List.of("查询质检情况","列出全部质检记录。","帮我看看检验结果怎么样？")){
            var envelope=json.valueToTree(runtime.plan(create(question),plan("INSPECTION_TRACE","100",null,"QI202607022","TODAY")));
            assertEquals("QUALITY",envelope.at("/plan/intents/0").asText());
            for(String selector:List.of("orderPk","orderNo","keyword")) assertTrue(envelope.at("/plan/"+selector).isNull());
            assertEquals("CURRENT",envelope.at("/plan/period").asText());
        }
        var recent=json.valueToTree(runtime.plan(create("查询最近的质检情况"),plan("QUALITY",null,null,null,"LAST_7_DAYS")));
        assertEquals("LAST_7_DAYS",recent.at("/plan/period").asText());
    }
    @Test void modelSelectedRouteUsesRealMasterRowsAndPreservesRouteMenu() throws Exception {
        assertFalse(AssistantReadPolicy.of(7,"OPERATOR",null,List.of("production:myDispatch")).allows("PROCESS_ROUTE"));
        assertTrue(AssistantReadPolicy.of(8,"PLANNER",null,List.of("production:route")).allows("PROCESS_ROUTE"));
        when(overview.activeRouteSteps(null)).thenReturn(List.of(Map.of("route_id",1,"route_code","R1","route_name","甲路线","version_no","V1","step_id",3,"step_name","测试工序")));
        String run=create("这款产品的加工顺序是什么");
        assertEquals(List.of("PRODUCTION"),runtime.plan(run,plan("PROCESS_ROUTE",null,null,null,"CURRENT")).get("requiredAgents"));
        var begun=json.valueToTree(runtime.begin(run,"PRODUCTION"));
        assertEquals("测试工序",begun.at("/evidence/0/data/rows/0/step_name").asText());
        assertEquals(8,begun.at("/evidence/0/data/productionStages").size());
        assertEquals("支架底座装配",begun.at("/evidence/0/data/productionStages/7/step_name").asText());
        verify(overview).activeRouteSteps(null);
    }
    @Test void exactInspectionAnchorsAndMissingIdsCannotBroadenToOverview() throws Exception {
        String r=create("QI202607021存在哪些问题");
        assertEquals(List.of("QUALITY"),runtime.plan(r,plan("INSPECTION_TRACE",null,null,"QI202607021","CURRENT")).get("requiredAgents"));
        var begun=json.valueToTree(runtime.begin(r,"QUALITY"));
        assertEquals("NOT_FOUND",begun.at("/evidence/0/data/status").asText());
        verify(overview).traceInspection("QI202607021");
        var invalid=runtime.plan(create("QI202607021和QI202607022"),plan("INSPECTION_TRACE",null,null,"QI202607021","CURRENT"));
        assertEquals("CLARIFY",json.valueToTree(invalid).at("/plan/action").asText());
        var absent=runtime.plan(create("为什么近期某个批次的不良率升高？"),plan("QUALITY",null,null,null,"LAST_7_DAYS"));
        assertEquals("CLARIFY",json.valueToTree(absent).at("/plan/action").asText());
        var overviewOnly=runtime.plan(create("查询 QI202607021 的不良情况"),plan("QUALITY",null,null,null,"CURRENT"));
        assertEquals("INSPECTION_TRACE",json.valueToTree(overviewOnly).at("/plan/intents/0").asText());
        var filteredTrace=runtime.plan(create("今天QI202607021的派工"),plan("INSPECTION_TRACE",null,null,null,"TODAY"));
        assertEquals("CLARIFY",json.valueToTree(filteredTrace).at("/plan/action").asText());
    }
    @Test void inspectionFieldsAndLinkedFollowupKeepOneInspectionScope() throws Exception {
        var first=create("请核对检验单 QI202611001 的判定状态与不合格数量，是否一致？");
        when(overview.traceInspection("QI202611001")).thenReturn(Map.of("inspection_id",10,"inspection_no","QI202611001"));
        var input=plan("INSPECTION_TRACE",null,null,"QI202611001","CURRENT");
        assertEquals("QUERY",json.valueToTree(runtime.plan(first,input)).at("/plan/action").asText());
        runtime.begin(first,"QUALITY");runtime.complete(first,"QUALITY",node("{\"summary\":\"ok\"}"));runtime.finalizeRun(first);
        var mixed=(com.fasterxml.jackson.databind.node.ObjectNode)input.deepCopy();
        mixed.set("intents",json.valueToTree(List.of("INSPECTION_TRACE","EQUIPMENT")));
        var followup=(String)runtime.create(json.valueToTree(Map.of("query","那这张单关联的设备能确定吗？","ownerUserId","42","previousRunRef",first))).get("runRef");
        assertEquals(List.of("QUALITY"),runtime.plan(followup,mixed).get("requiredAgents"));
        clearInvocations(overview);
        runtime.begin(followup,"QUALITY"); verify(overview).traceInspection("QI202611001");
        for(String question:List.of("QI202611001设备以及全厂设备都查一下","QI202611001 的判定状态为合格的记录")){
            assertEquals("CLARIFY",json.valueToTree(runtime.plan(create(question),input)).at("/plan/action").asText());
        }
    }

    @Test void modelGeneralModeDoesNotReadDatabaseFactsOrRegisterKnowledgeCalls() throws Exception {
        var run=create("什么是防错设计？");
        assertEquals(List.of(),runtime.plan(run,plan("GENERAL",null,null,null,"CURRENT")).get("requiredAgents"));
        var result=runtime.finalizeRun(run);
        assertEquals("EVIDENCE_READY",result.get("verdict"));assertEquals(List.of(),result.get("executedAgents"));
        verifyNoInteractions(overview);
        assertFalse(json.valueToTree(runtime.observe(run)).toString().contains("KNOWLEDGE_RETRIEVAL"));
    }
    @Test void practicalGuidanceUsesReferenceSourcesWhenThePlannerMissesThem() throws Exception {
        var question="这块液晶屏黑屏时要怎样区分输入端和面板故障？";
        var ref=create(question);
        var ctx=json.valueToTree(runtime.plan(ref,plan("GENERAL",null,null,null,"CURRENT")));
        assertEquals("KNOWLEDGE",ctx.at("/plan/intents/0").asText());
        assertEquals(List.of("QUALITY"),runtime.context(ref).get("requiredAgents"));
        assertTrue(json.valueToTree(runtime.begin(ref,"QUALITY")).path("evidence").isEmpty());
        for(String other:List.of("把这句翻译成英文：显示器怎么排查故障？","什么是量具复测？","查本厂检验记录里的显示器故障诊断")) {
            assertFalse(BusinessReadIntent.needsTechnicalGuidance(other),other);
        }
        verifyNoInteractions(overview,delivery);
    }
    @Test void generalContentTreatsDatesQuantitiesAndColumnNamesAsUserInput() throws Exception {
        for(String q:List.of("把这句改成英文：库存小于12时，显示状态改成黄色。","给本厂写一段说明2025年价格与数量关系的文字","设计一个仓库管理培训练习")){
            var ref=create(q);
            var c=json.valueToTree(runtime.plan(ref,plan("GENERAL",null,null,null,"CURRENT")));
            assertEquals("QUERY",c.at("/plan/action").asText(),q);
            assertTrue(c.path("requiredAgents").isEmpty());
            assertTrue(json.valueToTree(runtime.finalizeRun(ref)).path("facts").isEmpty());
        }
        verifyNoInteractions(overview,delivery);
    }
    @Test void generalFollowupUsesServerBoundAnswerOnlyForPreviousGeneralTurn() throws Exception {
        String original=create("帮我拟两条口号");
        runtime.plan(original,plan("GENERAL",null,null,null,"CURRENT"));runtime.finalizeRun(original);
        String follow=(String)runtime.create(json.valueToTree(Map.of("query","第二条改成英文","ownerUserId","42","previousRunRef",original,
            "previousGeneralAnswer","甲计划。乙方案。"))).get("runRef");
        runtime.plan(follow,plan("GENERAL",null,null,null,"CURRENT"));
        assertEquals("甲计划。乙方案。",json.valueToTree(runtime.finalizeRun(follow)).at("/previousGeneralTurn/generalAnswer").asText());
        var business=create("查看库存");runtime.plan(business,plan("INVENTORY",null,null,null,"CURRENT"));
        var next=runtime.create(json.valueToTree(Map.of("query","写个标语","ownerUserId","42","previousRunRef",business,
            "previousGeneralAnswer","不应进入新提示词")));
        assertFalse(json.valueToTree(next).has("generalAnswer"));
        assertFalse(json.valueToTree(next).path("previousContext").has("generalAnswer"));
        var combined=(com.fasterxml.jackson.databind.node.ObjectNode)plan("INVENTORY",null,null,null,"CURRENT");
        combined.putArray("intents").add("INVENTORY").add("GENERAL");
        var planned=json.valueToTree(runtime.plan(create("看库存并替我解释结果"),combined));
        assertEquals(List.of("SUPPLY"),json.convertValue(planned.path("requiredAgents"),List.class));
        assertEquals(List.of("INVENTORY"),json.convertValue(planned.at("/plan/intents"),List.class));
    }
    @Test void reformattingABusinessResultRequeriesTheExactApprovedRangeWithFreshValues() throws Exception {
        String initial=create("查询PCB库存");
        runtime.plan(initial,plan("INVENTORY",null,null,"PCB","CURRENT"));
        runtime.begin(initial,"SUPPLY");runtime.complete(initial,"SUPPLY",node("{\"summary\":\"库存查询完成\"}"));runtime.finalizeRun(initial);
        String next=follow("把它写成一句交班话",initial);
        when(overview.rows("INVENTORY",null,"PCB",null,null)).thenReturn(List.of(Map.of("record_id",9,"quantity_available",37)));
        when(overview.count("INVENTORY",null,"PCB",null,null)).thenReturn(1L);
        var brief=plan("RECAP",null,null,null,"CURRENT");
        var c=json.valueToTree(runtime.plan(next,brief)); assertEquals("QUERY",c.at("/plan/action").asText());
        assertEquals("PCB",c.at("/scope/keyword").asText());assertEquals("把它写成一句交班话",c.path("userQuery").asText());
        assertEquals(c,json.valueToTree(runtime.plan(next,brief)));
        assertEquals("37",json.valueToTree(runtime.begin(next,"SUPPLY")).at("/evidence/0/data/overview/rows/0/quantity_available").asText());
        assertEquals("CLARIFY",json.valueToTree(runtime.plan(create("写成一段摘要"),brief)).at("/plan/action").asText());
    }
    @Test void unspecifiedRecentQualityUsesSevenShanghaiDaysEvenWhenThePlannerSuppliesCurrent() throws Exception {
        var run=create("看一下最近的质检情况。");
        var context=json.valueToTree(runtime.plan(run,plan("QUALITY",null,null,null,"CURRENT")));
        assertEquals("LAST_7_DAYS",context.at("/plan/period").asText());
        var facts=json.valueToTree(runtime.begin(run,"QUALITY")).at("/evidence/0/data");
        assertEquals("2026-11-03 00:00:00",facts.at("/overview/startInclusive").asText());
        assertEquals("2026-11-10 00:00:00",facts.at("/overview/endExclusive").asText());
        assertTrue(facts.path("traceConclusion").asText().contains("缺少检验样本"));
    }
    @Test void multipleToolsSelectedByPlannerArePreservedForEnterpriseDiagnosis() throws Exception {
        var selected=(com.fasterxml.jackson.databind.node.ObjectNode)plan("QUALITY",null,null,null,"CURRENT");
        selected.putArray("intents").add("QUALITY").add("KNOWLEDGE");
        var context=json.valueToTree(runtime.plan(create("分析本厂质检记录中的色斑，给出方法参考"),selected));
        assertEquals(selected.get("intents"),context.at("/plan/intents"));
    }
    @Test void scopedBatchComparisonUsesTwoDateRangesAndDoesNotReadTheInterveningDates() throws Exception {
        String batch="BATCH-WO202607002-3201";
        var selectors=Map.of("batch_no",batch);
        String early="2026-07-01 00:00:00",earlyEnd="2026-07-03 00:00:00",late="2026-07-05 00:00:00",lateEnd="2026-07-09 00:00:00";
        when(overview.countScoped("QUALITY",null,null,early,earlyEnd,selectors)).thenReturn(1L);
        when(overview.rowsScoped("QUALITY",null,null,early,earlyEnd,selectors)).thenReturn(List.of(sample("7","50","5","FINISHED",batch)));
        when(overview.countScoped("QUALITY",null,null,late,lateEnd,selectors)).thenReturn(1L);
        when(overview.rowsScoped("QUALITY",null,null,late,lateEnd,selectors)).thenReturn(List.of(sample("8","100","15","FINISHED",batch)));
        String ref=create("查询批次"+batch+"的抽检不合格率变化，2026-07-01 至 2026-07-02 与 2026-07-05 至 2026-07-08。");
        var p=json.valueToTree(runtime.plan(ref,plan("QUALITY",null,null,null,"CURRENT")));
        assertEquals("QUERY",p.at("/plan/action").asText(),p.at("/plan/question").asText());
        var result=json.valueToTree(runtime.begin(ref,"QUALITY")).at("/evidence/0/data");
        assertEquals("5.00",result.at("/batchComparison/0/difference_percentage_points").asText());
        assertTrue(result.path("traceConclusion").asText().contains("5/50"));
        verify(overview,never()).rowsScoped("QUALITY",null,null,early,lateEnd,selectors);
        var invalid=runtime.plan(create("比较"+batch+"两期不良率：2026-07-01至2026-07-10 与 2026-07-10至2026-07-12"),plan("QUALITY",null,null,null,"CURRENT"));
        assertEquals("CLARIFY",json.valueToTree(invalid).at("/plan/action").asText());
    }
    private Map<String,Object> sample(String id,String n,String bad,String type,String batch){
        var row=new LinkedHashMap<String,Object>();row.put("record_id",id);row.put("material_id",8);row.put("unit","台");
        row.put("batch_no",batch);row.put("material_name","显示器");row.put("inspection_category","OQC");row.put("inspection_type",type);
        row.put("sample_quantity",n);row.put("qualified_quantity",Integer.parseInt(n)-Integer.parseInt(bad));row.put("unqualified_quantity",bad);
        row.put("inspection_status","FAILED");row.put("status","UNQUALIFIED");return row;
    }
    @Test void duplicateReinspectionAndMissingPeriodNeverProduceAComparisonRate(){
        String batch="BATCH-X";var selector=Map.of("batch_no",batch);
        var periods=BusinessDateRange.comparison("2026-07-01 至 2026-07-02 与 2026-07-05",LocalDate.now());
        var first=periods.get(0);var second=periods.get(1);
        var initial=sample("7","50","5","INITIAL",batch);var repeat=sample("8","10","1","RECHECK",batch);
        when(overview.countScoped("QUALITY",null,null,first.fromTimestamp(),first.toTimestamp(),selector)).thenReturn(3L);
        when(overview.rowsScoped("QUALITY",null,null,first.fromTimestamp(),first.toTimestamp(),selector)).thenReturn(List.of(initial,initial,repeat));
        var result=json.valueToTree(BatchQualityComparison.read(new NaturalLanguageRuntime.Data(overview,service),batch,periods));
        assertEquals(2,result.path("batchComparison").size());
        for(var row:result.path("batchComparison"))assertTrue(row.path("difference_percentage_points").isNull());
        assertTrue(result.path("traceConclusion").asText().contains("后期 2026-07-05 至 2026-07-05（0 单）"));
    }
    @Test void experienceNeedsAnAuthorizedGlobalTopicAndIsNeverCalledForCurrentStatus() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var engine=new NaturalLanguageRuntime.Research() {
            public Map<String,Object> retrieve(String role,List<Map<String,Object>> evidence){return Map.of();}
            public Map<String,Object> retrieveExperience(String text,String domain) {
                calls.incrementAndGet();return Map.of("status","NO_MATCHES","hits",List.of(),"graph_paths",List.of());
            }
        };
        for(var policy:List.of(AssistantReadPolicy.admin(),AssistantReadPolicy.of(42,"DEVICE",null,List.of("device:maintenance")))) {
            var r=new NaturalLanguageRuntime(clock,(owner,fn)->fn.apply(new NaturalLanguageRuntime.Data(overview,service,policy)),engine);
            for(var query:List.of("历史维修经验有哪些","今天的历史维修经验有哪些","一号车间的历史维修经验有哪些","查看设备维修记录")) {
                String ref=(String)r.create(json.valueToTree(Map.of("query",query,"ownerUserId","42"))).get("runRef");
                var c=r.plan(ref,plan("MAINTENANCE",null,null,null,query.contains("今天")?"TODAY":"CURRENT"));
                if(((List<?>)c.get("requiredAgents")).contains("EQUIPMENT"))r.begin(ref,"EQUIPMENT");
            }
            String prior=(String)r.create(json.valueToTree(Map.of("query","今天的维修记录","ownerUserId","42"))).get("runRef");
            r.plan(prior,plan("MAINTENANCE",null,null,null,"TODAY"));
            String follow=(String)r.create(json.valueToTree(Map.of("query","相关维修经验呢","previousRunRef",prior,"ownerUserId","42"))).get("runRef");
            r.plan(follow,plan("MAINTENANCE",null,null,null,"CURRENT"));
            r.begin(follow,"EQUIPMENT");
        }
        assertEquals(1,calls.get());
    }
    @Test void crossDomainDiagnosisReadsBothMysqlDomainsAndOneCrossDomainHistoryBundle() throws Exception {
        var domains=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var engine=new NaturalLanguageRuntime.Research() {
            public Map<String,Object> retrieve(String role,List<Map<String,Object>> evidence){return Map.of();}
            public Map<String,Object> retrieveExperience(String text,String domain) {
                domains.add(domain);return Map.of("status","NO_MATCHES","hits",List.of(),"graph_paths",List.of());
            }
        };
        var r=new NaturalLanguageRuntime(clock,(owner,fn)->fn.apply(new NaturalLanguageRuntime.Data(overview,service)),engine);
        String ref=(String)r.create(json.valueToTree(Map.of("query","分析本厂历史维修记录与质检记录的关联","ownerUserId","42"))).get("runRef");
        r.plan(ref,plan("KNOWLEDGE",null,null,null,"CURRENT"));
        var equipment=r.begin(ref,"EQUIPMENT");var quality=r.begin(ref,"QUALITY");
        assertEquals(List.of("DIAGNOSTIC"),domains);
        assertEquals("OVERVIEW_MAINTENANCE",json.valueToTree(equipment).at("/evidence/0/kind").asText());
        assertEquals("OVERVIEW_QUALITY",json.valueToTree(quality).at("/evidence/0/kind").asText());
        assertFalse(quality.containsKey("experienceEvidence"));
    }
    @Test void riskQuestionsUseOnlyTheRequestedResourceEvidence() throws Exception {
        for(String[] q:List.of(new String[]{"哪些订单在竞争同一批物料","ORDER","SUPPLY","RESOURCE_MATERIAL"},
                                new String[]{"哪些派工有产能冲突","PRODUCTION","PRODUCTION","RESOURCE_CAPACITY"})) {
            String ref=create(q[0]);
            var context=runtime.plan(ref,plan(q[1],null,null,null,"CURRENT"));
            assertEquals(List.of("ORDER",q[2]),context.get("requiredAgents"));
            assertEquals(q[3],json.valueToTree(runtime.begin(ref,q[2])).at("/evidence/0/kind").asText());
        }
    }
    @Test void unanchoredBatchAndCrossCustomerRequestsNeverReadBusinessRecords() throws Exception {
        var batch=runtime.plan(create("这批显示器为什么反复出现色斑"),plan("QUALITY",null,null,"显示器","CURRENT"));
        assertEquals("CLARIFY",json.valueToTree(batch).at("/plan/action").asText());
        assertEquals(List.of(),batch.get("requiredAgents"));
        String ref=create("给另外一个客户看这张订单");
        var share=runtime.plan(ref,plan("ORDER",null,null,null,"CURRENT"));
        assertEquals("UNSUPPORTED",json.valueToTree(share).at("/plan/action").asText());
        assertEquals(List.of(),share.get("requiredAgents"));verifyNoInteractions(overview,delivery);
    }
    @Test void workOrderAnswerToBatchClarificationReadsOnlyThatOrdersQualityEvidence() throws Exception {
        String initial=create("这批显示器为什么反复出现色斑");
        runtime.plan(initial,plan("QUALITY",null,null,null,"CURRENT"));
        when(overview.resolveWorkOrders("WO202607003",null)).thenReturn(List.of(Map.of("work_order_id",3,"work_order_no","WO202607003")));
        String next=(String)runtime.create(json.valueToTree(Map.of("query","工单号 WO202607003","ownerUserId","42","previousRunRef",initial))).get("runRef");
        var out=runtime.plan(next,plan("PRODUCTION",null,null,null,"CURRENT"));
        assertEquals(List.of("QUALITY"),out.get("requiredAgents"));runtime.begin(next,"QUALITY");
        verify(overview).batchInspections(3L);verify(overview,never()).rows(any(),any(),any(),any(),any());
    }
    @Test void factoryRecordsAndPublicMethodsUseTheirRequestedDomains() throws Exception {
        var factory=runtime.plan(create("分析本厂历史维修记录与质检记录的关联"),plan("KNOWLEDGE",null,null,null,"CURRENT"));
        assertEquals(List.of("QUALITY","EQUIPMENT"),factory.get("requiredAgents"));
        assertEquals(List.of("MAINTENANCE","QUALITY"),json.convertValue(json.valueToTree(factory).at("/plan/intents"),List.class));
        var published=runtime.plan(create("公开文献中对显示器色斑有哪些检测方法"),plan("QUALITY",null,null,null,"CURRENT"));
        assertEquals(List.of("KNOWLEDGE"),json.convertValue(json.valueToTree(published).at("/plan/intents"),List.class));
        assertEquals("CLARIFY",json.valueToTree(runtime.plan(create("查这台设备的历史维修记录"),plan("MAINTENANCE",null,null,null,"CURRENT"))).at("/plan/action").asText());
        var equipment=json.valueToTree(runtime.plan(create("查本厂这台设备EQ-DISP-01的历史维修记录"),plan("KNOWLEDGE",null,null,null,"CURRENT")));
        assertEquals("EQ-DISP-01",equipment.at("/scope/selectors/equipment_code").asText());
        var batch=json.valueToTree(runtime.plan(create("查这批BATCH-101的质检记录"),plan("QUALITY",null,null,"BATCH-101","CURRENT")));
        assertEquals("BATCH-101",batch.at("/scope/selectors/batch_no").asText());
        assertEquals("CLARIFY",json.valueToTree(runtime.plan(create("查本厂设备EQ-DISP-01关联的质检记录"),plan("QUALITY",null,null,null,"CURRENT"))).at("/plan/action").asText());
    }
    @Test void otherMaterialsAndAvailabilityKeepExclusionWarehouseAndTimeThroughFinalRead() throws Exception {
        String initial=create("查今天仓库WH-01的PCB板库存");
        runtime.plan(initial,plan("INVENTORY",null,null,"PCB板","TODAY"));
        String other=(String)runtime.create(json.valueToTree(Map.of("query","其他物料有多少","ownerUserId","42","previousRunRef",initial))).get("runRef");
        var selectors=Map.of("excluded_material_keyword","PCB板","warehouse_code","WH-01");
        var second=json.valueToTree(runtime.plan(other,plan("INVENTORY",null,null,"PCB板","CURRENT")));
        assertEquals(json.valueToTree(selectors),second.at("/scope/selectors"));
        assertTrue(second.at("/scope/keyword").isNull());assertEquals("TODAY",second.at("/scope/period").asText());
        String next=(String)runtime.create(json.valueToTree(Map.of("query","其中可用库存","ownerUserId","42","previousRunRef",other))).get("runRef");
        var third=json.valueToTree(runtime.plan(next,plan("INVENTORY",null,null,null,"CURRENT")));
        assertEquals(json.valueToTree(selectors),third.at("/scope/selectors"));
        assertEquals("AVAILABLE",third.at("/scope/criteria/INVENTORY").asText());
        runtime.begin(next,"SUPPLY");runtime.complete(next,"SUPPLY",node("{\"summary\":\"已读取\"}"));runtime.finalizeRun(next);
        verify(overview,times(2)).rowsScoped("INVENTORY",null,null,"2026-11-09 00:00:00","2026-11-10 00:00:00",selectors);
        for(String q:List.of("其他物料有多少","其中可用库存"))assertEquals("CLARIFY",json.valueToTree(runtime.plan(create(q),plan("INVENTORY",null,null,null,"CURRENT"))).at("/plan/action").asText());
    }
    @Test void crossOrderResourceExpertsAreIndependentAndRevalidateTheirActualSources() throws Exception {
        when(overview.count("ORDER",null,null,null,null)).thenReturn(1L);
        when(overview.rows("ORDER",null,null,null,null)).thenReturn(List.of(Map.of("record_id","1","order_no","SO1","status","APPROVED")));
        when(overview.resourcePlans(List.of(1L))).thenReturn(List.of(SharedResourceRiskTest.plan("1","80")));
        when(overview.resourceBoms(eq(List.of(10L)),anyString())).thenReturn(List.of(SharedResourceRiskTest.bom()));
        when(overview.resourceStock(List.of(20L))).thenReturn(List.of(SharedResourceRiskTest.inventory()));
        String ref=create("分析跨订单共享物料竞争和产能冲突风险");
        var context=json.valueToTree(runtime.plan(ref,plan("ORDER",null,null,null,"CURRENT")));
        assertEquals(List.of("ORDER","SUPPLY","PRODUCTION"),json.convertValue(context.get("requiredAgents"),List.class));
        assertEquals(0,context.at("/dependencies/SUPPLY").size());assertEquals(0,context.at("/dependencies/PRODUCTION").size());
        for(String role:List.of("SUPPLY","PRODUCTION","ORDER")){runtime.begin(ref,role);runtime.complete(ref,role,node("{\"summary\":\"已读取\"}"));}
        var changed=new HashMap<String,Object>(SharedResourceRiskTest.inventory());changed.put("quantity_on_hand","60");changed.put("quantity_available","50");
        when(overview.resourceStock(List.of(20L))).thenReturn(List.of(changed));
        var result=runtime.finalizeRun(ref);assertEquals("DATA_CHANGED",result.get("verdict"));assertTrue(((Map<?,?>)result.get("facts")).isEmpty());
    }
    @Test void customerRiskDoesNotCreateResourceExpertsOrReadCompetitorSources() throws Exception {
        var policy=AssistantReadPolicy.of(42,"CUSTOMER","甲公司",List.of("customer:orders"));
        var scoped=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,service,policy)));
        String ref=(String)scoped.create(json.valueToTree(Map.of("query","跨订单共享物料竞争和产能冲突分析","ownerUserId","42"))).get("runRef");
        var context=scoped.plan(ref,plan("ORDER",null,null,null,"CURRENT"));assertEquals(List.of("ORDER"),context.get("requiredAgents"));
        assertThrows(ResponseStatusException.class,()->scoped.begin(ref,"SUPPLY"));verify(overview,never()).resourcePlans(any());
    }
    @Test void unanchoredOrAmbiguousTraceCannotReadGlobalRecords() throws Exception {
        for(String query:List.of("同批有没有质检异常","哪道工序没做完","同批上周有没有质检异常")){
            var result=runtime.plan(create(query),plan("QUALITY",null,null,null,"CURRENT"));
            assertEquals("CLARIFY",json.valueToTree(result).at("/plan/action").asText());
            assertTrue(((List<?>)result.get("requiredAgents")).isEmpty());
        }
        verify(overview,never()).batchInspections(anyLong());
    }
    @Test void roleWithoutQualityCannotInheritBatchResults() throws Exception {
        var policy=AssistantReadPolicy.of(42,"OPERATOR",null,List.of("production:myDispatch"));
        var scoped=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,service,policy)));
        String ref=(String)scoped.create(json.valueToTree(Map.of("query","WO202607003同批有没有质检异常","ownerUserId","42"))).get("runRef");
        assertEquals("UNSUPPORTED",json.valueToTree(scoped.plan(ref,plan("QUALITY",null,null,null,"CURRENT"))).at("/plan/action").asText());
        verify(overview,never()).batchInspections(anyLong());
    }
    @Test void dispatchQueryNeverDropsExtraOrderKeywordOrDateConditions() throws Exception {
        when(overview.resolveWorkOrders(anyString(),isNull())).thenReturn(List.of(Map.of("work_order_id",1,"work_order_no","WO202607001")));
        for(String query:List.of("WO202607001和WO202607002派工了吗","WO202607001和订单101派工了吗","今天WO202607001这个工单派工了吗","WO202607001这个工单的PCB派工了吗")) {
            String ref=create(query);
            var answer=runtime.plan(ref,plan("PRODUCTION",null,null,query.contains("PCB")?"PCB":null,query.startsWith("今天")?"TODAY":"CURRENT"));
            assertEquals("CLARIFY",json.valueToTree(answer).at("/plan/action").asText(),query);
        }
    }
    @Test void bareWorkOrderAfterClarificationIsAcceptedButBareNumberWithoutContextIsNot() {
        assertEquals("WO202607001",WorkOrderReference.extract("WO202607001",Map.of("userQuery","7001这个订单派工了吗")));
        assertNull(WorkOrderReference.extract("7001",null));
    }
    @Test void workOrderCodeAndSuffixResolveProductionWithoutCustomerOrderEvidence() throws Exception {
        when(overview.resolveWorkOrders(anyString(),isNull())).thenReturn(List.of(Map.of("work_order_id",1,"work_order_no","WO202607001")));
        when(overview.workOrderRows(eq(1L),isNull(),isNull(),isNull())).thenReturn(List.of(Map.of("record_id",1,"work_order_no","WO202607001","status","RUNNING")));
        when(overview.workOrderDispatch(1L,null)).thenReturn(List.of(Map.of("dispatch_id",4,"status","ASSIGNED")));
        for(String text:List.of("7001这个订单现在派工了吗","WO202607001这个订单现在派工了吗")) {
            String ref=create(text);runtime.plan(ref,plan("PRODUCTION",null,"WO202607001",null,"CURRENT"));
            var start=json.valueToTree(runtime.begin(ref,"PRODUCTION"));
            assertEquals(1,start.at("/evidence/0/data/dispatchRows").size());
            runtime.complete(ref,"PRODUCTION",node("{\"summary\":\"读取真实派工\"}"));
            assertEquals("EVIDENCE_READY",runtime.finalizeRun(ref).get("verdict"));
            String follow=(String)runtime.create(json.valueToTree(Map.of("query","你发给我的就是这个","ownerUserId","42","previousRunRef",ref))).get("runRef");
            var ambiguous=node("{\"action\":\"CLARIFY\",\"intents\":[\"PRODUCTION\"],\"orderPk\":null,\"orderNo\":null,\"keyword\":null,\"period\":\"CURRENT\",\"question\":\"哪个订单\"}");
            assertEquals("QUERY",json.valueToTree(runtime.plan(follow,ambiguous)).at("/plan/action").asText());
        }
    }
    @Test void multiCustomerScopeIsAppliedAndRevalidatedWithoutQueryingOtherCustomers() throws Exception {
        var selectors=Map.of("customer_names","[\"丙公司\",\"甲公司\"]");
        when(overview.countScoped("ORDER",null,null,null,null,selectors)).thenReturn(1L);
        when(overview.rowsScoped("ORDER",null,null,null,null,selectors)).thenReturn(List.of(Map.of("record_id",1,"customer_name","甲公司","status","PENDING")));
        String ref=create("查询客户‘甲公司’和‘丙公司’的订单");runtime.plan(ref,plan("ORDER",null,null,null,"CURRENT"));
        runtime.begin(ref,"ORDER");runtime.complete(ref,"ORDER",node("{\"summary\":\"已读取\"}"));
        assertEquals("EVIDENCE_READY",runtime.finalizeRun(ref).get("verdict"));
        verify(overview,times(2)).rowsScoped("ORDER",null,null,null,null,selectors);
        verify(overview,never()).rows(any(),any(),any(),any(),any());
    }
    @Test void dailyAlarmTrendRetainsDetailsThroughFinalRevalidation() throws Exception {
        when(overview.count("ALARMS",null,null,null,null)).thenReturn(1L);
        when(overview.rows("ALARMS",null,null,null,null)).thenReturn(List.of(Map.of("record_id",1,"equipment_id",2,"event_at","2026-11-15 01:00:00","alarm_type","FAULT","alarm_level","HIGH","status","OPEN")));
        String ref=create("查询全部设备报警记录，按日分析趋势");runtime.plan(ref,plan("ALARMS",null,null,null,"CURRENT"));
        runtime.begin(ref,"EQUIPMENT");runtime.complete(ref,"EQUIPMENT",node("{\"summary\":\"已读取\"}"));
        var result=json.valueToTree(runtime.finalizeRun(ref));
        assertEquals(1,result.at("/facts/alarms/alarmTrend/0/alarm_record_count").asInt());
        assertEquals(1,result.at("/facts/alarms/overview/rows").size());
    }
    @Test void independentAgentReadsOverlapButFinalizationWaitsForAllReceipts() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(3);
        var release=new java.util.concurrent.CountDownLatch(1);
        for(String domain:List.of("INVENTORY","PRODUCTION","EQUIPMENT")) {
            when(overview.rows(domain,null,null,null,null)).thenAnswer(call->{
                entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));
                return List.of(Map.of("record_id",domain));
            });
            when(overview.count(domain,null,null,null,null)).thenReturn(1L);
        }
        String ref=create("查询当前库存、生产工单和设备台账");
        var request=(com.fasterxml.jackson.databind.node.ObjectNode)plan("INVENTORY",null,null,null,"CURRENT");
        request.set("intents",json.valueToTree(List.of("INVENTORY","PRODUCTION","EQUIPMENT")));
        runtime.plan(ref,request);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var reads=List.of("SUPPLY","PRODUCTION","EQUIPMENT").stream()
                .map(role->executor.submit(()->runtime.begin(ref,role))).toList();
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS),"Independent reads must overlap");
            var observation=json.valueToTree(runtime.observe(ref));
            assertEquals(3,observation.path("tasks").size());
            assertThrows(ResponseStatusException.class,()->runtime.complete(ref,"SUPPLY",node("{\"summary\":\"premature\"}")));
            release.countDown();
            for(var read:reads)assertFalse(read.get(3,java.util.concurrent.TimeUnit.SECONDS).isEmpty());
            runtime.complete(ref,"SUPPLY",node("{\"summary\":\"inventory\"}"));
            assertNotEquals("EVIDENCE_READY",runtime.finalizeRun(ref).get("verdict"));
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void warehouseAndLocationAreConfirmedWithoutBecomingMaterialKeywords() throws Exception {
        String ref=create("查询仓库WH-01、库位A-01-01的可用库存");
        var result=json.valueToTree(runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT")));
        assertEquals("QUERY",result.at("/plan/action").asText());
        assertEquals("WH-01",result.at("/scope/selectors/warehouse_code").asText());
        assertEquals("A-01-01",result.at("/scope/selectors/location_code").asText());
    }
    @Test void customerOrderScopeSurvivesExplicitFollowupButNotNewTopic() throws Exception {
        String ref=create("查询客户名称为“华星电子”的待审核订单");
        var result=json.valueToTree(runtime.plan(ref,plan("ORDER",null,null,null,"CURRENT")));
        assertEquals("QUERY",result.at("/plan/action").asText());
        assertEquals("华星电子",result.at("/scope/selectors/customer_name").asText());
        assertEquals("PENDING",result.at("/scope/criteria/ORDER").asText());
        String next=(String)runtime.create(json.valueToTree(Map.of("query","其中今天的订单","ownerUserId","42","previousRunRef",ref))).get("runRef");
        var follow=json.valueToTree(runtime.plan(next,plan("ORDER",null,null,null,"TODAY")));
        assertEquals("华星电子",follow.at("/scope/selectors/customer_name").asText());
        assertEquals("PENDING",follow.at("/scope/criteria/ORDER").asText());
        String fresh=(String)runtime.create(json.valueToTree(Map.of("query","查库存","ownerUserId","42","previousRunRef",ref))).get("runRef");
        assertEquals(0,json.valueToTree(runtime.plan(fresh,plan("INVENTORY",null,null,null,"CURRENT"))).at("/scope/selectors").size());
    }
    @Test void ambiguousOrUnsupportedScopeNeverReadsGlobalRecords() throws Exception {
        for(String q:List.of("查一号仓库的库存","查询仓库WH-01和一号仓库的库存","查询客户华星电子的库存","查询状态为随便的库存","查询WH-01仓库除外的库存")) {
            String ref=create(q);
            assertEquals("CLARIFY",json.valueToTree(runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT"))).at("/plan/action").asText(),q);
        }
        verifyNoInteractions(overview);
    }
    @Test void scopedFactsAreRecheckedUsingSameSelectorsAndChangedRowsAreInvalidated() throws Exception {
        var selectors=Map.of("warehouse_code","WH-01");
        when(overview.countScoped("INVENTORY",null,null,null,null,selectors)).thenReturn(1L);
        when(overview.rowsScoped("INVENTORY",null,null,null,null,selectors)).thenReturn(List.of(Map.of("record_id",1,"quantity_available",5)));
        String ref=create("查询仓库WH-01库存");runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT"));
        runtime.begin(ref,"SUPPLY");runtime.complete(ref,"SUPPLY",node("{\"summary\":\"已读取\"}"));
        when(overview.rowsScoped("INVENTORY",null,null,null,null,selectors)).thenReturn(List.of(Map.of("record_id",1,"quantity_available",4)));
        assertEquals("DATA_CHANGED",runtime.finalizeRun(ref).get("verdict"));
        verify(overview,times(2)).rowsScoped("INVENTORY",null,null,null,null,selectors);
        verify(overview,never()).rows(any(),any(),any(),any(),any());
    }
    @Test void conflictingAndNegativeStatusesClarifyRatherThanWidenTheQuery() throws Exception {
        for(String q:List.of("查询已审核和已取消订单","查询已取消订单","查询既已审核又待审核订单")) {
            String ref=create(q);assertEquals("CLARIFY",json.valueToTree(runtime.plan(ref,plan("ORDER",null,null,null,"CURRENT"))).at("/plan/action").asText(),q);
        }
    }
    @Test void multipleWarehouseFiltersAndStatusSetsAreAppliedAndInherited() throws Exception {
        var selectors=Map.of("warehouse_codes","WH-01,WH-02");
        when(overview.countScoped("INVENTORY",null,null,null,null,selectors)).thenReturn(1L);
        when(overview.rowsScoped("INVENTORY",null,null,null,null,selectors)).thenReturn(List.of(Map.of("record_id",1,"warehouse_code","WH-02","quantity_available",9)));
        String first=create("查询仓库WH-01、WH-02库存");
        assertEquals("QUERY",json.valueToTree(runtime.plan(first,plan("INVENTORY",null,null,null,"CURRENT"))).at("/plan/action").asText());
        runtime.begin(first,"SUPPLY");runtime.complete(first,"SUPPLY",node("{\"summary\":\"ok\"}"));
        assertEquals("EVIDENCE_READY",runtime.finalizeRun(first).get("verdict"));
        verify(overview,times(2)).rowsScoped("INVENTORY",null,null,null,null,selectors);
        String next=follow("其中可用库存",first);
        assertEquals("WH-01,WH-02",json.valueToTree(runtime.plan(next,plan("INVENTORY",null,null,null,"CURRENT"))).at("/scope/selectors/warehouse_codes").asText());
        String orders=create("查询已审核和待审核订单");
        assertEquals("SET:APPROVED,PENDING",json.valueToTree(runtime.plan(orders,plan("ORDER",null,null,null,"CURRENT"))).at("/scope/criteria/ORDER").asText());
    }
    @Test void exclusionFollowupCannotExpandPreviouslyConfirmedStatusSet() throws Exception {
        String first=create("查询已审核和待审核订单");runtime.plan(first,plan("ORDER",null,null,null,"CURRENT"));
        String next=follow("其中排除已审核的订单",first);
        assertEquals("SET:PENDING",json.valueToTree(runtime.plan(next,plan("ORDER",null,null,null,"CURRENT"))).at("/scope/criteria/ORDER").asText());
        String reset=follow("其中全部状态的订单",first);
        assertEquals("ALL",json.valueToTree(runtime.plan(reset,plan("ORDER",null,null,null,"CURRENT"))).at("/scope/criteria/ORDER").asText());
    }
    @Test void customerLiteralDatesAndStateWordsAreNotFilters() throws Exception {
        String ref=create("查询客户名称为“2026-11-01已审核公司”的订单");
        JsonNode context=json.valueToTree(runtime.plan(ref,plan("ORDER",null,null,null,"CURRENT")));
        assertEquals("QUERY",context.at("/plan/action").asText());
        assertEquals("2026-11-01已审核公司",context.at("/scope/selectors/customer_name").asText());
        assertTrue(context.at("/scope/startInclusive").isNull());
        assertEquals("ALL",context.at("/scope/criteria/ORDER").asText());
    }
    @Test void explicitAllStatesClearsInheritedStateWithoutDroppingCustomer() throws Exception {
        String ref=create("查询客户名称为“华星电子”的待审核订单");runtime.plan(ref,plan("ORDER",null,null,null,"CURRENT"));
        String next=(String)runtime.create(json.valueToTree(Map.of("query","其中全部状态的订单","ownerUserId","42","previousRunRef",ref))).get("runRef");
        var result=json.valueToTree(runtime.plan(next,plan("ORDER",null,null,null,"CURRENT")));
        assertEquals("QUERY",result.at("/plan/action").asText());
        assertEquals("华星电子",result.at("/scope/selectors/customer_name").asText());
        assertEquals("ALL",result.at("/scope/criteria/ORDER").asText());
    }
    @Test void confirmedKeywordSurvivesMultipleFollowupsButCannotLeakIntoANewTopic() throws Exception {
        String first=create("查询PCB库存");runtime.plan(first,plan("INVENTORY",null,null,"PCB","CURRENT"));
        String second=follow("其中哪些库存不足",first);runtime.plan(second,plan("INVENTORY",null,null,"PCB","CURRENT"));
        String third=follow("其中今天更新的",second);
        assertEquals("QUERY",json.valueToTree(runtime.plan(third,plan("INVENTORY",null,null,"PCB","TODAY"))).at("/plan/action").asText());
        String unrelated=follow("查询全部订单",first);
        assertEquals("CLARIFY",json.valueToTree(runtime.plan(unrelated,plan("ORDER",null,null,"PCB","CURRENT"))).at("/plan/action").asText());
    }
    @Test void restartRestoresOnlyScopeAndQueriesFreshFactsAfterAuthorization(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var store=new ConversationScopeStore(directory,"runtime",clock,"test-only-scope-signing-key-123456");
        NaturalLanguageRuntime.Access gate=(owner,operation)->{
            if(!admin.get())throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            return operation.apply(new NaturalLanguageRuntime.Data(overview,service));
        };
        NaturalLanguageRuntime.Research research=(role,evidence)->Map.of("status","NOT_CONFIGURED");
        var first=new NaturalLanguageRuntime(clock,gate,research,store);
        String ref=(String)first.create(json.valueToTree(Map.of("query","查询PCB库存","ownerUserId","42"))).get("runRef");
        when(overview.rows("INVENTORY",null,"PCB",null,null)).thenReturn(List.of(Map.of("record_id",9,"quantity_available",3)));
        when(overview.count("INVENTORY",null,"PCB",null,null)).thenReturn(1L);
        first.plan(ref,plan("INVENTORY",null,null,"PCB","CURRENT"));first.begin(ref,"SUPPLY");
        first.complete(ref,"SUPPLY",node("{\"summary\":\"OLD_MODEL_ANSWER\"}"));first.finalizeRun(ref);
        String disk=java.nio.file.Files.readString(directory.resolve("runtime").resolve(ref+".json"));
        assertFalse(disk.contains("OLD_MODEL_ANSWER"));assertFalse(disk.contains("\"facts\""));assertFalse(disk.contains("\"evidence\""));
        var restarted=new NaturalLanguageRuntime(clock,gate,research,new ConversationScopeStore(directory,"runtime",clock,"test-only-scope-signing-key-123456"));
        String follow=(String)restarted.create(json.valueToTree(Map.of("query","其中可用库存","ownerUserId","42","previousRunRef",ref))).get("runRef");
        when(overview.rows("INVENTORY",null,"PCB",null,null)).thenReturn(List.of(Map.of("record_id",9,"quantity_available",7)));
        when(overview.count("INVENTORY",null,"PCB",null,null)).thenReturn(1L);
        when(overview.selectedRows("INVENTORY",List.of(9L),null,null,Map.of(),"PCB",null,null)).thenReturn(List.of(Map.of("record_id",9,"quantity_available",7)));
        restarted.plan(follow,plan("INVENTORY",null,null,"PCB","CURRENT"));
        assertEquals("7",json.valueToTree(restarted.begin(follow,"SUPPLY")).at("/evidence/0/data/overview/rows/0/quantity_available").asText());
        String recap=(String)restarted.create(json.valueToTree(Map.of("query","把这些数据改成交接摘要","ownerUserId","42","previousRunRef",ref))).get("runRef");
        assertEquals("PCB",json.valueToTree(restarted.plan(recap,plan("RECAP",null,null,null,"CURRENT"))).at("/scope/keyword").asText());
        assertEquals("7",json.valueToTree(restarted.begin(recap,"SUPPLY")).at("/evidence/0/data/overview/rows/0/quantity_available").asText());
        assertThrows(ResponseStatusException.class,()->restarted.create(json.valueToTree(Map.of("query","其中库存","ownerUserId","99","previousRunRef",ref))));
        admin.set(false);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->restarted.create(json.valueToTree(Map.of("query","其中库存","ownerUserId","42","previousRunRef",ref)))).getStatusCode().value());
    }
    @Test void restoredRelativeDatesStayFixedUnlessUserExplicitlyRequestsNewToday(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        NaturalLanguageRuntime.Access gate=(owner,operation)->operation.apply(new NaturalLanguageRuntime.Data(overview,service));
        NaturalLanguageRuntime.Research research=(role,evidence)->Map.of("status","NOT_CONFIGURED");
        var first=new NaturalLanguageRuntime(clock,gate,research,new ConversationScopeStore(directory,"runtime",clock,"test-only-scope-signing-key-123456"));
        String ref=(String)first.create(json.valueToTree(Map.of("query","今天PCB库存","ownerUserId","42"))).get("runRef");
        first.plan(ref,plan("INVENTORY",null,null,"PCB","TODAY"));first.begin(ref,"SUPPLY");first.complete(ref,"SUPPLY",node("{\"summary\":\"ok\"}"));first.finalizeRun(ref);
        Clock nextDay=Clock.offset(clock,Duration.ofDays(1));
        var next=new NaturalLanguageRuntime(nextDay,gate,research,new ConversationScopeStore(directory,"runtime",nextDay,"test-only-scope-signing-key-123456"));
        String follow=(String)next.create(json.valueToTree(Map.of("query","其中可用库存","ownerUserId","42","previousRunRef",ref))).get("runRef");
        next.plan(follow,plan("INVENTORY",null,null,"PCB","TODAY"));
        assertEquals("2026-11-09 00:00:00",json.valueToTree(next.begin(follow,"SUPPLY")).at("/evidence/0/data/overview/startInclusive").asText());
        String explicit=(String)next.create(json.valueToTree(Map.of("query","其中今天更新的","ownerUserId","42","previousRunRef",ref))).get("runRef");
        next.plan(explicit,plan("INVENTORY",null,null,"PCB","TODAY"));
        assertEquals("2026-11-10 00:00:00",json.valueToTree(next.begin(explicit,"SUPPLY")).at("/evidence/0/data/overview/startInclusive").asText());
        for(String phrase:List.of("近七天","过去七天","最近七天")) {
            String seven=(String)next.create(json.valueToTree(Map.of("query","其中"+phrase+"更新的","ownerUserId","42","previousRunRef",ref))).get("runRef");
            next.plan(seven,plan("INVENTORY",null,null,"PCB","LAST_7_DAYS"));
            var evidence=json.valueToTree(next.begin(seven,"SUPPLY"));
            assertEquals("2026-11-04 00:00:00",evidence.at("/evidence/0/data/overview/startInclusive").asText(),phrase);
            assertEquals("2026-11-11 00:00:00",evidence.at("/evidence/0/data/overview/endExclusive").asText(),phrase);
        }
    }
    @Test void cancelledRunsAreNotRestoredFromScopeStorage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var store=new ConversationScopeStore(directory,"runtime",clock,"test-only-scope-signing-key-123456");
        NaturalLanguageRuntime.Access gate=(owner,operation)->operation.apply(new NaturalLanguageRuntime.Data(overview,service));
        NaturalLanguageRuntime.Research research=(role,evidence)->Map.of("status","NOT_CONFIGURED");
        var first=new NaturalLanguageRuntime(clock,gate,research,store);
        String ref=(String)first.create(json.valueToTree(Map.of("query","PCB库存","ownerUserId","42"))).get("runRef");
        first.plan(ref,plan("INVENTORY",null,null,"PCB","CURRENT"));first.cancel(ref);
        assertNull(store.load(ref,42));
        var next=new NaturalLanguageRuntime(clock,gate,research,store);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->next.create(json.valueToTree(Map.of("query","其中库存","ownerUserId","42","previousRunRef",ref)))).getStatusCode().value());
    }
    @Test void explicitDatesCombineWithStatusFilterAndRevalidateSameScope() throws Exception {
        String from="2026-11-01 00:00:00",to="2026-11-09 00:00:00";
        var rows=List.<Map<String,Object>>of(Map.of("record_id",1,"status","RUNNING"),Map.of("record_id",2,"status","COMPLETED"));
        when(overview.count("PRODUCTION",null,null,from,to)).thenReturn(2L);
        when(overview.rows("PRODUCTION",null,null,from,to)).thenReturn(rows);
        String ref=create("查询2026-11-01至2026-11-08未完成工单");
        runtime.plan(ref,plan("PRODUCTION",null,null,null,"CURRENT"));
        runtime.begin(ref,"PRODUCTION");runtime.complete(ref,"PRODUCTION",node("{\"summary\":\"已核对\"}"));
        JsonNode result=json.valueToTree(runtime.finalizeRun(ref));
        assertEquals("EVIDENCE_READY",result.path("verdict").asText());
        assertEquals(1,result.at("/facts/production/overview/rows").size());
        assertEquals(to,result.at("/facts/production/overview/endExclusive").asText());
        verify(overview,times(2)).rows("PRODUCTION",null,null,from,to);
        String next=(String)runtime.create(json.valueToTree(Map.of("query","其中正在生产的工单","ownerUserId","42","previousRunRef",ref))).get("runRef");
        runtime.plan(next,plan("PRODUCTION",null,null,null,"CURRENT"));
        JsonNode followup=json.valueToTree(runtime.begin(next,"PRODUCTION"));
        assertEquals(from,followup.at("/evidence/0/data/overview/startInclusive").asText());
        assertEquals(to,followup.at("/evidence/0/data/overview/endExclusive").asText());
    }
    @Test void progressAndCancellationRemainAvailableWhileDatabaseToolIsRunning() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(overview.rows("INVENTORY",null,null,null,null)).thenAnswer(call -> {
            entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return List.of();
        });
        String ref=create("查库存");runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT"));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var reading=executor.submit(()->runtime.begin(ref,"SUPPLY"));
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            JsonNode observed=executor.submit(()->json.<JsonNode>valueToTree(runtime.observe(ref))).get(1,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("RUNNING",observed.at("/tasks/0/status").asText());
            assertEquals("RUNNING",observed.at("/tasks/0/steps/0/status").asText());
            assertEquals(409,assertThrows(ResponseStatusException.class,()->runtime.begin(ref,"SUPPLY")).getStatusCode().value());
            assertEquals(409,assertThrows(ResponseStatusException.class,()->runtime.complete(ref,"SUPPLY",node("{\"summary\":\"early\"}"))).getStatusCode().value());
            runtime.cancel(ref);release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class,()->reading.get(2,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLED",json.valueToTree(runtime.observe(ref)).at("/tasks/0/status").asText());
            assertEquals(0,json.valueToTree(runtime.observe(ref)).at("/tasks/0/evidence").size());
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void databaseToolTimeoutEmitsSameCallFailureWithoutSuccessOrRawException() throws Exception {
        when(overview.rows("INVENTORY",null,null,null,null)).thenThrow(new IllegalStateException("SECRET database timeout",new java.sql.SQLTimeoutException()));
        String ref=create("查库存");runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT"));
        assertThrows(IllegalStateException.class,()->runtime.begin(ref,"SUPPLY"));
        JsonNode observed=json.valueToTree(runtime.observe(ref));
        assertEquals("FAILED",observed.at("/tasks/0/status").asText());
        var calls=new ArrayList<JsonNode>();
        for(JsonNode e:observed.at("/workflow/events"))if(e.path("toolId").asText().equals("OVERVIEW_INVENTORY"))calls.add(e);
        assertEquals(2,calls.size());
        assertEquals("RUNNING",calls.get(0).path("status").asText());
        assertEquals("FAILED",calls.get(1).path("status").asText());
        assertFalse(calls.get(0).path("toolCallId").asText().isBlank());
        assertEquals(calls.get(0).get("toolCallId"),calls.get(1).get("toolCallId"));
        assertFalse(observed.toString().contains("SECRET"));
        assertEquals(0,observed.at("/tasks/0/evidence").size());
    }
    @Test void scopedOverviewDoesNotSilentlyLimitRowsToOneHundred() throws Exception {
        var config = new org.apache.ibatis.session.Configuration();
        config.addMapper(NaturalBusinessReadMapper.class);
        var parameters = new HashMap<String,Object>();
        parameters.put("domain", "INVENTORY"); parameters.put("id", 100L);
        parameters.put("keyword", "PCB"); parameters.put("start", "2026-11-01"); parameters.put("end", "2026-12-01");
        var statement = config.getMappedStatement(NaturalBusinessReadMapper.class.getName() + ".rows").getBoundSql(parameters);
        assertFalse(statement.getSql().toUpperCase(Locale.ROOT).contains("LIMIT 100"));
        assertTrue(statement.getSql().contains("LOCATE(?,t.search_text)"));
        assertTrue(statement.getSql().contains("t.event_at >= ? AND t.event_at < ?"));
        assertTrue(statement.getSql().contains("order_id=?"));
        var records = java.util.stream.IntStream.range(0, 165).mapToObj(i -> Map.<String,Object>of("record_id", i, "material_name", "PCB", "quantity_available", "0")).toList();
        when(overview.rows("INVENTORY",null,null,null,null)).thenReturn(records);
        when(overview.count("INVENTORY",null,null,null,null)).thenReturn(165L);
        String ref = create("查库存"); runtime.plan(ref, plan("INVENTORY",null,null,null,"CURRENT"));
        JsonNode started = json.valueToTree(runtime.begin(ref,"SUPPLY"));
        assertEquals(165, started.at("/evidence/0/data/overview/rows").size());
        assertFalse(started.at("/evidence/0/data/overview/truncated").asBoolean());
    }
    final ObjectMapper json=new ObjectMapper();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-08T18:30:00Z"),ZoneOffset.UTC);
    final NaturalBusinessReadMapper overview=mock(NaturalBusinessReadMapper.class);
    final AiDeliveryReadMapper delivery=mock(AiDeliveryReadMapper.class);
    final AiBusinessReadMapper business=mock(AiBusinessReadMapper.class);
    final AtomicBoolean admin=new AtomicBoolean(true);
    final AiDeliveryQueryService service=new AiDeliveryQueryService(delivery,business,new DeliveryEvidenceRegistry(clock),clock);
    final NaturalLanguageRuntime runtime=new NaturalLanguageRuntime(clock,(owner,operation)->{
        if(!admin.get())throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        return operation.apply(new NaturalLanguageRuntime.Data(overview,service));
    });
    JsonNode node(String text)throws Exception{return json.readTree(text);}
    String create(String query)throws Exception{return (String)runtime.create(json.valueToTree(Map.of("query",query,"ownerUserId","42"))).get("runRef");}
    JsonNode plan(String intent,String pk,String no,String keyword,String period)throws Exception{
        var p=new LinkedHashMap<String,Object>();p.put("action","QUERY");p.put("intents",List.of(intent));p.put("orderPk",pk);p.put("orderNo",no);p.put("keyword",keyword);p.put("period",period);p.put("question","");return json.valueToTree(p);
    }
    @Test void contextHasShanghaiDateAndNoInventedAgents()throws Exception{
        var context=runtime.context(create("查库存"));
        assertEquals("2026-11-09",context.get("currentDate"));assertEquals("Asia/Shanghai",context.get("timeZone"));
        assertEquals(List.of(),context.get("requiredAgents"));assertEquals("NATURAL",context.get("queryMode"));
    }
    @Test void overviewUsesLiteralKeywordAndShanghaiSevenDayRangeWithCountAndTruncation()throws Exception{
        when(overview.count("INVENTORY",null,"螺丝","2026-11-03 00:00:00","2026-11-10 00:00:00")).thenReturn(203L);
        when(overview.rows("INVENTORY",null,"螺丝","2026-11-03 00:00:00","2026-11-10 00:00:00")).thenReturn(List.of(Map.of("material_name","螺丝","quantity_available","10","unit","件")));
        String ref=create("最近7天螺丝库存");
        assertEquals(List.of("SUPPLY"),runtime.plan(ref,plan("INVENTORY",null,null,"螺丝","LAST_7_DAYS")).get("requiredAgents"));
        JsonNode started=json.valueToTree(runtime.begin(ref,"SUPPLY"));
        assertEquals(203,started.at("/evidence/0/data/overview/totalRecords").asInt());assertTrue(started.at("/evidence/0/data/overview/truncated").asBoolean());
        runtime.complete(ref,"SUPPLY",node("{\"summary\":\"模型声称库存1000，但不应进入事实\"}"));
        var result=runtime.finalizeRun(ref);assertEquals("EVIDENCE_READY",result.get("verdict"));assertTrue(result.get("reply").toString().contains("203条"));
        assertFalse(result.get("reply").toString().contains("1000"));
    }
    @Test void changedOverviewHidesOldFactsAndOldCommentary()throws Exception{
        String ref=create("查生产概览");runtime.plan(ref,plan("PRODUCTION",null,null,null,"CURRENT"));
        runtime.begin(ref,"PRODUCTION");runtime.complete(ref,"PRODUCTION",node("{\"summary\":\"old\"}"));
        when(overview.count("PRODUCTION",null,null,null,null)).thenReturn(1L);
        var result=runtime.finalizeRun(ref);assertEquals("DATA_CHANGED",result.get("verdict"));assertEquals(Map.of(),result.get("facts"));assertEquals(List.of(),result.get("expertExplanations"));
        JsonNode observation=json.valueToTree(runtime.observe(ref));
        assertTrue(observation.at("/workflow/invalidated").asBoolean());
        assertTrue(observation.at("/workflow/plan").isNull());
        assertEquals(0,observation.at("/workflow/events").size());
        assertEquals(0,observation.at("/tasks/0/evidence").size());
    }
    @Test void workflowKeepsShortStepsAndRecordsCancellationWithoutModelText() throws Exception {
        String ref=create("查库存");JsonNode input=plan("INVENTORY",null,null,null,"CURRENT");
        runtime.plan(ref,input);runtime.plan(ref,input);
        String taskId=(String)runtime.begin(ref,"SUPPLY").get("taskId");
        runtime.begin(ref,"SUPPLY");runtime.cancel(ref);
        JsonNode workflow=json.valueToTree(runtime.observe(ref)).path("workflow");
        assertEquals("SUPPLY",workflow.at("/plan/requiredAgents/0").asText());
        assertEquals("PLAN_CONFIRMED",workflow.at("/events/0/kind").asText());
        assertEquals("AGENT_STARTED",workflow.at("/events/1/kind").asText());
        assertEquals(taskId,workflow.at("/events/1/taskId").asText());
        assertEquals("STEP_STARTED",workflow.at("/events/2/kind").asText());
        assertEquals("STEP_COMPLETED",workflow.at("/events/4/kind").asText());
        assertEquals("CANCELLED",workflow.at("/events/7/kind").asText());
        assertEquals(8,workflow.at("/events/7/seq").asInt());
        assertFalse(workflow.toString().contains("查库存"));
    }
    @Test void nonexistentExactOrderClarifiesAndNeverReadsGlobalOverview()throws Exception{
        String ref=create("订单9999交付情况");runtime.plan(ref,plan("DELIVERY","9999",null,null,"CURRENT"));
        var result=runtime.finalizeRun(ref);assertEquals("CLARIFY",result.get("verdict"));verifyNoInteractions(overview);
    }
    @Test void ungroundedOrderAndKeywordClarifyBeforeReadingAnything()throws Exception{
        String ref=create("查质量");runtime.plan(ref,plan("QUALITY","123",null,null,"CURRENT"));
        assertEquals("CLARIFY",runtime.finalizeRun(ref).get("verdict"));verifyNoInteractions(delivery,overview);
        ref=create("查库存");runtime.plan(ref,plan("INVENTORY",null,null,"模型编造","CURRENT"));assertEquals("CLARIFY",runtime.finalizeRun(ref).get("verdict"));
    }
    @Test void unsupportedDatesNeverSilentlyBecomeCurrentOverview()throws Exception{
        String ref=create("截至昨天的库存");runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT"));assertEquals("CLARIFY",runtime.finalizeRun(ref).get("verdict"));verifyNoInteractions(overview);
    }
    @Test void explicitAvailableFilterUsesRealValuesAndKeepsWholeMatchingSet()throws Exception {
        when(overview.count("INVENTORY",null,null,null,null)).thenReturn(3L);
        when(overview.rows("INVENTORY",null,null,null,null)).thenReturn(List.of(
            Map.of("record_id",1,"quantity_available","0"),Map.of("record_id",2,"quantity_available","5"),Map.of("record_id",3)));
        String ref=create("查询库存不足的物料");
        assertEquals(List.of("SUPPLY"),runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT")).get("requiredAgents"));
        JsonNode result=json.valueToTree(runtime.begin(ref,"SUPPLY"));
        assertEquals(1,result.at("/evidence/0/data/overview/rows").size());
        assertEquals(1,result.at("/evidence/0/data/overview/totalRecords").asInt());
        assertEquals("NO_AVAILABLE",result.at("/evidence/0/data/overview/filter").asText());
    }
    @Test void pureKnowledgeUsesResearchWithoutReadingBusinessTables()throws Exception {
        var knowledgeRuntime=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,service)),
            new NaturalLanguageRuntime.Research() {
                public Map<String,Object> retrieve(String role,List<Map<String,Object>> records) {throw new AssertionError("Not a business query");}
                public Map<String,Object> retrieveQuestion(String query) {return Map.of("status","MATCHES","hits",List.of(Map.of("evidence_ref","paper:1")),"graph_paths",List.of());}
            });
        String ref=(String)knowledgeRuntime.create(json.valueToTree(Map.of("query","LCD面板常见缺陷有哪些","ownerUserId","42"))).get("runRef");
        knowledgeRuntime.plan(ref,plan("KNOWLEDGE",null,null,null,"CURRENT"));
        assertEquals(List.of(),knowledgeRuntime.begin(ref,"QUALITY").get("evidence"));
        knowledgeRuntime.research(ref,"QUALITY");
        knowledgeRuntime.complete(ref,"QUALITY",json.valueToTree(Map.of("summary","{\"summary\":\"研究资料\",\"findings\":[{\"text\":\"文献描述\",\"evidenceRefs\":[\"paper:1\"]}],\"hypotheses\":[],\"actions\":[],\"missing\":[]}")));
        JsonNode result=json.valueToTree(knowledgeRuntime.finalizeRun(ref));
        assertEquals("KNOWLEDGE",result.at("/intents/0").asText());
        assertEquals(1,result.at("/analyses/0/findings").size());
        assertEquals(1,result.path("knowledgeEvidence").size());
        verifyNoInteractions(overview,delivery,business);
    }
    @Test void exactOrderFiltersAndPureKnowledgeOrderScopesAreNeverSilentlyIgnored()throws Exception {
        for(String intent:List.of("KNOWLEDGE","QUALITY","PRODUCTION")) {
            String query=switch(intent){case "KNOWLEDGE"->"订单100的SOP";case "QUALITY"->"查询订单100不合格记录";default->"订单100未完成工单";};
            String ref=create(query);runtime.plan(ref,plan(intent,"100",null,null,"CURRENT"));
            assertEquals("CLARIFY",runtime.finalizeRun(ref).get("verdict"));
        }
        verifyNoInteractions(overview,delivery,business);
    }
    @Test void maintenanceAndStockHistoryHaveSeparateToolsAndRecordedDateBasis()throws Exception {
        for(String domain:List.of("MAINTENANCE","ALARMS","STOCK_MOVEMENT")) {
            String ref=create("查询维修、报警和库存变动记录");
            String role=domain.equals("STOCK_MOVEMENT")?"SUPPLY":"EQUIPMENT";
            assertEquals(List.of(role),runtime.plan(ref,plan(domain,null,null,null,"CURRENT")).get("requiredAgents"));
            JsonNode task=json.valueToTree(runtime.begin(ref,role));
            assertEquals(domain,task.at("/evidence/0/data/overview/domain").asText());
            verify(overview).rows(domain,null,null,null,null);
        }
    }
    @Test void currentOrderOutsideLegacyAllowlistUsesExistingDeliveryCalculatorsAndDependencies()throws Exception{
        var head=List.<Map<String,Object>>of(Map.of("order_id",987L,"order_no","ORD-987"));when(delivery.resolve(null,"ORD-987")).thenReturn(head);when(delivery.order(987L)).thenReturn(head);
        String ref=create("ORD-987交付");var context=runtime.plan(ref,plan("DELIVERY",null,"ORD-987",null,"CURRENT"));
        assertEquals(List.of("ORDER","SUPPLY","PRODUCTION"),context.get("requiredAgents"));assertThrows(ResponseStatusException.class,()->runtime.begin(ref,"PRODUCTION"));
        for(String role:List.of("ORDER","SUPPLY","PRODUCTION")){runtime.begin(ref,role);runtime.complete(ref,role,node("{\"summary\":\"当前记录\"}"));}
        var result=runtime.finalizeRun(ref);assertEquals("INSUFFICIENT_EVIDENCE",result.get("verdict"));assertNotNull(result.get("revalidation"));
        assertEquals(5,((List<?>)result.get("queryRefs")).size());
        JsonNode workflow=json.valueToTree(runtime.observe(ref)).path("workflow");
        assertEquals("SUPPLY",workflow.at("/plan/dependencies/PRODUCTION/1").asText());
        List<String> handoffs=new ArrayList<>();
        workflow.path("events").forEach(event->{
            if("HANDOFF".equals(event.path("kind").asText()))
                handoffs.add(event.path("fromAgent").asText()+":"+event.path("toAgent").asText());
        });
        assertEquals(List.of("ORDER:SUPPLY","ORDER:PRODUCTION","SUPPLY:PRODUCTION"),handoffs);
    }
    @Test void clarificationCanCarryPriorOwnerScopeButCannotCrossOwners()throws Exception{
        String previous=create("查订单交付");runtime.plan(previous,plan("DELIVERY",null,null,null,"CURRENT"));runtime.finalizeRun(previous);
        assertThrows(ResponseStatusException.class,()->runtime.create(json.valueToTree(Map.of("query","987","ownerUserId","99","previousRunRef",previous))));
        var next=runtime.create(json.valueToTree(Map.of("query","987","ownerUserId","42","previousRunRef",previous)));
        assertEquals("查订单交付",json.valueToTree(next).at("/previousContext/userQuery").asText());
    }
    @Test void revokedRoleBlocksEvenCachedContextAndFinalResults()throws Exception{
        String ref=create("库存");admin.set(false);assertThrows(ResponseStatusException.class,()->runtime.context(ref));assertThrows(ResponseStatusException.class,()->runtime.observe(ref));
    }
    @Test void strictPlanRejectsLegacyAndUnknownKeys()throws Exception{
        String ref=create("库存");assertThrows(ResponseStatusException.class,()->runtime.plan(ref,node("{\"action\":\"LEGACY\"}")));
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)plan("INVENTORY",null,null,null,"CURRENT");p.put("sql","SELECT");assertThrows(ResponseStatusException.class,()->runtime.plan(ref,p));
    }
    @Test void quantityCannotBecomeOrderIdAndExplicitTodayCannotBeDropped()throws Exception{
        String ref=create("100台产品的库存");runtime.plan(ref,plan("INVENTORY","100",null,null,"CURRENT"));
        assertEquals("CLARIFY",runtime.finalizeRun(ref).get("verdict"));verifyNoInteractions(delivery,overview);
        ref=create("今天质量检验");runtime.plan(ref,plan("QUALITY",null,null,null,"CURRENT"));
        assertEquals("CLARIFY",runtime.finalizeRun(ref).get("verdict"));verifyNoInteractions(overview);
    }
    @Test void httpNaturalRoutesUseBootstrapAndSupRolesAndPreserveTaskQueryMode()throws Exception{
        Map<String,String> env=new HashMap<>();for(String role:List.of("BOOTSTRAP","SUP","ORDER","SUPPLY","PRODUCTION","QUALITY","EQUIPMENT","AFTERSALES"))env.put("MES_AGENT_"+role+"_KEY",(role+"_"+"x".repeat(40)));env.put("MES_AGENT_ALLOWED_ORDER_IDS","100,101");
        try(var server=new Stage5RuntimeServer(env,clock,operation->operation.apply(service),runtime,0)) {
            server.start();var client=java.net.http.HttpClient.newHttpClient();String base="http://127.0.0.1:"+server.port();
            var create=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/stage5/natural/runs")).header("Authorization","Bearer "+env.get("MES_AGENT_BOOTSTRAP_KEY")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"query\":\"库存\",\"ownerUserId\":\"42\"}")).build();
            var response=client.send(create,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(201,response.statusCode());String ref=node(response.body()).path("runRef").asText();
            String prefix=base+"/stage5/runs/"+ref;
            var planRequest=java.net.http.HttpRequest.newBuilder(java.net.URI.create(prefix+"/plan")).header("Authorization","Bearer "+env.get("MES_AGENT_SUP_KEY")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(plan("INVENTORY",null,null,null,"CURRENT").toString())).build();
            assertEquals(200,client.send(planRequest,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            var begin=java.net.http.HttpRequest.newBuilder(java.net.URI.create(prefix+"/tasks/SUPPLY/begin")).header("Authorization","Bearer "+env.get("MES_AGENT_SUPPLY_KEY")).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
            var task=client.send(begin,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,task.statusCode());assertEquals("NATURAL",node(task.body()).path("queryMode").asText());
        }
    }
    void order100(){var head=List.<Map<String,Object>>of(Map.of("order_id",100L,"order_no","ORD-100"));when(delivery.resolve(100L,null)).thenReturn(head);when(delivery.order(100L)).thenReturn(head);}
    String follow(String query,String prior)throws Exception{return (String)runtime.create(json.valueToTree(Map.of("query",query,"ownerUserId","42","previousRunRef",prior))).get("runRef");}
    @Test void rejectedModelOrderCannotBeLaunderedThroughPreviousPlan()throws Exception {
        order100();String first=create("查库存");runtime.plan(first,plan("INVENTORY","100",null,null,"CURRENT"));runtime.finalizeRun(first);
        String next=follow("继续",first);runtime.plan(next,plan("INVENTORY","100",null,null,"CURRENT"));
        assertEquals("CLARIFY",runtime.finalizeRun(next).get("verdict"));verify(delivery,never()).resolve(any(),any());
    }
    @Test void bareNumberRequiresCompletedOrderClarificationWithMatchingIntent()throws Exception {
        order100();String bare=create("100");runtime.plan(bare,plan("INVENTORY","100",null,null,"CURRENT"));assertEquals("CLARIFY",runtime.finalizeRun(bare).get("verdict"));
        String first=create("查订单交付");runtime.plan(first,plan("DELIVERY",null,null,null,"CURRENT"));
        String premature=follow("100",first);runtime.plan(premature,plan("DELIVERY","100",null,null,"CURRENT"));assertEquals("CLARIFY",runtime.finalizeRun(premature).get("verdict"));
        runtime.finalizeRun(first);
        String wrongIntent=follow("100",first);runtime.plan(wrongIntent,plan("QUALITY","100",null,null,"CURRENT"));assertEquals("CLARIFY",runtime.finalizeRun(wrongIntent).get("verdict"));
        String accepted=follow("100",first);assertEquals(List.of("ORDER","SUPPLY","PRODUCTION"),runtime.plan(accepted,plan("DELIVERY","100",null,null,"CURRENT")).get("requiredAgents"));
    }
    @Test void modelCannotOmitExplicitDateWarehouseOrOrderConstraints()throws Exception {
        for(String query:List.of("查2026年10月库存","查A仓库库存","查订单100库存","查ORD-100订单库存","查最近500天库存")) {
            String ref=create(query);runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT"));
            assertEquals("CLARIFY",runtime.finalizeRun(ref).get("verdict"),query);
        }
        verifyNoInteractions(overview,delivery);
    }
    @Test void globalQualityReplyDoesNotPretendToHaveAnOrderScope()throws Exception {
        String ref=create("查质量概览");runtime.plan(ref,plan("QUALITY",null,null,null,"CURRENT"));runtime.begin(ref,"QUALITY");runtime.complete(ref,"QUALITY",node("{\"summary\":\"质检概览\"}"));
        String reply=(String)runtime.finalizeRun(ref).get("reply");assertFalse(reply.contains("订单null"));assertFalse(reply.contains("候选关联证据"));assertTrue(reply.contains("质检"));
    }
    @Test void exactOrderSpecialistsRetainDetailedEvidenceAndContradictoryRelations()throws Exception {
        order100();when(business.read(100L)).thenReturn(Map.of(
            "quality_inspection",List.of(Map.of("inspection_id",1L,"sample_quantity","10","qualified_quantity","8","unqualified_quantity","2","relation_status","CONTRADICTORY")),
            "quality_inspection_item",List.of(Map.of("inspection_item_id",2L,"inspection_id",1L,"item_name","尺寸")),
            "nonconforming_product",List.of(Map.of("nonconforming_id",3L,"defect_type","裂纹")),
            "andon_alarm",List.of(Map.of("alarm_id",4L,"equipment_id",7L,"relation_status","CANDIDATE_EQUIPMENT")),
            "equipment_maintenance_record",List.of(Map.of("maintenance_id",5L,"equipment_id",7L)),
            "after_sales_case",List.of(Map.of("case_id",6L,"order_id",200L,"relation_status","CONTRADICTORY")),
            "after_sales_task",List.of(Map.of("task_id",7L,"case_no","AS-6"))));
        for(String[] pair:List.of(new String[]{"QUALITY","Q1"},new String[]{"EQUIPMENT","D1"},new String[]{"AFTERSALES","A1"})) {
            String ref=create("订单100的"+pair[0]);runtime.plan(ref,plan(pair[0],"100",null,null,"CURRENT"));
            if(pair[0].equals("AFTERSALES")){runtime.begin(ref,"QUALITY");runtime.complete(ref,"QUALITY",node("{\"summary\":\"质量协查\"}"));}
            JsonNode task=json.valueToTree(runtime.begin(ref,pair[0]));assertFalse(task.at("/evidence/0/data").has("overview"));
            runtime.complete(ref,pair[0],node("{\"summary\":\"已保留关联和缺失依据\"}"));JsonNode result=json.valueToTree(runtime.finalizeRun(ref));
            assertFalse(result.path("reply").asText().isBlank());
            if(pair[0].equals("QUALITY")){assertEquals(1,result.at("/facts/quality/inspectionItems").size());assertEquals(1,result.at("/facts/quality/nonconformingProducts").size());assertTrue(result.at("/facts/quality/inspections/0/samplePassRate").isNull());assertTrue(result.path("missing").toString().contains("CONTRADICTORY_RELATION"));}
            if(pair[0].equals("EQUIPMENT")){assertEquals(1,result.at("/facts/equipment/alarms").size());assertEquals(1,result.at("/facts/equipment/maintenanceRecords").size());}
            if(pair[0].equals("AFTERSALES")){assertEquals(1,result.at("/facts/aftersales/tasks").size());assertEquals(0,result.at("/facts/aftersales/tracePaths").size());assertTrue(result.path("missing").toString().contains("CONTRADICTORY_CASE"));}
        }
        verifyNoInteractions(overview);
    }
    @Test void exactAftersalesNeedsQualityReceiptAndHandsItOver()throws Exception {
        order100();String ref=create("追溯订单100售后");var context=runtime.plan(ref,plan("AFTERSALES","100",null,null,"CURRENT"));
        assertEquals(List.of("QUALITY","AFTERSALES"),context.get("requiredAgents"));
        assertThrows(ResponseStatusException.class,()->runtime.begin(ref,"AFTERSALES"));
        runtime.begin(ref,"QUALITY");var receipt=runtime.complete(ref,"QUALITY",node("{\"summary\":\"质量待核查\"}"));
        var task=json.valueToTree(runtime.begin(ref,"AFTERSALES"));
        assertEquals(receipt.get("resultRef"),task.at("/upstreamFindings/0/resultRef").asText());
        assertEquals("QUALITY",task.at("/upstreamFindings/0/agentId").asText());
    }
    @Test void aftersalesHandoffContainsRecordedQuantitiesInsteadOfQualityModelSummary()throws Exception {
        order100();when(business.read(100L)).thenReturn(Map.of(
            "quality_inspection",List.of(Map.of("inspection_id",1L,"sample_quantity","10","qualified_quantity","8","unqualified_quantity","2","relation_status","EXPLICIT")),
            "quality_inspection_item",List.of(Map.of("inspection_item_id",2L,"inspection_id",1L,"item_name","不应重复交接的检测项原文","result","PASSED"))));
        String ref=create("追溯订单100售后");runtime.plan(ref,plan("AFTERSALES","100",null,null,"CURRENT"));
        JsonNode quality=json.valueToTree(runtime.begin(ref,"QUALITY"));
        var analysis=Map.of("summary","模型误写2项待检","findings",List.of(),"hypotheses",List.of(),"actions",List.of(),"missing",List.of());
        var receipt=runtime.complete(ref,"QUALITY",json.valueToTree(Map.of("summary",json.writeValueAsString(analysis))));
        JsonNode task=json.valueToTree(runtime.begin(ref,"AFTERSALES"));
        JsonNode upstream=task.at("/upstreamFindings/0");
        assertEquals("BUSINESS_EVIDENCE_SNAPSHOT",upstream.path("authority").asText());
        assertEquals(receipt.get("resultRef"),upstream.path("resultRef").asText());
        assertFalse(upstream.has("summary"));assertFalse(upstream.toString().contains("模型误写2项待检"));
        assertFalse(upstream.toString().contains("不应重复交接的检测项原文"));
        assertEquals(quality.at("/evidence/0/queryRef").asText(),upstream.at("/evidence/0/queryRef").asText());
        assertEquals("2",upstream.at("/evidence/0/data/inspections/0/unqualified_quantity").asText());
        assertEquals("0.800000",upstream.at("/evidence/0/data/inspections/0/samplePassRate").asText());
        assertEquals(1,upstream.at("/evidence/0/data/qualityMetrics/inspectionRecordCount").asInt());
        assertTrue(upstream.at("/evidence/0/data/visionEvidence").isObject());
        assertEquals(1,task.path("evidence").size());assertTrue(task.at("/evidence/0/data/cases").isArray());
    }
    @Test void aftersalesAcceptsOnlyActuallyHandedBusinessRefsAndRejectsAnotherRunOrUnhandedRole()throws Exception {
        order100();
        String foreign=create("订单100质量");runtime.plan(foreign,plan("QUALITY","100",null,null,"CURRENT"));
        String foreignRef=json.valueToTree(runtime.begin(foreign,"QUALITY")).at("/evidence/0/queryRef").asText();
        String ref=create("订单100售后和设备");
        var multi=(com.fasterxml.jackson.databind.node.ObjectNode)plan("AFTERSALES","100",null,null,"CURRENT");
        multi.set("intents",json.valueToTree(List.of("AFTERSALES","EQUIPMENT")));runtime.plan(ref,multi);
        String unhanded=json.valueToTree(runtime.begin(ref,"EQUIPMENT")).at("/evidence/0/queryRef").asText();
        runtime.complete(ref,"EQUIPMENT",node("{\"summary\":\"设备查询\"}"));
        String upstream=json.valueToTree(runtime.begin(ref,"QUALITY")).at("/evidence/0/queryRef").asText();
        runtime.complete(ref,"QUALITY",node("{\"summary\":\"质量摘要不得当证据\"}"));runtime.begin(ref,"AFTERSALES");
        var findings=List.of(Map.of("text","本轮交接的质量记录","evidenceRefs",List.of(upstream)),
            Map.of("text","其他轮查询","evidenceRefs",List.of(foreignRef)),Map.of("text","未交接设备查询","evidenceRefs",List.of(unhanded)));
        var analysis=Map.of("summary","仅保留授权交接依据","findings",findings,"hypotheses",List.of(),"actions",List.of(),"missing",List.of());
        JsonNode receipt=json.valueToTree(runtime.complete(ref,"AFTERSALES",json.valueToTree(Map.of("summary",json.writeValueAsString(analysis)))));
        assertEquals("PARTIAL_INVALID_REFERENCES",receipt.at("/analysis/status").asText());
        assertEquals(1,receipt.at("/analysis/findings").size());
        assertEquals(upstream,receipt.at("/analysis/findings/0/evidenceRefs/0").asText());
        assertEquals("本轮交接的质量记录",receipt.at("/analysis/findings/0/text").asText());
    }
    @Test void analysisAcceptsOnlyIssuedCitationsAndResearchIsRecordedOnce()throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var r=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,service)),(role,evidence)->{
            calls.incrementAndGet();return Map.of("status","MATCHES","mode_used","vector","hits",List.of(Map.of("evidence_ref","release:paper:chunk")),"graph_paths",List.of());
        });
        String ref=(String)r.create(json.valueToTree(Map.of("query","分析质量问题","ownerUserId","42"))).get("runRef");
        r.plan(ref,plan("QUALITY",null,null,null,"CURRENT"));JsonNode task=json.valueToTree(r.begin(ref,"QUALITY"));
        String q=task.at("/evidence/0/queryRef").asText();r.research(ref,"QUALITY");r.research(ref,"QUALITY");assertEquals(1,calls.get());
        var analysis=Map.of("summary","需要复核","findings",List.of(Map.of("text","已查询检验记录","evidenceRefs",List.of(q))),"hypotheses",List.of(),"actions",List.of(),"missing",List.of());
        r.complete(ref,"QUALITY",json.valueToTree(Map.of("summary",json.writeValueAsString(analysis))));
        JsonNode result=json.valueToTree(r.finalizeRun(ref));assertEquals("VALIDATED_REFERENCES",result.at("/analyses/0/status").asText());
        assertEquals("MATCHES",result.at("/knowledgeEvidence/0/status").asText());
        var ids=new ArrayList<String>();
        json.valueToTree(r.observe(ref)).at("/tasks/0/steps").forEach(s->ids.add(s.path("id").asText()));
        assertEquals(1,Collections.frequency(ids,"SOP_LOOKUP"));
        assertEquals(1,Collections.frequency(ids,"KNOWLEDGE_RETRIEVAL"));
        assertTrue(ids.indexOf("SOP_LOOKUP")<ids.indexOf("KNOWLEDGE_RETRIEVAL"));
    }
    @Test void slowResearchDoesNotBlockObservationAndCancellation()throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var r=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,service)),(role,evidence)->{
            entered.countDown();try{release.await(4,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return Map.of("status","NO_MATCHES","hits",List.of(),"graph_paths",List.of());
        });
        String ref=(String)r.create(json.valueToTree(Map.of("query","质量分析","ownerUserId","42"))).get("runRef");
        r.plan(ref,plan("QUALITY",null,null,null,"CURRENT"));r.begin(ref,"QUALITY");
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();var future=executor.submit(()->r.research(ref,"QUALITY"));
        try {assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->assertEquals("RUNNING",r.observe(ref).get("status")));
            r.cancel(ref);release.countDown();assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(2,java.util.concurrent.TimeUnit.SECONDS));
            JsonNode events=json.valueToTree(r.observe(ref)).at("/workflow/events");
            assertEquals("CANCELLED",events.get(events.size()-1).path("kind").asText());
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void failedResearchIsRecordedWithoutExposingProviderData() throws Exception {
        var r=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,service)),
            (role,evidence)->Map.of("status","SECRET_PROVIDER_TEXT"));
        String ref=(String)r.create(json.valueToTree(Map.of("query","质量分析","ownerUserId","42"))).get("runRef");
        r.plan(ref,plan("QUALITY",null,null,null,"CURRENT"));r.begin(ref,"QUALITY");r.research(ref,"QUALITY");
        JsonNode events=json.valueToTree(r.observe(ref)).at("/workflow/events");
        var failed=new ArrayList<JsonNode>();
        events.forEach(event->{if("FAILED".equals(event.path("status").asText()))failed.add(event);});
        assertEquals(1,failed.size());
        assertEquals("STEP_COMPLETED",failed.get(0).path("kind").asText());
        assertTrue(failed.get(0).path("label").asText().contains("GraphRAG"));
        assertFalse(events.toString().contains("SECRET_PROVIDER_TEXT"));
    }
    @Test void failedToolCannotBeRepeatedOrCompletedAndDoesNotLeakProviderErrors() throws Exception {
        when(overview.count("INVENTORY",null,null,null,null)).thenThrow(new IllegalStateException("SECRET_DB_ERROR"));
        String ref=create("查库存");runtime.plan(ref,plan("INVENTORY",null,null,null,"CURRENT"));
        assertThrows(IllegalStateException.class,()->runtime.begin(ref,"SUPPLY"));
        for(int attempt=0;attempt<40;attempt++)assertThrows(ResponseStatusException.class,()->runtime.begin(ref,"SUPPLY"));
        JsonNode observation=json.valueToTree(runtime.observe(ref));
        assertEquals("FAILED",observation.at("/tasks/0/status").asText());
        assertTrue(observation.at("/workflow/events").size()<128);
        assertFalse(observation.toString().contains("SECRET_DB_ERROR"));
        verify(overview,times(1)).count("INVENTORY",null,null,null,null);
    }
}
