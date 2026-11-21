package com.upc.computer.assistant.v2.tools.graphexport;
import com.upc.computer.assistant.v2.security.GraphExportAccess;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class GraphExportAccessTest {
    static final String KEY = "test-only-maintenance-key-0123456789abcdef";
    @Test void requiresDedicatedCredentialAndConfiguredOrderScope() {
        var access = new GraphExportAccess(KEY, Set.of("1"));
        assertEquals(1L, access.requireOrder("Bearer " + KEY, "1"));
        for (String token : new String[]{null, "Bearer ordinary-mes-token", "Bearer run-token", "Basic " + KEY, "Bearer " + KEY + "x"})
            assertEquals(401, assertThrows(ResponseStatusException.class, () -> access.requireOrder(token, "1")).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.requireOrder("Bearer " + KEY, "2")).getStatusCode().value());
    }
    @Test void rejectsNoncanonicalAndOverflowingOrderIds() {
        var access = new GraphExportAccess(KEY, Set.of("1"));
        for (String id : new String[]{null,"", "0", "01", "-1", "1 OR 1=1", "9223372036854775808"})
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> access.requireOrder("Bearer " + KEY, id)).getStatusCode().value());
    }
    @Test void rejectsMissingWeakCredentialAndUnboundedScopeAtConstruction() {
        for (String key : new String[]{null, "", "short", " ".repeat(32)})
            assertThrows(IllegalArgumentException.class, () -> new GraphExportAccess(key, Set.of("1")));
        assertThrows(IllegalArgumentException.class, () -> new GraphExportAccess(KEY, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new GraphExportAccess(KEY, Set.of("*")));
    }
}
