package com.upc.computer.assistant.v2.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.runtime.*;
import com.upc.computer.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectBusinessQueriesTest {
    final ObjectMapper json = new ObjectMapper();
    final NaturalBusinessReadMapper rows = mock(NaturalBusinessReadMapper.class);
    final WorkbenchModelClient models = mock(WorkbenchModelClient.class);
    final AssistantReadPolicy policy = AssistantReadPolicy.of(42, "ORDER", null, List.of("order:list"));
    final DirectBusinessQueries reads = new DirectBusinessQueries((owner, operation) -> {
        assertEquals(42, owner);
        return operation.apply(new NaturalLanguageRuntime.Data(rows, null, policy));
    }, models, json);

    private com.fasterxml.jackson.databind.JsonNode plan(String domain) {
        var plan = json.createObjectNode();
        plan.put("action","QUERY"); plan.putArray("intents").add(domain);
        plan.putNull("orderPk"); plan.putNull("orderNo"); plan.putNull("keyword"); plan.put("period","CURRENT"); plan.put("question","");
        return plan;
    }

    @Test void localBusinessTurnReadsOnlyAuthorizedRowsAndUsesLocalModelForAnalysis() {
        when(rows.countAuthorized(eq("ORDER"),isNull(),isNull(),isNull(),isNull(),anyMap(),isNull(),isNull())).thenReturn(1L);
        when(rows.rowsAuthorized(eq("ORDER"),isNull(),isNull(),isNull(),isNull(),anyMap(),isNull(),isNull()))
                .thenReturn(List.of(Map.of("record_id","7","order_no","FICTION-7","status","APPROVED")));
        when(models.generate(eq(ChatMode.Source.LOCAL),anyList())).thenReturn("已核对授权订单记录。");
        var result = reads.answer(42,"查询订单",plan("ORDER"),new ChatMode(ChatMode.Source.LOCAL,false),Map.of());
        assertEquals("EVIDENCE_READY",result.get("verdict"));
        assertTrue(result.get("facts").toString().contains("FICTION-7"));
        assertTrue(result.get("expertExplanations").toString().contains("已核对授权订单记录"));
        verify(models).generate(eq(ChatMode.Source.LOCAL),argThat(messages -> messages.toString().contains("FICTION-7")));
        verifyNoMoreInteractions(models);
    }

    @Test void modelCannotBroadenAnOrderUsersPermissions() {
        var result = reads.answer(42,"查询全部设备",plan("EQUIPMENT"),new ChatMode(ChatMode.Source.API,false),Map.of());
        assertEquals("UNSUPPORTED",result.get("verdict"));
        assertTrue(((Map<?, ?>) result.get("facts")).isEmpty());
        verifyNoInteractions(models,rows);
    }

    @Test void modelCannotReenableDisabledKnowledgeRetrieval() {
        assertThrows(BusinessException.class, () -> reads.answer(42,"解释显示器工艺",plan("KNOWLEDGE"),
                new ChatMode(ChatMode.Source.LOCAL,false),Map.of()));
        verifyNoInteractions(models,rows);
    }
}
