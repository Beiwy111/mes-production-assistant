package com.upc.computer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.common.Result;
import com.upc.computer.service.AuthService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** The private shared dataset is administered with a current MES session. The browser never sees the writer credential. */
@RestController
@RequestMapping("/assistant-v2/knowledge/ingestion")
public class WorkbenchIngestionController {
    private static final String ENDPOINT = "http://127.0.0.1:18081/internal/ingestion";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final JwtUtil jwt;
    private final AuthService auth;

    public WorkbenchIngestionController(ObjectMapper json, JwtUtil jwt, AuthService auth) {
        this.json = json;
        this.jwt = jwt;
        this.auth = auth;
    }

    private void requireAdmin(String authorization) {
        var token = jwt.extractTokenFromHeader(authorization);
        var session = token != null && jwt.validateToken(token) ? auth.getLoginSession(token) : null;
        if (session == null) throw new BusinessException(401, "请登录后查看入库资料。");
        if (!"ADMIN".equalsIgnoreCase(session.getRoleCode())) throw new BusinessException(403, "此资料库仅向管理员开放。");
    }

    private String credential() {
        String key = System.getenv("MES_KNOWLEDGE_INGESTION_API_KEY");
        if (key != null && !key.isBlank()) return key;
        String prefix = "MES_KNOWLEDGE_INGESTION_API_KEY=";
        for (String base : new String[]{"assistant/deploy", "backend/assistant/deploy"}) {
            Path path = Path.of(base, ".env.ingestion.local");
            if (!Files.isRegularFile(path)) continue;
            try (var lines = Files.lines(path)) {
                return lines.map(String::strip).filter(line -> line.startsWith(prefix))
                        .map(line -> line.substring(prefix.length()).strip()).filter(value -> !value.isEmpty())
                        .findFirst().orElseThrow(() -> new BusinessException(503, "知识入库凭据未配置。"));
            } catch (IOException e) { break; }
        }
        throw new BusinessException(503, "请先初始化本机知识入库服务。");
    }

    private String jobPath(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new BusinessException(400, "文件任务编号无效。");
        return "/jobs/" + id;
    }

    private byte[] call(String method, String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(ENDPOINT + path)).timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + credential());
            var content = HttpRequest.BodyPublishers.noBody();
            if (body != null) {
                content = HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
                builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            }
            var res = http.send(builder.method(method, content).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 == 2) return res.body();
            throw switch (res.statusCode()) {
                case 404 -> new BusinessException(404, "任务或原文件不存在，请刷新后重试。");
                case 409 -> new BusinessException(409, "文件状态已变化，请刷新后重试。");
                case 413, 422, 400 -> new BusinessException(400, "文件、查询或格式不符合入库要求。");
                case 429 -> new BusinessException(429, "处理队列繁忙，请稍后重试。");
                default -> new BusinessException(503, "入库服务暂不可用，请检查本机知识服务。");
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "请求已中断。");
        } catch (IOException e) {
            throw new BusinessException(503, "连接知识入库服务失败。");
        }
    }

    private Result<JsonNode> result(String method, String path, Object body) {
        try { return Result.success(json.readTree(call(method, path, body))); }
        catch (IOException e) { throw new BusinessException(502, "知识服务返回的数据无法读取。"); }
    }

    @GetMapping("/jobs")
    public Result<JsonNode> list(@RequestHeader(value="Authorization", required=false) String authorization) {
        requireAdmin(authorization);
        return result("GET", "/jobs", null);
    }

    @PostMapping(value="/jobs", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<JsonNode> upload(@RequestHeader(value="Authorization", required=false) String authorization,
                                   @RequestParam MultipartFile file) throws IOException {
        requireAdmin(authorization);
        if (file.isEmpty() || file.getSize() > 10L * 1024 * 1024)
            throw new BusinessException(400, "请选择非空文件，每份资料最多 10 MiB。");
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 200 || name.matches("(?s).*[\\p{Cntrl}/\\\\].*"))
            throw new BusinessException(400, "文件名无效。");
        return result("POST", "/jobs", Map.of("filename", name, "content_base64", Base64.getEncoder().encodeToString(file.getBytes())));
    }

    @GetMapping("/jobs/{id}")
    public Result<JsonNode> details(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable String id) {
        requireAdmin(authorization);
        return result("GET", jobPath(id), null);
    }

    @PostMapping("/jobs/{id}/retry")
    public Result<JsonNode> retry(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable String id) {
        requireAdmin(authorization);
        return result("POST", jobPath(id) + "/retry", null);
    }

    @DeleteMapping("/jobs/{id}")
    public Result<JsonNode> remove(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable String id) {
        requireAdmin(authorization);
        return result("DELETE", jobPath(id), null);
    }

    @DeleteMapping("/jobs/{id}/document")
    public Result<JsonNode> removePublishedDocument(@RequestHeader(value="Authorization", required=false) String authorization,
                                                     @PathVariable String id) {
        requireAdmin(authorization);
        return result("DELETE", jobPath(id) + "/document", null);
    }

    @GetMapping("/jobs/{id}/source")
    public ResponseEntity<byte[]> source(@RequestHeader(value="Authorization", required=false) String authorization, @PathVariable String id) {
        requireAdmin(authorization);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff").body(call("GET", jobPath(id) + "/source", null));
    }

    public record Search(String query, String jobId, Integer top_k) {}

    @PostMapping("/search")
    public Result<JsonNode> search(@RequestHeader(value="Authorization", required=false) String authorization, @RequestBody Search request) {
        requireAdmin(authorization);
        if (request == null || request.query() == null || request.query().isBlank() || request.query().length() > 200)
            throw new BusinessException(400, "请输入 1 至 200 字的检索词。");
        if (request.jobId() != null) jobPath(request.jobId());
        int count = request.top_k() == null ? 6 : request.top_k();
        if (count < 1 || count > 8) throw new BusinessException(400, "单次检索可返回 1 至 8 个片段。");
        return result("POST", "/search", request.jobId() == null ? Map.of("query",request.query(),"top_k",count)
                : Map.of("query",request.query(),"top_k",count,"jobId",request.jobId()));
    }
}
