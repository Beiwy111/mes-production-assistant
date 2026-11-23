package com.upc.computer.face;

import com.upc.computer.entity.User;
import com.upc.computer.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.Semaphore;

@Service
public class FaceAuthService {
    public static final String CONSENT_VERSION = "face-demo-2026-11-v3";
    public static final String NOTICE = "仅用于本 MES 的人脸录入和账号验证。点击开始后，浏览器会间隔采集画面并交由 CompreFace 检测。录入或更新会展示自动采集的实际照片和目标账号，需你确认后才保存；登录和匹配测试在检测通过后自动提交，可随时停止检测。MES 不保存原始图像或特征向量，CompreFace 处理并保存录入的人脸特征；其原图留存由管理员配置。你可以随时撤销绑定。当前未提供可靠活体检测，可能被照片或视频欺骗，请保留密码登录方式。";
    private static final Logger log = LoggerFactory.getLogger(FaceAuthService.class);
    private final FaceAuthProperties properties;
    private final FaceStore store;
    private final CompreFaceClient client;
    private final FaceImageQuality quality;
    private final FaceAttemptGuard attempts;
    private final UserMapper users;
    private final Semaphore previewSlots = new Semaphore(2);
    public FaceAuthService(FaceAuthProperties properties, FaceStore store, CompreFaceClient client,
                           FaceImageQuality quality, FaceAttemptGuard attempts, UserMapper users) {
        this.properties = properties; this.store = store; this.client = client;
        this.quality = quality; this.attempts = attempts; this.users = users;
    }
    public Map<String, Object> capabilities() {
        return Map.of("enabled", properties.isEnabled(), "configured", properties.configured(),
                "loginAllowed", properties.isEnabled() && properties.configured() && properties.isAllowDemoLogin(),
                "demoOnly", true, "livenessAvailable", false, "consentVersion", CONSENT_VERSION,
                "notice", NOTICE, "maxImageBytes", FaceImageQuality.MAX_BYTES);
    }
    public String challenge(String username, String ip) {
        requireLoginEnabled();
        requireUsername(username);
        return attempts.challenge(username, ip);
    }
    public Map<String, Object> preview(String image, boolean accepted, String version, String ip) {
        properties.requireService();
        consent(accepted, version);
        attempts.preview(ip);
        if (!previewSlots.tryAcquire()) throw new FaceException(503, "PREVIEW_BUSY", "人脸检测服务繁忙，请稍后重新开始");
        try {
            FaceImageQuality.Capture capture = quality.decode(image);
            var face = quality.singleFace(client.detect(capture.jpeg()), capture);
            var box = face.path("box");
            double width = capture.image().getWidth(), height = capture.image().getHeight();
            return Map.of("ready", true, "message", "检测到清晰的单人脸，请保持稳定", "box", Map.of(
                    "x", box.path("x_min").asDouble() / width,
                    "y", box.path("y_min").asDouble() / height,
                    "width", (box.path("x_max").asDouble() - box.path("x_min").asDouble()) / width,
                    "height", (box.path("y_max").asDouble() - box.path("y_min").asDouble()) / height));
        } catch (FaceException ex) {
            if (ex.status() == 422) return Map.of("ready", false, "message", ex.getMessage());
            throw ex;
        } finally { previewSlots.release(); }
    }
    public long verify(String username, String challengeId, String image, boolean consent, String consentVersion, String ip) {
        requireLoginEnabled();
        requireUsername(username);
        Long target = null;
        try {
            attempts.beginLogin(username, ip, challengeId);
            consent(consent, consentVersion);
            User user = users.getUserByUsername(username.trim());
            if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || user.getRoleId() == null) throw mismatch();
            target = user.getUserId();
            FaceStore.Binding binding = store.find(target);
            if (binding == null || !"ACTIVE".equals(binding.state()) || binding.imageId() == null) throw mismatch();
            client.validateSubject(binding.subjectId());
            FaceImageQuality.Capture capture = quality.decode(image);
            var face = quality.singleFace(client.verify(binding.imageId(), capture.jpeg()), capture);
            double similarity = similarity(face);
            auditScore(null, target, "LOGIN_SCORE", similarity, binding.revision());
            if (similarity < properties.getSimilarityThreshold()) throw mismatch();
            FaceStore.Binding latest = store.find(target);
            if (latest == null || !"ACTIVE".equals(latest.state()) || latest.revision() != binding.revision()
                    || !binding.imageId().equals(latest.imageId())) throw mismatch();
            store.audit(target, target, "LOGIN", "VERIFIED");
            attempts.success(username);
            return target;
        } catch (FaceException ex) {
            store.audit(null, target, "LOGIN", ex.reason());
            throw ex;
        }
    }
    /** Authenticated diagnostic only: compares with the current binding without enrollment or a login session. */
    public Map<String, Object> testMatch(long userId, String image, boolean accepted, String version) {
        properties.requireService();
        consent(accepted, version);
        FaceStore.Binding binding = store.find(userId);
        if (binding == null || !"ACTIVE".equals(binding.state()) || binding.imageId() == null) {
            throw new FaceException(409, "NOT_ENROLLED", "请先录入本人人脸，再进行匹配测试");
        }
        client.validateSubject(binding.subjectId());
        FaceImageQuality.Capture capture = quality.decode(image);
        var face = quality.singleFace(client.verify(binding.imageId(), capture.jpeg()), capture);
        double score = similarity(face);
        FaceStore.Binding latest = store.find(userId);
        if (latest == null || !"ACTIVE".equals(latest.state()) || latest.revision() != binding.revision()
                || !binding.imageId().equals(latest.imageId())) {
            throw new FaceException(409, "CONCURRENT_CHANGE", "绑定已变化，请重新进行匹配测试");
        }
        auditScore(userId, userId, "TEST_SCORE", score, binding.revision());
        return Map.of("similarity", score, "threshold", properties.getSimilarityThreshold(),
                "matched", score >= properties.getSimilarityThreshold(), "bindingRevision", binding.revision(),
                "demoOnly", true);
    }
    private double similarity(com.fasterxml.jackson.databind.JsonNode face) {
        var value = face.path("similarity");
        if (!value.isNumber()) throw CompreFaceClient.invalidResponse();
        double score = value.doubleValue();
        if (!Double.isFinite(score) || score < 0 || score > 1) throw CompreFaceClient.invalidResponse();
        return score;
    }
    private void auditScore(Long actorId, long targetId, String event, double score, long revision) {
        store.audit(actorId, targetId, event, String.format(Locale.ROOT, "S=%.5f;T=%.5f;R%d", score, properties.getSimilarityThreshold(), revision));
    }
    public Map<String, Object> status(long userId) {
        properties.requireService();
        FaceStore.Binding binding = store.find(userId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("enrolled", binding != null && "ACTIVE".equals(binding.state()));
        value.put("state", binding == null ? "NONE" : binding.state());
        value.put("bindingRevision", binding == null ? 0 : binding.revision());
        value.put("updatedAt", binding == null ? null : binding.updatedAt());
        value.put("consentAt", binding == null ? null : binding.consentAt());
        value.put("pendingCleanup", store.pending(userId));
        boolean mismatch = binding != null && binding.subjectId() != null
                && !binding.subjectId().startsWith("mes-" + userId + "-" + properties.serviceScope() + "-");
        value.put("serviceMismatch", mismatch);
        value.put("demoOnly", true);
        return value;
    }
    public Map<String, Object> enroll(long userId, String image, boolean accepted, String version) {
        properties.requireService();
        consent(accepted, version);
        FaceStore.Binding existing = store.find(userId);
        if (existing != null && existing.subjectId() != null) client.validateSubject(existing.subjectId());
        if (store.pending(userId) > 0) throw new FaceException(409, "CLEANUP_PENDING", "仍有远端样本待清理，请清理完成后再录入或更新");
        FaceImageQuality.Capture capture = quality.decode(image);
        quality.singleFace(client.detect(capture.jpeg()), capture);
        String subject = "mes-" + userId + "-" + properties.serviceScope() + "-" + UUID.randomUUID().toString().replace("-", "");
        if (!store.beginEnrollment(userId, subject)) throw new FaceException(409, "BUSY", "当前人脸正在更新，请稍后重试");
        boolean activated = false;
        try {
            store.audit(userId, userId, "ENROLL", "STARTED");
            String imageId = client.enroll(subject, capture.jpeg());
            store.activate(userId, subject, imageId, version);
            activated = true;
            store.audit(userId, userId, "ENROLL", "ACTIVE");
        } catch (RuntimeException ex) {
            if (!activated) store.enrollmentFailed(userId, subject);
            store.audit(userId, userId, "ENROLL", ex instanceof FaceException face ? face.reason() : "FAILED");
            throw ex;
        }
        cleanup(userId);
        return status(userId);
    }
    public Map<String, Object> revoke(long actorId, long targetId) {
        store.revoke(targetId);
        store.audit(actorId, targetId, "DELETE", "LOCAL_REVOKED");
        cleanup(targetId);
        return Map.of("enrolled", false, "state", "NONE", "pendingCleanup", store.pending(targetId), "demoOnly", true);
    }
    public void cleanup(Long userId) {
        if (!properties.isEnabled() || !properties.configured()) return;
        int remaining = userId == null ? 20 : 1;
        for (FaceStore.Cleanup item : store.cleanups(userId)) {
            if (remaining-- <= 0) break;
            try {
                client.deleteSubject(item.subjectId());
                store.audit(null, item.userId(), "REMOTE_DELETE", "DELETED");
                store.cleaned(item.id());
            } catch (RuntimeException ex) {
                store.cleanupFailed(item.id());
            }
        }
    }
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void recoverAndCleanup() {
        if (!properties.isEnabled()) return;
        try { store.recover(); cleanup(null); }
        catch (RuntimeException ex) { log.warn("Face maintenance pending: check face schema and service configuration (payload omitted)"); }
    }
    private void requireLoginEnabled() {
        properties.requireService();
        if (!properties.isAllowDemoLogin()) throw new FaceException(403, "DEMO_DISABLED", "尚未开放人脸登录，请使用密码登录");
    }
    private void consent(boolean accepted, String version) {
        if (!accepted || !CONSENT_VERSION.equals(version)) throw new FaceException(400, "CONSENT_REQUIRED", "请阅读并同意当前人脸信息授权说明");
    }
    private void requireUsername(String value) {
        if (value == null || value.isBlank() || value.length() > 64) throw new FaceException(400, "USERNAME_REQUIRED", "请输入有效账号");
    }
    private FaceException mismatch() { return new FaceException(401, "NOT_VERIFIED", "人脸验证未通过，请重新采集或使用密码登录"); }
}
