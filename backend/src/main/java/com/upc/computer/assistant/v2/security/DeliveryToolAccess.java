package com.upc.computer.assistant.v2.security;

import com.upc.computer.service.AuthService;
import com.upc.computer.mapper.RoleMapper;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Conservative initial policy: internal order/stock facts require an active ADMIN. */
public final class DeliveryToolAccess {
    private final AssistantSessionResolver sessions;
    private final AuthService auth;
    private final RoleMapper roles;
    public DeliveryToolAccess(AssistantSessionResolver sessions, AuthService auth, RoleMapper roles) {
        this.sessions = sessions; this.auth = auth; this.roles = roles;
    }
    public long requireUser(String authorization) {
        long id = sessions.requireUserId(authorization);
        var user = auth.getCurrentUser(id);
        var role = user != null && user.getRoleId() != null ? roles.getRoleById(user.getRoleId()) : null;
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || role == null
                || !Integer.valueOf(1).equals(role.getStatus()) || !"ADMIN".equals(role.getRoleCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Delivery tools require an active administrator");
        }
        return id;
    }
}
