package com.upc.computer.assistant.v2.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.upc.computer.assistant.v2.security.GraphExportAccess;
import com.upc.computer.assistant.v2.tools.graphexport.GraphExport;
import com.upc.computer.assistant.v2.tools.graphexport.GraphExportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/ai-internal/knowledge-export")
@ConditionalOnProperty(prefix = "assistant.v2.graph-export", name = "enabled", havingValue = "true")
public class GraphExportController {
    private final GraphExportAccess access;
    private final GraphExportService service;
    public GraphExportController(GraphExportAccess access, GraphExportService service) { this.access = access; this.service = service; }

    @PostMapping("/order-chain")
    public GraphExport orderChain(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody JsonNode body) {
        access.requireCredential(authorization);
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("orderPk") || !body.get("orderPk").isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a string orderPk is accepted");
        return service.export(access.requireOrder(authorization, body.get("orderPk").textValue()));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> denied(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("status", "ERROR", "message", error.getReason() == null ? "Request rejected" : error.getReason()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Invalid request"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> unavailable(Exception error) {
        return ResponseEntity.status(503).body(Map.of("status", "ERROR", "message", "Read-only export unavailable"));
    }
}
