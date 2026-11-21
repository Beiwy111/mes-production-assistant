package com.upc.computer.assistant.v2.tools.delivery;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryProductionCalculatorTest {
    @Test void oneConfirmedReportPerStageGivesRecordedBottleneckWithoutAddingStages() {
        var work = Map.of("work_order_id","1","route_id","2","status","RUNNING","planned_quantity","10");
        var steps = List.of(Map.of("step_id","3","route_id","2","step_no","1","status","1"), Map.of("step_id","4","route_id","2","step_no","2","status","1"));
        var dispatch = List.of(Map.of("dispatch_id","5","work_order_id","1","step_id","3","status","COMPLETED"),Map.of("dispatch_id","6","work_order_id","1","step_id","4","status","PRODUCING"));
        var reports = List.of(Map.of("report_id","7","dispatch_id","5","work_order_id","1","step_id","3","completed_quantity","10","report_status","CONFIRMED"),Map.of("report_id","8","dispatch_id","6","work_order_id","1","step_id","4","completed_quantity","4","report_status","CONFIRMED"));
        var result = DeliveryProductionCalculator.execution(work, steps, dispatch, reports, true);
        assertEquals("4", result.get("bottleneckCompletedQty"));
        assertEquals("4", result.get("finalStageCompletedQty"));
        assertEquals("IN_PROGRESS", result.get("executionStatus"));
        var duplicates = new ArrayList<>(dispatch); duplicates.add(Map.of("dispatch_id","9","work_order_id","1","step_id","4","status","PRODUCING"));
        assertNull(DeliveryProductionCalculator.execution(work,steps,duplicates,reports,true).get("bottleneckCompletedQty"));
    }
    @Test void uniqueEffectiveBomProducesEstimateButOverlappingVersionIsUnknown() {
        var work = Map.of("work_order_id","1","material_id","2","planned_quantity","100","status","RELEASED");
        var bom = Map.of("parent_material_id","2","child_material_id","3","quantity","2","loss_rate","0.02","version_no","V1","status","1","effective_date","2020-01-01");
        var result = DeliveryProductionCalculator.gross(work, List.of(bom), LocalDate.of(2026,11,8));
        assertEquals("204", result.get("3"));
        var second = new HashMap<>(bom); second.put("version_no","V2");
        assertTrue(DeliveryProductionCalculator.gross(work,List.of(bom,second),LocalDate.of(2026,11,8)).isEmpty());
    }
}
