package com.upc.computer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbenchIngestionControllerTest {
    final JwtUtil jwt = mock(JwtUtil.class);
    final AuthService auth = mock(AuthService.class);
    final WorkbenchIngestionController api = new WorkbenchIngestionController(new ObjectMapper(), jwt, auth);

    void session(String role) {
        var user = new LoginResponse();
        user.setRoleCode(role);
        when(jwt.extractTokenFromHeader("Bearer session")).thenReturn("session");
        when(jwt.validateToken("session")).thenReturn(true);
        when(auth.getLoginSession("session")).thenReturn(user);
    }
    void rejected(int code, Executable endpoint) {
        assertEquals(code, assertThrows(BusinessException.class, endpoint).getCode());
    }

    @Test void unauthenticatedRequestsCannotSeeJobs() {
        rejected(401, () -> api.list(null));
    }

    @Test void nonAdminCannotReadOrWriteAnyPrivateResource() {
        session("QC");
        String header = "Bearer session";
        rejected(403, () -> api.list(header));
        rejected(403, () -> api.details(header, "id"));
        rejected(403, () -> api.source(header, "id"));
        rejected(403, () -> api.upload(header, null));
        rejected(403, () -> api.retry(header, "id"));
        rejected(403, () -> api.remove(header, "id"));
        rejected(403, () -> api.removePublishedDocument(header, "id"));
        rejected(403, () -> api.search(header, null));
    }

    @Test void adminStillNeedsValidIdentifierQueryAndFilename() {
        session("ADMIN");
        String header = "Bearer session";
        rejected(400, () -> api.source(header, "../source"));
        rejected(400, () -> api.search(header, new WorkbenchIngestionController.Search(" ", null, 2)));
        rejected(400, () -> api.search(header, new WorkbenchIngestionController.Search("Mura", null, 999)));
        rejected(400, () -> api.upload(header, new MockMultipartFile("file", "../paper.pdf", "application/pdf", new byte[]{1})));
    }
}
