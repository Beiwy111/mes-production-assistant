package com.upc.computer.face;

import cn.hutool.crypto.digest.BCrypt;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.common.RedisUtil;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.User;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.mapper.UserMapper;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
public class FaceSessionGuard {
    private final JwtUtil jwt;
    private final RedisUtil redis;
    private final UserMapper users;
    private final RoleMapper roles;
    public FaceSessionGuard(JwtUtil jwt, RedisUtil redis, UserMapper users, RoleMapper roles) {
        this.jwt = jwt; this.redis = redis; this.users = users; this.roles = roles;
    }
    public LoginResponse require(String authorization) {
        String token = jwt.extractTokenFromHeader(authorization);
        if (!jwt.validateToken(token)) throw unauthorized();
        LoginResponse session = redis.getJson(RedisUtil.loginTokenKey(token), LoginResponse.class);
        if (session == null || session.getUserId() == null) throw unauthorized();
        User current = users.getUserById(session.getUserId());
        if (current == null || !Integer.valueOf(1).equals(current.getStatus()) || current.getRoleId() == null
                || !Objects.equals(current.getRoleId(), session.getRoleId())) throw unauthorized();
        var role = roles.getRoleById(current.getRoleId());
        if (role == null || !Integer.valueOf(1).equals(role.getStatus()) || !Objects.equals(role.getRoleCode(), session.getRoleCode())) throw unauthorized();
        return session;
    }
    public void admin(LoginResponse session) {
        if (!"ADMIN".equalsIgnoreCase(session.getRoleCode())) throw new FaceException(403, "FORBIDDEN", "仅管理员可管理其他用户的人脸绑定");
    }
    public void password(LoginResponse session, String password) {
        User user = users.getUserById(session.getUserId());
        if (password == null || password.isEmpty() || password.length() > 128 || user == null || user.getPasswordHash() == null) throw passwordError();
        String stored = user.getPasswordHash();
        boolean ok;
        try { ok = stored.startsWith("$2") ? BCrypt.checkpw(password, stored) : java.security.MessageDigest.isEqual(password.getBytes(java.nio.charset.StandardCharsets.UTF_8), stored.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (RuntimeException ex) { ok = false; }
        if (!ok) throw passwordError();
    }
    static FaceException unauthorized() { return new FaceException(401, "SESSION_INVALID", "登录已失效，请重新登录"); }
    private FaceException passwordError() { return new FaceException(403, "PASSWORD_INVALID", "当前账号密码验证失败"); }
}
