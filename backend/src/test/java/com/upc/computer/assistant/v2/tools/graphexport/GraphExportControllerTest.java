package com.upc.computer.assistant.v2.tools.graphexport;
import com.upc.computer.assistant.v2.api.GraphExportController;
import com.upc.computer.assistant.v2.security.GraphExportAccess;
import com.upc.computer.assistant.v2.mapper.graphexport.GraphExportMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GraphExportControllerTest {
    private static final String PATH = "/ai-internal/knowledge-export/order-chain";
    private final GraphExportMapper mapper = mock(GraphExportMapper.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new GraphExportController(new GraphExportAccess(GraphExportAccessTest.KEY, Set.of("1")), new GraphExportService(mapper, Clock.systemUTC()))).build();
    @Test void rejectsSelfClaimedRolesSqlAndUnexpectedBodyFieldsBeforeReading() throws Exception {
        for (String body : List.of("{\"orderPk\":\"1\",\"role\":\"ADMIN\"}", "{\"orderPk\":\"1\",\"sql\":\"SELECT *\"}", "{\"orderPk\":1}", "{}", "[]", "null"))
            mvc.perform(post(PATH).header("Authorization", "Bearer " + GraphExportAccessTest.KEY).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }
    @Test void rejectsOrdinaryTokensAndUnlistedOrder() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer run-token").contentType("application/json").content("{\"orderPk\":\"1\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + GraphExportAccessTest.KEY).contentType("application/json").content("{\"orderPk\":\"2\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(mapper);
    }
    @Test void serializesContractAndSanitizesSourceFailures() throws Exception {
        when(mapper.order(1L)).thenReturn(List.of(Map.of("id", 1L)));
        mvc.perform(post(PATH).header("Authorization", "Bearer " + GraphExportAccessTest.KEY).contentType("application/json").content("{\"orderPk\":\"1\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value("1")).andExpect(jsonPath("$.scope.orderPk").value("1"))
            .andExpect(jsonPath("$.nodes[0].source_table").value("customer_order")).andExpect(jsonPath("$.sourceObservedAt").isString());
        when(mapper.order(1L)).thenThrow(new IllegalStateException("private JDBC connection details"));
        mvc.perform(post(PATH).header("Authorization", "Bearer " + GraphExportAccessTest.KEY).contentType("application/json").content("{\"orderPk\":\"1\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("JDBC"))));
    }
}
