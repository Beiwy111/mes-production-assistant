package com.upc.computer.controller;

import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.service.AuthService;
import com.upc.computer.service.MesWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperatorInboxControllerTest {
    @Test void usernameQueryCannotRedirectAnAuthenticatedInbox() {
        var controller = new MesController();
        var jwt = mock(JwtUtil.class);
        var auth = mock(AuthService.class);
        var workflow = mock(MesWorkflowService.class);
        ReflectionTestUtils.setField(controller,"jwt",jwt);
        ReflectionTestUtils.setField(controller,"auth",auth);
        ReflectionTestUtils.setField(controller,"mesWorkflowService",workflow);
        when(jwt.extractTokenFromHeader("Bearer token")).thenReturn("token");
        when(jwt.validateToken("token")).thenReturn(true);
        var session = new LoginResponse();
        session.setUsername("li_operator");
        when(auth.getLoginSession("token")).thenReturn(session);
        assertThatThrownBy(() -> controller.operatorNotifications("wu_operator","Bearer token"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.operatorNotifications("li_operator",null))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(workflow);
        controller.operatorNotifications("li_operator","Bearer token");
        verify(workflow).listOperatorNotices("li_operator");
    }
}
