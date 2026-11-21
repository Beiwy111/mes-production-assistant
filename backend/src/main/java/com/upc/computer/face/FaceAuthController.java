package com.upc.computer.face;

import com.upc.computer.common.JwtUtil;
import com.upc.computer.common.Result;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/auth/face")
public class FaceAuthController {
    public record ChallengeRequest(String username) { }
    public record CaptureRequest(String username, String challengeId, String imageBase64, String password,
                                 boolean consent, String consentVersion) {
        @Override public String toString() { return "FaceCaptureRequest[REDACTED]"; }
    }
    public record PasswordRequest(String password) {
        @Override public String toString() { return "FacePasswordRequest[REDACTED]"; }
    }
    public record PreviewRequest(String imageBase64, boolean consent, String consentVersion) {
        @Override public String toString() { return "FacePreviewRequest[REDACTED]"; }
    }
    private final FaceAuthService faces;
    private final FaceSessionGuard guard;
    private final FaceAttemptGuard attempts;
    private final FaceStore store;
    private final AuthService auth;
    private final JwtUtil jwt;
    public FaceAuthController(FaceAuthService faces, FaceSessionGuard guard, FaceAttemptGuard attempts,
                              FaceStore store, AuthService auth, JwtUtil jwt) {
        this.faces = faces; this.guard = guard; this.attempts = attempts; this.store = store; this.auth = auth; this.jwt = jwt;
    }
    @GetMapping("/capabilities")
    public Result<Map<String, Object>> capabilities() { return Result.success(faces.capabilities()); }
    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody PreviewRequest body, HttpServletRequest request) {
        return Result.success(faces.preview(body.imageBase64(), body.consent(), body.consentVersion(), request.getRemoteAddr()));
    }
    @PostMapping("/challenge")
    public Result<Map<String, String>> challenge(@RequestBody ChallengeRequest body, HttpServletRequest request) {
        return Result.success(Map.of("challengeId", faces.challenge(body.username(), request.getRemoteAddr())));
    }
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody CaptureRequest body, HttpServletRequest request) {
        long id = faces.verify(body.username(), body.challengeId(), body.imageBase64(), body.consent(), body.consentVersion(), request.getRemoteAddr());
        LoginResponse session = auth.sessionForVerifiedUser(id);
        String token = jwt.generateToken(session.getUserId(), session.getUsername(), session.getRoleId(), session.getRoleCode());
        auth.cacheLoginSession(token, session);
        return Result.success(Map.of("token", token, "demoOnly", true));
    }
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String header) {
        return Result.success(faces.status(guard.require(header).getUserId()));
    }
    @PostMapping("/me/test")
    public Result<Map<String, Object>> testMatch(@RequestHeader(value = "Authorization", required = false) String header,
                                                @RequestBody CaptureRequest body) {
        LoginResponse session = guard.require(header);
        reauthenticate(session, body.password(), session.getUserId(), "TEST_AUTH");
        return Result.success(faces.testMatch(session.getUserId(), body.imageBase64(), body.consent(), body.consentVersion()));
    }
    @PutMapping("/me")
    public Result<Map<String, Object>> enroll(@RequestHeader(value = "Authorization", required = false) String header,
                                             @RequestBody CaptureRequest body) {
        LoginResponse session = guard.require(header);
        reauthenticate(session, body.password(), session.getUserId(), "ENROLL_AUTH");
        return Result.success(faces.enroll(session.getUserId(), body.imageBase64(), body.consent(), body.consentVersion()));
    }
    @DeleteMapping("/me")
    public Result<Map<String, Object>> deleteMe(@RequestHeader(value = "Authorization", required = false) String header,
                                               @RequestBody PasswordRequest body) {
        LoginResponse session = guard.require(header);
        reauthenticate(session, body.password(), session.getUserId(), "DELETE_AUTH");
        return Result.success(faces.revoke(session.getUserId(), session.getUserId()));
    }
    @GetMapping("/users/{userId}")
    public Result<Map<String, Object>> user(@RequestHeader(value = "Authorization", required = false) String header,
                                           @PathVariable long userId) {
        LoginResponse session = guard.require(header);
        requireAdmin(session, userId);
        return Result.success(faces.status(userId));
    }
    @DeleteMapping("/users/{userId}")
    public Result<Map<String, Object>> deleteUser(@RequestHeader(value = "Authorization", required = false) String header,
                                                 @PathVariable long userId, @RequestBody PasswordRequest body) {
        LoginResponse session = guard.require(header);
        requireAdmin(session, userId);
        reauthenticate(session, body.password(), userId, "ADMIN_DELETE_AUTH");
        return Result.success(faces.revoke(session.getUserId(), userId));
    }
    private void requireAdmin(LoginResponse session, long targetId) {
        if (targetId <= 0) throw new FaceException(400, "INVALID_TARGET", "用户编号无效");
        try { guard.admin(session); }
        catch (FaceException ex) { store.audit(session.getUserId(), targetId, "ADMIN_ACCESS", "FORBIDDEN"); throw ex; }
    }
    private void reauthenticate(LoginResponse session, String password, long target, String event) {
        try { attempts.management(session.getUserId()); guard.password(session, password); }
        catch (FaceException ex) { store.audit(session.getUserId(), target, event, ex.reason()); throw ex; }
    }
}
