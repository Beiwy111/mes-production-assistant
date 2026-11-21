package com.upc.computer.assistant.v2.tools.delivery;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryReadinessCalculatorTest {
    @Test void fullInspectionAndBalancedLotProvideCandidateButNotAnInventedReservation() {
        var t=stock();var line=DeliveryReadinessCalculator.fulfillment(t,true).get(0);
        assertEquals("60",line.get("remainingDeliveryQty"));
        assertEquals("50",line.get("candidateReadyQty"));
        assertNull(line.get("shippableQty"));
        assertEquals("SCENARIO_NOT_RESERVED",line.get("allocationStatus"));
    }
    @Test void samplingDoesNotReleaseTheWholeLotAndTruncationDisablesCalculations() {
        var t=stock();t.get("quality_inspection").get(0).put("sample_quantity","5");
        assertNull(DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
        assertNull(DeliveryReadinessCalculator.fulfillment(stock(),false).get(0).get("candidateReadyQty"));
    }
    @Test void conflictingLotLineageAndInventoryBalanceDisableCandidate() {
        var t=stock();t.get("inventory_transaction").get(0).put("related_work_order_id","999");
        assertNull(DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
        t=stock();t.get("inventory").get(0).put("quantity_on_hand","70");
        assertNull(DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
    }
    @Test void duplicateOrderLinesShareOnePoolRatherThanDoubleCounting() {
        var t=stock();t.get("customer_order_item").add(row("order_item_id","2","order_id","1","material_id","9","quantity","60","unit","unit","material_unit","unit","item_status","CONFIRMED"));
        var lines=DeliveryReadinessCalculator.fulfillment(t,true);
        assertEquals("50",lines.get(0).get("candidateReadyQty"));
        assertEquals("0",lines.get(1).get("candidateReadyQty"));
    }
    @Test void emptyRecordedStockMeansZeroButFrozenOrAmbiguousStockIsNeverReleased() {
        var t=stock();t.put("inventory",new ArrayList<>());
        assertEquals("0",DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("shippableQty"));
        t=stock();t.get("inventory").get(0).put("inventory_status","FROZEN");
        assertEquals("0",DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
    }
    @Test void currentBomScenarioIsUsefulButNotCalledConfirmedShortage() {
        var t=stock();t.put("bom",new ArrayList<>(List.of(row("bom_id","1","parent_material_id","9","child_material_id","1","child_unit","part","quantity","2","loss_rate","0","version_no","v1","effective_date","2020-01-01","status","1"))));
        t.get("inventory").add(row("inventory_id","12","material_id","1","quantity_on_hand","30","quantity_available","30","quantity_reserved","0","inventory_status","NORMAL"));
        var m=DeliveryReadinessCalculator.materials(t,true,LocalDate.of(2026,11,9)).get(0);
        assertEquals("120",m.get("estimatedGrossRequirementQty"));
        assertEquals("90",m.get("scenarioGapQty"));
        assertNull(m.get("confirmedShortageNow"));
        assertEquals("CURRENT_BOM_SCENARIO",m.get("calculationBasis"));
    }
    @Test void reservedQuantityAndNegativeOrInconsistentStockNeverBecomeAvailable() {
        var t=stock();t.get("inventory").get(0).put("quantity_reserved","20");t.get("inventory").get(0).put("quantity_available","30");
        assertEquals("30",DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
        t.get("inventory").get(0).put("quantity_available","51");
        assertNull(DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
    }
    @Test void materialsAlreadyIssuedToClosedWorkCannotSatisfyAnOpenWorkOrder() {
        var t=stock();t.put("bom",new ArrayList<>(List.of(row("bom_id","1","parent_material_id","9","child_material_id","1","child_unit","part","quantity","2","loss_rate","0","version_no","v1","effective_date","2020-01-01","status","1"))));
        t.get("work_order").add(row("work_order_id","20","order_item_id","1","material_id","9","mapping_status","LINKED","planned_quantity","60","status","COMPLETED"));
        t.get("inventory_transaction").add(row("transaction_id","77","material_id","1","transaction_type","MATERIAL_OUT","related_work_order_id","20","quantity","120"));
        var material=DeliveryReadinessCalculator.materials(t,true,LocalDate.of(2026,11,9)).get(0);
        assertEquals("120",material.get("estimatedRequiredUnissuedQty"));
    }
    @Test void excessIssueOnOneOpenWorkCannotSatisfyAnotherOpenWork() {
        var t=stock();t.put("bom",new ArrayList<>(List.of(row("bom_id","1","parent_material_id","9","child_material_id","1","child_unit","part","quantity","2","loss_rate","0","version_no","v1","effective_date","2020-01-01","status","1"))));
        t.get("work_order").add(row("work_order_id","20","order_item_id","1","material_id","9","mapping_status","LINKED","planned_quantity","60","status","PRODUCING"));
        t.get("inventory_transaction").add(row("transaction_id","77","material_id","1","transaction_type","MATERIAL_OUT","related_work_order_id","10","quantity","240"));
        assertEquals("120",DeliveryReadinessCalculator.materials(t,true,LocalDate.of(2026,11,9)).get(0).get("estimatedRequiredUnissuedQty"));
    }
    @Test void nonconformingLinkedByInspectionStillBlocksLotWhenWorkOrderKeyIsNull() {
        var t=stock();t.get("nonconforming_product").add(row("nonconforming_id","6","inspection_id","1","material_id","9","batch_no","B1","handle_status","PENDING"));
        assertNull(DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
    }
    @Test void inventoryKeyLineageCannotBeHiddenByMaterialFilter() {
        var t=stock();t.put("inventory_lineage",new ArrayList<>(t.get("inventory_transaction")));
        t.get("inventory_lineage").add(row("transaction_id","88","inventory_id","11","material_id","999","batch_no","B1","transaction_type","ADJUST_IN","quantity","1"));
        assertNull(DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
    }
    @Test void identicalStockKeyAndMaterialProjectionsAreNotDoubleCounted() {
        var t=stock();t.put("inventory_lineage",new ArrayList<>(t.get("inventory_transaction")));
        assertEquals("50",DeliveryReadinessCalculator.fulfillment(t,true).get(0).get("candidateReadyQty"));
    }
    static Map<String,List<Map<String,String>>> stock() {
        var t=new LinkedHashMap<String,List<Map<String,String>>>();
        for(String name:List.of("customer_order_item","work_order","delivery_order","inventory","inventory_transaction","quality_inspection","nonconforming_product","bom"))t.put(name,new ArrayList<>());
        t.get("customer_order_item").add(row("order_item_id","1","order_id","1","material_id","9","quantity","60","unit","unit","material_unit","unit","item_status","CONFIRMED"));
        t.get("work_order").add(row("work_order_id","10","order_item_id","1","material_id","9","mapping_status","LINKED","planned_quantity","60","status","PRODUCING"));
        t.get("inventory").add(row("inventory_id","11","material_id","9","batch_no","B1","quantity_on_hand","50","quantity_available","50","quantity_reserved","0","inventory_status","NORMAL"));
        t.get("inventory_transaction").add(row("transaction_id","1","inventory_id","11","material_id","9","batch_no","B1","transaction_type","PRODUCT_IN","related_work_order_id","10","quantity","50"));
        t.get("quality_inspection").add(row("inspection_id","1","work_order_id","10","material_id","9","batch_no","B1","inspection_type","FINAL","inspection_status","PASSED","inspection_result","QUALIFIED","sample_quantity","50","qualified_quantity","50","unqualified_quantity","0"));
        return t;
    }
    static Map<String,String> row(String... fields){var r=new LinkedHashMap<String,String>();for(int i=0;i<fields.length;i+=2)r.put(fields[i],fields[i+1]);return r;}
}
