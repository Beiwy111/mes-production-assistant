package com.upc.computer.assistant.v2.tools.delivery;

import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper;
import com.upc.computer.assistant.v2.tools.business.BusinessEvidenceCalculator;
import com.upc.computer.assistant.v2.vision.QualityEvidenceAnalysis;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Current-state, fixed SELECT queries. No legacy workflow/runtime service is invoked. */
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 15)
public class AiDeliveryQueryService {
    private final AiDeliveryReadMapper mapper;
    private final DeliveryEvidenceRegistry registry;
    private final Clock clock;
    private final AiBusinessReadMapper business;
    private final DeliveryQuantityCalculator quantities = new DeliveryQuantityCalculator();
    public AiDeliveryQueryService(AiDeliveryReadMapper mapper, DeliveryEvidenceRegistry registry, Clock clock) {
        this(mapper,null,registry,clock);
    }
    public AiDeliveryQueryService(AiDeliveryReadMapper mapper, AiBusinessReadMapper business, DeliveryEvidenceRegistry registry, Clock clock) {
        this.mapper=mapper;this.business=business;this.registry=registry;this.clock=clock;
    }
    public boolean hasBusinessEvidence() { return business!=null; }
    public Map<String,Object> resolve(long user, String orderPk, String orderNo, LocalDate targetDate, String targetKind) {
        if (orderPk == null && (orderNo == null || orderNo.isBlank())) throw error(HttpStatus.BAD_REQUEST, "An exact orderPk or orderNo is required");
        if (orderNo != null && (orderNo.isBlank() || orderNo.length() > 100)) throw error(HttpStatus.BAD_REQUEST, "Invalid orderNo");
        if (targetKind == null) targetKind = "FACTORY_SHIPMENT";
        if (!Set.of("FACTORY_SHIPMENT", "CUSTOMER_RECEIPT").contains(targetKind)) throw error(HttpStatus.BAD_REQUEST, "Invalid targetKind");
        var heads = mapper.resolve(orderPk == null ? null : id(orderPk), orderNo);
        if (heads.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Order unavailable");
        if (heads.size() != 1) throw error(HttpStatus.CONFLICT, "Order number is ambiguous");
        String foundId = String.valueOf(heads.get(0).get("order_id"));
        var snapshot = read(id(foundId));
        var scope = registry.create(user, foundId, targetDate, targetKind);
        var data = object("resolutionStatus", "RESOLVED", "orderPk", foundId, "orderNo", snapshot.rows("customer_order").get(0).get("order_no"),
                "scopeVersion", "1", "targetKind", targetKind, "historyMode", "CURRENT_RECORDS_ONLY");
        var targets = new ArrayList<Map<String,Object>>();
        var selected = new ArrayList<String>();
        for (var line : snapshot.rows("customer_order_item")) {
            if ("CANCELLED".equals(line.get("item_status"))) continue;
            selected.add(line.get("order_item_id"));
            String due = targetDate == null ? line.get("delivery_date") : targetDate.toString();
            String source = targetDate == null ? "ORDER_LINE" : "USER_DATE";
            if (due == null) { due = snapshot.rows("customer_order").get(0).get("required_delivery_date"); source = "ORDER_HEADER"; }
            String deadline = due == null ? null : LocalDate.parse(due).plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8)).toString();
            targets.add(object("orderItemPk", line.get("order_item_id"), "contractDueDate", line.get("delivery_date"), "assessmentDueDate", due, "deadlineExclusive", deadline, "dateSource", due == null ? "UNKNOWN" : source));
            if (due == null) data.put("resolutionStatus", "NEEDS_INPUT");
        }
        data.put("selectedItemPks", selected); data.put("lineTargets", targets);
        data.put("excludedItems", snapshot.rows("customer_order_item").stream().filter(l -> "CANCELLED".equals(l.get("item_status"))).map(l -> object("orderItemPk", l.get("order_item_id"), "reason", "CANCELLED")).toList());
        return envelope(scope, "E0", snapshot, data, List.of("CURRENT_STATE_ONLY", "TARGET_KIND_DEFAULTS_TO_FACTORY_SHIPMENT"));
    }
    public Map<String,Object> query(long user, String orderPk, String scopeRef, String kind, String contextRef, String fulfillmentRef, String materialRef) {
        var scope = registry.requireScope(user, orderPk, scopeRef);
        if (!Set.of("E1", "E2", "E3", "E4", "Q1", "D1", "A1").contains(kind)) throw error(HttpStatus.BAD_REQUEST, "Invalid tool");
        if (Set.of("Q1","D1","A1").contains(kind)&&business==null) throw error(HttpStatus.SERVICE_UNAVAILABLE,"Business source unavailable");
        var snapshot = read(id(orderPk));
        if (kind.equals("E2") || kind.equals("E3")) checkDependency(scope, contextRef, "E1", snapshot);
        if (kind.equals("E2")) checkDependency(scope, fulfillmentRef, "E4", snapshot);
        if (kind.equals("E3")) checkDependency(scope, materialRef, "E2", snapshot);
        Map<String,Object> data;
        var missing = new ArrayList<String>();
        switch (kind) {
            case "E1" -> {
                data = object("order", snapshot.rows("customer_order").get(0), "lines", snapshot.rows("customer_order_item"), "planRefs", snapshot.rows("production_plan_item"), "workOrderRefs", snapshot.rows("work_order"),
                        "plannedCoverageQty", null, "remainingPlannedCoverageQty", null, "unplannedQty", null);
                missing.add("PLAN_VERSION_AND_OUTPUT_OWNERSHIP_UNVERIFIED");
            }
            case "E4" -> {
                data = object("lines", business==null ? quantities.fulfillment(snapshot.rows("customer_order_item"), snapshot.rows("delivery_order"), !snapshot.truncated()) : DeliveryReadinessCalculator.fulfillment(snapshot.tables(),!snapshot.truncated()),
                        "deliveries", snapshot.rows("delivery_order"), "stockEvidence", snapshot.rows("inventory"), "receiptEvidence", "UNKNOWN", "metDeadline", "UNKNOWN", "allocationScenarioId", null);
                missing.addAll(List.of("ORDER_RESERVATION_OWNERSHIP_UNAVAILABLE", business==null ? "QUALITY_AND_INBOUND_RECONCILIATION_UNVERIFIED" : "RECONCILED_LOT_SCENARIO_NOT_ORDER_RESERVATION", "HISTORICAL_DEADLINE_AND_RECEIPT_UNVERIFIED"));
            }
            case "E2" -> {
                Map<String,String> units = new TreeMap<>();
                snapshot.rows("bom").stream().filter(b -> b.get("child_material_id") != null && b.get("child_unit") != null).forEach(b -> units.put(b.get("child_material_id"), b.get("child_unit")));
                var fulfillment = quantities.fulfillment(snapshot.rows("customer_order_item"), snapshot.rows("delivery_order"), !snapshot.truncated());
                boolean notNeeded = !fulfillment.isEmpty() && fulfillment.stream().allMatch(l -> "0".equals(l.get("remainingDeliveryQty")))
                        && snapshot.rows("work_order").stream().allMatch(w -> Set.of("COMPLETED", "CANCELLED").contains(w.getOrDefault("status", ""))) && !snapshot.truncated();
                var buckets = snapshot.rows("work_order").stream().map(w -> object("workOrderPk", w.get("work_order_id"), "productionQty", w.get("planned_quantity"),
                        "bomBasis", "CURRENT_BOM_ESTIMATE", "quantityUnits", units, "grossRequirementByMaterial", snapshot.truncated() || !"LINKED".equals(w.get("mapping_status")) || snapshot.rows("bom").stream().anyMatch(b -> Objects.equals(b.get("parent_material_id"), w.get("material_id")) && b.get("child_unit") == null) ? Map.of() : DeliveryProductionCalculator.gross(w, snapshot.rows("bom"), snapshot.calculationDate()))).toList();
                data = object("materials", business==null ? materialFacts(snapshot) : DeliveryReadinessCalculator.materials(snapshot.tables(),!snapshot.truncated(),snapshot.calculationDate()), "bomRecords", snapshot.rows("bom"), "purchaseRecords", snapshot.rows("purchase_order_item"),
                        "readiness", notNeeded ? "NOT_NEEDED" : "UNKNOWN", "scenarioReadiness", "UNKNOWN", "returnCoverage", "UNVERIFIED", "uncoveredNewProductionQty", notNeeded ? "0" : null,
                        "parentFulfillmentQueryRef", fulfillmentRef, "allocationScenarioId", null, "demandBuckets", buckets, "productionCoverageBuckets", snapshot.rows("work_order"));
                missing.addAll(List.of("WORK_ORDER_BOM_SNAPSHOT_UNAVAILABLE", "RETURNS_UNVERIFIED", "MULTILEVEL_BOM_AND_MAKE_BUY_UNVERIFIED", "NONOVERLAPPING_PRODUCTION_COVERAGE_UNVERIFIED", "QUALIFIED_SHARED_POOL_UNVERIFIED"));
            }
            case "E3" -> {
                var execution = snapshot.rows("work_order").stream().map(w -> DeliveryProductionCalculator.execution(w, snapshot.rows("process_step"), snapshot.rows("dispatch_task"), snapshot.rows("work_report"), !snapshot.truncated())).toList();
                data = object("workOrders", execution, "savedSchedules", snapshot.rows("production_plan_schedule"), "scheduleBasis", "PLAN_LEVEL_RECORDED_NOT_A_PROMISE", "materialQueryRef", materialRef, "estimatedShipmentAt", null, "calendarCoverage", "UNKNOWN", "recordCompleteness", "PARTIAL");
                missing.addAll(List.of("MULTIPLE_DISPATCH_OR_REPORT_REWORK_DEDUPLICATION_UNVERIFIED", "RESOURCE_CALENDAR_AND_QUALITY_NOT_VERIFIED", "UNLINKED_WORK_ORDERS_NOT_ALLOCATED"));
            }
            default -> {
                data=new BusinessEvidenceCalculator().calculate(kind,snapshot.tables(),!snapshot.truncated());
                for(Object warning:(List<?>)data.getOrDefault("warnings",List.of()))missing.add(String.valueOf(warning));
                if(kind.equals("Q1") && data.get("visionEvidence") instanceof Map<?,?> vision) {
                    for(Object limitation:(List<?>)vision.get("limitations"))missing.add(String.valueOf(limitation));
                }
            }
        }
        return envelope(scope, kind, snapshot, data, missing);
    }
    public Map<String,Object> revalidate(long user, String orderPk, String scopeRef, List<String> queryRefs, String scopeVersion, String calculationVersion) {
        var scope = registry.requireScope(user, orderPk, scopeRef);
        if (!"1".equals(scopeVersion) || !DeliveryEvidenceRegistry.VERSION.equals(calculationVersion)) throw error(HttpStatus.CONFLICT, "Version changed");
        if (queryRefs == null || queryRefs.isEmpty() || queryRefs.size() > 32) throw error(HttpStatus.BAD_REQUEST, "Provide 1..32 queryRefs");
        var registered = queryRefs.stream().map(ref -> registry.requireQuery(scope, ref, null)).toList();
        var snapshot = read(id(orderPk));
        var invalidated = registered.stream().filter(q -> !q.fingerprint().equals(snapshot.fingerprint())).map(DeliveryEvidenceRegistry.Query::ref).toList();
        return envelope(scope, "E5", snapshot, object("revalidationStatus", invalidated.isEmpty() ? "PARTIAL" : "CHANGED", "comparedFactsStatus", invalidated.isEmpty() ? "UNCHANGED" : "CHANGED",
                "invalidatedQueryRefs", invalidated, "checkedAt", now(), "replacementEvidence", snapshot.tables(), "scopeVersion", "1"),
                business==null ? List.of("QUALITY_AND_RUNTIME_NOT_COVERED", "DIFY_RESULT_REFS_NOT_SUPPORTED", "REVALIDATION_DOES_NOT_RESERVE_STOCK") : List.of("REDIS_TRANSIENT_WORKFLOW_STATE_NOT_A_DURABLE_LEDGER","REVALIDATION_DOES_NOT_RESERVE_STOCK"));
    }
    private void checkDependency(DeliveryEvidenceRegistry.Scope scope, String ref, String kind, Snapshot snapshot) {
        if (!registry.requireQuery(scope, ref, kind).fingerprint().equals(snapshot.fingerprint())) throw error(HttpStatus.CONFLICT, "Dependency facts changed; query again");
    }
    private List<Map<String,Object>> materialFacts(Snapshot snapshot) {
        Set<String> materials = new TreeSet<>();
        snapshot.rows("bom").forEach(b -> { if (b.get("child_material_id") != null) materials.add(b.get("child_material_id")); });
        Set<String> workOrders = new HashSet<>(); snapshot.rows("work_order").stream().filter(w -> "LINKED".equals(w.get("mapping_status"))).forEach(w -> workOrders.add(w.get("work_order_id")));
        List<Map<String,Object>> result = new ArrayList<>();
        for (String material : materials) {
            String unit = snapshot.rows("bom").stream().filter(b -> material.equals(b.get("child_material_id"))).map(b -> b.get("child_unit")).filter(Objects::nonNull).findFirst().orElse(null);
            BigDecimal issued = BigDecimal.ZERO; boolean valid = !snapshot.truncated();
            for (var transaction : snapshot.rows("inventory_transaction")) {
                if (!material.equals(transaction.get("material_id")) || !"MATERIAL_OUT".equals(transaction.get("transaction_type")) || !workOrders.contains(transaction.get("related_work_order_id"))) continue;
                var quantity = DeliveryQuantityCalculator.decimal(transaction.get("quantity"));
                if (quantity == null) valid = false; else issued = issued.add(quantity);
            }
            var row = object("materialId", material, "unit", unit, "recordedIssuedQty", valid && unit != null ? DeliveryQuantityCalculator.text(issued) : null, "recordedIssueCoverage", "LINKED_WORK_ORDERS_ONLY", "quantityBasis", "RECORDED", "readiness", "UNKNOWN");
            for (String field : List.of("grossRequirementQty", "confirmedReturnedQty", "netIssuedQty", "requiredUnissuedQty", "ownedReservedUsableQty", "publicAvailablePoolQty", "scenarioAllocatedQty", "shortageNow", "confirmedShortageNow", "bestCaseGapQty", "scenarioPurchaseGapQty")) row.put(field, null);
            result.add(row);
        }
        return result;
    }
    private Snapshot read(long orderId) {
        String start = now(); var tables = new LinkedHashMap<String,List<Map<String,String>>>();
        tables.put("customer_order", normalize(mapper.order(orderId)));
        if (tables.get("customer_order").isEmpty()) throw error(HttpStatus.NOT_FOUND, "Order unavailable");
        tables.put("customer_order_item", normalize(mapper.lines(orderId)));
        tables.put("delivery_order", normalize(mapper.deliveries(orderId)));
        tables.put("production_plan_item", normalize(mapper.plans(orderId)));
        tables.put("work_order", normalize(mapper.workOrders(orderId)));
        tables.put("bom", normalize(mapper.bom(orderId)));
        tables.put("inventory", normalize(mapper.inventory(orderId)));
        tables.put("inventory_transaction", normalize(mapper.transactions(orderId)));
        tables.put("purchase_order_item", normalize(mapper.purchases(orderId)));
        tables.put("work_report", normalize(mapper.reports(orderId)));
        tables.put("dispatch_task", normalize(mapper.dispatch(orderId)));
        tables.put("process_step", normalize(mapper.steps(orderId)));
        tables.put("production_plan_schedule", normalize(mapper.schedules(orderId)));
        if(business!=null)business.read(orderId).forEach((name,records)->tables.put(name,normalize(records)));
        if(business!=null)QualityEvidenceAnalysis.appendArchive(tables);
        return new Snapshot(tables, start, now(), OffsetDateTime.parse(start).toLocalDate());
    }
    private Map<String,Object> envelope(DeliveryEvidenceRegistry.Scope scope, String kind, Snapshot snapshot, Map<String,Object> data, List<String> missing) {
        var issues = new ArrayList<>(missing);
        if (snapshot.truncated()) issues.add("SOURCE_RECORD_LIMIT_1000_EXCEEDED");
        String fingerprint = snapshot.fingerprint();
        String ref = registry.register(scope, kind, fingerprint);
        var evidence = snapshot.tables().entrySet().stream().map(e -> object("evidenceId", ref + ":" + e.getKey(), "table", e.getKey(), "recordCount", e.getValue().size(), "sourceFingerprint", DeliveryEvidenceRegistry.fingerprint(Map.of(e.getKey(), e.getValue())), "capturedAt", snapshot.finished())).toList();
        return object("schemaVersion", "1", "businessEvidenceVersion",business==null?"legacy":"order-business-2", "calculationVersion", DeliveryEvidenceRegistry.VERSION, "status", "PARTIAL", "queryRef", ref, "scopeRef", scope.ref(),
                "observedAt", snapshot.finished(), "readStartedAt", snapshot.started(), "readFinishedAt", snapshot.finished(), "calculationDate", snapshot.calculationDate().toString(), "sourceFingerprint", fingerprint,
                "data", data, "evidence", evidence, "completeness", "PARTIAL", "missing", issues, "warnings", List.of("Read-only current records; no delivery promise", "Raw database records use snake_case; IDs and quantities are decimal strings"));
    }
    private static List<Map<String,String>> normalize(List<Map<String,Object>> rows) {
        var result = new ArrayList<Map<String,String>>();
        for (var row : rows) {
            Map<String,String> copy = new TreeMap<>();
            row.forEach((key, value) -> { if (value != null) copy.put(key, value instanceof BigDecimal n ? n.toPlainString() : value instanceof java.sql.Date d ? d.toLocalDate().toString() : value instanceof java.sql.Timestamp t ? t.toLocalDateTime().atOffset(ZoneOffset.ofHours(8)).toString() : value instanceof LocalDateTime d ? d.atOffset(ZoneOffset.ofHours(8)).toString() : value.toString()); });
            result.add(Collections.unmodifiableMap(copy));
        }
        return List.copyOf(result);
    }
    public static long id(String value) {
        try { if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException e) { throw error(HttpStatus.BAD_REQUEST, "Invalid decimal ID"); }
    }
    private String now() { return clock.instant().atOffset(ZoneOffset.ofHours(8)).toString(); }
    private record Snapshot(Map<String,List<Map<String,String>>> tables, String started, String finished, LocalDate calculationDate) {
        List<Map<String,String>> rows(String table) { return tables.get(table); }
        boolean truncated() { return tables.values().stream().anyMatch(rows -> rows.size() > 1000); }
        String fingerprint() {
            // Effective BOM selection can change at midnight without any database row changing.
            var inputs = new LinkedHashMap<>(tables);
            inputs.put("__calculation_context", List.of(Map.of("calculationDate", calculationDate.toString())));
            return DeliveryEvidenceRegistry.fingerprint(inputs);
        }
    }
    private static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    private static Map<String,Object> object(Object... fields) { var map = new LinkedHashMap<String,Object>(); for (int i=0;i<fields.length;i+=2) map.put((String)fields[i],fields[i+1]); return map; }
}
