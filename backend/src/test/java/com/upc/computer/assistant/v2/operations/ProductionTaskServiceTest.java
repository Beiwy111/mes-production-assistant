package com.upc.computer.assistant.v2.operations;

import com.fasterxml.jackson.databind.*;
import com.upc.computer.ai.action.*;
import com.upc.computer.assistant.v2.orders.OrderSessionGuard;
import com.upc.computer.common.*;
import com.upc.computer.dto.LoginResponse;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionTaskServiceTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    ProductionTaskModel model;ProductionTaskTools tools;ProductionOperationService operations;AgentActionPlanService plans;
    RedisUtil redis;OrderSessionGuard guard;ProductionTaskService service;LoginResponse user;
    final Map<String,Object> cache=new HashMap<>();final List<Map<String,Object>> events=new CopyOnWriteArrayList<>();
    @BeforeEach void setup(){
        model=mock(ProductionTaskModel.class);tools=mock(ProductionTaskTools.class);operations=mock(ProductionOperationService.class);
        plans=mock(AgentActionPlanService.class);redis=mock(RedisUtil.class);guard=mock(OrderSessionGuard.class);
        user=new LoginResponse();user.setUserId(1L);user.setUsername("admin");user.setRoleCode("ADMIN");
        when(guard.requireActive("auth")).thenReturn(user);
        when(redis.getJson(anyString(),eq(Map.class))).thenAnswer(i->cache.get(i.getArgument(0)));
        doAnswer(i->{cache.put(i.getArgument(0),json.convertValue(i.getArgument(1),Map.class));return null;}).when(redis).setJson(anyString(),any(),eq(86400L));
        when(tools.catalog(user)).thenReturn(List.of(Map.of("tool","orders"),Map.of("tool","resources"),Map.of("tool","preview")));
        service=new ProductionTaskService(model,tools,operations,plans,redis,guard,json);
    }
    @AfterEach void stop(){service.close();}
    JsonNode plan(Object... calls){return json.valueToTree(Map.of("goal","安排选定订单的计划及派工","reply","请选择订单","calls",List.of(calls)));}
    Map<String,Object> call(String tool,Map<String,Object> args){return Map.of("tool",tool,"args",args);}
    ProductionTaskService.Turn turn(String text){return new ProductionTaskService.Turn(text,"session-1",null,null,List.of());}
    ProductionOperationService.Preview pending(String id,String action,Map<String,Object> fields){return new ProductionOperationService.Preview("PENDING","请确认",action,fields,List.of(),Map.of("proposalId",id,"params",fields));}


    @Test void newProductionChoiceRereadsSelectedOrderInsteadOfAskingStockChoiceAgain() {
        cache.put("assistant:production-task:1:ADMIN:session-1",new LinkedHashMap<>(Map.of("selectedOrderId","CO123","focused",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderId","COOLD","plannedQty","20")))));
        when(tools.read("orderContext",Map.of("orderId","CO123"),user)).thenReturn(Map.of("orderId","CO123","orderQuantity",100,"recommendedPlanQty",0));
        when(operations.preview(any(),eq(user))).thenAnswer(i-> {
            ProductionOperationService.Turn t=i.getArgument(0);
            return new ProductionOperationService.Preview("NEEDS_INPUT","选择策略",t.actionCode(),t.fields(),
                List.of(new AgentRequiredField("schedulingMode","排产策略")),null);
        });
        service.run(turn("新生产"),"auth",events::add);
        verify(operations).preview(argThat(t->"saveProductionPlan".equals(t.actionCode())&&"100".equals(t.fields().get("plannedQty"))&&"CO123".equals(t.fields().get("orderId"))),eq(user));
        verifyNoInteractions(model,plans);
    }
    @Test void negativeProductionChoiceIsNotAutoConvertedToProduction() {
        cache.put("assistant:production-task:1:ADMIN:session-1",new LinkedHashMap<>(Map.of("selectedOrderId","CO123")));
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan());
        service.run(turn("不要新生产"),"auth",events::add);
        verifyNoInteractions(operations,plans);
    }
    @Test void missingPlanReportsActualReasonAndDoesNotPresentPermissionFailure() {
        when(tools.catalog(user)).thenReturn(List.of(Map.of("tool","planContext")));
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("planContext",Map.of("planId","PP404"))),plan());
        when(tools.read("planContext",Map.of("planId","PP404"),user)).thenThrow(new BusinessException(404,"计划不存在：PP404"));
        service.run(turn("核对 PP404"),"auth",events::add);
        assertThat(events.toString()).contains("计划不存在：PP404").doesNotContain("查询失败或权限不足");
        verifyNoInteractions(plans,operations);
    }
    @Test void planningNeverAutomaticallyAssignsPeopleAndKeepsFieldsOnFollowup() {
        var fields=Map.<String,Object>of("orderId","CO123","plannedQty","100","planStart","2026-11-27","planEnd","2026-12-07","schedulingMode","DELIVERY");
        when(model.plan(anyString(),anyMap(),any())).thenReturn(
            plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",fields))),
            plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("planEnd","2026-12-31")))));
        when(operations.preview(any(),eq(user))).thenAnswer(i-> {
            ProductionOperationService.Turn t=i.getArgument(0);
            return new ProductionOperationService.Preview("NEEDS_INPUT","交期不足",t.actionCode(),t.fields(),
                List.of(new AgentRequiredField("planEnd","调整截止日")),null);
        });
        var result=service.run(turn("新生产并安排工序"),"auth",events::add);
        assertThat(result).doesNotContainKey("staffingPreview");
        verify(tools,never()).read(eq("orderStaffing"),anyMap(),any());
        service.run(turn("截止日期改为2026-12-31"),"auth",events::add);
        verify(operations).preview(argThat(t->"2026-12-31".equals(t.fields().get("planEnd")) && "100".equals(t.fields().get("plannedQty")) && "CO123".equals(t.fields().get("orderId"))),eq(user));
        verifyNoInteractions(plans);
    }

    @Test void operatorPickerRequiresCurrentProposalAndRevalidatesWhileKeepingOtherRows(){
        var fields=Map.<String,Object>of("planId","PP123","assignmentOverrides","{\"电源板装配\":\"huang_operator\"}","staffingPolicy","IDLE_FIRST");
        cache.put("assistant:production-task:1:ADMIN:session-1",new LinkedHashMap<>(Map.of("proposalId","old","focused",Map.of("actionCode","confirmSmartDispatch","fields",fields))));
        when(operations.preview(any(),eq(user))).thenAnswer(i->{var t=(ProductionOperationService.Turn)i.getArgument(0);return pending("new",t.actionCode(),t.fields());});
        var edit=Map.<String,Object>of("editProposalId","old","assignmentOverrides","{\"主板装配\":\"bai_operator\"}");
        var result=service.run(new ProductionTaskService.Turn("","session-1","confirmSmartDispatch",edit,List.of()),"auth",events::add);
        verify(plans).confirmProduction("old","SKIP",user);
        verify(operations).preview(argThat(t->t.fields().get("assignmentOverrides").toString().contains("huang_operator")&&t.fields().get("assignmentOverrides").toString().contains("bai_operator")&&"PP123".equals(t.fields().get("planId"))),eq(user));
        assertThat(((Map<?,?>)result.get("confirmation")).get("proposalId")).isEqualTo("new");
        assertThatThrownBy(()->service.run(new ProductionTaskService.Turn("","session-1","confirmSmartDispatch",edit,List.of()),"auth",events::add)).hasMessageContaining("方案已变化");
        verify(plans,never()).confirmProduction(anyString(),eq("APPROVE"),any());
    }
    @Test void switchingToPortfolioClearsSingleOrderAndKeepsBatchOnDateAdjustment(){
        cache.put("assistant:production-task:1:ADMIN:session-1",new LinkedHashMap<>(Map.of("focused",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderId","CO1","plannedQty","99")))));
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderIds","[\"CO1\",\"CO2\"]")))),
            plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("planEnd","2027-04-01")))));
        when(operations.preview(any(),eq(user))).thenAnswer(i->{var t=(ProductionOperationService.Turn)i.getArgument(0);return pending("new",t.actionCode(),t.fields());});
        service.run(turn("这两单一起计划"),"auth",events::add);
        service.run(turn("截止日期改到4月1日"),"auth",events::add);
        verify(operations).preview(argThat(t->t.fields().containsKey("planEnd")&&t.fields().containsKey("orderIds")&&!t.fields().containsKey("orderId")&&!t.fields().containsKey("plannedQty")),eq(user));
    }
    @Test void unsupportedAssistantClaimsAreNotTrustedAsExecutionReceipts() {
        cache.put("assistant:production-task:1:ADMIN:session-1",new LinkedHashMap<>(Map.of("lastAnswer","已发货 SHIP-FAKE")));
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan());
        service.run(new ProductionTaskService.Turn("改成新生产","session-1",null,null,List.of(
            Map.of("role","assistant","text","已保存 PP-FAKE"),Map.of("role","user","text","选择 CO123"))),"auth",events::add);
        verify(model).plan(anyString(),argThat(input->!input.toString().contains("SHIP-FAKE")&&!input.toString().contains("PP-FAKE")&&input.toString().contains("CO123")),any());
    }
    @Test void independentReadsReallyOverlapAndModelReplansFromResults() {
        var barrier=new CyclicBarrier(2);
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("orders",Map.of()),call("resources",Map.of())),plan());
        when(tools.read(anyString(),anyMap(),eq(user))).thenAnswer(i->{barrier.await(2,TimeUnit.SECONDS);return List.of(Map.of("record",i.getArgument(0)));});
        var result=service.run(turn("查订单并核对人员设备"),"auth",events::add);
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        verify(model,times(2)).plan(eq("auth"),anyMap(),any());verifyNoInteractions(plans,operations);
        assertThat(events).anyMatch(e->e.toString().contains("TASK_ORDERS")).anyMatch(e->e.toString().contains("TASK_RESOURCES"));
    }
    @Test void publicAnswerStartsOnlyAfterToolResultAndAnnouncesSummaryPhase(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("orders",Map.of()))).thenAnswer(i->{
            java.util.function.Consumer<String> text=i.getArgument(2);text.accept("已查到");text.accept("订单");
            return json.valueToTree(Map.of("calls",List.of(),"reply","已查到订单"));
        });
        when(tools.read("orders",Map.of(),user)).thenReturn(List.of(Map.of("orderId","CO123")));
        service.run(turn("先列出订单让我选"),"auth",events::add);
        int completed=-1,summary=-1,firstText=-1;
        for(int n=0;n<events.size();n++){
            var event=events.get(n);String serialized=event.toString();
            if(serialized.contains("STEP_COMPLETED")&&serialized.contains("TASK_ORDERS"))completed=n;
            if(serialized.contains("SUMMARIZING")&&serialized.contains("RUNNING"))summary=n;
            if("delta".equals(event.get("type"))&&firstText<0)firstText=n;
        }
        assertThat(completed).isGreaterThanOrEqualTo(0);assertThat(summary).isGreaterThan(completed);assertThat(firstText).isGreaterThan(summary);
        assertThat(events).anyMatch(e->e.toString().contains("SUMMARIZING")&&e.toString().contains("COMPLETED"));
    }
    @Test void failedDispatchRetainsCriteriaAndDoesNotRepeatIdenticalPreview() {
        var fields=Map.<String,Object>of("planId","PP123","idleOnly","true","assignmentOverrides","{}");
        when(model.plan(anyString(),anyMap(),any())).thenReturn(
            plan(call("preview",Map.of("actionCode","confirmSmartDispatch","fields",fields))),
            plan(call("preview",Map.of("actionCode","confirmSmartDispatch","fields",fields))),plan());
        when(operations.preview(any(),eq(user))).thenThrow(new BusinessException(409,"当前没有符合空闲限制的人员"));
        service.run(turn("只能安排空闲人员"),"auth",events::add);
        verify(operations,times(1)).preview(any(),eq(user));
        verify(model,atLeastOnce()).plan(anyString(),argThat(input-> {
            var focused=(Map<?,?>)((Map<?,?>)input.get("state")).get("focused");
            return focused!=null && "true".equals(((Map<?,?>)focused.get("fields")).get("idleOnly"));
        }),any()); // repeated model calls may all include the persisted condition
    }
    @Test void dispatchEditsKeepOtherAssignmentsAndConstraintsAndInvalidateOldConfirmation() throws Exception {
        var initial=Map.<String,Object>of("planId","PP123","idleOnly","true","assignmentOverrides","{\"主板装配\":\"wu_operator\"}");
        when(model.plan(anyString(),anyMap(),any())).thenReturn(
            plan(call("preview",Map.of("actionCode","confirmSmartDispatch","fields",initial))),
            plan(call("preview",Map.of("actionCode","confirmSmartDispatch","fields",Map.of("assignmentOverrides","{\"电源板装配\":\"xu_operator\"}")))));
        when(operations.preview(any(),eq(user))).thenAnswer(i -> {
            ProductionOperationService.Turn t=i.getArgument(0);
            return pending(events.size()+"-proposal","confirmSmartDispatch",t.fields());
        });
        service.run(turn("先给这个计划派工"),"auth",events::add);
        service.run(turn("电源板改由徐师傅做"),"auth",events::add);
        verify(operations).preview(argThat(t -> "true".equals(t.fields().get("idleOnly"))
            && t.fields().get("assignmentOverrides").toString().contains("wu_operator")
            && t.fields().get("assignmentOverrides").toString().contains("xu_operator")),eq(user));
        verify(plans).confirmProduction(anyString(),eq("SKIP"),eq(user));
        verify(plans,never()).confirmProduction(anyString(),eq("APPROVE"),any());
    }
    @Test void modelCannotInvokeUnregisteredWritesOrExecuteSql(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("executeSql",Map.of("sql","delete from customer_order"))));
        assertThatThrownBy(()->service.run(turn("安排生产"),"auth",events::add)).isInstanceOf(BusinessException.class).hasMessageContaining("未授权");
        verifyNoInteractions(operations,plans);
    }
    @Test void replacementRevalidatesAndCancelsOldProposalWithoutExecuting(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderId","CO123")))),
            plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderId","CO123","schedulingMode","COST")))));
        when(operations.preview(any(),eq(user))).thenReturn(pending("P1","saveProductionPlan",Map.of("orderId","CO123")),pending("P2","saveProductionPlan",Map.of("orderId","CO123","schedulingMode","COST")));
        service.run(turn("制定计划"),"auth",events::add);service.run(turn("改成成本优先"),"auth",events::add);
        verify(plans).confirmProduction("P1","SKIP",user);verify(plans,never()).confirmProduction(anyString(),eq("APPROVE"),any());
        assertThatThrownBy(()->service.decide("session-1","P1","APPROVE","auth")).hasMessageContaining("被替换");
    }
    @Test void receiptsPreserveAnchorsAcrossBooleanPublicationReceiptAndDifferentSessionCannotConfirm(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderId","CO123")))),
            plan(call("preview",Map.of("actionCode","publishPlan","fields",Map.of("planId","PP456")))),plan());
        when(operations.preview(any(),eq(user))).thenReturn(pending("P1","saveProductionPlan",Map.of("orderId","CO123")),pending("P2","publishPlan",Map.of("planId","PP456")));
        when(plans.confirmProduction("P1","APPROVE",user)).thenReturn(Map.of("status","EXECUTED","result",Map.of("planId","PP456")));
        when(plans.confirmProduction("P2","APPROVE",user)).thenReturn(Map.of("status","EXECUTED","result",true));
        service.run(turn("先制定计划后派工"),"auth",events::add);
        assertThatThrownBy(()->service.decide("other-session","P1","APPROVE","auth")).hasMessageContaining("不属于");
        service.decide("session-1","P1","APPROVE","auth");service.run(turn("继续"),"auth",events::add);service.decide("session-1","P2","APPROVE","auth");
        service.run(turn("继续派工"),"auth",events::add);
        verify(model).plan(eq("auth"),argThat(input->{var state=(Map<?,?>)input.get("state");return state.get("anchors") instanceof Map<?,?> anchors && "PP456".equals(anchors.get("planId"))
            && state.get("receipt") instanceof Map<?,?> receipt && Boolean.TRUE.equals(receipt.get("result"));}),any());
    }
    @Test void missingFieldFormCannotChangeActionOrInjectSchedules(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of()))));
        when(operations.preview(any(),eq(user))).thenReturn(new ProductionOperationService.Preview("NEEDS_INPUT","选订单","saveProductionPlan",Map.of(),List.of(new AgentRequiredField("orderId","订单")),null));
        service.run(turn("制定计划"),"auth",events::add);
        assertThatThrownBy(()->service.run(new ProductionTaskService.Turn("","session-1","saveProductionPlan",Map.of("schedules",List.of()),List.of()),"auth",events::add)).hasMessageContaining("只可补充");
        assertThatThrownBy(()->service.run(new ProductionTaskService.Turn("","session-1","confirmSmartDispatch",Map.of(),List.of()),"auth",events::add)).hasMessageContaining("上下文已变化");
    }
    @Test void toolFailureIsReturnedToPlannerForReplanning(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("orders",Map.of())),plan());
        when(tools.read(eq("orders"),anyMap(),eq(user))).thenThrow(new BusinessException(503,"订单查询暂不可用"));
        service.run(turn("查询可计划订单"),"auth",events::add);
        verify(model).plan(eq("auth"),argThat(i->i.get("observations").toString().contains("FAILED")),any());
        verifyNoInteractions(plans);
    }
    @Test void invalidAdjustmentAlsoInvalidatesTheOldConfirmation(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderId","CO123")))),
            plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderId","CO123","schedulingMode","COST")))),plan());
        when(operations.preview(any(),eq(user))).thenReturn(pending("P1","saveProductionPlan",Map.of("orderId","CO123")))
            .thenThrow(new BusinessException(409,"截止日期过早"));
        service.run(turn("制定计划"),"auth",events::add);
        service.run(turn("改为成本优先"),"auth",events::add);
        verify(plans).confirmProduction("P1","SKIP",user);
        assertThatThrownBy(()->service.decide("session-1","P1","APPROVE","auth")).hasMessageContaining("被替换");
    }
    @Test void repeatedReadIsExplainedToPlannerWithoutRepeatingQueryOrKillingConversation(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("orders",Map.of())),plan(call("orders",Map.of())),plan());
        when(tools.read("orders",Map.of(),user)).thenReturn(List.of(Map.of("orderId","CO123")));
        assertThat(service.run(turn("先列出订单让我选"),"auth",events::add).get("status")).isEqualTo("COMPLETED");
        verify(tools,times(1)).read("orders",Map.of(),user);
        verify(model).plan(eq("auth"),argThat(i->i.get("observations").toString().contains("ALREADY_READ")),any());
    }
    @Test void oldReceiptRetryCannotEraseNewPendingProposal(){
        when(model.plan(anyString(),anyMap(),any())).thenReturn(plan(call("preview",Map.of("actionCode","saveProductionPlan","fields",Map.of("orderId","CO123")))),
            plan(call("preview",Map.of("actionCode","publishPlan","fields",Map.of("planId","PP456")))));
        when(operations.preview(any(),eq(user))).thenReturn(pending("P1","saveProductionPlan",Map.of("orderId","CO123")),pending("P2","publishPlan",Map.of("planId","PP456")));
        when(plans.confirmProduction("P1","APPROVE",user)).thenReturn(Map.of("status","EXECUTED","result",Map.of("planId","PP456")));
        when(plans.confirmProduction("P2","APPROVE",user)).thenReturn(Map.of("status","EXECUTED","result",true));
        service.run(turn("制定计划"),"auth",events::add);service.decide("session-1","P1","APPROVE","auth");
        service.run(turn("继续发布"),"auth",events::add);service.decide("session-1","P1","APPROVE","auth");
        assertThat(service.decide("session-1","P2","APPROVE","auth").get("status")).isEqualTo("EXECUTED");
    }
}
