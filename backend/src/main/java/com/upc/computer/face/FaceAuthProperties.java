package com.upc.computer.face;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mes.face")
public class FaceAuthProperties {
    private boolean enabled = false;
    private boolean allowDemoLogin = false;
    private String baseUrl = "http://127.0.0.1:8000";
    private String recognitionApiKey = "";
    private String detectionApiKey = "";
    private double similarityThreshold = 0.85;
    private double detectionThreshold = 0.95;
    private int minFacePixels = 100;
    private double minSharpness = 35;
    private int minBrightness = 40;
    private int maxBrightness = 220;
    private int timeoutSeconds = 20;
    private int challengeSeconds = 120;
    private int maxFailures = 5;
    private int attemptWindowSeconds = 300;
    public boolean configured() {
        return recognitionApiKey != null && !recognitionApiKey.isBlank()
                && detectionApiKey != null && !detectionApiKey.isBlank();
    }
    public void requireService() {
        // Validate before any remote access; no configuration value is exposed to the browser.
        if (!enabled || !configured()) throw new FaceException(503, "NOT_CONFIGURED", "人脸服务尚未启用或配置，请使用密码登录");
        if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0.5 || similarityThreshold > 1
                || !Double.isFinite(detectionThreshold) || detectionThreshold < 0.5 || detectionThreshold > 1
                || timeoutSeconds < 1 || timeoutSeconds > 60 || maxFailures < 1 || challengeSeconds < 1
                || attemptWindowSeconds < 1 || minFacePixels < 32 || !Double.isFinite(minSharpness) || minSharpness < 0
                || minBrightness < 0 || maxBrightness > 255 || minBrightness >= maxBrightness) {
            throw new FaceException(503, "INVALID_CONFIG", "人脸服务配置无效，请联系管理员");
        }
    }
    /** Non-reversible deployment/service fingerprint; never expose through public APIs. */
    public String serviceScope() {
        try {
            String identity = baseUrl.replaceAll("/+$", "") + "\n" + recognitionApiKey;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(java.util.Arrays.copyOf(digest, 16));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
