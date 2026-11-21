package com.upc.computer.assistant.v2.tools.delivery;

import com.upc.computer.assistant.v2.api.AiDeliveryToolController;
import com.upc.computer.assistant.v2.security.DeliveryToolAccess;
import com.upc.computer.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiDeliveryToolControllerTest {
    @Test void missingSessionIsHttp401AndDoesNotQueryFacts() throws Exception {
        var access = mock(DeliveryToolAccess.class); var service = mock(AiDeliveryQueryService.class);
        when(access.requireUser(null)).thenThrow(new BusinessException(401, "Unauthenticated"));
        var mvc = MockMvcBuilders.standaloneSetup(new AiDeliveryToolController(access, service)).build();
        mvc.perform(get("/ai-tools/orders/10/fulfillment-evidence").param("scopeRef", "x")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void modelCannotSupplyRoleOrScopeInResolveBody() throws Exception {
        var access = mock(DeliveryToolAccess.class); var service = mock(AiDeliveryQueryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiDeliveryToolController(access, service)).build();
        mvc.perform(post("/ai-tools/orders/resolve").contentType("application/json").content("{\"orderPk\":\"10\",\"role\":\"ADMIN\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
