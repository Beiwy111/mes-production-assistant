package com.upc.computer.assistant.v2.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.common.BusinessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class WorkbenchModelClient {
    private final WorkbenchChatProperties properties;
    private final ObjectMapper json;

    public WorkbenchModelClient(WorkbenchChatProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public boolean configured(ChatMode.Source source) {
        var settings = properties.endpoint(source);
        return endpoint(settings.getBaseUrl()) != null && !settings.getModel().isBlank()
                && (source == ChatMode.Source.LOCAL || !settings.getApiKey().isBlank());
    }

    public void requireConfigured(ChatMode.Source source) {
        if (!configured(source)) throw new BusinessException(503, label(source) + "尚未配置，请检查后端模型设置");
    }

    public String generate(ChatMode.Source source, List<Map<String, String>> messages) {
        requireConfigured(source);
        var settings = properties.endpoint(source);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint(settings.getBaseUrl()).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(90000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (!settings.getApiKey().isBlank()) connection.setRequestProperty("Authorization", "Bearer " + settings.getApiKey());
            byte[] payload = json.writeValueAsBytes(Map.of("model", settings.getModel(), "messages", messages, "stream", false));
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (var out = connection.getOutputStream()) { out.write(payload); }
            int code = connection.getResponseCode();
            if (code != 200) throw new BusinessException(502, label(source) + "调用失败（HTTP " + code + "），请检查服务、模型名称和凭据");
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readNBytes(262145);
                if (bytes.length > 262144) throw new IOException();
                JsonNode root = json.readTree(bytes);
                if (root == null || !root.isObject()) throw new IOException();
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                if (!content.isTextual() || content.asText().isBlank() || content.asText().length() > 32000) throw new IOException();
                return content.asText().strip();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new BusinessException(502, label(source) + "连接失败、超时或响应格式无效");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String label(ChatMode.Source source) {
        return source == ChatMode.Source.LOCAL ? "本地模型" : "API 模型";
    }

    private static URI endpoint(String address) {
        try {
            URI base = URI.create(address.strip());
            if (base.getHost() == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                    || base.getRawUserInfo() != null || base.getRawFragment() != null || base.getRawQuery() != null) return null;
            String url = base.toString().replaceFirst("/+$", "");
            return URI.create(url.endsWith("/chat/completions") ? url : url + "/chat/completions");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
