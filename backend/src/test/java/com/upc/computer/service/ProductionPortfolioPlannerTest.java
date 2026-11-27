package com.upc.computer.service;

import com.upc.computer.common.BusinessException;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ProductionPortfolioPlannerTest {
    CustomerOrderMapper orders=mock(CustomerOrderMapper.class);CustomerOrderItemMapper items=mock(CustomerOrderItemMapper.class);
    InventoryMapper inventory=mock(InventoryMapper.class);BomMapper bom=mock(BomMapper.class);MaterialMapper materials=mock(MaterialMapper.class);
    ProductionPlanMapper plans=mock(ProductionPlanMapper.class);MesPlannerSchedulingService scheduling=mock(MesPlannerSchedulingService.class);
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    ProductionPortfolioPlanner service=new ProductionPortfolioPlanner(orders,items,inventory,bom,materials,plans,scheduling,jdbc);
    Map<String,Object> input=new LinkedHashMap<>(Map.of("orderIds","[\"CO1\",\"CO2\"]","planStart","2027-01-01","schedulingMode","DELIVERY"));
    @SafeVarargs static <T> ArrayList<T> array(T... rows){return new ArrayList<>(Arrays.asList(rows));}
    @BeforeEach void setup(){
        var a=order(1);var b=order(2);when(orders.customerOrderList()).thenReturn(array(a,b));
        when(items.orderItemList()).thenReturn(array(item(1),item(2)));
        var raw=new Bom();raw.setParentMaterialId(10L);raw.setChildMaterialId(20L);raw.setQuantity(BigDecimal.valueOf(2));raw.setStatus(1);
        when(bom.bomList()).thenReturn(array(raw));when(inventory.inventoryList()).thenReturn(array(stock(10,15),stock(20,100)));
        when(scheduling.compareSchemes(anyString(),any(),any(),anyInt())).thenAnswer(i->{LocalDate start=i.getArgument(1);return Map.of("schemes",List.of(Map.of("key","DELIVERY","finishDate",start.plusDays(1).toString(),"canSubmit",true,"schedules",List.of(Map.of("stepName","装配","plannedQuantity",i.getArgument(3,Integer.class))))));});
    }
    CustomerOrder order(long id){var o=new CustomerOrder();o.setOrderId(id);o.setOrderNo("CO"+id);o.setAuditStatus("PLAN_PENDING");o.setRequiredDeliveryDate(LocalDate.of(2027,2,1));return o;}
    CustomerOrderItem item(long id){var i=new CustomerOrderItem();i.setOrderId(id);i.setMaterialId(10L);i.setQuantity(BigDecimal.TEN);i.setProductName("显示器");return i;}
    Inventory stock(long id,int n){var r=new Inventory();r.setMaterialId(id);r.setInventoryStatus("NORMAL");r.setQuantityAvailable(BigDecimal.valueOf(n));return r;}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> r){return (List<Map<String,Object>>)r.get("orderPlans");}
    @Test void sharedFinishedStockIsAllocatedOnceAndOnlyGapIsProduced(){
        var result=service.preview(input);assertThat(result.get("canSubmit")).isEqualTo(true);
        assertThat(rows(result).get(0)).containsEntry("stockAllocation",10).containsEntry("plannedQty",0);
        assertThat(rows(result).get(1)).containsEntry("stockAllocation",5).containsEntry("plannedQty",5);
        verify(scheduling).compareSchemes(eq("CO2"),any(),any(),eq(5));verify(scheduling,never()).savePlanWithSchedule(any(),any());
    }
    @Test void newProductionUsesFullDemandAndDoesNotDoubleCountRawMaterial(){
        input.put("fulfillment","NEW_PRODUCTION");when(inventory.inventoryList()).thenReturn(array(stock(10,15),stock(20,30)));
        var result=service.preview(input);assertThat(result.get("canSubmit")).isEqualTo(false);
        assertThat(rows(result)).allSatisfy(r->assertThat(r).containsEntry("plannedQty",10).containsEntry("stockAllocation",0));
        assertThat(result.get("conflicts").toString()).contains("共享需求缺口 10");
        assertThat(rows(result).get(1).get("planStart")).isEqualTo("2027-01-03");
    }
    @Test void actualDeadlineAndExistingPlansConstrainPortfolio(){
        var p=new ProductionPlan();p.setPlanStatus("EXECUTING");p.setPlannedEndDate(LocalDate.of(2027,2,2));when(plans.planList()).thenReturn(array(p));
        var result=service.preview(input);assertThat(result.get("canSubmit")).isEqualTo(false);assertThat(result.get("conflicts").toString()).contains("晚于");
    }
    @Test void heldStockCannotCoverDemand(){var held=stock(10,100);held.setInventoryStatus("FROZEN");when(inventory.inventoryList()).thenReturn(array(held,stock(20,100)));assertThat(rows(service.preview(input))).allSatisfy(r->assertThat(r.get("stockAllocation")).isEqualTo(0));}
    @Test void changedInventoryPreventsAnyWritesAtConfirmation(){
        var result=service.preview(input);when(inventory.inventoryList()).thenReturn(array(stock(10,0),stock(20,100)));
        assertThatThrownBy(()->service.saveReviewed(Map.of("portfolioInputs",input,"planning",result),"admin")).isInstanceOf(BusinessException.class).hasMessageContaining("重新预览");
        verify(scheduling,never()).savePlanWithSchedule(any(),any());
    }
    @Test void confirmationSavesOnlyNeededDraftsAndNeverDispatches() throws Exception {
        var result=service.preview(input);when(scheduling.savePlanWithSchedule(any(),eq("admin"))).thenReturn(Map.of("planId","PP1"));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String,Object> stored=mapper.readValue(mapper.writeValueAsString(Map.of("portfolioInputs",input,"planning",result)),Map.class);
        var receipt=service.saveReviewed(stored,"admin");
        assertThat(receipt.get("message")).isEqualTo("已保存 1 份生产计划草稿；尚未发布或派工");
        verify(scheduling).savePlanWithSchedule(argThat(r->"draft".equals(r.get("saveAction"))&&"CO2".equals(r.get("orderId"))),eq("admin"));
    }
    @Test void progressReportsActualReadsAndNoFailedStageIsCompleted(){
        var events=new ArrayList<Map<String,Object>>();when(inventory.inventoryList()).thenThrow(new BusinessException(503,"库存不可用"));
        assertThatThrownBy(()->ProductionCalculationProgress.observe(events::add,()->service.preview(input))).isInstanceOf(BusinessException.class);
        assertThat(events).anySatisfy(e->assertThat(e).containsEntry("key","portfolio_stock").containsEntry("status","FAILED"));
        assertThat(events).noneMatch(e->"portfolio_stock".equals(e.get("key"))&&"COMPLETED".equals(e.get("status")));
    }
    @Test void malformedDuplicateOrMissingOrdersAreRejected(){
        assertThatThrownBy(()->ProductionPortfolioPlanner.orderNumbers("[\"CO1\",\"CO1\"]")).isInstanceOf(BusinessException.class);
        input.put("orderIds","[\"CO999\"]");assertThatThrownBy(()->service.preview(input)).hasMessageContaining("不存在");
    }
    @Test void portfolioDraftWritesAreAtomicInAnIsolatedDatabase(){
        var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE customer_order(order_id BIGINT,order_no VARCHAR(80))");
        db.execute("CREATE TABLE inventory(inventory_id BIGINT)");
        db.execute("CREATE TABLE plan_receipt(order_no VARCHAR(80))");
        db.update("INSERT INTO customer_order VALUES(1,'CO1'),(2,'CO2')");db.update("INSERT INTO inventory VALUES(1)");
        var target=new ProductionPortfolioPlanner(orders,items,inventory,bom,materials,plans,scheduling,db);
        var factory=new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional=(ProductionPortfolioPlanner)factory.getProxy();input.put("fulfillment","NEW_PRODUCTION");
        var payload=Map.<String,Object>of("portfolioInputs",input,"planning",transactional.preview(input));
        when(scheduling.savePlanWithSchedule(any(),eq("admin"))).thenAnswer(i->{
            Map<String,Object> row=i.getArgument(0);db.update("INSERT INTO plan_receipt VALUES(?)",row.get("orderId"));
            if("CO2".equals(row.get("orderId")))throw new BusinessException(409,"second plan failed");
            return Map.of("planId","PP1");
        });
        assertThatThrownBy(()->transactional.saveReviewed(payload,"admin")).hasMessageContaining("second plan failed");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM plan_receipt",Integer.class)).isZero();
    }
}
