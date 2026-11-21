package com.upc.computer.assistant.v2.tools.delivery;

import java.math.BigDecimal;
import java.util.*;

/** Pure current-record arithmetic. No reservation or quality claims are inferred. */
public final class DeliveryQuantityCalculator {
    public List<Map<String,Object>> fulfillment(List<Map<String,String>> lines, List<Map<String,String>> deliveries, boolean complete) {
        Map<String,BigDecimal> shipped = new HashMap<>();
        Set<String> unknown = new HashSet<>();
        Map<String,Map<String,String>> seen = new HashMap<>();
        for (var delivery : deliveries) {
            if (!Set.of("SHIPPED", "RECEIVED").contains(delivery.getOrDefault("delivery_status", ""))) continue;
            var candidates = lines.stream().filter(l -> Objects.equals(l.get("material_id"), delivery.get("material_id"))).toList();
            if (candidates.isEmpty() || delivery.get("delivery_id") == null || delivery.get("material_id") == null) {
                lines.forEach(l -> unknown.add(l.get("order_item_id"))); continue;
            }
            var previous = seen.putIfAbsent(delivery.get("delivery_id"), delivery);
            if (previous != null && previous.equals(delivery)) continue;
            String linked = delivery.get("mapped_order_item_id");
            var matches = linked == null ? candidates : candidates.stream().filter(l -> linked.equals(l.get("order_item_id"))).toList();
            boolean brokenLink = "INVALID".equals(delivery.get("mapping_status"));
            if (previous != null || brokenLink || matches.size() != 1 || decimal(delivery.get("delivery_quantity")) == null) {
                candidates.forEach(l -> unknown.add(l.get("order_item_id")));
                continue;
            }
            var line = matches.get(0);
            if (line.get("unit") == null || !line.get("unit").equals(line.get("material_unit"))) {
                unknown.add(line.get("order_item_id")); continue;
            }
            shipped.merge(line.get("order_item_id"), decimal(delivery.get("delivery_quantity")), BigDecimal::add);
        }
        List<Map<String,Object>> output = new ArrayList<>();
        for (var line : lines) {
            String id = line.get("order_item_id");
            BigDecimal ordered = decimal(line.get("quantity"));
            BigDecimal sent = complete && !unknown.contains(id) ? shipped.getOrDefault(id, BigDecimal.ZERO) : null;
            boolean active = !"CANCELLED".equals(line.get("item_status"));
            BigDecimal remaining = ordered != null && sent != null && active ? ordered.subtract(sent).max(BigDecimal.ZERO) : null;
            var row = new LinkedHashMap<String,Object>();
            row.put("orderItemPk", id); row.put("materialId", line.get("material_id")); row.put("unit", line.get("unit"));
            row.put("orderedQty", text(ordered)); row.put("recordedShippedQty", text(sent));
            row.put("remainingDeliveryQty", text(remaining));
            row.put("overDeliveredQty", ordered != null && sent != null ? text(sent.subtract(ordered).max(BigDecimal.ZERO)) : null);
            row.put("quantityBasis", sent == null ? "UNKNOWN" : "RECORDED");
            row.put("shipmentEvidenceStatus", sent == null ? "UNKNOWN" : "RECORDED");
            row.put("mappingStatus", unknown.contains(id) ? "AMBIGUOUS" : "RECORDED");
            for (String field : List.of("orderReadyQty", "shippableQty", "candidateReadyQty", "readyGapQty", "scenarioReadyGapQty", "awaitingInspectionQty", "awaitingInboundQty")) row.put(field, null);
            row.put("verifiedReadyLowerBoundQty", "0");
            row.put("allocationStatus", "UNKNOWN");
            output.add(row);
        }
        return output;
    }
    static BigDecimal decimal(String value) {
        try { var n = new BigDecimal(value); return n.signum() < 0 ? null : n; }
        catch (RuntimeException ignored) { return null; }
    }
    static String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
