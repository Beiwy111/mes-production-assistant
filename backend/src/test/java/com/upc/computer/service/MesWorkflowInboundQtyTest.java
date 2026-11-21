package com.upc.computer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MesWorkflowInboundQtyTest {

    @Test
    void usesSubmitQtyMinusUnqualifiedWhenSamplingPasses() {
        // 送检 100，抽检 5 台全合格 → 入库 100
        assertEquals(100, MesWorkflowService.resolveInboundReleaseQty(100, 5, 0, 5));
        // 送检 100，抽检不合格 2 → 入库 98
        assertEquals(98, MesWorkflowService.resolveInboundReleaseQty(100, 3, 2, 5));
    }

    @Test
    void fallsBackToSampleQualifiedWithoutSubmitQty() {
        assertEquals(5, MesWorkflowService.resolveInboundReleaseQty(0, 5, 0, 5));
        assertEquals(3, MesWorkflowService.resolveInboundReleaseQty(0, 0, 2, 5));
        assertEquals(0, MesWorkflowService.resolveInboundReleaseQty(0, 0, 0, 0));
    }
}
