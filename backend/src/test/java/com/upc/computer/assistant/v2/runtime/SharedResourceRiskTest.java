package com.upc.computer.assistant.v2.runtime;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedResourceRiskTest {
    @Test void allocationUsesEachPlanLineDueDateBeforeAggregatingOrders() {
        var early=plan("1","10");early.put("due_date","2026-12-01");
        var late=plan("1","90");late.put("plan_item_id","11");late.put("order_item_id","11");late.put("due_date","2026-12-20");
        var middle=plan("2","50");middle.put("due_date","2026-12-10");
        var stock=inventory();stock.put("quantity_on_hand","70");stock.put("quantity_available","60");
        var r=SharedMaterialRisk.calculate(List.of(early,late,middle),List.of(bom()),List.of(stock),List.of(),List.of(),LocalDate.of(2026,11,16));
        var rows=(List<Map<String,Object>>)r.get("rows");assertEquals("10",rows.get(0).get("scenario_allocated_qty"));assertEquals("50",rows.get(1).get("scenario_allocated_qty"));
    }
    @Test void zeroPlanRemainderDoesNotHideContradictoryWorkProgress() {
        var p=plan("1","80");p.put("completed_quantity","80");
        var w=row("plan_item_id","1","status","RUNNING","completed_quantity","0","mapping_status","LINKED");
        var r=SharedMaterialRisk.calculate(List.of(p),List.of(bom()),List.of(inventory()),List.of(w),List.of(),LocalDate.of(2026,11,16));
        assertFalse(((List<?>)r.get("coverage")).isEmpty());
    }
    @Test void unplannedOrderLinesAndPlansWithoutWindowsRemainUnknown() {
        var mapper=org.mockito.Mockito.mock(NaturalBusinessReadMapper.class);
        org.mockito.Mockito.when(mapper.resourcePlans(List.of(1L))).thenReturn(List.of(plan("1","80")));
        org.mockito.Mockito.when(mapper.riskLines(List.of(1L),null)).thenReturn(List.of(row("order_id","1","order_item_id","9","quantity","20","item_status","PENDING")));
        var r=ResourceRiskQueries.execute(mapper,AssistantReadPolicy.admin(),"RESOURCE_CAPACITY",List.of(row("record_id","1","order_no","SO1","status","APPROVED")),LocalDateTime.of(2026,11,16,0,0));
        var coverage=(List<Map<String,Object>>)r.get("coverage");
        assertTrue(coverage.stream().anyMatch(c->"UNPLANNED_ORDER_LINE".equals(c.get("risk_status"))));
        assertTrue(coverage.stream().anyMatch(c->"NO_CURRENT_SCHEDULE".equals(c.get("risk_status"))));
    }
    static Map<String,Object> row(Object... pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m;}
    static Map<String,Object> plan(String order,String qty){return row("order_id",order,"order_no","SO"+order,"order_status","APPROVED","plan_id",order,"plan_item_id",order,"order_item_id",order,"material_id","10","material_unit","台","order_unit","台","planned_quantity",qty,"completed_quantity","0","plan_status","EXECUTING","item_status","RUNNING","due_date","2026-12-01","mapping_status","LINKED");}
    static Map<String,Object> bom(){return row("bom_id","8","parent_material_id","10","child_material_id","20","material_name","PCB","unit","块","quantity","1","loss_rate","0","version_no","V1","status","1","effective_date","2026-01-01");}
    static Map<String,Object> inventory(){return row("inventory_id","5","material_id","20","unit","块","quantity_on_hand","110","quantity_reserved","10","quantity_available","100","inventory_status","NORMAL");}
    @Test void sharedStockIsAllocatedOnceAcrossOrdersAndNeverPromisedAsRealShortage() {
        var result=SharedMaterialRisk.calculate(List.of(plan("1","80"),plan("2","60")),List.of(bom()),List.of(inventory()),List.of(),List.of(),LocalDate.of(2026,11,16));
        var rows=(List<Map<String,Object>>)result.get("rows");
        assertEquals(2,rows.size());assertEquals("80",rows.get(0).get("scenario_allocated_qty"));assertEquals("20",rows.get(1).get("scenario_allocated_qty"));
        assertEquals("40",rows.get(1).get("scenario_gap_qty"));assertEquals("SHARED_POOL_SCENARIO_GAP",rows.get(1).get("risk_status"));
        assertEquals("SCENARIO_NOT_CONFIRMED_SHORTAGE",result.get("status"));
    }
    @Test void ambiguousBomAndInconsistentInventoryNeverBecomeReliableGaps() {
        var other=new HashMap<>(bom());other.put("bom_id","9");other.put("version_no","V2");
        var r=SharedMaterialRisk.calculate(List.of(plan("1","80")),List.of(bom(),other),List.of(inventory()),List.of(),List.of(),LocalDate.of(2026,11,16));
        assertFalse(((List<?>)r.get("coverage")).isEmpty());assertTrue(((List<?>)r.get("rows")).isEmpty());
        var bad=new HashMap<>(inventory());bad.put("quantity_available","110");
        r=SharedMaterialRisk.calculate(List.of(plan("1","80")),List.of(bom()),List.of(bad),List.of(),List.of(),LocalDate.of(2026,11,16));
        assertNull(((List<Map<String,Object>>)r.get("rows")).get(0).get("scenario_gap_qty"));
    }
    static Map<String,Object> slot(String id,String order,String start,String end){return row("schedule_id",id,"plan_id",order,"order_id",order,"order_no","SO"+order,"plan_status","EXECUTING","order_status","APPROVED","step_id","4","equipment_id","7","planned_start",start,"planned_end",end);}
    @Test void capacityUsesHalfOpenFutureIntervalsAndKeepsBothEvidenceIds() {
        var slots=List.of(slot("1","1","2026-11-17 08:00:00","2026-11-17 10:00:00"),slot("2","2","2026-11-17 09:00:00","2026-11-17 11:00:00"),slot("3","3","2026-11-17 11:00:00","2026-11-17 12:00:00"));
        var r=CapacityConflictRisk.calculate(slots,List.of(),List.of(),LocalDateTime.of(2026,11,16,0,0));
        var conflicts=(List<Map<String,Object>>)r.get("rows");assertEquals(1,conflicts.size());
        assertEquals("60",conflicts.get(0).get("overlap_minutes"));assertEquals("1",conflicts.get(0).get("left_source_id"));assertEquals("2",conflicts.get(0).get("right_source_id"));
    }
    @Test void dispatchWithoutUniqueScheduleNeverInventsOccupancyFromAssignmentTime() {
        var d=row("dispatch_id","9","order_id","1","plan_id","1","work_order_id","5","step_id","4","operator_id","6","status","ASSIGNED","assigned_at","2026-11-17 08:00:00");
        var r=CapacityConflictRisk.calculate(List.of(),List.of(d),List.of(),LocalDateTime.of(2026,11,16,0,0));
        assertTrue(((List<?>)r.get("rows")).isEmpty());assertFalse(((List<?>)r.get("coverage")).isEmpty());
    }
    @Test void frozenStockAndRecordedIssuesDoNotInflateOrSilentlyReducePool() {
        var frozen=new HashMap<>(inventory());frozen.put("inventory_id","6");frozen.put("inventory_status","FROZEN");
        var issued=row("order_id","1","material_id","20","transaction_id","3","transaction_type","MATERIAL_OUT","mapping_status","LINKED","quantity","70");
        var r=SharedMaterialRisk.calculate(List.of(plan("1","120")),List.of(bom()),List.of(inventory(),frozen),List.of(),List.of(issued),LocalDate.of(2026,11,16));
        var actual=((List<Map<String,Object>>)r.get("rows")).get(0);
        assertEquals("100",actual.get("public_available_qty"));assertEquals("20",actual.get("scenario_gap_qty"));assertEquals("70",actual.get("recorded_issued_qty"));
    }
    @Test void operatorsNeedMatchingWindowsAndCompletedDispatchesDoNotOccupy() {
        var a=slot("1","1","2026-11-17 08:00:00","2026-11-17 10:00:00");
        var b=slot("2","2","2026-11-17 09:00:00","2026-11-17 11:00:00");b.put("equipment_id","8");
        var d1=row("dispatch_id","10","order_id","1","plan_id","1","step_id","4","operator_id","6","status","ASSIGNED","mapping_status","LINKED");
        var d2=new HashMap<>(d1);d2.put("dispatch_id","11");d2.put("order_id","2");d2.put("plan_id","2");
        var r=CapacityConflictRisk.calculate(List.of(a,b),List.of(d1,d2),List.of(),LocalDateTime.of(2026,11,16,0,0));
        var rows=(List<Map<String,Object>>)r.get("rows");assertEquals(1,rows.size());assertEquals("OPERATOR",rows.get(0).get("resource_type"));assertEquals("60",rows.get(0).get("overlap_minutes"));
        d2.put("status","COMPLETED");r=CapacityConflictRisk.calculate(List.of(a,b),List.of(d1,d2),List.of(),LocalDateTime.of(2026,11,16,0,0));assertTrue(((List<?>)r.get("rows")).isEmpty());
    }
}
