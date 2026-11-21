package com.upc.computer.ai.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.OperationLog;
import com.upc.computer.service.SystemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentAuditServiceTest {

    @Mock
    private SystemService systemService;

    private AgentAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AgentAuditService(systemService, new ObjectMapper());
    }

    @Test
    void shouldUseFailStatusWhenRecordingFailedPlan() {
        LoginResponse session = new LoginResponse();
        session.setUserId(1L);
        AgentActionDefinition action = new AgentActionDefinition(
                "createPurchaseOrder", "新建采购订单", "采购管理", java.util.Set.of("PURCHASER"),
                java.util.List.of());

        auditService.record(session, action, "AI_PLAN_FAILED", "AIP-TEST", Map.of("materialCode", "MAT-002"),
                false, "业务异常");

        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(systemService).insertOperationLog(captor.capture());
        assertThat(captor.getValue().getResultStatus()).isEqualTo("FAIL");
    }
}
