package com.upc.computer.face;

import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.upc.computer.common.*;
import com.upc.computer.controller.AuthController;
import com.upc.computer.entity.Role;
import com.upc.computer.entity.User;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.mapper.UserMapper;
import com.upc.computer.service.impl.AuthServiceImpl;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Adapter integration only: synthetic non-face images, H2, Redis test double and local HTTP CompreFace contract double. */
class FaceAuthIntegrationTest {
    static final String PASSWORD = "Test-only-not-a-real-user!42";
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Map<String, String> redisValues = new ConcurrentHashMap<>();
    final Map<String, Example> examples = new ConcurrentHashMap<>();
    final Map<Long, User> usersById = new HashMap<>();
    final Map<Long, Role> rolesById = new HashMap<>();
    final AtomicInteger faceCount = new AtomicInteger(1);
    final AtomicInteger detectStatus = new AtomicInteger(200);
    final AtomicInteger deleteStatus = new AtomicInteger(200);
    final AtomicInteger enrollStatus = new AtomicInteger(200);
    final AtomicReference<String> lastVerifyPath = new AtomicReference<>();
    final AtomicReference<JsonNode> overrideSimilarity = new AtomicReference<>();
    HttpServer remote;
    JdbcTemplate jdbc;
    JdbcFaceStore store;
    FaceAuthService service;
    FaceAuthProperties props;
    CompreFaceClient client;
    MockMvc mvc;
    String aliceToken, bobToken, adminToken, aliceImage, bobImage;
    record Example(String subject, byte[] image) { }

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() throws Exception {
        props = new FaceAuthProperties(); props.setEnabled(true); props.setAllowDemoLogin(true);
        props.setRecognitionApiKey("test-recognition-secret"); props.setDetectionApiKey("test-detection-secret");
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/api/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            int code = 200;
            Object response;
            String expectedKey = path.contains("/detection/") ? "test-detection-secret" : "test-recognition-secret";
            if (!expectedKey.equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
                code = 401; response = Map.of("message", "test secret must never reach browser");
            } else if (path.endsWith("/detect")) {
                code = detectStatus.get();
                response = code == 200 ? Map.of("result", faces(null)) : Map.of("message", "secret upstream image/key diagnostic");
            } else if (path.endsWith("/verify")) {
                lastVerifyPath.set(path);
                String id = path.substring("/api/v1/recognition/faces/".length(), path.length() - "/verify".length());
                Example known = examples.get(id);
                response = Map.of("result", faces(known != null && Arrays.equals(known.image(), jpegPart(body)) ? 0.97 : 0.11));
                if (overrideSimilarity.get() != null) {
                    var tree = json.valueToTree(response);
                    var first = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("result").get(0);
                    if (overrideSimilarity.get().isMissingNode()) first.remove("similarity");
                    else first.set("similarity", overrideSimilarity.get());
                    response = tree;
                }
            } else if (path.contains("/subjects/") && "DELETE".equals(exchange.getRequestMethod())) {
                String subject = path.substring(path.lastIndexOf('/') + 1);
                code = deleteStatus.get();
                if (code == 200) {
                    boolean present = examples.values().stream().anyMatch(value -> value.subject().equals(subject));
                    examples.entrySet().removeIf(entry -> entry.getValue().subject().equals(subject));
                    if (!present) code = 404;
                }
                response = Map.of("subject", subject);
            } else if (path.equals("/api/v1/recognition/faces") && "POST".equals(exchange.getRequestMethod())) {
                String subject = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                        .filter(p -> p.startsWith("subject=")).findFirst().orElseThrow().substring(8);
                String id = UUID.randomUUID().toString();
                examples.put(id, new Example(subject, jpegPart(body)));
                code = enrollStatus.get();
                response = Map.of("image_id", id, "subject", subject);
            } else { code = 404; response = Map.of("message", "unsupported test endpoint"); }
            byte[] output = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, output.length);
            exchange.getResponseBody().write(output); exchange.close();
        });
        remote.start(); props.setBaseUrl("http://127.0.0.1:" + remote.getAddress().getPort());
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:face_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        try (var connection = ds.getConnection()) { ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/face-auth-v1.sql")); }
        jdbc.execute("CREATE TABLE `user` (user_id BIGINT PRIMARY KEY)");
        store = new JdbcFaceStore(jdbc, new DataSourceTransactionManager(ds));
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> redisValues.get(i.getArgument(0)));
        doAnswer(i -> { redisValues.put(i.getArgument(0), i.getArgument(1)); return null; }).when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        doAnswer(i -> { redisValues.put(i.getArgument(0), i.getArgument(1)); return null; }).when(values).set(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
        when(redis.delete(anyString())).thenAnswer(i -> redisValues.remove(i.getArgument(0)) != null);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(i -> {
            RedisScript<?> script = i.getArgument(0); List<String> keys = i.getArgument(1);
            if (script.getResultType() == String.class) return redisValues.remove(keys.get(0));
            long count = Long.parseLong(redisValues.getOrDefault(keys.get(0), "0")) + 1;
            redisValues.put(keys.get(0), Long.toString(count)); return count;
        });
        RedisUtil redisUtil = new RedisUtil(); ReflectionTestUtils.setField(redisUtil, "stringRedisTemplate", redis); ReflectionTestUtils.setField(redisUtil, "objectMapper", json);
        JwtUtil jwt = new JwtUtil(); ReflectionTestUtils.setField(jwt, "jwtSecret", "face-test-only-signing-key-not-real-at-least-32-bytes"); ReflectionTestUtils.setField(jwt, "jwtExpireHours", 1L);
        UserMapper userMapper = mock(UserMapper.class); RoleMapper roleMapper = mock(RoleMapper.class);
        when(userMapper.getUserById(anyLong())).thenAnswer(i -> usersById.get(i.getArgument(0)));
        when(userMapper.getUserByUsername(anyString())).thenAnswer(i -> usersById.values().stream().filter(u -> u.getUsername().equalsIgnoreCase(i.getArgument(0))).findFirst().orElse(null));
        when(roleMapper.getRoleById(anyLong())).thenAnswer(i -> rolesById.get(i.getArgument(0)));
        addRole(1, "OPERATOR"); addRole(2, "ADMIN"); addUser(1, "fiction_alice", 1); addUser(2, "fiction_bob", 1); addUser(3, "fiction_admin", 2);
        AuthServiceImpl auth = new AuthServiceImpl(); ReflectionTestUtils.setField(auth, "userMapper", userMapper); ReflectionTestUtils.setField(auth, "roleMapper", roleMapper); ReflectionTestUtils.setField(auth, "redisUtil", redisUtil); ReflectionTestUtils.setField(auth, "jwtUtil", jwt);
        client = new CompreFaceClient(props, json);
        var attempts = new FaceAttemptGuard(redis, props);
        service = new FaceAuthService(props, store, client, new FaceImageQuality(props), attempts, userMapper);
        var guard = new FaceSessionGuard(jwt, redisUtil, userMapper, roleMapper);
        var faceController = new FaceAuthController(service, guard, attempts, store, auth, jwt);
        AuthController passwordController = new AuthController(); ReflectionTestUtils.setField(passwordController, "authService", auth); ReflectionTestUtils.setField(passwordController, "jwtUtil", jwt);
        mvc = MockMvcBuilders.standaloneSetup(faceController, passwordController)
                .setControllerAdvice(new FaceExceptionAdvice(), new GlobalExceptionHandler()).addFilter(new FaceRequestFilter()).build();
        aliceToken = passwordLogin("fiction_alice"); bobToken = passwordLogin("fiction_bob"); adminToken = passwordLogin("fiction_admin");
        aliceImage = generatedImage(41, false); bobImage = generatedImage(99, false);
    }
    @AfterEach void stop() { if (remote != null) remote.stop(0); if (jdbc != null) jdbc.execute("SHUTDOWN"); }
    void addRole(long id, String code) { Role r = new Role(); r.setRoleId(id); r.setRoleCode(code); r.setRoleName(code); r.setStatus(1); rolesById.put(id, r); }
    void addUser(long id, String username, long role) { User u = new User(); u.setUserId(id); u.setUsername(username); u.setRealName("Fictional tester"); u.setRoleId(role); u.setStatus(1); u.setPasswordHash(BCrypt.hashpw(PASSWORD)); usersById.put(id, u); jdbc.update("INSERT INTO `user` VALUES (?)", id); }
    List<Map<String, Object>> faces(Double similarity) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < faceCount.get(); i++) {
            Map<String, Object> face = new LinkedHashMap<>(); face.put("box", Map.of("probability", 0.999, "x_min", 80, "y_min", 60, "x_max", 300, "y_max", 300));
            if (similarity != null) face.put("similarity", similarity); result.add(face);
        }
        return result;
    }
    static byte[] jpegPart(byte[] data) {
        int start = -1, end = -1;
        for (int i = 0; i < data.length - 1; i++) { if (start < 0 && data[i] == (byte)255 && data[i+1] == (byte)216) start = i; if (data[i] == (byte)255 && data[i+1] == (byte)217) end = i + 2; }
        return start < 0 || end < start ? new byte[0] : Arrays.copyOfRange(data, start, end);
    }
    static String generatedImage(int seed, boolean blank) throws Exception {
        BufferedImage image = new BufferedImage(480, 360, BufferedImage.TYPE_INT_RGB); Random random = new Random(seed);
        for (int y = 0; y < 360; y++) for (int x = 0; x < 480; x++) { int gray = blank ? 120 : 50 + random.nextInt(150); image.setRGB(x, y, (gray << 16) | (gray << 8) | gray); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    JsonNode request(String method, String path, String token, Object body) throws Exception {
        var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.contentType("application/json").content(json.writeValueAsBytes(body));
        return json.readTree(mvc.perform(builder).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    String passwordLogin(String name) throws Exception { JsonNode r = request("POST", "/auth/login", null, Map.of("username", name, "password", PASSWORD)); assertEquals(200, r.path("code").asInt(), r.toString()); return r.path("data").path("token").asText(); }
    JsonNode enroll(String token, String image) throws Exception { return request("PUT", "/auth/face/me", token, Map.of("imageBase64", image, "password", PASSWORD, "consent", true, "consentVersion", FaceAuthService.CONSENT_VERSION)); }
    String challenge(String username) throws Exception { return request("POST", "/auth/face/challenge", null, Map.of("username", username)).path("data").path("challengeId").asText(); }
    JsonNode login(String username, String image) throws Exception { return login(username, image, challenge(username)); }
    JsonNode login(String username, String image, String challenge) throws Exception { return request("POST", "/auth/face/login", null, Map.of("username", username, "imageBase64", image, "challengeId", challenge, "consent", true, "consentVersion", FaceAuthService.CONSENT_VERSION)); }
    JsonNode preview(String image) throws Exception { return request("POST", "/auth/face/preview", null, Map.of("imageBase64", image, "consent", true, "consentVersion", FaceAuthService.CONSENT_VERSION)); }

    @Test void authenticatedComparisonReportsScoresWithoutChangingBindingOrCreatingSessions() throws Exception {
        enroll(aliceToken, aliceImage);
        var before = store.find(1);
        var sessions = new HashMap<>(redisValues);
        var payload = new HashMap<String, Object>(Map.of("imageBase64", aliceImage, "password", PASSWORD, "consent", true, "consentVersion", FaceAuthService.CONSENT_VERSION));
        assertEquals(401, request("POST", "/auth/face/me/test", null, payload).path("code").asInt());
        payload.put("password", "wrong");
        assertEquals(403, request("POST", "/auth/face/me/test", aliceToken, payload).path("code").asInt());
        payload.put("password", PASSWORD);
        JsonNode same = request("POST", "/auth/face/me/test", aliceToken, payload).path("data");
        assertTrue(same.path("matched").asBoolean()); assertEquals(0.97, same.path("similarity").asDouble());
        assertEquals(props.getSimilarityThreshold(), same.path("threshold").asDouble());
        assertFalse(same.has("token")); assertFalse(same.has("image_id"));
        payload.put("imageBase64", bobImage); payload.put("userId", 2);
        JsonNode different = request("POST", "/auth/face/me/test", aliceToken, payload).path("data");
        assertFalse(different.path("matched").asBoolean()); assertEquals(0.11, different.path("similarity").asDouble());
        assertEquals(before, store.find(1)); assertNull(store.find(2)); assertEquals(1, examples.size());
        assertEquals("/api/v1/recognition/faces/" + before.imageId() + "/verify", lastVerifyPath.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mes_face_audit WHERE event_type='TEST_SCORE'", Integer.class));
        for (var entry : sessions.entrySet()) if (!entry.getKey().contains("mes:face:attempt:")) assertEquals(entry.getValue(), redisValues.get(entry.getKey()));
        assertEquals(sessions.keySet().stream().filter(key -> !key.contains("mes:face:attempt:")).count(), redisValues.keySet().stream().filter(key -> !key.contains("mes:face:attempt:")).count());
    }

    @Test void malformedSimilarityCannotBecomeAnAcceptedIdentity() throws Exception {
        enroll(aliceToken, aliceImage);
        props.setMaxFailures(20);
        for (JsonNode bad : List.of(json.getNodeFactory().booleanNode(true), json.getNodeFactory().textNode("1.0"),
                json.getNodeFactory().nullNode(), json.getNodeFactory().numberNode(-0.01), json.getNodeFactory().numberNode(1.01),
                json.getNodeFactory().missingNode())) {
            overrideSimilarity.set(bad);
            JsonNode response = login("fiction_alice", aliceImage);
            assertEquals(503, response.path("code").asInt()); assertFalse(response.path("data").has("token"));
        }
        overrideSimilarity.set(null);
        assertEquals(401, login("fiction_alice", bobImage).path("code").asInt());
        assertEquals(200, login("fiction_alice", aliceImage).path("code").asInt());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM mes_face_audit WHERE event_type='LOGIN_SCORE'", Integer.class));
        String scores = jdbc.queryForList("SELECT result_code FROM mes_face_audit WHERE event_type='LOGIN_SCORE'").toString();
        assertTrue(scores.contains("S=0.11000;T=0.85000")); assertTrue(scores.contains("S=0.97000;T=0.85000"));
        assertTrue(scores.contains(";R1"));
    }

    @Test void livePreviewChecksQualityWithoutSavingFacesOrConsumingLoginAttempts() throws Exception {
        faceCount.set(0);
        for (int i = 0; i < 7; i++) {
            JsonNode result = preview(aliceImage);
            assertEquals(200, result.path("code").asInt()); assertFalse(result.path("data").path("ready").asBoolean());
        }
        faceCount.set(2); assertFalse(preview(aliceImage).path("data").path("ready").asBoolean());
        faceCount.set(1); assertFalse(preview(generatedImage(1, true)).path("data").path("ready").asBoolean());
        detectStatus.set(400); assertFalse(preview(aliceImage).path("data").path("ready").asBoolean());
        detectStatus.set(200);
        JsonNode ready = preview(aliceImage).path("data");
        assertTrue(ready.path("ready").asBoolean());
        assertTrue(ready.path("box").path("width").asDouble() > 0);
        assertTrue(ready.path("box").path("width").asDouble() <= 1);
        assertFalse(ready.toString().contains("subject")); assertFalse(ready.toString().contains("test-detection-secret"));
        assertTrue(examples.isEmpty()); assertNull(store.find(1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM mes_face_audit", Integer.class));
        assertTrue(redisValues.keySet().stream().noneMatch(key -> key.contains("attempt:login-") || key.contains("attempt:manage:")));
        assertEquals(200, enroll(aliceToken, aliceImage).path("code").asInt());
        assertEquals(200, login("fiction_alice", aliceImage).path("code").asInt());
    }

    @Test void livePreviewRequiresCurrentConsentAndHasAnIndependentIpLimit() throws Exception {
        assertEquals(400, request("POST", "/auth/face/preview", null, Map.of("imageBase64", aliceImage, "consent", false)).path("code").asInt());
        assertEquals(400, request("POST", "/auth/face/preview", null, Map.of("imageBase64", aliceImage, "consent", true, "consentVersion", "face-demo-2026-11-v1")).path("code").asInt());
        assertEquals(400, request("POST", "/auth/face/preview", null, Map.of("imageBase64", aliceImage, "consent", true, "consentVersion", "face-demo-2026-11-v2")).path("code").asInt());
        assertTrue(redisValues.keySet().stream().noneMatch(key -> key.contains("preview-ip")));
        for (int i = 0; i < 120; i++) assertEquals(200, preview("invalid-image").path("code").asInt());
        assertEquals(429, preview("invalid-image").path("code").asInt());
        assertEquals(200, enroll(aliceToken, aliceImage).path("code").asInt());
        assertEquals(200, login("fiction_alice", aliceImage).path("code").asInt());
    }

    @Test void livePreviewBoundsConcurrencyAndReleasesCapacityAfterUpstreamErrors() throws Exception {
        var slots = (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(service, "previewSlots");
        assertNotNull(slots); slots.acquire(2);
        try { assertEquals(503, preview(aliceImage).path("code").asInt()); }
        finally { slots.release(2); }
        detectStatus.set(401);
        JsonNode failed = preview(aliceImage);
        assertEquals(503, failed.path("code").asInt()); assertFalse(failed.toString().contains("test secret"));
        detectStatus.set(200); assertTrue(preview(aliceImage).path("data").path("ready").asBoolean());
        assertEquals(2, slots.availablePermits());
        props.setEnabled(false); assertEquals(503, preview(aliceImage).path("code").asInt());
    }

    @Test void twoAccountsVerifyOnlyOwnTemplateAndShareNormalLogout() throws Exception {
        assertEquals(200, enroll(aliceToken, aliceImage).path("code").asInt());
        assertEquals(200, enroll(bobToken, bobImage).path("code").asInt());
        assertEquals(401, login("fiction_alice", bobImage).path("code").asInt());
        JsonNode success = login("fiction_alice", aliceImage); assertEquals(200, success.path("code").asInt(), success.toString());
        assertEquals("/api/v1/recognition/faces/" + store.find(1).imageId() + "/verify", lastVerifyPath.get());
        String token = success.path("data").path("token").asText();
        assertEquals(1, request("GET", "/auth/userInfo", token, null).path("data").path("userId").asInt());
        assertEquals(200, request("POST", "/auth/logout", token, null).path("code").asInt());
        assertEquals(401, request("GET", "/auth/face/me", token, null).path("code").asInt());
        assertEquals(401, request("GET", "/auth/userInfo", token, null).path("code").asInt());
        assertEquals(200, login("fiction_bob", bobImage).path("code").asInt());
        assertFalse(passwordLogin("fiction_alice").isBlank());
    }
    @Test void rejectsNoFaceMultipleFacesBlurAndMissingConsent() throws Exception {
        faceCount.set(0); assertEquals(422, enroll(aliceToken, aliceImage).path("code").asInt());
        faceCount.set(2); assertEquals(422, enroll(aliceToken, aliceImage).path("code").asInt());
        faceCount.set(1); assertEquals(422, enroll(aliceToken, generatedImage(1, true)).path("code").asInt());
        assertEquals(400, request("PUT", "/auth/face/me", aliceToken, Map.of("password", PASSWORD, "imageBase64", aliceImage, "consent", false)).path("code").asInt());
        assertTrue(examples.isEmpty()); assertNull(store.find(1));
    }
    @Test void protectsSelfEnrollmentAndOtherUsersMetadata() throws Exception {
        assertEquals(401, request("GET", "/auth/face/me", null, null).path("code").asInt());
        assertEquals(403, request("GET", "/auth/face/users/2", aliceToken, null).path("code").asInt());
        assertEquals(403, request("DELETE", "/auth/face/users/2", aliceToken, Map.of("password", PASSWORD)).path("code").asInt());
        assertEquals(403, request("PUT", "/auth/face/me", aliceToken, Map.of("password", "wrong", "imageBase64", aliceImage, "consent", true, "consentVersion", FaceAuthService.CONSENT_VERSION)).path("code").asInt());
        assertTrue(examples.isEmpty());
        JsonNode capabilities = request("GET", "/auth/face/capabilities", null, null);
        assertFalse(capabilities.toString().contains("test-recognition-secret")); assertFalse(capabilities.path("data").path("livenessAvailable").asBoolean());
    }
    @Test void updateSwitchesTemplateAndDeleteRevokesBothRemoteAndLocal() throws Exception {
        enroll(aliceToken, aliceImage); String previous = store.find(1).imageId();
        assertEquals(200, enroll(aliceToken, bobImage).path("code").asInt());
        assertFalse(examples.containsKey(previous)); assertEquals(1, examples.size());
        assertEquals(401, login("fiction_alice", aliceImage).path("code").asInt());
        assertEquals(200, login("fiction_alice", bobImage).path("code").asInt());
        JsonNode deleted = request("DELETE", "/auth/face/users/1", adminToken, Map.of("password", PASSWORD));
        assertEquals(200, deleted.path("code").asInt()); assertEquals(0, deleted.path("data").path("pendingCleanup").asInt());
        assertTrue(examples.isEmpty()); assertEquals("NONE", store.find(1).state());
        assertEquals(401, login("fiction_alice", bobImage).path("code").asInt());
        assertFalse(passwordLogin("fiction_alice").isBlank());
    }
    @Test void deletionFailureIsDurableAndNeverAllowsLogin() throws Exception {
        enroll(aliceToken, aliceImage); deleteStatus.set(503);
        JsonNode result = request("DELETE", "/auth/face/me", aliceToken, Map.of("password", PASSWORD));
        assertEquals(1, result.path("data").path("pendingCleanup").asInt()); assertEquals(1, examples.size());
        assertEquals(401, login("fiction_alice", aliceImage).path("code").asInt());
        deleteStatus.set(200); jdbc.update("UPDATE mes_face_cleanup SET next_attempt_at=?", java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)));
        service.recoverAndCleanup(); assertEquals(0, store.pending(1)); assertTrue(examples.isEmpty());
    }
    @Test void failedReplacementRetainsOriginalAndTracksUncertainUpload() throws Exception {
        enroll(aliceToken, aliceImage); String old = store.find(1).imageId(); enrollStatus.set(503);
        assertEquals(503, enroll(aliceToken, bobImage).path("code").asInt());
        assertEquals(old, store.find(1).imageId()); assertEquals("ACTIVE", store.find(1).state()); assertEquals(1, store.pending(1));
        assertEquals(200, login("fiction_alice", aliceImage).path("code").asInt());
        assertEquals(2, examples.size()); jdbc.update("UPDATE mes_face_cleanup SET next_attempt_at=?", java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)));
        service.recoverAndCleanup(); assertEquals(1, examples.size()); assertTrue(examples.containsKey(old));
    }
    @Test void interruptedEnrollmentCanRecoverAndDuplicateEnrollmentIsRejected() throws Exception {
        String subject = "mes-1-" + props.serviceScope() + "-" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(store.beginEnrollment(1, subject)); assertFalse(store.beginEnrollment(1, "mes-1-" + props.serviceScope() + "-" + "a".repeat(32)));
        client.enroll(subject, new FaceImageQuality(props).decode(aliceImage).jpeg());
        jdbc.update("UPDATE mes_face_binding SET updated_at=?", java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(6)));
        service.recoverAndCleanup(); assertEquals("NONE", store.find(1).state()); assertEquals(1, store.pending(1));
        jdbc.update("UPDATE mes_face_cleanup SET next_attempt_at=?", java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)));
        service.recoverAndCleanup(); assertTrue(examples.isEmpty());
    }
    @Test void deletedBusinessAccountIsRevokedAndCleanedWithoutChangingOtherAccounts() throws Exception {
        enroll(aliceToken, aliceImage); enroll(bobToken, bobImage);
        usersById.remove(1L); jdbc.update("DELETE FROM `user` WHERE user_id=1"); service.recoverAndCleanup();
        assertEquals("NONE", store.find(1).state()); assertEquals(1, examples.size());
        assertEquals(200, login("fiction_bob", bobImage).path("code").asInt());
    }
    @Test void nonceReplayExpiryAndFailureLimitAreEnforced() throws Exception {
        enroll(aliceToken, aliceImage); String once = challenge("fiction_alice");
        assertEquals(200, login("fiction_alice", aliceImage, once).path("code").asInt());
        assertEquals(401, login("fiction_alice", aliceImage, once).path("code").asInt());
        String expired = challenge("fiction_alice"); redisValues.remove("mes:face:challenge:" + expired);
        assertEquals(401, login("fiction_alice", aliceImage, expired).path("code").asInt());
        for (int i = 0; i < 3; i++) assertEquals(401, login("fiction_alice", bobImage).path("code").asInt());
        assertEquals(429, login("fiction_alice", aliceImage).path("code").asInt());
        assertFalse(passwordLogin("fiction_alice").isBlank());
    }
    @Test void staleRolesDisabledUsersAndDisabledDemoCannotAuthenticate() throws Exception {
        enroll(aliceToken, aliceImage);
        usersById.get(1L).setStatus(0); assertEquals(401, login("fiction_alice", aliceImage).path("code").asInt());
        assertEquals(401, request("GET", "/auth/face/me", aliceToken, null).path("code").asInt());
        usersById.get(1L).setStatus(1); usersById.get(1L).setRoleId(2L);
        assertEquals(401, request("GET", "/auth/face/me", aliceToken, null).path("code").asInt());
        props.setAllowDemoLogin(false); assertEquals(403, request("POST", "/auth/face/challenge", null, Map.of("username", "fiction_alice")).path("code").asInt());
    }
    @Test void responseAndAuditNeverContainImagesPasswordsKeysOrRawErrors() throws Exception {
        detectStatus.set(503); String error = enroll(aliceToken, aliceImage).toString();
        assertFalse(error.contains("secret upstream")); assertFalse(error.contains(aliceImage.substring(0, 80))); assertFalse(error.contains(PASSWORD));
        String status = request("GET", "/auth/face/me", aliceToken, null).toString();
        assertFalse(status.contains("subject_id")); assertFalse(status.contains("image_id"));
        String audit = jdbc.queryForList("SELECT * FROM mes_face_audit").toString(); assertFalse(audit.contains(PASSWORD)); assertFalse(audit.contains("test-recognition-secret"));
        var response = mvc.perform(get("/auth/face/capabilities")).andReturn().getResponse(); assertEquals("no-store", response.getHeader("Cache-Control"));
        var large = mvc.perform(post("/auth/face/login").contentType("application/json").content("x".repeat(FaceImageQuality.MAX_JSON_BYTES + 1))).andReturn().getResponse(); assertEquals(413, large.getStatus());
    }
    @Test void malformedAndMissingImagesFailClosedWithoutTouchingCollection() throws Exception {
        assertEquals(422, enroll(aliceToken, "not-base64").path("code").asInt());
        assertEquals(422, enroll(aliceToken, Base64.getEncoder().encodeToString("not an image".getBytes(StandardCharsets.UTF_8))).path("code").asInt());
        assertTrue(examples.isEmpty());
    }
    @Test void changedServiceCannotTreatAnotherCollections404AsSuccessfulDeletion() throws Exception {
        enroll(aliceToken, aliceImage);
        props.setRecognitionApiKey("different-service-test-key");
        assertTrue(request("GET", "/auth/face/me", aliceToken, null).path("data").path("serviceMismatch").asBoolean());
        assertEquals(503, login("fiction_alice", aliceImage).path("code").asInt());
        JsonNode revoked = request("DELETE", "/auth/face/me", aliceToken, Map.of("password", PASSWORD));
        assertEquals(1, revoked.path("data").path("pendingCleanup").asInt());
        assertEquals(1, examples.size()); assertEquals("NONE", store.find(1).state());
        props.setRecognitionApiKey("test-recognition-secret");
        jdbc.update("UPDATE mes_face_cleanup SET next_attempt_at=?", java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)));
        service.recoverAndCleanup(); assertEquals(0, store.pending(1)); assertTrue(examples.isEmpty());
    }
}
