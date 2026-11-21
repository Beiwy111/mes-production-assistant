package com.upc.computer.service;

import com.upc.computer.common.BusinessException;
import com.upc.computer.entity.Inventory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MesWorkflowInventoryIssueTest {

    @Test
    void deductsOnHandAndKeepsAvailableNonNegativeWhenReservedHigh() {
        Inventory inv = new Inventory();
        inv.setQuantityOnHand(new BigDecimal("56"));
        inv.setQuantityReserved(new BigDecimal("40"));
        // 旧数据：available 可能已小于 onHand-reserved，或直接小于领料数
        inv.setQuantityAvailable(new BigDecimal("10"));

        MesWorkflowService.applyInventoryIssue(inv, new BigDecimal("51"));

        assertEquals(0, new BigDecimal("5").compareTo(inv.getQuantityOnHand()));
        assertEquals(0, new BigDecimal("5").compareTo(inv.getQuantityReserved()));
        assertEquals(0, BigDecimal.ZERO.compareTo(inv.getQuantityAvailable()));
    }

    @Test
    void rejectsWhenOnHandInsufficient() {
        Inventory inv = new Inventory();
        inv.setQuantityOnHand(new BigDecimal("10"));
        inv.setQuantityReserved(BigDecimal.ZERO);
        inv.setQuantityAvailable(new BigDecimal("10"));

        assertThrows(BusinessException.class,
                () -> MesWorkflowService.applyInventoryIssue(inv, new BigDecimal("51")));
    }
}
