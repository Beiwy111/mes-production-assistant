package com.upc.computer.assistant.v2.tools.delivery;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryQuantityCalculatorTest {
    private final DeliveryQuantityCalculator calculator = new DeliveryQuantityCalculator();
    private Map<String,String> line(String id, String qty, String status) {
        return Map.of("order_item_id", id, "material_id", "9", "quantity", qty, "unit", "pcs", "material_unit", "pcs", "item_status", status);
    }
    private Map<String,String> shipment(String id, String qty, String status) {
        return Map.of("delivery_id", id, "material_id", "9", "delivery_quantity", qty, "delivery_status", status);
    }
    @Test void ignoresPreparedAndDeduplicatesDeliveryIds() {
        var shipped = shipment("1", "20", "SHIPPED");
        var result = calculator.fulfillment(List.of(line("1", "100", "ACTIVE")), List.of(shipped, shipped, shipment("2", "15", "PREPARED")), true).get(0);
        assertEquals("80", result.get("remainingDeliveryQty"));
        assertNull(result.get("orderReadyQty"));
        assertNull(result.get("shippableQty"));
    }
    @Test void cancelledDuplicateMaterialStillMakesFallbackAmbiguous() {
        var result = calculator.fulfillment(List.of(line("1", "100", "ACTIVE"), line("2", "50", "CANCELLED")), List.of(shipment("1", "20", "SHIPPED")), true);
        assertNull(result.get(0).get("remainingDeliveryQty"));
    }
    @Test void overDeliveryAndMissingCoverageAreNotHiddenAsZero() {
        var lines = List.of(line("1", "100", "ACTIVE"));
        var shipments = List.of(shipment("1", "120", "RECEIVED"));
        assertEquals("20", calculator.fulfillment(lines, shipments, true).get(0).get("overDeliveredQty"));
        assertNull(calculator.fulfillment(lines, shipments, false).get(0).get("remainingDeliveryQty"));
    }
    @Test void sameInventoryCannotCoverFinishedGoodsAndRawMaterialTwice() {
        var ledger = new MaterialReadinessCalculator.AllocationLedger(Map.of("stock-1", new BigDecimal("50")));
        assertEquals(new BigDecimal("30"), ledger.allocate("stock-1", new BigDecimal("30")));
        assertEquals(new BigDecimal("20"), ledger.allocate("stock-1", new BigDecimal("40")));
        assertEquals(new BigDecimal("20"), MaterialReadinessCalculator.shortage(new BigDecimal("40"), BigDecimal.ZERO, new BigDecimal("20")));
    }
    @Test void entireWorkOrderTargetIsUsedWithLifetimeIssues() {
        assertEquals(new BigDecimal("62.00"), MaterialReadinessCalculator.unissued(new BigDecimal("100"), new BigDecimal("2"), new BigDecimal("0.01"), new BigDecimal("140"), BigDecimal.ZERO));
        assertNull(MaterialReadinessCalculator.unissued(new BigDecimal("100"), new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("140"), null));
    }
    @Test void missingShipmentIdentityOrMaterialCannotEstablishZeroShipped() {
        var broken = Map.of("delivery_id", "1", "delivery_quantity", "20", "delivery_status", "SHIPPED");
        assertNull(calculator.fulfillment(List.of(line("1", "100", "PENDING")), List.of(broken), true).get(0).get("remainingDeliveryQty"));
    }
}
