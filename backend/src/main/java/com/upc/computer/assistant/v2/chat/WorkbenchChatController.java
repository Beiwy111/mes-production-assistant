package com.upc.computer.assistant.v2.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.upc.computer.assistant.v2.security.AssistantSessionResolver;
import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.common.Result;
import com.upc.computer.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/assistant-v2/chat")
public class WorkbenchChatController {
    private final AssistantSessionResolver sessions;
    private final WorkbenchModelChat chat;

    public WorkbenchChatController(JwtUtil jwt, AuthService auth, WorkbenchModelChat chat) {
        this.sessions = new AssistantSessionResolver(jwt, auth);
        this.chat = chat;
    }

    @ModelAttribute
    public void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestHeader(name = "Authorization", required = false) String header) {
        return Result.success(chat.status(sessions.requireUserId(header)));
    }

    @PostMapping("/turn")
    public Result<Map<String, Object>> turn(@RequestHeader(name = "Authorization", required = false) String header,
                                             @RequestBody JsonNode body) {
        return Result.success(chat.turn(sessions.requireUserId(header), body));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> failure(Exception failure) {
        if (failure instanceof BusinessException business) return Result.fail(business.getCode(), business.getMessage());
        if (failure instanceof ResponseStatusException businessRead) {
            int code = businessRead.getStatusCode().value();
            return Result.fail(code, code == 403 ? "当前账号的业务权限不足或已变化" : "MES 无法按本次模型的查询计划读取业务数据");
        }
        if (failure instanceof org.springframework.http.converter.HttpMessageNotReadableException) return Result.fail(400, "模型问答请求格式无效");
        return Result.fail(503, "模型问答暂时不可用，请检查后端服务");
    }
}
