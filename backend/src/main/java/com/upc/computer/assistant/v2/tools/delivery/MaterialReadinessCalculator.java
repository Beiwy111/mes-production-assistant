package com.upc.computer.assistant.v2.tools.delivery;

import java.math.BigDecimal;
import java.util.*;

/** Calculations require proved inputs. Unknown returns stay unknown. */
public final class MaterialReadinessCalculator {
    private MaterialReadinessCalculator() { }
    public static BigDecimal unissued(BigDecimal wholeTask, BigDecimal bomQty, BigDecimal lossRate, BigDecimal issued, BigDecimal confirmedReturned) {
        if (unknown(wholeTask, bomQty, lossRate, issued, confirmedReturned) || confirmedReturned.compareTo(issued) > 0) return null;
        return wholeTask.multiply(bomQty).multiply(BigDecimal.ONE.add(lossRate)).subtract(issued.subtract(confirmedReturned)).max(BigDecimal.ZERO);
    }
    public static BigDecimal shortage(BigDecimal required, BigDecimal owned, BigDecimal allocated) {
        return unknown(required, owned, allocated) ? null : required.subtract(owned).subtract(allocated).max(BigDecimal.ZERO);
    }
    private static boolean unknown(BigDecimal... numbers) { return Arrays.stream(numbers).anyMatch(n -> n == null || n.signum() < 0); }
    /** One ledger shared across finished/semi-finished/raw material scenario allocations. */
    public static final class AllocationLedger {
        private final Map<String,BigDecimal> remaining;
        public AllocationLedger(Map<String,BigDecimal> verifiedPublicPool) {
            if (verifiedPublicPool.values().stream().anyMatch(n -> unknown(n))) throw new IllegalArgumentException("Unverified inventory pool");
            remaining = new HashMap<>(verifiedPublicPool);
        }
        public BigDecimal allocate(String inventoryId, BigDecimal demand) {
            if (unknown(demand) || !remaining.containsKey(inventoryId)) return null;
            var amount = demand.min(remaining.get(inventoryId));
            remaining.compute(inventoryId, (id, available) -> available.subtract(amount));
            return amount;
        }
    }
}
