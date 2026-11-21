package com.upc.computer.assistant.v2.tools.graphexport;

import com.upc.computer.assistant.v2.mapper.graphexport.GraphExportMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphExportServiceTest {
    private final GraphExportMapper mapper = mock(GraphExportMapper.class);
    private final GraphExportService service = new GraphExportService(mapper, Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC));
    private void chain() {
        when(mapper.order(1L)).thenReturn(List.of(Map.of("id", 1L, "status", "APPROVED", "customer_name", "DO NOT EXPORT")));
        when(mapper.items(1L)).thenReturn(List.of(Map.of("id", 2L, "quantity", 100)));
        when(mapper.plans(1L)).thenReturn(List.of(Map.of("id", 3L, "status", "RUNNING")));
        when(mapper.planLinks(1L)).thenReturn(List.of(Map.of("id", 4L, "item_id", 2L, "plan_id", 3L)));
        when(mapper.workOrders(1L)).thenReturn(List.of(Map.of("id", 5L, "plan_id", 3L, "plan_item_id", 4L, "item_id", 2L)));
        when(mapper.inspections(1L)).thenReturn(List.of(Map.of("id", 6L, "work_order_id", 5L, "status", "PASSED")));
    }
    @Test void exportsOnlySafeFieldsWithSourceBackedDeterministicRelations() {
        chain(); var result = service.export(1L);
        assertEquals("1", result.schemaVersion()); assertEquals(Map.of("orderPk", "1"), result.scope());
        assertDoesNotThrow(() -> UUID.fromString(result.exportId()));
        assertEquals(5, result.nodes().size()); assertEquals(4, result.edges().size());
        assertEquals(Set.of("HAS_ITEM", "PLANNED_AS", "HAS_WORK_ORDER", "HAS_INSPECTION"), new HashSet<>(result.edges().stream().map(GraphExport.Edge::type).toList()));
        assertTrue(result.edges().stream().allMatch(e -> e.reliability().equals("CONFIRMED")));
        assertEquals(Map.of("status", "APPROVED"), result.nodes().get(0).properties());
        var planned = result.edges().stream().filter(e -> e.type().equals("PLANNED_AS")).findFirst().orElseThrow();
        assertEquals("production_plan_item", planned.source_table()); assertEquals("4", planned.source_id());
        assertEquals("OrderItem:2", planned.source()); assertEquals("Plan:3", planned.target());
        assertTrue(result.complete()); assertTrue(result.missingLinks().isEmpty());
        assertEquals(result.edges(), service.export(1L).edges());
    }
    @Test void missingExactLineLinksNeverCreateMaterialBasedEdges() {
        chain(); when(mapper.planLinks(1L)).thenReturn(List.of());
        when(mapper.workOrders(1L)).thenReturn(List.of()); when(mapper.inspections(1L)).thenReturn(List.of());
        when(mapper.unresolvedPlans(1L)).thenReturn(List.of(Map.of("id", 3L)));
        var result = service.export(1L);
        assertEquals(List.of("HAS_ITEM"), result.edges().stream().map(GraphExport.Edge::type).toList());
        assertTrue(result.missingLinks().stream().anyMatch(m -> m.source().equals("Plan:3") && m.reason().equals("UNBOUND_OR_CONFLICTING_WORK_ORDER")));
    }
    @Test void strictCapReportsIncompleteWithoutDanglingEdges() {
        chain(); var rows = new ArrayList<Map<String,Object>>();
        for (long i = 10; i < 1010; i++) rows.add(Map.of("id", i));
        when(mapper.items(1L)).thenReturn(rows);
        var result = service.export(1L);
        assertFalse(result.complete());
        assertTrue(result.missingLinks().stream().anyMatch(m -> m.reason().equals("ROW_LIMIT_REACHED")));
        var ids = result.nodes().stream().map(GraphExport.Node::id).toList();
        assertTrue(result.edges().stream().allMatch(e -> ids.contains(e.source()) && ids.contains(e.target())));
        assertFalse(ids.contains("WorkOrder:5")); assertFalse(ids.contains("Inspection:6"));
    }
    @Test void noOrderIsNotAnEmptySuccessfulExport() {
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.export(1L));
        assertEquals(404, error.getStatusCode().value());
    }
}
