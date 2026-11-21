package com.upc.computer.face;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class FaceAttemptGuard {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n", Long.class);
    private static final DefaultRedisScript<String> TAKE = new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]); if v then redis.call('DEL',KEYS[1]); end; return v", String.class);
    private final StringRedisTemplate redis;
    private final FaceAuthProperties properties;
    public FaceAttemptGuard(StringRedisTemplate redis, FaceAuthProperties properties) { this.redis = redis; this.properties = properties; }
    public String challenge(String username, String ip) {
        limit("challenge-ip", ip, 30);
        String id = UUID.randomUUID().toString();
        redis.opsForValue().set("mes:face:challenge:" + id, hash(normalize(username) + "\n" + ip), Duration.ofSeconds(properties.getChallengeSeconds()));
        return id;
    }
    public void beginLogin(String username, String ip, String challenge) {
        limit("login-ip", ip, 30);
        limit("login-user", normalize(username), properties.getMaxFailures());
        if (challenge == null || !challenge.matches("[a-fA-F0-9-]{36}")) throw replay();
        String expected = redis.execute(TAKE, List.of("mes:face:challenge:" + challenge));
        if (expected == null || !expected.equals(hash(normalize(username) + "\n" + ip))) throw replay();
    }
    public void success(String username) { redis.delete(key("login-user", normalize(username))); }
    public void management(long actorId) { limit("manage", String.valueOf(actorId), 10); }
    // Preview frames never consume account login or management attempts.
    public void preview(String ip) { limit("preview-ip", ip, 120); }
    private void limit(String scope, String value, int max) {
        Long n = redis.execute(INCREMENT, List.of(key(scope, value)), String.valueOf(properties.getAttemptWindowSeconds()));
        if (n == null) throw new FaceException(503, "RATE_UNAVAILABLE", "认证保护服务暂不可用");
        if (n > max) throw new FaceException(429, "RATE_LIMIT", "尝试次数过多，请五分钟后重试或使用密码登录");
    }
    private String key(String scope, String value) { return "mes:face:attempt:" + scope + ":" + hash(value); }
    static String normalize(String username) { return username == null ? "" : username.trim().toLowerCase(Locale.ROOT); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private FaceException replay() { return new FaceException(401, "CHALLENGE_INVALID", "本次采集凭证已失效，请重新拍摄"); }
}
