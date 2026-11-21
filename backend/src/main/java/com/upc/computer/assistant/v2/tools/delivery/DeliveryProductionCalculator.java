package com.upc.computer.assistant.v2.tools.delivery;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Conservative saved production facts; no dispatch regeneration or scheduling. */
public final class DeliveryProductionCalculator {
    private DeliveryProductionCalculator() { }
    public static Map<String,Object> execution(Map<String,String> work, List<Map<String,String>> allSteps,
            List<Map<String,String>> allDispatch, List<Map<String,String>> allReports, boolean complete) {
        var result = new LinkedHashMap<String,Object>(work);
        String state = work.getOrDefault("status", "");
        result.put("executionStatus", switch(state) {
            case "COMPLETED" -> "COMPLETED";
            case "CANCELLED" -> "CANCELLED";
            case "DRAFT", "RELEASED", "DISPATCHED" -> "NOT_COMPLETED";
            case "RUNNING", "PRODUCING", "QC_PENDING", "PAUSED" -> "IN_PROGRESS";
            default -> "UNKNOWN";
        });
        var steps = allSteps.stream().filter(s -> Objects.equals(s.get("route_id"), work.get("route_id")) && "1".equals(s.get("status")))
                .sorted(Comparator.comparingInt(s -> stepNumber(s.get("step_no")))).toList();
        boolean valid = complete && !steps.isEmpty() && steps.stream().allMatch(s -> stepNumber(s.get("step_no")) > 0)
                && steps.stream().map(s -> s.get("step_no")).distinct().count() == steps.size();
        var stages = new ArrayList<Map<String,Object>>(); BigDecimal bottleneck = null, last = null;
        for (var step : steps) {
            var dispatch = allDispatch.stream().filter(d -> Objects.equals(d.get("work_order_id"), work.get("work_order_id")) && Objects.equals(d.get("step_id"), step.get("step_id")) && !"CANCELLED".equals(d.get("status"))).toList();
            var reports = allReports.stream().filter(r -> Objects.equals(r.get("work_order_id"), work.get("work_order_id")) && Objects.equals(r.get("step_id"), step.get("step_id")) && !"REJECTED".equals(r.get("report_status"))).toList();
            // Multiple dispatch/report records can include rework; do not sum without a physical-item deduplication key.
            BigDecimal quantity = null;
            if (complete && dispatch.isEmpty() && reports.isEmpty()) quantity = BigDecimal.ZERO;
            if (complete && dispatch.size() == 1 && reports.isEmpty()) quantity = BigDecimal.ZERO;
            if (complete && dispatch.size() == 1 && reports.size() == 1 && "CONFIRMED".equals(reports.get(0).get("report_status"))
                    && Objects.equals(dispatch.get(0).get("dispatch_id"), reports.get(0).get("dispatch_id"))) quantity = DeliveryQuantityCalculator.decimal(reports.get(0).get("completed_quantity"));
            if (quantity == null) valid = false;
            else { bottleneck = bottleneck == null ? quantity : bottleneck.min(quantity); last = quantity; }
            var stage = new LinkedHashMap<String,Object>(step);
            stage.put("completedQtyRecorded", DeliveryQuantityCalculator.text(quantity)); stage.put("quantityBasis", quantity == null ? "UNKNOWN" : "RECORDED");
            stage.put("dispatchIds", dispatch.stream().map(d -> d.get("dispatch_id")).toList());
            stage.put("reportIds", reports.stream().map(r -> r.get("report_id")).toList()); stages.add(stage);
        }
        result.put("stages", stages); result.put("bottleneckCompletedQty", valid ? DeliveryQuantityCalculator.text(bottleneck) : null);
        result.put("finalStageCompletedQty", valid ? DeliveryQuantityCalculator.text(last) : null);
        result.put("quantityBasis", "RECORDED"); result.put("scheduleSource", "SAVED_WORK_ORDER_PLAN_NOT_A_PROMISE");
        result.put("estimatedShipmentAt", null); return result;
    }
    private static int stepNumber(String value) { try { return Integer.parseInt(value); } catch(RuntimeException e) { return -1; } }
    /** Whole work-order target under a single current effective BOM version, explicitly an estimate. */
    public static Map<String,String> gross(Map<String,String> work, List<Map<String,String>> allBom, LocalDate day) {
        if (Set.of("COMPLETED", "CANCELLED").contains(work.getOrDefault("status", ""))) return Map.of();
        var quantity = DeliveryQuantityCalculator.decimal(work.get("planned_quantity"));
        if (quantity == null) return Map.of();
        var candidates = new ArrayList<Map<String,String>>();
        for (var bom : allBom) {
            if (!Objects.equals(bom.get("parent_material_id"), work.get("material_id")) || !"1".equals(bom.get("status"))) continue;
            try {
                if (bom.get("effective_date") == null || bom.get("version_no") == null) return Map.of();
                if (LocalDate.parse(bom.get("effective_date")).isAfter(day) || (bom.get("expire_date") != null && LocalDate.parse(bom.get("expire_date")).isBefore(day))) continue;
            } catch(DateTimeException e) { return Map.of(); }
            candidates.add(bom);
        }
        if (candidates.isEmpty() || candidates.stream().map(b -> b.get("version_no")).distinct().count() != 1) return Map.of();
        Map<String,BigDecimal> totals = new TreeMap<>();
        for (var bom : candidates) {
            var unit = DeliveryQuantityCalculator.decimal(bom.get("quantity")); var loss = DeliveryQuantityCalculator.decimal(bom.get("loss_rate"));
            if (unit == null || loss == null || loss.compareTo(BigDecimal.ONE) >= 0 || bom.get("child_material_id") == null) return Map.of();
            totals.merge(bom.get("child_material_id"), quantity.multiply(unit).multiply(BigDecimal.ONE.add(loss)), BigDecimal::add);
        }
        Map<String,String> result = new TreeMap<>(); totals.forEach((id,n) -> result.put(id, DeliveryQuantityCalculator.text(n))); return result;
    }
}
