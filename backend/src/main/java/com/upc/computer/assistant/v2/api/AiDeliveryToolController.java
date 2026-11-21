package com.upc.computer.assistant.v2.api;

import com.upc.computer.assistant.v2.security.DeliveryToolAccess;
import com.upc.computer.assistant.v2.tools.delivery.AiDeliveryQueryService;
import com.upc.computer.common.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/ai-tools/orders")
@ConditionalOnProperty(prefix = "assistant.v2", name = {"enabled", "tools.delivery.enabled"}, havingValue = "true")
public class AiDeliveryToolController {
    private final DeliveryToolAccess access;
    private final AiDeliveryQueryService service;
    public AiDeliveryToolController(DeliveryToolAccess access, AiDeliveryQueryService service) { this.access = access; this.service = service; }

    @PostMapping("/resolve")
    public Map<String,Object> resolve(@RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Map<String,Object> body) {
        long user = access.requireUser(authorization);
        fields(body, Set.of("orderPk", "orderNo", "targetDate", "targetKind"));
        LocalDate target = null;
        try { if (body.get("targetDate") != null) target = LocalDate.parse(string(body, "targetDate")); }
        catch (DateTimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetDate must be ISO date"); }
        return service.resolve(user, string(body,"orderPk"), string(body,"orderNo"), target, string(body,"targetKind"));
    }
    @GetMapping("/{orderPk}/{tool}")
    public Map<String,Object> query(@RequestHeader(value="Authorization", required=false) String authorization,
            @PathVariable String orderPk, @PathVariable String tool, @RequestParam String scopeRef,
            @RequestParam(required=false) String orderContextRef, @RequestParam(required=false) String fulfillmentQueryRef,
            @RequestParam(required=false) String materialQueryRef) {
        long user = access.requireUser(authorization);
        String kind = switch (tool) {
            case "delivery-context" -> "E1";
            case "material-readiness" -> "E2";
            case "production-execution" -> "E3";
            case "fulfillment-evidence" -> "E4";
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        return service.query(user, orderPk, scopeRef, kind, orderContextRef, fulfillmentQueryRef, materialQueryRef);
    }
    @PostMapping("/{orderPk}/delivery-revalidate")
    public Map<String,Object> revalidate(@RequestHeader(value="Authorization", required=false) String authorization,
            @PathVariable String orderPk, @RequestBody Map<String,Object> body) {
        long user = access.requireUser(authorization);
        fields(body, Set.of("scopeRef", "queryRefs", "expectedScopeVersion", "expectedCalculationVersion"));
        if (!(body.get("queryRefs") instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "queryRefs must be string array");
        var refs = values.stream().map(String.class::cast).toList();
        return service.revalidate(user, orderPk, string(body,"scopeRef"), refs, string(body,"expectedScopeVersion"), string(body,"expectedCalculationVersion"));
    }
    private static String string(Map<String,Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        if (!(value instanceof String)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a string");
        return (String)value;
    }
    private static void fields(Map<String,Object> body, Set<String> allowed) {
        if (!allowed.containsAll(body.keySet())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported request fields");
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> statusError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("status", "ERROR", "message", error.getReason() == null ? "Request failed" : error.getReason()));
    }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String,Object>> sessionError(BusinessException error) {
        int code = Set.of(401,403,404,409,429).contains(error.getCode()) ? error.getCode() : 500;
        return ResponseEntity.status(code).body(Map.of("status", "ERROR", "message", code == 500 ? "Authentication unavailable" : "Session unavailable"));
    }
    @ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,Object>> invalidRequest(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Invalid request"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> unavailable(Exception error) {
        return ResponseEntity.status(503).body(Map.of("status", "ERROR", "message", "Read-only data source unavailable"));
    }
}
