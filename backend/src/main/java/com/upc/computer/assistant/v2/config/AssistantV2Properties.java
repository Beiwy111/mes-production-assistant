package com.upc.computer.assistant.v2.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 新助手的独立配置。默认关闭；不复用旧 mes.ai/assistant.nlu 的密钥或开关。
 * 不使用 Lombok Data/ToString，避免凭据出现在日志中。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "assistant.v2")
public class AssistantV2Properties {

    private boolean enabled = false;
    private String assetsRoot = "";
    private final Endpoint dify = new Endpoint();
    private final KnowledgeEndpoint knowledge = new KnowledgeEndpoint();
    private final RuntimeSettings runtime = new RuntimeSettings();

    @Getter
    @Setter
    public static class Endpoint {
        private String baseUrl = "";
        private String apiKey = "";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(120);
    }

    @Getter
    @Setter
    public static class KnowledgeEndpoint extends Endpoint {
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class RuntimeSettings {
        private Duration runTimeout = Duration.ofMinutes(10);
        private Duration retention = Duration.ofHours(24);
    }
}
