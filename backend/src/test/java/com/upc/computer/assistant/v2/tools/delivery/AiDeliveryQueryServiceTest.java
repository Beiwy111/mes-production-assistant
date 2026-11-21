package com.upc.computer.assistant.v2.tools.delivery;

import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AiDeliveryQueryServiceTest {
    @SuppressWarnings("unchecked")
    @Test void midnightInvalidatesReferencesWhenEffectiveBomChangesWithoutDatabaseWrites() {
        var fixture = new BomFixture();
        var e1 = fixture.query("E1", null, null, null);
        var e4 = fixture.query("E4", null, null, null);
        var e2 = fixture.query("E2", ref(e1), ref(e4), null);
        assertEquals("100", gross(e2));

        fixture.instant.set(Instant.parse("2026-11-08T16:01:00Z"));
        var revalidated = fixture.service.revalidate(5, "10", fixture.scope, List.of(ref(e2)), "1", DeliveryEvidenceRegistry.VERSION);
        var data = (Map<String,Object>)revalidated.get("data");
        assertEquals("CHANGED", data.get("revalidationStatus"));
        assertEquals(List.of(ref(e2)), data.get("invalidatedQueryRefs"));
        var newContext = fixture.query("E1", null, null, null);
        var stale = assertThrows(ResponseStatusException.class, () -> fixture.query("E3", ref(newContext), null, ref(e2)));
        assertEquals(409, stale.getStatusCode().value());
        var newFulfillment = fixture.query("E4", null, null, null);
        var newMaterial = fixture.query("E2", ref(newContext), ref(newFulfillment), null);
        assertEquals("200", gross(newMaterial));
        assertEquals("2026-11-08", e2.get("calculationDate"));
        assertEquals("2026-11-09", newMaterial.get("calculationDate"));
        assertNotEquals(e2.get("sourceFingerprint"), newMaterial.get("sourceFingerprint"));
    }

    @Test void aReadCrossingMidnightKeepsOneCalculationDateForBomAndFingerprint() {
        var fixture = new BomFixture();
        var e1 = fixture.query("E1", null, null, null);
        var e4 = fixture.query("E4", null, null, null);
        when(fixture.mapper.order(10L)).thenAnswer(invocation -> {
            fixture.instant.set(Instant.parse("2026-11-08T16:00:01Z"));
            return fixture.head;
        });
        var e2 = fixture.query("E2", ref(e1), ref(e4), null);
        assertEquals("100", gross(e2));
        assertEquals("2026-11-08", e2.get("calculationDate"));
        assertEquals(e1.get("sourceFingerprint"), e2.get("sourceFingerprint"));
        assertTrue(((String)e2.get("readFinishedAt")).startsWith("2026-11-09"));
    }

    private static String ref(Map<String,Object> envelope) { return (String)envelope.get("queryRef"); }
    @SuppressWarnings("unchecked")
    private static Object gross(Map<String,Object> envelope) {
        var data = (Map<String,Object>)envelope.get("data");
        var buckets = (List<Map<String,Object>>)data.get("demandBuckets");
        return ((Map<String,String>)buckets.get(0).get("grossRequirementByMaterial")).get("20");
    }
    private static final class BomFixture {
        final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-11-08T15:58:00Z"));
        final AiDeliveryReadMapper mapper = mock(AiDeliveryReadMapper.class);
        final List<Map<String,Object>> head = List.of(Map.of("order_id", 10L, "order_no", "O-10"));
        final AiDeliveryQueryService service;
        final String scope;
        BomFixture() {
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenAnswer(invocation -> instant.get());
            when(clock.withZone(any(ZoneId.class))).thenAnswer(invocation -> Clock.fixed(instant.get(), invocation.getArgument(0)));
            when(mapper.resolve(10L, null)).thenReturn(head);
            when(mapper.order(10L)).thenReturn(head);
            when(mapper.lines(10L)).thenReturn(List.of(Map.of("order_item_id", 1L, "material_id", 9L, "quantity", "100", "unit", "pcs", "material_unit", "pcs")));
            when(mapper.workOrders(10L)).thenReturn(List.of(Map.of("work_order_id", 1L, "material_id", 9L, "planned_quantity", "100", "status", "RELEASED", "mapping_status", "LINKED")));
            when(mapper.bom(10L)).thenReturn(List.of(
                    Map.of("parent_material_id", 9L, "child_material_id", 20L, "child_unit", "pcs", "quantity", "1", "loss_rate", "0", "version_no", "V1", "status", "1", "effective_date", "2026-01-01", "expire_date", "2026-11-08"),
                    Map.of("parent_material_id", 9L, "child_material_id", 20L, "child_unit", "pcs", "quantity", "2", "loss_rate", "0", "version_no", "V2", "status", "1", "effective_date", "2026-11-09")));
            service = new AiDeliveryQueryService(mapper, new DeliveryEvidenceRegistry(clock), clock);
            scope = (String)service.resolve(5, "10", null, null, "FACTORY_SHIPMENT").get("scopeRef");
        }
        Map<String,Object> query(String kind, String context, String fulfillment, String material) {
            return service.query(5, "10", scope, kind, context, fulfillment, material);
        }
    }

    @SuppressWarnings("unchecked")
    @Test void referencesAreServerBoundAndRevalidationDetectsNewShipments() {
        var mapper = mock(AiDeliveryReadMapper.class);
        var head = List.<Map<String,Object>>of(Map.of("order_id", 10L, "order_no", "O-10"));
        when(mapper.resolve(10L, null)).thenReturn(head); when(mapper.order(10L)).thenReturn(head);
        when(mapper.lines(10L)).thenReturn(List.of(Map.of("order_item_id", 1L, "material_id", 9L, "quantity", "100", "unit", "pcs", "material_unit", "pcs")));
        var registry = new DeliveryEvidenceRegistry(Clock.systemUTC());
        var service = new AiDeliveryQueryService(mapper, registry, Clock.systemUTC());
        var resolved = service.resolve(5, "10", null, null, "FACTORY_SHIPMENT");
        String scope = (String) resolved.get("scopeRef");
        var e1 = service.query(5, "10", scope, "E1", null, null, null);
        var e4 = service.query(5, "10", scope, "E4", null, null, null);
        var e2 = service.query(5, "10", scope, "E2", (String)e1.get("queryRef"), (String)e4.get("queryRef"), null);
        assertEquals("UNKNOWN", ((Map<String,Object>)e2.get("data")).get("readiness"));
        assertThrows(ResponseStatusException.class, () -> service.query(6, "10", scope, "E4", null, null, null));
        when(mapper.deliveries(10L)).thenReturn(List.of(Map.of("delivery_id", 1L, "material_id", 9L, "delivery_quantity", "20", "delivery_status", "SHIPPED")));
        var checked = service.revalidate(5, "10", scope, List.of((String)e4.get("queryRef")), "1", DeliveryEvidenceRegistry.VERSION);
        assertEquals("CHANGED", ((Map<String,Object>)checked.get("data")).get("revalidationStatus"));
        assertThrows(ResponseStatusException.class, () -> service.query(5, "10", scope, "E2", (String)e1.get("queryRef"), (String)e4.get("queryRef"), null));
    }
}
