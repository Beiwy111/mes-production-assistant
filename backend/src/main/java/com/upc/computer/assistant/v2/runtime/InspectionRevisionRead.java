package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;

/** Supplement an already authorized, exact inspection read. Demonstration references never become events. */
final class InspectionRevisionRead {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SEEDS = loadReferences();
    private InspectionRevisionRead() {}

    static void append(NaturalLanguageRuntime.Data data, long id, Map<String,Object> facts) {
        data.policy().require("QUALITY");
        InspectionTraceMapper mapper = data.overview();
        Set<String> tables = new HashSet<>(mapper.revisionTables());
        if (tables.containsAll(List.of("quality_inspection_round","quality_inspection_round_item"))) {
            var events = mapper.traceEvents(id);
            var items = events.isEmpty() ? Map.of() : mapper.traceEventItems(id).stream()
                .collect(Collectors.groupingBy(row -> row.get("round_id")));
            var enriched = new ArrayList<Map<String,Object>>();
            for (var event : events) {
                var copy = new LinkedHashMap<>(event);
                copy.put("items", items.containsKey(event.get("round_id")) ? items.get(event.get("round_id")) : List.of());
                enriched.add(copy);
            }
            facts.put("inspectionEvents", enriched);
        }
        if (tables.contains("quality_defect_sample")) {
            facts.put("sampleBindings", mapper.traceSampleBindings(id));
            if (tables.contains("quality_sample_measurement"))
                facts.put("sampleMeasurements", mapper.traceSampleMeasurements(id));
        }
        if (facts.get("inspection") instanceof List<?> headers && !headers.isEmpty() && headers.get(0) instanceof Map<?,?> h) {
            var row = SEEDS.path("inspections").path(Objects.toString(h.get("inspection_no"),""));
            if (row.isObject() && List.of("inspected_at","batch_no","inspection_category").stream().allMatch(
                    key -> row.path(key).asText().equals(Objects.toString(h.get(key),"")))) {
                Map<String,Object> reference = JSON.convertValue(row,new com.fasterxml.jackson.core.type.TypeReference<>(){});
                reference.put("kind","DEMO_SEED_REFERENCE");
                reference.put("source",SEEDS.path("source").asText());
                reference.put("source_sha256",SEEDS.path("sha256").asText());
                facts.put("legacyBaseline",reference);
            }
        }
    }

    private static JsonNode loadReferences() {
        try (var input = InspectionRevisionRead.class.getResourceAsStream("/assistant/inspection-seed-reference.json")) {
            return input == null ? JSON.createObjectNode() : JSON.readTree(input);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid bundled inspection reference",e);
        }
    }
}
