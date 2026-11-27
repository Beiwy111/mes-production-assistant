package com.upc.computer.assistant.v2.orders;

import com.upc.computer.common.*;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.service.AuthService;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class OrderSessionGuard {
    private final JwtUtil jwt;private final AuthService auth;private final RoleMapper roles;
    public OrderSessionGuard(JwtUtil jwt,AuthService auth,RoleMapper roles){this.jwt=jwt;this.auth=auth;this.roles=roles;}
    public LoginResponse require(String authorization) {
        LoginResponse session = requireActive(authorization);
        if (!Set.of("ADMIN","ORDER","CUSTOMER").contains(session.getRoleCode()))
            throw new BusinessException(403,"当前账号无下单权限，仅管理员、订单员和客户可办理");
        return session;
    }
    public LoginResponse requireActive(String authorization) {
        String token=jwt.extractTokenFromHeader(authorization);
        if(token==null||!jwt.validateToken(token))throw new BusinessException(401,"登录已失效，请重新登录");
        LoginResponse cached=auth.getLoginSession(token);
        if(cached==null||cached.getUserId()==null)throw new BusinessException(401,"登录已失效，请重新登录");
        User user=auth.getCurrentUser(cached.getUserId());
        Role role=user==null?null:roles.getRoleById(user.getRoleId());
        if(user==null||!Integer.valueOf(1).equals(user.getStatus())||role==null||!Integer.valueOf(1).equals(role.getStatus())||role.getRoleCode()==null)
            throw new BusinessException(403,"当前账号或角色已停用");
        LoginResponse session=new LoginResponse();session.setUserId(user.getUserId());session.setUsername(user.getUsername());session.setRoleId(role.getRoleId());session.setRoleCode(role.getRoleCode());
        session.setRealName(user.getRealName());session.setCustomerName(user.getCustomerName());session.setPhone(user.getPhone());session.setShippingAddress(user.getShippingAddress());return session;
    }
}
