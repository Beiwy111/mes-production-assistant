package com.upc.computer.assistant.v2.runtime;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrossOrderRiskAnalysisTest {
    private Map<String,Object> order=Map.of("record_id","1","order_no","SO1","required_delivery_date","2026-11-10","status","APPROVED");
    private Map<String,Object> line=Map.of("order_id","1","order_item_id","11","material_id","8","product_name","显示器","quantity","100","unit","台","material_unit","台","item_status","ACTIVE");
    @Test void postApprovalStatesAreNotAuditBlockedAndCancelledHeadersAreExcluded() {
        for(String status:List.of("PLAN_PENDING","PLANNED","PRODUCING","SHIPPED")) {
            var future=new HashMap<>(order);future.put("status",status);future.put("required_delivery_date","2026-12-01");
            assertEquals("PENDING_DELIVERY",CrossOrderRiskAnalysis.summarize(List.of(future),List.of(line),List.of(),LocalDate.of(2026,11,15)).get(0).get("risk_status"));
        }
        var cancelled=new HashMap<>(order);cancelled.put("status","CANCELLED");
        assertEquals("EXCLUDED",CrossOrderRiskAnalysis.summarize(List.of(cancelled),List.of(line),List.of(),LocalDate.of(2026,11,15)).get(0).get("risk_status"));
    }
    @Test void remainingAndOverdueArePerLineNotSummedAcrossOrders() {
        var shipment=Map.<String,Object>of("order_id","1","delivery_id","9","material_id","8","delivery_quantity","30","delivery_status","SHIPPED");
        var rows=CrossOrderRiskAnalysis.summarize(List.of(order),List.of(line),List.of(shipment),LocalDate.of(2026,11,15));
        assertEquals("70",rows.get(0).get("remainingDeliveryQty"));assertEquals("OVERDUE",rows.get(0).get("risk_status"));assertEquals(5L,rows.get(0).get("overdue_days"));
    }
    @Test void duplicateMaterialLinesWithUnmappedShipmentAreUnknownNotInventedAllocation() {
        var second=new HashMap<>(line);second.put("order_item_id","12");
        var shipment=Map.<String,Object>of("order_id","1","delivery_id","9","material_id","8","delivery_quantity","30","delivery_status","SHIPPED");
        var rows=CrossOrderRiskAnalysis.summarize(List.of(order),List.of(line,second),List.of(shipment),LocalDate.of(2026,11,15));
        assertEquals(2,rows.size());assertTrue(rows.stream().allMatch(r->r.get("remainingDeliveryQty")==null&&r.get("risk_status").equals("UNKNOWN")));
    }
    @Test void noLineAndUnknownDueDateDoNotBecomeSafeOrders() {
        var noDate=new HashMap<>(order);noDate.remove("required_delivery_date");
        assertEquals("UNKNOWN",CrossOrderRiskAnalysis.summarize(List.of(order),List.of(),List.of(),LocalDate.now()).get(0).get("risk_status"));
        assertEquals("UNKNOWN",CrossOrderRiskAnalysis.summarize(List.of(noDate),List.of(line),List.of(),LocalDate.now()).get(0).get("risk_status"));
    }
}
