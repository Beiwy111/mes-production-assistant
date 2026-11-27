package com.upc.computer.service;

import com.upc.computer.mapper.ProcessStepMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionSchemeIntegrityTest {
    @Test void stockCoveredOrderHasConsistentZeroProductionRecommendation() {
        var service = new MesPlannerSchedulingService();
        var orders = mock(com.upc.computer.mapper.CustomerOrderMapper.class);
        var items = mock(com.upc.computer.mapper.CustomerOrderItemMapper.class);
        var agent = mock(MesPlannerAgentService.class);
        ReflectionTestUtils.setField(service, "customerOrderMapper", orders);
        ReflectionTestUtils.setField(service, "customerOrderItemMapper", items);
        ReflectionTestUtils.setField(service, "plannerAgentService", agent);
        var order = new com.upc.computer.entity.CustomerOrder();
        order.setOrderId(1L); order.setOrderNo("CO20261115008"); order.setAuditStatus("PLAN_PENDING");
        when(orders.customerOrderList()).thenReturn(new ArrayList<>(List.of(order)));
        when(items.orderItemList()).thenReturn(new ArrayList<>());
        when(agent.analyze(eq(order.getOrderNo()), any(), any())).thenReturn(Map.of(
                "recommendedPlanQty", 25, "orderQuantity", 25,
                "inventoryCheck", Map.of("needToProduce", 0, "recommendedPlanQty", 25, "availableStock", 264)));
        var context = service.previewOrderContext(order.getOrderNo());
        assertThat(context).containsEntry("recommendedPlanQty", 0);
        assertThat((Map<String, Object>) context.get("inventory"))
                .containsEntry("recommendedPlanQty", 0).containsEntry("availableStock", 264);
    }
    @Test void selectedProductRouteHoursOverrideOtherProductsWithTheSameStepName(){
        var service=new MesPlannerSchedulingService();
        var mapper=mock(ProcessStepMapper.class);ReflectionTestUtils.setField(service,"processStepMapper",mapper);
        var suggestions=List.of(Map.<String,Object>of("processStep","主板装配","standardHours",2.0,"workshop","主板车间","equipmentCode","EQ1"));
        List<Map<String,Object>> rows=ReflectionTestUtils.invokeMethod(service,"buildSchedulesFromSuggestions",suggestions,20,LocalDate.of(2027,1,1),LocalDate.of(2027,1,10),"DELIVERY");
        assertThat(rows.get(0)).containsEntry("standardHours",2.0).containsEntry("plannedEnd","2027-01-03 18:00:00");
        verifyNoInteractions(mapper);
    }
    @Test void everySequentialOperationRetainsFullQuantityEvenWhenRequestedWindowIsTooShort(){
        var service=new MesPlannerSchedulingService();
        ReflectionTestUtils.setField(service,"processStepMapper",mock(ProcessStepMapper.class));
        var suggestions=ProductionWorkshopCatalog.PRODUCTION_STAGES.stream().map(s->Map.<String,Object>of("processStep",s.stepName(),"workshop","测试车间","equipmentCode","EQ-TEST")).toList();
        for(String strategy:List.of("DELIVERY","BALANCE","COST")){
            List<Map<String,Object>> rows=ReflectionTestUtils.invokeMethod(service,"buildSchedulesFromSuggestions",suggestions,100,LocalDate.of(2027,1,1),LocalDate.of(2027,1,2),strategy);
            assertThat(rows).hasSize(suggestions.size());
            assertThat(rows).allSatisfy(row->assertThat(row.get("plannedQuantity")).isEqualTo(100));
            assertThat(rows.get(rows.size()-1).get("plannedEnd").toString()).isGreaterThan("2027-01-02 23:59:59");
        }
    }
}
