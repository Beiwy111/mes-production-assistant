package com.upc.computer.controller;

import com.upc.computer.common.GlobalExceptionHandler;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.assistant.v2.runtime.KnowledgeEvidenceClient;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkbenchKnowledgeControllerTest {
    @Test void catalogRequiresALiveLoginSession() throws Exception {
        var api = new WorkbenchKnowledgeController(mock(AuthService.class), mock(JwtUtil.class), "test");
        var mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/assistant-v2/knowledge/catalog")).andExpect(jsonPath("$.code").value(401));
    }

    @Test void catalogReleaseChangeReachesTheBrowserAsHttpConflict() throws Exception {
        var auth = mock(AuthService.class);
        var jwt = mock(JwtUtil.class);
        var knowledge = mock(KnowledgeEvidenceClient.class);
        var api = new WorkbenchKnowledgeController(auth, jwt, "test");
        ReflectionTestUtils.setField(api, "knowledge", knowledge);
        var mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new GlobalExceptionHandler()).build();
        String release = "a".repeat(32);
        when(jwt.extractTokenFromHeader("Bearer session")).thenReturn("session");
        when(jwt.validateToken("session")).thenReturn(true);
        when(auth.getLoginSession("session")).thenReturn(new LoginResponse());
        when(knowledge.catalog(5, 100, release)).thenThrow(new KnowledgeEvidenceClient.CatalogChanged());
        mvc.perform(get("/assistant-v2/knowledge/catalog").header("Authorization", "Bearer session")
                .param("offset", "5").param("releaseId", release))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
    }
}
