package com.upc.computer.service;

import com.upc.computer.common.BusinessException;
import com.upc.computer.entity.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MesWorkflowBoundaryTest {
    @ParameterizedTest
    @CsvSource({
            "100,5,0,5,100", "100,3,2,5,98", "1,0,1,1,0",
            "1,0,2,2,0", "100,5,-1,5,100", "0,5,2,7,5",
            "0,0,2,5,3", "0,0,5,5,0", "0,0,6,5,0",
            "0,0,-1,5,5", "0,0,0,0,0", "-1,-1,-1,-1,0"
    })
    void calculatesReleaseQuantityAtDecisionBoundaries(int submitted, int qualified,
            int unqualified, int sampled, int expected) {
        assertEquals(expected, MesWorkflowService.resolveInboundReleaseQty(
                submitted, qualified, unqualified, sampled));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void rejectsInvalidIssueQuantityWithoutChangingInventory(String quantity) {
        Inventory inventory = inventory("10", "2");
        assertThrows(BusinessException.class, () -> MesWorkflowService.applyInventoryIssue(
                inventory, quantity == null ? null : new BigDecimal(quantity)));
        assertEquals(new BigDecimal("10"), inventory.getQuantityOnHand());
        assertEquals(new BigDecimal("2"), inventory.getQuantityReserved());
    }

    @Test
    void rejectsMissingInventoryAndMissingOnHand() {
        assertThrows(BusinessException.class,
                () -> MesWorkflowService.applyInventoryIssue(null, BigDecimal.ONE));
        assertThrows(BusinessException.class,
                () -> MesWorkflowService.applyInventoryIssue(new Inventory(), BigDecimal.ONE));
    }

    @ParameterizedTest
    @CsvSource({"10,2,3,7,2,5", "10,8,5,5,5,0", "10,-2,3,7,0,7",
            "10,2,10,0,0,0", "1.5,0.5,0.25,1.25,0.5,0.75"})
    void preservesInventoryAccounting(String onHand, String reserved, String quantity,
            String expectedHand, String expectedReserved, String expectedAvailable) {
        Inventory inventory = inventory(onHand, reserved);
        MesWorkflowService.applyInventoryIssue(inventory, new BigDecimal(quantity));
        assertAmount(expectedHand, inventory.getQuantityOnHand());
        assertAmount(expectedReserved, inventory.getQuantityReserved());
        assertAmount(expectedAvailable, inventory.getQuantityAvailable());
        assertEquals(0, inventory.getQuantityOnHand().compareTo(
                inventory.getQuantityAvailable().add(inventory.getQuantityReserved())));
    }

    @Test
    void treatsMissingReservationAsZero() {
        Inventory inventory = inventory("10", "0");
        inventory.setQuantityReserved(null);
        MesWorkflowService.applyInventoryIssue(inventory, BigDecimal.ONE);
        assertAmount("9", inventory.getQuantityAvailable());
    }

    private static Inventory inventory(String onHand, String reserved) {
        Inventory result = new Inventory();
        result.setQuantityOnHand(new BigDecimal(onHand));
        result.setQuantityReserved(new BigDecimal(reserved));
        return result;
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
