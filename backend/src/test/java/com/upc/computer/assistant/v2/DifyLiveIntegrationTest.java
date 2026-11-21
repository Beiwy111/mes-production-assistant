package com.upc.computer.assistant.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.AssistantService;
import com.upc.computer.assistant.v2.agent.DifyWorkflowClient;
import com.upc.computer.assistant.v2.api.AssistantRunController;
import com.upc.computer.assistant.v2.application.AssistantFacade;
import com.upc.computer.assistant.v2.config.AssistantV2Properties;
import com.upc.computer.assistant.v2.runtime.AssistantRunRegistry;
import com.upc.computer.assistant.v2.security.AssistantSessionResolver;
import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.controller.AssistantController;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Explicit opt-in: calls a real, published Dify Workflow and may consume model tokens.
 * Real controller/facade/client/JWT; login-session storage is stubbed, no MES DB/Redis or server starts.
 * This is not a browser/login end-to-end test. Credentials are read only from process environment.
 */
@EnabledIfEnvironmentVariable(named = "DIFY_LIVE_TESTS", matches = "true")
class DifyLiveIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AssistantV2Properties settings;
    private AssistantController controller;
    private AssistantRunController runController;
    private DifyWorkflowClient client;
    private AssistantService legacy;
    private String firstUser;
    private String secondUser;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        String endpoint = System.getenv("ASSISTANT_V2_DIFY_BASE_URL");
        String key = System.getenv("ASSISTANT_V2_DIFY_API_KEY");
        assertThat(endpoint != null && !endpoint.isBlank()).as("Dify endpoint configured").isTrue();
        assertThat(key != null && key.startsWith("app-")).as("Dify application key configured").isTrue();
        settings = new AssistantV2Properties();
        settings.setEnabled(true); // Only this isolated test object; not the running MES.
        settings.getDify().setBaseUrl(endpoint);
        settings.getDify().setApiKey(key);
        settings.getRuntime().setRunTimeout(Duration.ofSeconds(60));
        client = spy(new DifyWorkflowClient(settings, mapper));

        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "jwtSecret", UUID.randomUUID() + UUID.randomUUID().toString());
        ReflectionTestUtils.setField(jwt, "jwtExpireHours", 1L);
        AuthService sessions = mock(AuthService.class);
        firstUser = identity(jwt, sessions, 91000001L);
        secondUser = identity(jwt, sessions, 91000002L);
        var facade = new AssistantFacade(client, new AssistantSessionResolver(jwt, sessions),
                new AssistantRunRegistry(Duration.ofHours(1), Clock.systemUTC()), mapper);
        runController = new AssistantRunController(facade);
        controller = new AssistantController();
        legacy = mock(AssistantService.class);
        ObjectProvider<AssistantFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(facade);
        ReflectionTestUtils.setField(controller, "assistantV2Properties", settings);
        ReflectionTestUtils.setField(controller, "assistantV2Provider", provider);
        ReflectionTestUtils.setField(controller, "assistant", legacy);
    }

    private String identity(JwtUtil jwt, AuthService sessions, long id) {
        String token = jwt.generateToken(id, "live-check-" + id, 1L, "TEST");
        LoginResponse session = new LoginResponse();
        session.setUserId(id);
        when(sessions.getLoginSession(token)).thenReturn(session);
        return "Bearer " + token;
    }

    private Map<String, Object> question(String text) {
        return Map.of("text", text, "requestId", "live-" + UUID.randomUUID(), "sessionId", "isolated-live-check");
    }

    @Test void publishedWorkflowReturnsAnswerAndSuccessfulReplayDoesNotCallModelAgain() {
        var request = question("请用一句话说明你目前能做什么。");
        var result = controller.interpret(request, firstUser).getData();
        assertThat(result).containsEntry("provider", "dify").containsEntry("type", "answer");
        assertThat((String) result.get("reply")).isNotBlank();
        assertThat(controller.interpret(request, firstUser).getData()).isEqualTo(result);
        verify(client, times(1)).execute(any(), eq("mes-user-91000001"), any(), any());
        String runId = (String) result.get("runId");
        assertThat(runController.status(runId, firstUser).getData()).containsEntry("status", "SUCCEEDED");
        assertThatThrownBy(() -> runController.status(runId, secondUser))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> runController.cancel(runId, secondUser))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        verifyNoInteractions(legacy);
    }

    @Test void realSseBecomesFrontendNdjsonWithOnlyFinalAnswer() throws Exception {
        var response = controller.interpretStream(question("当前实际库存有多少？请给出具体数量。"), firstUser);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        List<JsonNode> events = readEvents(output);
        assertThat(events.stream().map(e -> e.path("type").asText()).toList())
                .containsExactly("run", "status", "delta", "result", "done");
        String answer = events.get(3).path("data").path("reply").asText();
        assertThat(answer).isNotBlank();
        assertThat(events.get(2).path("text").asText()).isEqualTo(answer);
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("task_id", "node_started", "text_chunk");
        assertThat(output.toString(StandardCharsets.UTF_8).contains(settings.getDify().getApiKey()))
                .as("no API credential in frontend output").isFalse();
        verifyNoInteractions(legacy);
    }

    @Test void cancellationAfterRealWorkflowStartedHasLocalCancelledStateAndNoSuccessAnswer() throws Exception {
        var response = controller.interpretStream(question("请介绍 MES 中采购、计划、排产和质检的关系。"), firstUser);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            private boolean cancellationRequested;
            @Override public void flush() throws IOException {
                super.flush();
                if (!cancellationRequested && toString(StandardCharsets.UTF_8).contains("\"status\":\"RUNNING\"")) {
                    cancellationRequested = true;
                    String runId = readEvents(this).get(0).path("runId").asText();
                    assertThat(runController.cancel(runId, firstUser).getData()).containsEntry("status", "CANCELLED");
                }
            }
        };
        response.getBody().writeTo(output);
        List<JsonNode> events = readEvents(output);
        assertThat(events.stream().map(e -> e.path("type").asText()).toList())
                .contains("run", "status", "error").doesNotContain("result", "done", "delta");
        String runId = events.get(0).path("runId").asText();
        assertThat(runController.status(runId, firstUser).getData()).containsEntry("status", "CANCELLED");
        // Dify stop is best effort; this test does not claim the provider stopped generating.
        verifyNoInteractions(legacy);
    }

    @Test void unauthenticatedRequestCannotReachDify() {
        assertThatThrownBy(() -> controller.interpret(question("test"), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(401));
        verifyNoInteractions(client, legacy);
    }

    @Test void invalidApplicationKeyFailsWithoutLeakingUpstreamDetails() {
        settings.getDify().setApiKey("app-invalid-live-check");
        var invalidClient = new DifyWorkflowClient(settings, mapper);
        assertThatThrownBy(() -> invalidClient.execute(
                com.upc.computer.assistant.v2.model.AssistantRequest.from(question("test")),
                "mes-user-91000001", event -> {}, hook -> {}))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(502);
                    assertThat(e.getMessage()).isEqualTo("Dify 服务暂不可用，请检查配置或稍后重试");
                });
    }

    private List<JsonNode> readEvents(ByteArrayOutputStream output) throws IOException {
        List<JsonNode> events = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) events.add(mapper.readTree(line));
        }
        return events;
    }
}
