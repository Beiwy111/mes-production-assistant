package com.upc.computer.assistant.v2.operations;

import com.upc.computer.ai.action.*;
import com.upc.computer.common.BusinessException;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import com.upc.computer.service.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionOperationServiceTest {
    private final AgentActionPlanService plans = mock(AgentActionPlanService.class);
    private final CustomerOrderMapper orders = mock(CustomerOrderMapper.class);
    private final DispatchTaskMapper dispatches = mock(DispatchTaskMapper.class);
    private final MesPlannerSchedulingService scheduling = mock(MesPlannerSchedulingService.class);
    private final MesDispatchRecommendService dispatching = mock(MesDispatchRecommendService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final ProductionPlanMapper productionPlans = mock(ProductionPlanMapper.class);
    private final WorkOrderMapper workOrders = mock(WorkOrderMapper.class);
    private final ProductionDispatchSelection selection = new ProductionDispatchSelection(dispatching,dispatches,workOrders,productionPlans);
    private final ProductionOperationService service = new ProductionOperationService(new AgentActionCatalog(), plans,
            orders, productionPlans, dispatches, users, scheduling, selection);

    private ProductionOperationService.Preview preview(String role, String text, String code, Map<String,Object> fields) {
        LoginResponse session = new LoginResponse();
        session.setUserId(1L);
        session.setRoleCode(role);
        when(plans.previewProduction(anyString(), anyMap(), any(), anyString())).thenAnswer(call ->
                new AgentPlanOutcome("PENDING", "请确认", List.of(), Map.of("params", call.getArgument(1, Map.class))));
        return service.preview(new ProductionOperationService.Turn(text, "thread", code, fields), session);
    }

    @Test void planCarriesLiveStepRowsAndSavesDraftOnly() {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("CO202607001");
        order.setAuditStatus("PLAN_PENDING");
        order.setRequiredDeliveryDate(LocalDate.of(2027, 3, 30));
        when(orders.customerOrderList()).thenReturn(new ArrayList<>(List.of(order)));
        var rows = List.of(Map.of("stepName", "主板", "equipmentCode", "EQ-1"));
        when(scheduling.compareSchemes(order.getOrderNo(), LocalDate.parse("2027-03-01"), LocalDate.parse("2027-03-30"), 20))
                .thenReturn(Map.of("plannedQty", 20, "schemes", List.of(Map.of("key", "BALANCE", "schedules", rows,
                        "finishDate", "2027-03-30"))));
        var response = preview("PLANNER","给 CO202607001 按负载均衡排产20台，从2027-03-01到2027-03-30",null,null);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(((Map<?,?>) response.confirmation().get("params")).get("schedules")).isEqualTo(rows);
        assertThat(((Map<?,?>) response.confirmation().get("params")).get("saveAction")).isEqualTo("draft");
        verify(scheduling, never()).savePlanWithSchedule(anyMap(), anyString());
    }

    @Test void estimatedCompletionBeyondRequestedDeadlineCannotBecomeAConfirmation(){
        CustomerOrder order=new CustomerOrder();order.setOrderNo("CO123");order.setAuditStatus("PLAN_PENDING");
        order.setRequiredDeliveryDate(LocalDate.parse("2027-03-30"));
        when(orders.customerOrderList()).thenReturn(new ArrayList<>(List.of(order)));
        when(scheduling.compareSchemes(eq("CO123"),any(),any(),eq(100))).thenReturn(Map.of("plannedQty",100,"schemes",List.of(
            Map.of("key","COST","finishDate","2027-04-05","canSubmit",true,"schedules",List.of(Map.of("stepName","主板装配","plannedQuantity",100))))));
        var response=preview("ADMIN","","saveProductionPlan",Map.of("orderId","CO123","schedulingMode","COST","plannedQty","100","planStart","2027-03-01","planEnd","2027-03-30"));
        assertThat(response.status()).isEqualTo("NEEDS_INPUT");
        assertThat(response.message()).contains("2027-04-05","晚于要求","2027-03-30");
        assertThat(response.confirmation()).isNull();
        assertThat(response.fields().get("planEnd")).isEqualTo("2027-03-30");
        assertThat(response.missingFields()).extracting(AgentRequiredField::name).containsExactly("planEnd");
        verify(plans,never()).previewProduction(anyString(),anyMap(),any(),anyString());
    }

    @Test void politeScreenshotRequestsAreOperationsButQuestionsRemainReadOnly() {
        for (String text : List.of("你能帮我去排产吗", "帮我去制定生产计划呢", "可以帮我排产吗？", "能否帮我智能排产？"))
            assertThat(ProductionCommand.matchesRequest(text)).as(text).isTrue();
        for (String text : List.of("怎么智能排产？", "生产计划有哪些", "排产完成了吗", "不要帮我排产", "能否查询排产进度"))
            assertThat(ProductionCommand.matchesRequest(text)).as(text).isFalse();
    }

    @Test void plannerOffersEligibleOrdersAndManagerOffersUndispatchedSubmittedPlans() {
        CustomerOrder pending = new CustomerOrder(); pending.setOrderNo("CO123"); pending.setAuditStatus("PLAN_PENDING");
        CustomerOrder rejected = new CustomerOrder(); rejected.setOrderNo("CO124"); rejected.setAuditStatus("REJECTED");
        when(orders.customerOrderList()).thenReturn(new ArrayList<>(List.of(pending, rejected)));
        var planner = preview("PLANNER", "帮我去制定生产计划呢", null, null);
        assertThat(planner.actionCode()).isEqualTo("saveProductionPlan");
        assertThat(planner.options().get("orderId")).extracting(row -> row.get("value")).containsExactly("CO123");
        ProductionPlan submitted = new ProductionPlan(); submitted.setPlanNo("PP123"); submitted.setPlanStatus("SUBMITTED");
        ProductionPlan draft = new ProductionPlan(); draft.setPlanNo("PP124"); draft.setPlanStatus("DRAFT");
        ProductionPlan dispatched = new ProductionPlan(); dispatched.setPlanNo("PP125"); dispatched.setPlanStatus("EXECUTING");
        when(productionPlans.planList()).thenReturn(new ArrayList<>(List.of(submitted, draft, dispatched)));
        when(dispatches.hasDispatchForPlan("PP125")).thenReturn(true);
        var manager = preview("MANAGER", "你能帮我去排产吗", null, null);
        assertThat(manager.actionCode()).isEqualTo("confirmSmartDispatch");
        assertThat(manager.options().get("planId")).extracting(row -> row.get("value")).containsExactly("PP123");
        verifyNoInteractions(plans, scheduling, dispatching);
    }

    @Test void adminClarifiesAmbiguousSchedulingAndRespectsSelectedWorkflow() {
        var admin = preview("ADMIN", "你能帮我去排产吗", null, null);
        assertThat(admin.missingFields()).extracting(AgentRequiredField::name).containsExactly("planningTask");
        assertThat(admin.options().get("planningTask")).hasSize(2);
        var manager = preview("ADMIN", "", admin.actionCode(), Map.of("planningTask", "DISPATCH"));
        assertThat(manager.actionCode()).isEqualTo("confirmSmartDispatch");
        assertThat(manager.missingFields()).extracting(AgentRequiredField::name).containsExactly("planId");
        assertThat(preview("ADMIN", "帮我去制定生产计划呢", null, null).missingFields())
                .extracting(AgentRequiredField::name).containsExactly("orderId");
        verifyNoInteractions(plans);
    }

    @Test void comparesActualStrategiesBeforeSelectionAndUsesChosenLiveRows() {
        CustomerOrder order = new CustomerOrder(); order.setOrderNo("CO123"); order.setAuditStatus("APPROVED");
        order.setRequiredDeliveryDate(LocalDate.of(2027, 3, 30));
        when(orders.customerOrderList()).thenReturn(new ArrayList<>(List.of(order)));
        var costRows = List.of(Map.of("stepName", "主板", "equipmentCode", "EQ-COST"));
        var schemes = List.of(Map.of("key", "BALANCE", "label", "负载均衡", "schedules", List.of(Map.of("equipmentCode", "EQ-1")), "finishDate", "2027-03-29"),
                Map.of("key", "COST", "label", "成本优先", "schedules", costRows, "finishDate", "2027-03-30"));
        when(scheduling.compareSchemes("CO123", LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 30), 0))
                .thenReturn(Map.of("plannedQty", 20, "schemes", schemes));
        var selection = preview("PLANNER", "帮我给 CO123 制定生产计划，从2027-03-01到2027-03-30", null, null);
        assertThat(selection.status()).isEqualTo("NEEDS_INPUT");
        assertThat(selection.missingFields()).extracting(AgentRequiredField::name).containsExactly("schedulingMode");
        assertThat(selection.schemes()).hasSize(2);
        assertThat(selection.options().get("schedulingMode")).extracting(row -> row.get("value")).containsExactly("BALANCE", "COST");
        verifyNoInteractions(plans);
        var chosen = preview("PLANNER", "成本优先", selection.actionCode(), selection.fields());
        assertThat(chosen.status()).isEqualTo("PENDING");
        assertThat(((Map<?,?>) chosen.confirmation().get("params")).get("schedules")).isEqualTo(costRows);
        assertThat(chosen.schemes()).hasSize(2);
        verify(scheduling, never()).savePlanWithSchedule(anyMap(), anyString());
    }

    @Test void strategySelectionRevalidatesOrderStateAndBlocksUnsafeSchemes() {
        CustomerOrder order = new CustomerOrder(); order.setOrderNo("CO123"); order.setAuditStatus("PLAN_PENDING");
        when(orders.customerOrderList()).thenReturn(new ArrayList<>(List.of(order)));
        Map<String,Object> fields = Map.of("orderId", "CO123", "planStart", "2027-03-01", "planEnd", "2027-03-30", "schedulingMode", "DELIVERY");
        when(scheduling.compareSchemes("CO123", LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 30), 0))
                .thenReturn(Map.of("plannedQty", 20, "schemes", List.of(Map.of("key", "DELIVERY", "canSubmit", false,
                        "schedules", List.of(Map.of("stepName", "主板")), "finishDate", "2027-03-30"))));
        assertThatThrownBy(() -> preview("PLANNER", "", "saveProductionPlan", fields)).hasMessageContaining("阻断冲突");
        order.setAuditStatus("REJECTED");
        assertThatThrownBy(() -> preview("PLANNER", "", "saveProductionPlan", fields)).hasMessageContaining("审核通过");
        assertThatThrownBy(() -> preview("MANAGER", "帮我制定生产计划", null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> preview("OPERATOR", "帮我排产", null, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(plans);
    }

    @Test void managerCannotPublishDraftImplicitlyAndAdminSeesAllCombinedEffects() {
        var draft=new ProductionPlan();draft.setPlanNo("PP123");draft.setPlanStatus("DRAFT");
        when(productionPlans.planList()).thenReturn(new ArrayList<>(List.of(draft)));
        var fields=Map.<String,Object>of("planId","PP123","assignmentOverrides","{}");
        assertThatThrownBy(()->preview("MANAGER","","confirmSmartDispatch",fields)).isInstanceOf(BusinessException.class);
        verify(plans,never()).previewProduction(any(),any(),any(),any());
        when(dispatching.previewAssignments(eq("PP123"),anyMap(),anySet(),anyBoolean())).thenReturn(Map.of("canSubmit",true,
            "recommendations",List.of(Map.of("processStep","主板装配","recommendedOperator","li_operator","equipmentCode","EQ1"))));
        var result=preview("ADMIN","","confirmSmartDispatch",fields);
        assertThat(((Map<?,?>)result.confirmation().get("params")).get("preparePlan")).isEqualTo(true);
        assertThat(((List<?>)((Map<?,?>)result.confirmation().get("params")).get("executionSteps"))).hasSize(4);
        verify(plans,never()).confirmProduction(any(),any(),any());
    }

    @Test void smartDispatchBindsReviewedRecipientsWithoutExecuting() {
        var rows = List.of(Map.of("processStep", "主板", "recommendedOperator", "li_operator", "equipmentCode", "EQ-1"));
        when(dispatching.generateRecommendations("PP123")).thenReturn(Map.of("canSubmit", true, "recommendations", rows));
        var response = preview("MANAGER","为 PP123 智能派工",null,null);
        assertThat(response.actionCode()).isEqualTo("confirmSmartDispatch");
        assertThat(((Map<?,?>) response.confirmation().get("params")).get("recommendations")).isEqualTo(rows);
        verify(plans, never()).confirmProduction(anyString(), anyString(), any());
    }

    @Test void suggestionConflictOrWrongRoleProducesNoProposal() {
        assertThatThrownBy(() -> preview("OPERATOR","为 PP123 派工",null,null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(dispatching);
        when(dispatching.generateRecommendations("PP123")).thenReturn(Map.of("canSubmit", false,
                "recommendations",List.of(Map.of("processStep","主板装配","recommendedOperator","li_operator","equipmentCode","EQ-1"))));
        assertThatThrownBy(() -> preview("MANAGER","为 PP123 派工",null,null)).hasMessageContaining("冲突");
        when(dispatches.hasDispatchForPlan("PP124")).thenReturn(true);
        assertThatThrownBy(() -> preview("MANAGER","为 PP124 派工",null,null)).hasMessageContaining("已有派工");
        verify(plans, never()).previewProduction(any(),any(),any(),any());
    }

    @Test void namedWorkOrderDispatchBindsOnlyRequestedStepAndNamedPerson() {
        var operator = new User();
        operator.setUserId(41L);
        operator.setRealName("吴师傅");
        operator.setUsername("wu_operator");
        when(dispatching.activeOperators()).thenReturn(List.of(operator));
        var wo = new WorkOrder();
        wo.setPlanId(7L);
        wo.setWorkOrderNo("WO456");
        var plan = new ProductionPlan();
        plan.setPlanNo("PP123");
        when(workOrders.workOrderList()).thenReturn(new ArrayList<>(List.of(wo)));
        when(productionPlans.getPlanById(7L)).thenReturn(plan);
        var rows = List.of(Map.of("processStep", "主板装配", "recommendedOperator", "li_operator", "equipmentCode", "EQ-1"),
                Map.of("processStep","电源板装配","recommendedOperator","huang_operator","equipmentCode","EQ-2"));
        when(dispatching.generateRecommendations("PP123")).thenReturn(Map.of("workOrderId","WO456","recommendations",rows));
        when(dispatching.validateRecommendations(eq("PP123"),anyList())).thenReturn(Map.of("canSubmit",true));
        var clarification = preview("MANAGER","可以帮我把主板装配派给吴师傅吗？",null,null);
        assertThat(clarification.status()).isEqualTo("NEEDS_INPUT");
        assertThat(clarification.fields()).containsEntry("operator","wu_operator");
        var preview = preview("MANAGER","工单 WO456",clarification.actionCode(),clarification.fields());
        assertThat(preview.fields()).containsEntry("planId","PP123").containsEntry("operator","wu_operator");
        var selected = (List<?>) ((Map<?,?>) preview.confirmation().get("params")).get("recommendations");
        assertThat(selected).singleElement().satisfies(row -> {
            var values = (Map<?,?>) row;
            assertThat(values.get("recommendedOperator")).isEqualTo("wu_operator");
            assertThat(values.get("recommendedOperatorName")).isEqualTo("吴师傅");
            assertThat(values.get("processStep")).isEqualTo("主板装配");
        });
        assertThatThrownBy(() -> preview("MANAGER","请把工单 WO456 的主板装配派工给不存在的人",null,null))
                .hasMessageContaining("未能匹配");
        assertThatThrownBy(() -> preview("MANAGER","请把工单 WO456 的电源板装配派工给吴师傅",null,null))
                .hasMessageContaining("不匹配");
        when(dispatches.hasDispatchForStep("PP123","主板装配")).thenReturn(true);
        assertThatThrownBy(() -> preview("MANAGER","请把工单 WO456 的主板装配派工给吴师傅",null,null))
                .hasMessageContaining("已有派工");
    }

    @Test void clarificationsCannotInjectScheduleOrRecipientAndQueriesRemainReadOnly() {
        assertThat(preview("PLANNER","根据订单生成生产计划",null,null).missingFields())
                .extracting(AgentRequiredField::name).containsExactly("orderId");
        assertThat(preview("OPERATOR","查一下派工进度",null,null).status()).isEqualTo("UNHANDLED");
        assertThatThrownBy(() -> preview("MANAGER","PP123","confirmSmartDispatch",Map.of("recommendations","fake")))
                .hasMessageContaining("不支持的操作字段");
        var bind = ProductionCommand.SCHEDULE.bind("给 CO222 排产",Map.of("orderId","CO111","plannedQty","100"));
        assertThat(bind).containsOnly(entry("orderId","CO222"));
        verifyNoInteractions(scheduling, dispatching);
    }

    @Test void workAndReminderBelongToRecordedOperator() {
        DispatchTask task = new DispatchTask();
        task.setDispatchNo("DT123");
        task.setOperatorId(2L);
        task.setStatus("ASSIGNED");
        when(dispatches.dispatchList()).thenReturn(new ArrayList<>(List.of(task)));
        assertThatThrownBy(() -> preview("OPERATOR","接收派工 DT123",null,null)).hasMessageContaining("本人");
        User user = new User();
        user.setUsername("li_operator");
        when(users.getUserById(2L)).thenReturn(user);
        var reminder = preview("MANAGER","提醒操作员处理 DT123",null,null);
        assertThat(((Map<?,?>) reminder.confirmation().get("params")).get("targetUsername")).isEqualTo("li_operator");
        task.setOperatorId(1L);
        task.setStatus("PRODUCING");
        task.setAssignedQuantity(BigDecimal.TEN);
        assertThat(preview("OPERATOR","为 DT123 报工2台",null,null).missingFields())
                .extracting(AgentRequiredField::name).containsExactly("startTime");
        var report = preview("OPERATOR","报工 DT123 共2台，2026-12-01 08:30到2026-12-01 10:00",null,null);
        assertThat(((Map<?,?>) report.confirmation().get("params")).get("startTime")).isEqualTo("2026-12-01 08:30:00");
        assertThatThrownBy(() -> preview("OPERATOR","为 DT123 报工11台",null,null)).hasMessageContaining("超过");
    }
}
