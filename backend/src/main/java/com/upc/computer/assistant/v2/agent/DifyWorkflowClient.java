package com.upc.computer.assistant.v2.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.config.AssistantV2Properties;
import com.upc.computer.assistant.v2.model.AssistantRequest;
import com.upc.computer.common.BusinessException;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 仅传输 Workflow API；不选择专家、不计算业务事实、不重试有副作用的 POST。 */
public class DifyWorkflowClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper mapper;

    public DifyWorkflowClient(AssistantV2Properties settings, ObjectMapper mapper) {
        this.mapper = mapper;
        this.baseUrl = settings.getDify().getBaseUrl().replaceAll("/+$", "");
        this.apiKey = settings.getDify().getApiKey();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(settings.getDify().getConnectTimeout())
                .readTimeout(settings.getDify().getReadTimeout())
                .callTimeout(settings.getRuntime().getRunTimeout())
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }

    public String execute(AssistantRequest input, String user, Consumer<Map<String, Object>> events,
                          Consumer<Runnable> cancellation) {
        AtomicReference<String> taskId = new AtomicReference<>();
        AtomicReference<String> answer = new AtomicReference<>();
        AtomicBoolean stopSent = new AtomicBoolean();
        Runnable stopOnce = () -> {
            String id = taskId.get();
            if (id != null && stopSent.compareAndSet(false, true)) stop(id, user);
        };
        try {
            var inputs = Map.of("question", input.question(), "history", mapper.writeValueAsString(input.history()));
            Call call = http.newCall(post("/workflows/run", Map.of("inputs", inputs,
                    "response_mode", "streaming", "user", user)));
            cancellation.accept(() -> {
                call.cancel();
                stopOnce.run();
            });
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) throw unavailable();
                if (response.body() == null || response.body().contentType() == null
                        || !response.body().contentType().subtype().equals("event-stream")) throw incomplete();
                DifySseDecoder.read(new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)), frame -> {
                    try {
                        var event = mapper.readTree(frame);
                        String type = event.path("event").asText();
                        if (type.equals("workflow_started")) {
                            String id = event.path("task_id").asText();
                            if (id.matches("[A-Za-z0-9_-]{1,128}")) taskId.set(id);
                            if (call.isCanceled()) stopOnce.run();
                            events.accept(Map.of("type", "status", "status", "RUNNING"));
                        } else if (type.equals("error")) {
                            throw failed();
                        } else if (type.equals("workflow_finished")) {
                            if (!event.path("data").path("status").asText().equals("succeeded")) throw failed();
                            var output = event.path("data").path("outputs").path("answer");
                            if (!output.isTextual() || output.asText().isBlank() || output.asText().length() > 32000) throw incomplete();
                            answer.set(output.asText());
                            return false;
                        }
                        return true;
                    } catch (IOException invalidJson) { throw incomplete(); }
                });
            } catch (IOException failure) {
                stopOnce.run();
                throw unavailable();
            } catch (RuntimeException failure) {
                if (answer.get() == null) stopOnce.run();
                throw failure;
            }
            if (answer.get() == null) throw incomplete();
            return answer.get();
        } catch (IOException encodingFailure) {
            throw incomplete();
        }
    }

    private Request post(String path, Object payload) throws IOException {
        RequestBody encoded = RequestBody.create(mapper.writeValueAsBytes(payload), JSON);
        // retryOnConnectionFailure(false) 不禁止 503 + Retry-After:0 的协议级重放。
        // 标记单次请求体，确保工作流 POST 在任何自动 follow-up 中都不重发。
        RequestBody once = new RequestBody() {
            @Override public MediaType contentType() { return JSON; }
            @Override public long contentLength() throws IOException { return encoded.contentLength(); }
            @Override public void writeTo(okio.BufferedSink sink) throws IOException { encoded.writeTo(sink); }
            @Override public boolean isOneShot() { return true; }
        };
        return new Request.Builder().url(baseUrl + path).header("Authorization", "Bearer " + apiKey)
                .post(once).build();
    }

    private void stop(String taskId, String user) {
        // 只使用已解析的上游 taskId。停止失败不向浏览器泄露上游内容或伪称停止成功。
        try (Response ignored = http.newBuilder().callTimeout(Duration.ofSeconds(5)).build()
                .newCall(post("/workflows/tasks/" + taskId + "/stop", Map.of("user", user))).execute()) {
            // 最小接入阶段是尽力取消；本地取消不等于上游确认已停止。
        } catch (IOException ignored) { }
    }

    private static BusinessException unavailable() { return new BusinessException(502, "Dify 服务暂不可用，请检查配置或稍后重试"); }
    private static BusinessException incomplete() { return new BusinessException(502, "Dify 响应不完整或格式不符合约定"); }
    private static BusinessException failed() { return new BusinessException(502, "Dify 工作流未成功完成，请检查工作流运行记录"); }
}
