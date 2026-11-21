package com.upc.computer.assistant.v2.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 注册配置与本地校验；接入组件由独立 AccessConfiguration 按同一开关创建。
 * 后续有实际 Mapper 时再加入使用同一开关的专用 MapperScan。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistantV2Properties.class)
@Import(AssistantV2Configuration.EnabledFoundation.class)
public class AssistantV2Configuration {

    /**
     * 开启时校验本地配置。通过不表示上游可用。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "assistant.v2", name = "enabled", havingValue = "true")
    public static class EnabledFoundation {

        public EnabledFoundation(AssistantV2Properties settings) {
            try {
                if (settings.getAssetsRoot() != null && !settings.getAssetsRoot().isBlank()
                        && !Path.of(settings.getAssetsRoot()).isAbsolute()) {
                    throw invalid("assets-root", "must be an absolute path");
                }
            } catch (InvalidPathException exception) {
                // 不保留包含用户输入的异常原因，避免将配置值带入启动日志。
                throw invalid("assets-root", "must be an absolute path");
            }
            validateEndpoint(settings.getDify(), "dify");
            if (settings.getKnowledge().isEnabled()) {
                validateEndpoint(settings.getKnowledge(), "knowledge");
            }
            positive(settings.getKnowledge().getConnectTimeout(), "knowledge.connect-timeout");
            positive(settings.getKnowledge().getReadTimeout(), "knowledge.read-timeout");
            positive(settings.getRuntime().getRunTimeout(), "runtime.run-timeout");
            positive(settings.getRuntime().getRetention(), "runtime.retention");
            if (settings.getRuntime().getRetention().compareTo(settings.getRuntime().getRunTimeout()) < 0) {
                throw invalid("runtime.retention", "must be at least runtime.run-timeout");
            }
        }

        private static void validateEndpoint(AssistantV2Properties.Endpoint endpoint, String prefix) {
            requireText(endpoint.getBaseUrl(), prefix + ".base-url");
            requireText(endpoint.getApiKey(), prefix + ".api-key");
            try {
                URI uri = new URI(endpoint.getBaseUrl());
                int port = uri.getPort();
                boolean http = "http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme());
                if (!http || uri.getHost() == null || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || port == 0 || port > 65535) {
                    throw invalidUrl(prefix);
                }
            } catch (URISyntaxException exception) {
                throw invalidUrl(prefix);
            }
            positive(endpoint.getConnectTimeout(), prefix + ".connect-timeout");
            positive(endpoint.getReadTimeout(), prefix + ".read-timeout");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw invalid(field, "required when enabled");
            }
        }

        private static void positive(Duration value, String field) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw invalid(field, "must be positive");
            }
        }

        private static IllegalStateException invalidUrl(String prefix) {
            return invalid(prefix + ".base-url",
                    "expected an HTTP(S) base URL without credentials, query or fragment");
        }

        private static IllegalStateException invalid(String field, String reason) {
            return new IllegalStateException("Invalid assistant.v2." + field + ": " + reason);
        }
    }
}
