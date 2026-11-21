package com.upc.computer.assistant.v2.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantV2ConfigurationTest {

    @TempDir
    Path assets;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AssistantV2Configuration.class);

    @Test
    void defaultsLoadWithoutExternalServicesOrCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AssistantV2Properties.class);
            AssistantV2Properties settings = context.getBean(AssistantV2Properties.class);
            assertThat(settings.isEnabled()).isFalse();
            assertThat(settings.getDify().getApiKey()).isEmpty();
            assertThat(settings.getKnowledge().getApiKey()).isEmpty();
            assertThat(settings.getRuntime().getRunTimeout()).isEqualTo(Duration.ofMinutes(10));
            assertThat(settings.getRuntime().getRetention()).isEqualTo(Duration.ofHours(24));
            assertThat(context).doesNotHaveBean(AssistantV2Configuration.EnabledFoundation.class);
        });
    }

    @Test
    void disabledModuleDoesNotRequireUsableEndpointsOrAssets() {
        runner.withPropertyValues("assistant.v2.enabled=false",
                        "assistant.v2.assets-root=not-a-deployment-path",
                        "assistant.v2.dify.base-url=not-a-url")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(AssistantV2Configuration.EnabledFoundation.class));
    }

    @Test
    void enabledFoundationBindsSettingsWithoutOpeningConnections() {
        configured().withPropertyValues("assistant.v2.dify.read-timeout=45s")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(AssistantV2Configuration.EnabledFoundation.class);
                    AssistantV2Properties settings = context.getBean(AssistantV2Properties.class);
                    assertThat(settings.isEnabled()).isTrue();
                    assertThat(settings.getDify().getReadTimeout()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(settings.getKnowledge().getBaseUrl()).isEqualTo("http://127.0.0.1:1");
                    assertThat(settings.getAssetsRoot()).isEqualTo(assets.toString());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dify.base-url", "dify.api-key"})
    void enablingRequiresExplicitConfiguration(String field) {
        configured().withPropertyValues("assistant.v2." + field + "=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Invalid assistant.v2." + field + ": required when enabled");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/service", "https:///missing-host", "not-a-url",
            "https://user:secret@localhost/v1", "http://localhost:0", "http://localhost:70000",
            "https://localhost/v1?api_key=secret", "https://localhost/v1#fragment"})
    void rejectsUnsupportedEndpointWithoutEchoingItsValue(String url) {
        configured().withPropertyValues("assistant.v2.dify.base-url=" + url)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Invalid assistant.v2.dify.base-url: expected an HTTP(S) base URL without credentials, query or fragment");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dify.connect-timeout=0s", "dify.read-timeout=-1s",
            "knowledge.connect-timeout=0s", "knowledge.read-timeout=-1s",
            "runtime.run-timeout=0s", "runtime.retention=-1s"})
    void rejectsNonPositiveTimeouts(String value) {
        configured().withPropertyValues("assistant.v2." + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Invalid assistant.v2." + value.split("=")[0] + ": must be positive");
        });
    }

    @Test
    void retentionMustCoverTheRunTimeout() {
        configured().withPropertyValues("assistant.v2.runtime.retention=9m").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Invalid assistant.v2.runtime.retention: must be at least runtime.run-timeout");
        });
    }

    @Test
    void difyOnlyStageDoesNotRequireKnowledgeServiceOrAssets() {
        configured().withPropertyValues("assistant.v2.assets-root=", "assistant.v2.knowledge.base-url=",
                "assistant.v2.knowledge.api-key=").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void explicitlyEnabledKnowledgeRequiresItsOwnCredentials() {
        configured().withPropertyValues("assistant.v2.knowledge.enabled=true", "assistant.v2.knowledge.api-key=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsRelativeAssetRootRatherThanDependingOnWorkingDirectory() {
        configured().withPropertyValues("assistant.v2.assets-root=backend/assistant").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Invalid assistant.v2.assets-root: must be an absolute path");
        });
    }

    @Test
    void componentScanningTheNewModuleDoesNotActivateRuntimeBeans() {
        new ApplicationContextRunner().withUserConfiguration(ModuleScan.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AssistantV2Properties.class)
                    .doesNotHaveBean(AssistantV2Configuration.EnabledFoundation.class);
            assertThat(context.getBeanNamesForAnnotation(
                    org.springframework.web.bind.annotation.RestController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
        });
    }

    @Test
    void enabledModuleWiresTheThinAccessLayerWithoutLegacyAiOrKnowledgeClients() {
        new ApplicationContextRunner().withUserConfiguration(ModuleScan.class)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(com.upc.computer.service.AuthService.class, () -> org.mockito.Mockito.mock(com.upc.computer.service.AuthService.class))
                .withBean(com.upc.computer.common.JwtUtil.class, () -> org.mockito.Mockito.mock(com.upc.computer.common.JwtUtil.class))
                .withPropertyValues("assistant.v2.enabled=true", "assistant.v2.dify.base-url=http://127.0.0.1:1/v1",
                        "assistant.v2.dify.api-key=test-only-key")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(com.upc.computer.assistant.v2.application.AssistantFacade.class)
                        .hasSingleBean(com.upc.computer.assistant.v2.api.AssistantRunController.class)
                        .doesNotHaveBean(com.upc.computer.ai.service.AgentService.class));
    }

    @Test
    void realConfigDataProfileLoadsTemplateWithoutLoadingExistingProjectSecrets() throws IOException {
        try (var input = new ClassPathResource("application-assistant-v2.yml").getInputStream()) {
            java.nio.file.Files.copy(input, assets.resolve("application-assistant-v2.yml"));
        }
        runner.withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=optional:" + assets.toUri(),
                        "spring.profiles.active=assistant-v2", "ASSISTANT_V2_DIFY_READ_TIMEOUT=37s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(AssistantV2Properties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getDify().getReadTimeout()).isEqualTo(Duration.ofSeconds(37));
                });
    }

    @Test
    void optionalProfileStillDefaultsToDisabled() throws IOException {
        profileRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AssistantV2Properties.class).isEnabled()).isFalse();
        });
    }

    @Test
    void profileResolvesDeploymentVariablesWithoutEnablingChatRouting() throws IOException {
        profileRunner().withPropertyValues(
                        "ASSISTANT_V2_ENABLED=true",
                        "ASSISTANT_V2_ASSETS_ROOT=" + assets,
                        "ASSISTANT_V2_DIFY_BASE_URL=http://127.0.0.1:1/v1",
                        "ASSISTANT_V2_DIFY_API_KEY=test-dify-secret",
                        "ASSISTANT_V2_KNOWLEDGE_BASE_URL=http://127.0.0.1:1",
                        "ASSISTANT_V2_KNOWLEDGE_API_KEY=test-internal-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AssistantV2Properties settings = context.getBean(AssistantV2Properties.class);
                    assertThat(settings.getDify().getApiKey()).isEqualTo("test-dify-secret");
                    assertThat(settings.getKnowledge().getApiKey()).isEqualTo("test-internal-secret");
                    assertThat(settings.getDify().getReadTimeout()).isEqualTo(Duration.ofSeconds(120));
                    assertThat(context.getBeanNamesForAnnotation(
                            org.springframework.web.bind.annotation.RestController.class)).isEmpty();
                });
    }

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues(
                "assistant.v2.enabled=true",
                "assistant.v2.assets-root=" + assets,
                "assistant.v2.dify.base-url=http://127.0.0.1:1/v1",
                "assistant.v2.dify.api-key=test-dify-secret",
                "assistant.v2.knowledge.base-url=http://127.0.0.1:1",
                "assistant.v2.knowledge.api-key=test-internal-secret");
    }

    private ApplicationContextRunner profileRunner() throws IOException {
        var sources = new YamlPropertySourceLoader().load("assistant-v2",
                new ClassPathResource("application-assistant-v2.yml"));
        return runner.withInitializer(context -> sources.forEach(source ->
                context.getEnvironment().getPropertySources().addLast(source)));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.upc.computer.assistant.v2")
    static class ModuleScan {
    }
}
