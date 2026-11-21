package com.upc.computer.assistant.v2.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.agent.DifyWorkflowClient;
import com.upc.computer.assistant.v2.runtime.AssistantRunRegistry;
import com.upc.computer.assistant.v2.security.AssistantSessionResolver;
import com.upc.computer.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantFacadeTest {
    private final DifyWorkflowClient client = mock(DifyWorkflowClient.class);
    private final AssistantSessionResolver users = mock(AssistantSessionResolver.class);
    private final AssistantRunRegistry runs = new AssistantRunRegistry(Duration.ofHours(24), Clock.systemUTC());
    private final AssistantFacade facade = new AssistantFacade(client, users, runs, new ObjectMapper());

    @Test void unauthorizedRequestFailsBeforeOpeningStreamOrCallingDify() {
        when(users.requireUserId(null)).thenThrow(new BusinessException(401, "未登录"));
        assertThatThrownBy(() -> facade.stream(Map.of("text", "hello"), null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
    }
    @Test void streamPreservesLegacyEventsAndUsesTrustedIdentity() throws Exception {
        when(users.requireUserId("token")).thenReturn(7L);
        when(client.execute(any(), eq("mes-user-7"), any(), any())).thenReturn("完成");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        facade.stream(Map.of("text", "hello", "user", "admin"), "token").getBody().writeTo(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"type\":\"run\"", "\"type\":\"delta\"", "\"type\":\"result\"", "\"type\":\"done\"")
                .contains("完成").doesNotContain("proposalId");
    }
    @Test void streamNeverExposesUnexpectedExceptions() throws Exception {
        when(users.requireUserId("token")).thenReturn(7L);
        when(client.execute(any(), anyString(), any(), any())).thenThrow(new IllegalStateException("api-key-secret"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        facade.stream(Map.of("text", "hello"), "token").getBody().writeTo(output);
        assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("\"type\":\"error\"").doesNotContain("api-key-secret", "\"type\":\"done\"");
    }
}
