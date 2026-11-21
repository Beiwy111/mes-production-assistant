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
        first.plan(ref,plan("INVENTORY",null,null,"PCB","CURRENT"));first.begin(ref,"SUPPLY");
        first.complete(ref,"SUPPLY",node("{\"summary\":\"OLD_MODEL_ANSWER\"}"));first.finalizeRun(ref);
        String disk=java.nio.file.Files.readString(directory.resolve("runtime").resolve(ref+".json"));
        assertFalse(disk.contains("OLD_MODEL_ANSWER"));assertFalse(disk.contains("\"facts\""));assertFalse(disk.contains("\"evidence\""));
        var restarted=new NaturalLanguageRuntime(clock,gate,research,new ConversationScopeStore(directory,"runtime",clock,"test-only-scope-signing-key-123456"));
        String follow=(String)restarted.create(json.valueToTree(Map.of("query","其中可用库存","ownerUserId","42","previousRunRef",ref))).get("runRef");
        when(overview.rows("INVENTORY",null,"PCB",null,null)).thenReturn(List.of(Map.of("record_id",9,"quantity_available",7)));
        when(overview.count("INVENTORY",null,"PCB",null,null)).thenReturn(1L);
        restarted.plan(follow,plan("INVENTORY",null,null,"PCB","CURRENT"));
        assertEquals("7",json.valueToTree(restarted.begin(follow,"SUPPLY")).at("/evidence/0/data/overview/rows/0/quantity_available").asText());
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
        assertEquals(5,json.valueToTree(r.observe(ref)).at("/tasks/0/steps").size());
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
