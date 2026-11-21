package com.upc.computer.assistant.v2.tools.delivery;

import com.upc.computer.assistant.v2.security.*;
import com.upc.computer.service.AuthService;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.entity.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryToolAccessTest {
    @Test void currentDatabaseRoleWinsAndUnknownCustomerOwnershipIsDenied() {
        var sessions = mock(AssistantSessionResolver.class);
        var auth = mock(AuthService.class); var roles = mock(RoleMapper.class);
        when(sessions.requireUserId("session")).thenReturn(5L);
        var user = new User(); user.setUserId(5L); user.setRoleId(1L); user.setStatus(1);
        var role = new Role(); role.setRoleCode("ADMIN"); role.setStatus(1);
        when(auth.getCurrentUser(5L)).thenReturn(user); when(roles.getRoleById(1L)).thenReturn(role);
        var access = new DeliveryToolAccess(sessions, auth, roles);
        assertEquals(5L, access.requireUser("session"));
        role.setRoleCode("CUSTOMER");
        assertThrows(RuntimeException.class, () -> access.requireUser("session"));
        role.setRoleCode("ADMIN"); user.setStatus(0);
        assertThrows(RuntimeException.class, () -> access.requireUser("session"));
    }
}
