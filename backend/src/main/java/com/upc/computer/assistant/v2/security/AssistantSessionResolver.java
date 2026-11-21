package com.upc.computer.assistant.v2.security;

import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.service.AuthService;

/** 仅信任现有后端登录会话；请求正文中的 user/role 不参与身份判断。 */
public class AssistantSessionResolver {
    private final JwtUtil jwt;
    private final AuthService auth;
    public AssistantSessionResolver(JwtUtil jwt, AuthService auth) { this.jwt = jwt; this.auth = auth; }
    public long requireUserId(String authorization) {
        String token = jwt.extractTokenFromHeader(authorization);
        if (token == null || token.isBlank() || !jwt.validateToken(token)) {
            throw new BusinessException(401, "未登录或令牌无效");
        }
        var session = auth.getLoginSession(token);
        if (session == null || session.getUserId() == null) throw new BusinessException(401, "登录已失效，请重新登录");
        return session.getUserId();
    }
}
