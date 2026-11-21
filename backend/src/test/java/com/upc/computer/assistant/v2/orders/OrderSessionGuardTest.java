package com.upc.computer.assistant.v2.orders;
import com.upc.computer.common.*;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.service.AuthService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class OrderSessionGuardTest {
    @Test void roleAndCustomerBindingComeFromFreshDatabaseRecords() {
        JwtUtil jwt=mock(JwtUtil.class);AuthService auth=mock(AuthService.class);RoleMapper roles=mock(RoleMapper.class);
        when(jwt.extractTokenFromHeader("Bearer token")).thenReturn("token");when(jwt.validateToken("token")).thenReturn(true);
        LoginResponse cached=new LoginResponse();cached.setUserId(1L);cached.setRoleCode("ADMIN");cached.setCustomerName("旧客户");when(auth.getLoginSession("token")).thenReturn(cached);
        User user=new User();user.setUserId(1L);user.setRoleId(8L);user.setStatus(1);user.setUsername("customer");user.setCustomerName("新客户");when(auth.getCurrentUser(1L)).thenReturn(user);
        Role role=new Role();role.setRoleId(8L);role.setRoleCode("CUSTOMER");role.setStatus(1);when(roles.getRoleById(8L)).thenReturn(role);
        OrderSessionGuard guard=new OrderSessionGuard(jwt,auth,roles);LoginResponse actual=guard.require("Bearer token");
        assertThat(actual.getRoleCode()).isEqualTo("CUSTOMER");assertThat(actual.getCustomerName()).isEqualTo("新客户");assertThat(actual.getToken()).isNull();
        role.setRoleCode("OPERATOR");assertThatThrownBy(()->guard.require("Bearer token")).isInstanceOf(BusinessException.class);
        role.setRoleCode("CUSTOMER");user.setStatus(0);assertThatThrownBy(()->guard.require("Bearer token")).isInstanceOf(BusinessException.class);
    }
}
