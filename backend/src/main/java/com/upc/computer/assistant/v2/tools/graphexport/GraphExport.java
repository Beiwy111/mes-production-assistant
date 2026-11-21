package com.upc.computer.assistant.v2.tools.graphexport;

import java.util.List;
import java.util.Map;

/** Maintenance transfer only; completeness describes extraction, not business traceability. */
public record GraphExport(String schemaVersion, String exportId, Map<String,String> scope,
        String sourceObservedAt, String readFinishedAt, boolean complete,
        List<Node> nodes, List<Edge> edges, List<MissingLink> missingLinks) {
    public record Node(String id, String kind, String source_table, String source_id, Map<String,Object> properties) {}
    public record Edge(String id, String source, String target, String type, String source_table,
                       String source_id, String reliability) {}
    public record MissingLink(String source, String type, String reason) {}
}
