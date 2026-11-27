package com.upc.computer.ai.action;

import com.upc.computer.common.BusinessException;
import com.upc.computer.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentActionPlanServiceTest {

    private AgentPlanRepository repository;
    private AgentActionStateService stateService;
    private AgentActionExecutionService executionService;
    private AgentAuditService auditService;
    private AgentActionPlanService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentPlanRepository.class);
        stateService = mock(AgentActionStateService.class);
        executionService = mock(AgentActionExecutionService.class);
        auditService = mock(AgentAuditService.class);
        when(repository.findDraft(any(), anyString(), anyString())).thenReturn(new LinkedHashMap<>());
        when(stateService.fingerprint(any(), any(), any())).thenReturn("");
        service = new AgentActionPlanService(new AgentActionCatalog(), repository, stateService,
                executionService, auditService);
    }

    @Test
    void incompleteOrderShouldAskForMissingFieldsWithoutExecuting() {
        AgentPlanOutcome outcome = service.preview("createOrder", Map.of(
                "customerName", "演示客户",
                "productModel", "23.8寸电竞显示器"
        ), session("ORDER"), "chat-1");

        assertThat(outcome.status()).isEqualTo("NEEDS_INPUT");
        assertThat(outcome.missingFields()).extracting(AgentRequiredField::name)
                .containsExactly("quantity", "deliveryDate");
        verify(repository).saveDraft(any(), anyString(), anyString(), any());
        verify(executionService, never()).execute(any(), any());
    }

    @Test
    void draftParametersShouldMergeAcrossConversationTurns() {
        when(repository.findDraft(any(), anyString(), anyString())).thenReturn(new LinkedHashMap<>(Map.of(
                "customerName", "演示客户",
                "productModel", "23.8寸电竞显示器"
        )));

        AgentPlanOutcome outcome = service.preview("createOrder", Map.of(
                "quantity", 100,
                "deliveryDate", "2026-08-01"
        ), session("ORDER"), "chat-1");

        assertThat(outcome.status()).isEqualTo("PENDING");
        assertThat(outcome.confirmation()).containsEntry("type", "confirm");
        ArgumentCaptor<AgentActionPlan> planCaptor = ArgumentCaptor.forClass(AgentActionPlan.class);
        verify(repository).save(planCaptor.capture());
        assertThat(planCaptor.getValue().parameters()).containsEntry("customerName", "演示客户")
                .containsEntry("quantity", 100);
    }

    @Test
    void roleEscalationAndGenericExecutionParametersShouldBeRejected() {
        assertThatThrownBy(() -> service.preview("createOrder", Map.of(), session("QC"), "chat-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权执行");

        assertThatThrownBy(() -> service.preview("createOrder", Map.of("sql", "DELETE FROM user"),
                session("ORDER"), "chat-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SQL");
        verify(executionService, never()).execute(any(), any());
    }

    @Test
    void repeatedConfirmationShouldBeIdempotent() {
        AtomicReference<AgentActionPlan> stored = inMemoryRepository();
        when(executionService.execute(any(), any())).thenReturn(Map.of("orderNo", "CO-DEMO-1"));
        AgentPlanOutcome outcome = completeOrderPlan();
        String planId = String.valueOf(outcome.confirmation().get("proposalId"));

        Map<String, Object> first = service.confirm(planId, "APPROVE", null, session("ORDER"));
        Map<String, Object> second = service.confirm(planId, "APPROVE", null, session("ORDER"));

        assertThat(first).containsEntry("status", "EXECUTED").containsEntry("idempotent", false);
        assertThat(second).containsEntry("status", "EXECUTED").containsEntry("idempotent", true);
        assertThat(stored.get().status()).isEqualTo(AgentPlanStatus.EXECUTED);
        verify(executionService).execute(any(), any());
    }

    @Test
    void staleSnapshotShouldBlockExecution() {
        inMemoryRepository();
        when(stateService.fingerprint(any(), any(), any())).thenReturn("before", "after");
        AgentPlanOutcome outcome = completeOrderPlan();

        assertThatThrownBy(() -> service.confirm(String.valueOf(outcome.confirmation().get("proposalId")),
                "APPROVE", null, session("ORDER")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发生变化");
        verify(executionService, never()).execute(any(), any());
    }

    @Test
    void executionFailureShouldBeRecordedAndNeverReportedAsSuccess() {
        AtomicReference<AgentActionPlan> stored = inMemoryRepository();
        when(executionService.execute(any(), any())).thenThrow(new BusinessException("库存不足"));
        AgentPlanOutcome outcome = completeOrderPlan();

        assertThatThrownBy(() -> service.confirm(String.valueOf(outcome.confirmation().get("proposalId")),
                "APPROVE", null, session("ORDER")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");
        assertThat(stored.get().status()).isEqualTo(AgentPlanStatus.FAILED);
        assertThat(stored.get().errorMessage()).contains("库存不足");
    }

    @Test
    void anotherUserCannotConfirmThePlan() {
        inMemoryRepository();
        AgentPlanOutcome outcome = completeOrderPlan();
        LoginResponse attacker = session("ORDER");
        attacker.setUserId(99L);
        attacker.setUsername("other");

        assertThatThrownBy(() -> service.confirm(String.valueOf(outcome.confirmation().get("proposalId")),
                "APPROVE", null, attacker))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能确认本人");
        verify(executionService, never()).execute(any(), any());
    }

    private AgentPlanOutcome completeOrderPlan() {
        return service.preview("createOrder", Map.of(
                "customerName", "演示客户",
                "productModel", "23.8寸电竞显示器",
                "quantity", 100,
                "deliveryDate", "2026-08-01"
        ), session("ORDER"), "chat-1");
    }

    @Test
    void productionProposalsPreserveReviewedParametersAndSupportLostDecisionResponse() {
        inMemoryRepository();
        var session = session("MANAGER");
        var preview = service.previewProduction("confirmSmartDispatch",
                Map.of("planId","PP123","recommendations",List.of(Map.of("recommendedOperator","li_operator"))),
                session, "chat-production");
        String id = String.valueOf(preview.confirmation().get("proposalId"));
        assertThatThrownBy(() -> service.confirm(id, "MODIFY", Map.of("recommendations",List.of()), session))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.confirmProduction(id, "MODIFY", session))
                .isInstanceOf(BusinessException.class);
        assertThat(service.confirmProduction(id,"SKIP",session)).containsEntry("status","REJECTED");
        assertThat(service.confirmProduction(id,"SKIP",session)).containsEntry("status","REJECTED");
        assertThatThrownBy(() -> service.confirmProduction(id,"APPROVE",session)).isInstanceOf(BusinessException.class);
        verify(executionService, never()).execute(any(),any());
    }

    @Test
    void committedMesReceiptSurvivesLateAuditFailure() {
        inMemoryRepository();
        var session = session("MANAGER");
        var preview = service.previewProduction("confirmSmartDispatch", Map.of("planId","PP123"), session, "chat-1");
        String id = String.valueOf(preview.confirmation().get("proposalId"));
        when(executionService.execute(any(),any())).thenReturn(Map.of("count",1));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(auditService).record(
                any(), any(), org.mockito.ArgumentMatchers.eq("AI_PLAN_EXECUTED"), any(), any(),
                org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.isNull());
        assertThatThrownBy(() -> service.confirmProduction(id,"APPROVE",session)).hasMessageContaining("audit unavailable");
        assertThat(service.confirmProduction(id,"APPROVE",session)).containsEntry("status","EXECUTED")
                .containsEntry("idempotent",true).containsEntry("result",Map.of("count",1));
        verify(executionService).execute(any(),any());
    }

    @Test
    void productionProposalRequiresCurrentOwnerRoleAndUnexpiredBusinessState() {
        var saved = inMemoryRepository();
        var session = session("MANAGER");
        var preview = service.previewProduction("confirmSmartDispatch", Map.of("planId","PP123"), session, "chat-1");
        String id = String.valueOf(preview.confirmation().get("proposalId"));
        var otherUser = session("MANAGER");
        otherUser.setUserId(2L);
        assertThatThrownBy(() -> service.confirmProduction(id,"APPROVE",otherUser)).hasMessageContaining("本人");
        assertThatThrownBy(() -> service.confirmProduction(id,"APPROVE",session("ADMIN"))).hasMessageContaining("本人");
        var original = saved.get();
        saved.set(new AgentActionPlan(original.planId(),original.actionCode(),original.title(),original.module(),
                original.userId(),original.username(),original.roleCode(),original.sessionId(),original.parameters(),
                original.affectedResources(),original.snapshotHash(),original.status(),null,null,
                original.createdAt().minusHours(2),original.createdAt().minusHours(1),null));
        assertThatThrownBy(() -> service.confirmProduction(id,"APPROVE",session)).hasMessageContaining("过期");
        assertThat(saved.get().status()).isEqualTo(AgentPlanStatus.STALE);
        saved.set(original);
        when(stateService.fingerprint(any(),any(),any())).thenReturn("reassigned");
        assertThatThrownBy(() -> service.confirmProduction(id,"APPROVE",session)).hasMessageContaining("发生变化");
        assertThat(saved.get().status()).isEqualTo(AgentPlanStatus.STALE);
        verify(executionService, never()).execute(any(),any());
    }

    private AtomicReference<AgentActionPlan> inMemoryRepository() {
        AtomicReference<AgentActionPlan> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return null;
        }).when(repository).save(any(AgentActionPlan.class));
        when(repository.find(anyString())).thenAnswer(invocation -> stored.get());
        return stored;
    }

    private LoginResponse session(String roleCode) {
        LoginResponse session = new LoginResponse();
        session.setUserId(1L);
        session.setUsername("tester");
        session.setRoleCode(roleCode);
        return session;
    }
}
