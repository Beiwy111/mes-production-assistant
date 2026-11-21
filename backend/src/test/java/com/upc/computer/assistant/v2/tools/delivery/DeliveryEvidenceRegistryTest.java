package com.upc.computer.assistant.v2.tools.delivery;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryEvidenceRegistryTest {
    @Test void scopeAndQueryAreBoundToUserOrderAndKind() {
        var registry = new DeliveryEvidenceRegistry(Clock.systemUTC());
        var scope = registry.create(7, "10", null, "FACTORY_SHIPMENT");
        registry.requireScope(7, "10", scope.ref());
        assertThrows(RuntimeException.class, () -> registry.requireScope(8, "10", scope.ref()));
        assertThrows(RuntimeException.class, () -> registry.requireScope(7, "11", scope.ref()));
        var query = registry.register(scope, "E4", "hash");
        assertEquals("hash", registry.requireQuery(scope, query, "E4").fingerprint());
        assertThrows(RuntimeException.class, () -> registry.requireQuery(scope, query, "E1"));
    }
    @Test void fingerprintDetectsInsertsDeletesAndChangesWithoutUpdatedAt() {
        var a = Map.of("inventory_transaction", List.of(Map.of("transaction_id", "1", "quantity", "1")));
        var b = Map.of("inventory_transaction", List.of(Map.of("transaction_id", "1", "quantity", "2")));
        var c = Map.of("inventory_transaction", List.of(Map.of("transaction_id", "1", "quantity", "1"), Map.of("transaction_id", "2", "quantity", "1")));
        assertNotEquals(DeliveryEvidenceRegistry.fingerprint(a), DeliveryEvidenceRegistry.fingerprint(b));
        assertNotEquals(DeliveryEvidenceRegistry.fingerprint(a), DeliveryEvidenceRegistry.fingerprint(c));
        assertEquals(DeliveryEvidenceRegistry.fingerprint(a), DeliveryEvidenceRegistry.fingerprint(a));
    }
}
