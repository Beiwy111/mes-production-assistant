package com.upc.computer.ai.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.OperationLog;
import com.upc.computer.service.SystemService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentAuditService {

    private final SystemService systemService;
    private final ObjectMapper objectMapper;

    public AgentAuditService(SystemService systemService, ObjectMapper objectMapper) {
        this.systemService = systemService;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LoginResponse session, AgentActionDefinition action, String phase,
                       String planId, Map<String, Object> parameters, boolean success, String error) {
        OperationLog log = new OperationLog();
        log.setUserId(session.getUserId());
        log.setModuleName("AI Agent-" + action.module());
        log.setOperationType(phase);
        log.setBusinessTable("agent_action_plan");
        log.setOperationContent(summary(planId, action.code(), parameters));
        log.setIpAddress("agent");
        log.setResultStatus(success ? "SUCCESS" : "FAIL");
        log.setErrorMessage(error == null ? null : limit(error, 500));
        log.setOperatedAt(LocalDateTime.now());
        systemService.insertOperationLog(log);
    }

    // operation_log.operation_content is VARCHAR(500). The complete proposal lives in the plan repository.
    private String summary(String planId, String actionCode, Map<String, Object> parameters) {
        var safe = redact(parameters);
        String full = toJson(Map.of("planId", planId, "actionCode", actionCode, "parameters", safe));
        if (full.length() <= 500) return full;
        var summary = new LinkedHashMap<String, Object>();
        summary.put("planId", planId);
        summary.put("actionCode", actionCode);
        for (String key : java.util.List.of("schedules", "recommendations")) {
            if (safe.get(key) instanceof java.util.Collection<?> rows) summary.put(key + "Count", rows.size());
        }
        for (String key : java.util.List.of("orderId", "planId", "dispatchId", "plannedQty")) {
            if (safe.get(key) != null) {
                String label = "target_" + key;
                summary.put(label, limit(String.valueOf(safe.get(key)), 80));
                if (toJson(summary).length() > 500) summary.remove(label);
            }
        }
        return toJson(summary);
    }

    private Map<String, Object> redact(Map<String, Object> parameters) {
        Map<String, Object> safe = new LinkedHashMap<>(parameters == null ? Map.of() : parameters);
        for (String key : new String[]{"password", "passwordHash", "token", "apiKey"}) {
            if (safe.containsKey(key)) safe.put(key, "***");
        }
        return safe;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
