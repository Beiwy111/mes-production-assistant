package com.upc.computer.assistant.v2.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.agent.DifyWorkflowClient;
import com.upc.computer.assistant.v2.model.AssistantRequest;
import com.upc.computer.assistant.v2.runtime.AssistantRunRegistry;
import com.upc.computer.assistant.v2.security.AssistantSessionResolver;
import com.upc.computer.common.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.Consumer;

/** 精简接入门面：认证、运行登记和协议转换；没有专家/业务规划。 */
public class AssistantFacade {
    public static final String RUN_ATTRIBUTE = AssistantFacade.class.getName() + ".run";
    private final DifyWorkflowClient dify;
    private final AssistantSessionResolver users;
    private final AssistantRunRegistry runs;
    private final ObjectMapper mapper;
    public AssistantFacade(DifyWorkflowClient dify, AssistantSessionResolver users, AssistantRunRegistry runs, ObjectMapper mapper) {
        this.dify = dify; this.users = users; this.runs = runs; this.mapper = mapper;
    }

    public Map<String, Object> interpret(Map<String, Object> body, String authorization) {
        var registration = prepare(body, authorization);
        return execute(registration, event -> {}, false);
    }

    public ResponseEntity<StreamingResponseBody> stream(Map<String, Object> body, String authorization) {
        var registration = prepare(body, authorization); // HTTP 流开始前完成认证与输入检查
        var run = registration.run();
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(RUN_ATTRIBUTE, run);
        }
        StreamingResponseBody response = output -> {
            Consumer<Map<String, Object>> emit = event -> write(output, event);
            try {
                emit.accept(Map.of("type", "run", "runId", run.id(), "requestId", run.input().requestId()));
                var result = execute(registration, emit, true);
                emit.accept(Map.of("type", "delta", "text", result.get("reply")));
                emit.accept(Map.of("type", "result", "data", result));
                emit.accept(Map.of("type", "done"));
            } catch (UncheckedIOException disconnected) {
                run.cancel();
                throw disconnected.getCause();
            } catch (Exception failure) {
                run.fail();
                String message = failure instanceof BusinessException business ? business.getMessage() : "新助手请求失败，请稍后重试";
                write(output, Map.of("type", "error", "runId", run.id(), "message", message));
            }
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(response);
    }

    public Map<String, Object> status(String id, String authorization) { return runs.require(users.requireUserId(authorization), id).view(); }
    public Map<String, Object> cancel(String id, String authorization) {
        var run = runs.require(users.requireUserId(authorization), id);
        run.cancel();
        return run.view();
    }

    private AssistantRunRegistry.Registration prepare(Map<String, Object> body, String authorization) {
        long user = users.requireUserId(authorization);
        return runs.open(user, AssistantRequest.from(body));
    }

    private Map<String, Object> execute(AssistantRunRegistry.Registration registration, Consumer<Map<String, Object>> events, boolean streamed) {
        var run = registration.run();
        try {
            if (registration.execute()) run.succeed(dify.execute(run.input(), run.upstreamUser(), events, run::bindCancellation));
            return Map.of("type", "answer", "reply", run.answer(), "sessionId", run.input().sessionId(),
                    "requestId", run.input().requestId(), "runId", run.id(), "provider", "dify", "streamed", streamed);
        } catch (UncheckedIOException disconnected) {
            run.cancel(); throw disconnected;
        } catch (BusinessException failure) {
            boolean cancelled = run.status().equals("CANCELLED");
            run.fail();
            if (cancelled) throw new BusinessException(409, "请求已取消，上游停止为尽力操作");
            throw failure;
        } catch (Exception failure) {
            run.fail(); throw new BusinessException(502, "新助手请求失败，请稍后重试");
        }
    }

    private void write(OutputStream output, Map<String, Object> event) {
        try { output.write(mapper.writeValueAsBytes(event)); output.write('\n'); output.flush(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
