package com.upc.computer.assistant.v2.model;

import com.upc.computer.common.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 用户输入只作为对话内容；客户端不能提供身份、工具或上游地址。 */
public record AssistantRequest(String requestId, String sessionId, String question,
                               List<Map<String, String>> history) {
    public static AssistantRequest from(Map<String, Object> body) {
        String question = text(body.get("text")).trim();
        if (question.isBlank() || question.length() > 4000) {
            throw new BusinessException(400, "问题不能为空且不能超过 4000 字符");
        }
        String requestId = identifier(body.get("requestId"), "requestId");
        String sessionId = text(body.get("sessionId")).isBlank()
                ? requestId : identifier(body.get("sessionId"), "sessionId");
        var history = new ArrayList<Map<String, String>>();
        if (body.get("conversation") instanceof List<?> messages) {
            int chars = 0;
            for (int i = messages.size() - 1; i >= 0 && history.size() < 12; i--) {
                if (!(messages.get(i) instanceof Map<?, ?> message)) continue;
                String role = text(message.get("role"));
                if (!role.equals("user") && !role.equals("assistant")) continue;
                String content = text(message.containsKey("text") ? message.get("text") : message.get("content")).trim();
                if (content.isEmpty()) continue;
                if (content.length() > 4000) content = content.substring(0, 4000);
                if (chars + content.length() > 12000) break;
                history.add(0, Map.of("role", role, "content", content));
                chars += content.length();
            }
        }
        return new AssistantRequest(requestId, sessionId, question, List.copyOf(history));
    }

    private static String identifier(Object value, String name) {
        String id = text(value).trim();
        if (id.isEmpty()) return UUID.randomUUID().toString();
        if (id.length() > 160 || id.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(400, name + " 格式无效");
        }
        return id;
    }

    private static String text(Object value) { return value instanceof String s ? s : ""; }
}
