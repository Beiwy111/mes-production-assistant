package com.upc.computer.assistant.v2.tools.graphexport;

import com.upc.computer.assistant.v2.mapper.graphexport.GraphExportMapper;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.*;

/** A single bounded database read. No pagination can mix source snapshots. */
public class GraphExportService {
    private static final int ROW_LIMIT = 1000;
    private final GraphExportMapper mapper;
    private final Clock clock;
    public GraphExportService(GraphExportMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public GraphExport export(long orderId) {
        var observedAt = clock.instant().toString();
        var state = new Assembly("Order:" + orderId);
        var orders = state.bounded(mapper.order(orderId), "customer_order");
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        var order = orders.get(0);
        if (!Long.toString(orderId).equals(id(order, "id"))) throw new IllegalStateException("Invalid export scope");
        state.node("Order", "customer_order", order, "status");
        for (var item : state.bounded(mapper.items(orderId), "customer_order_item")) {
            String target = state.node("OrderItem", "customer_order_item", item, "status", "quantity");
            state.edge(state.root, target, "HAS_ITEM", "customer_order_item", id(item, "id"));
        }
        for (var plan : state.bounded(mapper.plans(orderId), "production_plan"))
            state.node("Plan", "production_plan", plan, "status");

        var planLinks = new HashMap<String,Map<String,Object>>();
        for (var link : state.bounded(mapper.planLinks(orderId), "production_plan_item")) {
            if (state.edge("OrderItem:" + id(link, "item_id"), "Plan:" + id(link, "plan_id"),
                    "PLANNED_AS", "production_plan_item", id(link, "id"))) planLinks.put(id(link, "id"), link);
        }
        for (var work : state.bounded(mapper.workOrders(orderId), "work_order")) {
            var link = planLinks.get(id(work, "plan_item_id"));
            if (link == null || !id(link, "plan_id").equals(id(work, "plan_id")) || !id(link, "item_id").equals(id(work, "item_id"))) {
                state.complete = false;
                state.missing(state.root, "HAS_WORK_ORDER", "PARENT_OUTSIDE_EXPORTED_PROJECTION");
                continue;
            }
            String target = state.node("WorkOrder", "work_order", work, "status", "planned_quantity", "completed_quantity");
            state.edge("Plan:" + id(work, "plan_id"), target, "HAS_WORK_ORDER", "work_order", id(work, "id"));
        }
        for (var inspection : state.bounded(mapper.inspections(orderId), "quality_inspection")) {
            String source = "WorkOrder:" + id(inspection, "work_order_id");
            if (!state.nodes.containsKey(source)) {
                state.complete = false;
                state.missing(state.root, "HAS_INSPECTION", "PARENT_OUTSIDE_EXPORTED_PROJECTION");
                continue;
            }
            String target = state.node("Inspection", "quality_inspection", inspection, "status", "sample_quantity", "qualified_quantity", "unqualified_quantity");
            state.edge(source, target, "HAS_INSPECTION", "quality_inspection", id(inspection, "id"));
        }
        for (var unresolved : state.bounded(mapper.unresolvedPlans(orderId), "unresolved_work_order_links")) {
            String source = "Plan:" + id(unresolved, "id");
            if (state.nodes.containsKey(source)) state.missing(source, "HAS_WORK_ORDER", "UNBOUND_OR_CONFLICTING_WORK_ORDER");
        }
        state.describeMissingRelationships();
        return new GraphExport("1", UUID.randomUUID().toString(), Map.of("orderPk", Long.toString(orderId)),
                observedAt, clock.instant().toString(), state.complete, List.copyOf(state.nodes.values()),
                List.copyOf(state.edges), List.copyOf(state.missingLinks));
    }

    private static String id(Map<String,Object> row, String key) {
        Object value = row.get(key);
        if (value == null || !value.toString().matches("[1-9][0-9]{0,18}")) throw new IllegalStateException("Invalid source relationship key");
        return value.toString();
    }

    private static final class Assembly {
        private final String root;
        private boolean complete = true;
        private final Map<String,GraphExport.Node> nodes = new LinkedHashMap<>();
        private final List<GraphExport.Edge> edges = new ArrayList<>();
        private final Set<GraphExport.MissingLink> missingLinks = new LinkedHashSet<>();
        private Assembly(String root) { this.root = root; }
        private List<Map<String,Object>> bounded(List<Map<String,Object>> rows, String projection) {
            if (rows.size() >= ROW_LIMIT) {
                complete = false;
                missing(root, projection, "ROW_LIMIT_REACHED");
            }
            return rows.subList(0, Math.min(rows.size(), ROW_LIMIT));
        }
        private String node(String kind, String table, Map<String,Object> row, String... whitelist) {
            String sourceId = id(row, "id"), nodeId = kind + ":" + sourceId;
            Map<String,Object> properties = new LinkedHashMap<>();
            for (String name : whitelist) {
                Object value = row.get(name);
                if (value instanceof Number || ("status".equals(name) && value instanceof String text && text.matches("[A-Z_]{1,32}")))
                    properties.put(name, value);
            }
            nodes.put(nodeId, new GraphExport.Node(nodeId, kind, table, sourceId, Map.copyOf(properties)));
            return nodeId;
        }
        private boolean edge(String source, String target, String type, String table, String sourceId) {
            if (!nodes.containsKey(source) || !nodes.containsKey(target)) {
                complete = false;
                missing(root, type, "PARENT_OUTSIDE_EXPORTED_PROJECTION");
                return false;
            }
            edges.add(new GraphExport.Edge(type + ":" + table + ":" + sourceId, source, target, type, table, sourceId, "CONFIRMED"));
            return true;
        }
        private void missing(String source, String type, String reason) { missingLinks.add(new GraphExport.MissingLink(source, type, reason)); }
        private void describeMissingRelationships() {
            var outgoing = new HashSet<String>();
            var plannedTargets = new HashSet<String>();
            for (var edge : edges) {
                outgoing.add(edge.source() + "|" + edge.type());
                if (edge.type().equals("PLANNED_AS")) plannedTargets.add(edge.target());
            }
            for (var node : nodes.values()) {
                String relation = switch (node.kind()) {
                    case "Order" -> "HAS_ITEM";
                    case "OrderItem" -> "PLANNED_AS";
                    case "Plan" -> "HAS_WORK_ORDER";
                    case "WorkOrder" -> "HAS_INSPECTION";
                    default -> null;
                };
                if (relation != null && !outgoing.contains(node.id() + "|" + relation)) missing(node.id(), relation, "NO_EXACT_LINK_IN_SCOPE");
                if (node.kind().equals("Plan") && !plannedTargets.contains(node.id())) missing(node.id(), "PLANNED_AS", "NO_EXACT_ORDER_ITEM_LINK");
            }
        }
    }
}
