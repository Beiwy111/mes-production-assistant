package com.upc.computer.service;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MesDispatchAvailabilityTest {
    MesDispatchRecommendService service; DispatchTaskMapper tasks; WorkOrderMapper work;
    User li,wu,huang; ProductionPlanMapper plans; EquipmentMapper machines;
    @BeforeEach void setup() {
        service=spy(new MesDispatchRecommendService());
        tasks=mock(DispatchTaskMapper.class);work=mock(WorkOrderMapper.class);
        plans=mock(ProductionPlanMapper.class);machines=mock(EquipmentMapper.class);
        var steps=mock(ProcessStepMapper.class);
        Map.of("dispatchTaskMapper",tasks,"workOrderMapper",work,"productionPlanMapper",plans,
            "equipmentMapper",machines,"processStepMapper",steps,
            "productionPlanItemMapper",mock(ProductionPlanItemMapper.class),
            "customerOrderItemMapper",mock(CustomerOrderItemMapper.class),
            "processRouteMapper",mock(ProcessRouteMapper.class)).forEach((k,v)->ReflectionTestUtils.setField(service,k,v));
        li=user(1,"li_operator");wu=user(2,"wu_operator");huang=user(3,"huang_operator");
        doReturn(List.of(li,wu,huang)).when(service).activeOperators();
        var plan=new ProductionPlan();plan.setPlanId(1L);plan.setPlanNo("PP123");plan.setSourceOrderId(1L);plan.setPlanStatus("DRAFT");
        when(plans.planList()).thenReturn(new ArrayList<>(List.of(plan)));
        var step=new ProcessStep();step.setStepId(1L);step.setRouteId(1L);step.setStepNo(1);step.setStepName("主板装配");
        when(steps.stepList()).thenReturn(new ArrayList<>(List.of(step)));
        var eq=new Equipment();eq.setEquipmentId(1L);eq.setEquipmentCode("EQ1");eq.setEquipmentName("设备1");eq.setStatus("IDLE");
        when(machines.equipmentList()).thenReturn(new ArrayList<>(List.of(eq)));
    }
    static User user(long id,String name){var u=new User();u.setUserId(id);u.setUsername(name);u.setRealName(name);return u;}
    DispatchTask task(long user,long wo){var d=new DispatchTask();d.setOperatorId(user);d.setWorkOrderId(wo);d.setDispatchNo("DT"+user);d.setStatus("ASSIGNED");return d;}

    @Test void unsavedOrderUsesActualRouteAndStaffWithoutInventingPlanOrWrites() {
        var orders=mock(CustomerOrderMapper.class);var items=mock(CustomerOrderItemMapper.class);var routes=mock(ProcessRouteMapper.class);
        ReflectionTestUtils.setField(service,"customerOrderMapper",orders);
        ReflectionTestUtils.setField(service,"customerOrderItemMapper",items);
        ReflectionTestUtils.setField(service,"processRouteMapper",routes);
        var order=new CustomerOrder();order.setOrderId(3L);order.setOrderNo("CO123");order.setAuditStatus("PLAN_PENDING");
        var item=new CustomerOrderItem();item.setOrderId(3L);item.setMaterialId(7L);item.setProductName("显示器");item.setQuantity(java.math.BigDecimal.valueOf(100));
        var route=new ProcessRoute();route.setMaterialId(7L);route.setRouteId(1L);
        when(orders.customerOrderList()).thenReturn(new ArrayList<>(List.of(order)));when(items.orderItemList()).thenReturn(new ArrayList<>(List.of(item)));when(routes.routeList()).thenReturn(new ArrayList<>(List.of(route)));
        var result=service.previewOrderAssignments("CO123",100);
        assertThat(result).containsEntry("canSubmit",false).containsEntry("orderId","CO123").doesNotContainKey("planId");
        var row=(Map<?,?>)((List<?>)result.get("recommendations")).get(0);
        assertThat(row.containsKey("recommendedOperator")).isTrue();assertThat(row.containsKey("equipmentCode")).isTrue();
        assertThat(row.get("planQty")).isEqualTo(100);
        assertThat(row.containsKey("planId")).isFalse();
        verifyNoInteractions(plans);
        when(routes.routeList()).thenReturn(new ArrayList<>());
        assertThatThrownBy(()->service.previewOrderAssignments("CO123",100)).hasMessageContaining("工艺路线");
    }
    @Test void queuedTasksAreNotIdleAndClosedOrdersDoNotOccupyStaff() {
        var closed=new WorkOrder();closed.setWorkOrderId(2L);closed.setStatus("COMPLETED");
        when(work.workOrderList()).thenReturn(new ArrayList<>(List.of(closed)));
        when(tasks.dispatchList()).thenReturn(new ArrayList<>(List.of(task(1,1),task(2,2))));
        var rows=service.operatorAvailability();
        assertThat(rows.get(0)).containsEntry("idle",false).containsEntry("activeLoad",1);
        assertThat(rows.get(1)).containsEntry("idle",true).containsEntry("activeLoad",0);
        var result=service.previewAssignments("PP123",Map.of(),Set.of(),true);
        assertThat(((Map<?,?>)((List<?>)result.get("recommendations")).get(0)).get("recommendedOperator")).isEqualTo("wu_operator");
        verify(plans,never()).updatePlan(any());
    }
    @Test void explicitExclusionAndOverridesAreValidatedAgainstRealStaffAndSteps() {
        assertThatThrownBy(()->service.previewAssignments("PP123",Map.of("主板装配","huang_operator"),Set.of(),false))
            .hasMessageContaining("不能承担");
        assertThatThrownBy(()->service.previewAssignments("PP123",Map.of("不存在工序","wu_operator"),Set.of(),false))
            .hasMessageContaining("不属于");
        assertThatThrownBy(()->service.previewAssignments("PP123",Map.of("主板装配","wu_operator"),Set.of("wu_operator"),false))
            .hasMessageContaining("被排除");
        var result=service.previewAssignments("PP123",Map.of("主板装配","wu_operator"),Set.of("li_operator"),true);
        assertThat(((Map<?,?>)((List<?>)result.get("recommendations")).get(0)).get("recommendedOperator")).isEqualTo("wu_operator");
    }
    @Test void queuedSubstituteIsPreferredToBusyPrimaryAndNotCalledIdle() {
        var busy=task(1,1);busy.setStatus("PRODUCING");
        when(tasks.dispatchList()).thenReturn(new ArrayList<>(List.of(busy,task(2,1))));
        var result=service.previewAssignments("PP123",Map.of(),Set.of(),false);
        var row=(Map<?,?>)((List<?>)result.get("recommendations")).get(0);
        assertThat(row.get("recommendedOperator")).isEqualTo("wu_operator");
        assertThat(row.get("activeLoad")).isEqualTo(1);
        assertThat(row.get("recommendReason").toString()).contains("在手任务").doesNotContain("当前空闲");
    }
    @Test void idleOnlyDoesNotInventStaffWhenEveryoneIsOccupied() {
        when(tasks.dispatchList()).thenReturn(new ArrayList<>(List.of(task(1,1),task(2,1))));
        var result=service.previewAssignments("PP123",Map.of(),Set.of(),true);
        assertThat(result.get("canSubmit")).isEqualTo(false);
        assertThat(((Map<?,?>)((List<?>)result.get("recommendations")).get(0)).get("recommendedOperator")).isEqualTo("");
    }
}
