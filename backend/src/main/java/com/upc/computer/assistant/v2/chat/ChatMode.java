package com.upc.computer.assistant.v2.chat;

import com.upc.computer.common.BusinessException;

public record ChatMode(Source modelSource, boolean ragEnabled) {
    public enum Source { API, LOCAL }

    public static ChatMode parse(Object source, Object rag) {
        if (!(source instanceof String name) || !(rag instanceof Boolean enabled)) {
            throw new BusinessException(400, "请选择模型来源和 RAG 开关");
        }
        return switch (name) {
            case "API" -> new ChatMode(Source.API, enabled);
            case "LOCAL" -> new ChatMode(Source.LOCAL, enabled);
            default -> throw new BusinessException(400, "未知的模型来源");
        };
    }
}
