package com.upc.computer.controller;

import com.upc.computer.assistant.v2.runtime.KnowledgeEvidenceClient;
import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.common.Result;
import com.upc.computer.service.AuthService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/assistant-v2/knowledge")
public class WorkbenchKnowledgeController {
    private final AuthService auth;
    private final JwtUtil jwt;
    private final KnowledgeEvidenceClient knowledge;
    private final Semaphore slots = new Semaphore(3);

    public WorkbenchKnowledgeController(AuthService auth, JwtUtil jwt,
            @Value("${MES_KNOWLEDGE_API_KEY:}") String key) {
        this.auth = auth;
        this.jwt = jwt;
        this.knowledge = KnowledgeEvidenceClient.fromEnvironment(
                Map.of("MES_KNOWLEDGE_API_KEY", key.isBlank() ? localPublicKey() : key), Clock.systemUTC());
    }

    private static String localPublicKey() {
        for (var file : new String[]{"assistant/deploy/.env.knowledge.local", "backend/assistant/deploy/.env.knowledge.local"}) {
            var path = Path.of(file);
            if (!Files.isRegularFile(path)) continue;
            try (var lines = Files.lines(path)) {
                String prefix = "MES_KNOWLEDGE_API_KEY=";
                return lines.map(String::strip).filter(line -> line.startsWith(prefix))
                        .map(line -> line.substring(prefix.length()).strip()).findFirst().orElse("");
            } catch (IOException unavailable) { return ""; }
        }
        return "";
    }

    public record Search(String query) {}

    @GetMapping("/catalog")
    public ResponseEntity<Result<Map<String,Object>>> catalog(
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestParam(defaultValue="0") String offset, @RequestParam(defaultValue="100") String limit,
            @RequestParam(required=false) String releaseId) {
        requireSession(authorization);
        if (!slots.tryAcquire()) throw new BusinessException(429, "知识目录繁忙，请稍后重试。");
        try {
            return ResponseEntity.ok(Result.success(knowledge.catalog(Integer.parseInt(offset), Integer.parseInt(limit), releaseId)));
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(400, "知识目录分页参数无效。");
        } catch (KnowledgeEvidenceClient.CatalogChanged changed) {
            return ResponseEntity.status(409).body(Result.fail(409, "知识库版本已更新，请重新加载。"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "知识目录读取中断，请稍后重试。");
        } catch (IOException unavailable) {
            throw new BusinessException(503, "公开知识目录暂时不可用，请稍后重试。");
        } finally {
            slots.release();
        }
    }

    private void requireSession(String authorization) {
        var token = jwt.extractTokenFromHeader(authorization);
        if (token == null || !jwt.validateToken(token) || auth.getLoginSession(token) == null)
            throw new BusinessException(401, "请登录后查看知识库。");
    }

    @PostMapping("/explore")
    public Result<Map<String,Object>> explore(@RequestHeader(value="Authorization", required=false) String authorization,
                                             @RequestBody Search request) {
        requireSession(authorization);
        if (request == null || request.query() == null || request.query().isBlank() || request.query().length() > 200)
            throw new BusinessException(400, "请输入 1 至 200 字的检索词。");
        if (!slots.tryAcquire()) throw new BusinessException(429, "知识检索繁忙，请稍后重试。");
        try { return Result.success(knowledge.retrievePlanned(request.query().strip(), true)); }
        finally { slots.release(); }
    }
}
