package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.model.AssistantRequest;
import com.upc.computer.common.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 有界单实例接入记录，不是对话记忆或工作流引擎；重启后不保留。 */
public class AssistantRunRegistry {
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Duration retention;
    private final Clock clock;
    public AssistantRunRegistry(Duration retention, Clock clock) { this.retention = retention; this.clock = clock; }
    public record Registration(Run run, boolean execute) { }

    public synchronized Registration open(long owner, AssistantRequest input) {
        prune();
        for (Run run : runs.values()) {
            if (run.owner != owner || !run.input.requestId().equals(input.requestId())) continue;
            if (!run.input.equals(input)) throw new BusinessException(409, "同一请求编号不能用于不同内容");
            if (run.status().equals("SUCCEEDED")) return new Registration(run, false);
            throw new BusinessException(409, "该请求已受理，请查询运行状态或使用新的请求编号");
        }
        long active = runs.values().stream().filter(r -> r.status().equals("RUNNING")).count();
        boolean busy = runs.values().stream().anyMatch(r -> r.owner == owner && r.status().equals("RUNNING"));
        if (busy || active >= 4 || runs.size() >= 1000) throw new BusinessException(429, "助手请求较多，请等待当前请求完成后再试");
        Run run = new Run(owner, input, clock.instant());
        runs.put(run.id, run);
        return new Registration(run, true);
    }

    public synchronized Run require(long owner, String id) {
        prune();
        Run run = runs.get(id);
        if (run == null || run.owner != owner) throw new BusinessException(404, "运行不存在或已过期");
        return run;
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(retention);
        runs.values().removeIf(r -> r.created.isBefore(cutoff) && !r.status().equals("RUNNING"));
    }

    public static final class Run {
        private final String id = UUID.randomUUID().toString();
        private final long owner;
        private final AssistantRequest input;
        private final Instant created;
        private String status = "RUNNING";
        private String answer;
        private Runnable cancellation;
        private Run(long owner, AssistantRequest input, Instant created) { this.owner = owner; this.input = input; this.created = created; }
        public String id() { return id; }
        public AssistantRequest input() { return input; }
        public String upstreamUser() { return "mes-user-" + owner; }
        public synchronized String status() { return status; }
        public synchronized String answer() { return answer; }
        public synchronized void succeed(String value) {
            if (!status.equals("RUNNING")) throw new BusinessException(409, "请求已取消或结束");
            answer = value; status = "SUCCEEDED"; cancellation = null;
        }
        public synchronized void fail() {
            if (status.equals("RUNNING")) status = "FAILED";
            cancellation = null;
        }
        public void bindCancellation(Runnable action) {
            boolean cancelled;
            synchronized (this) {
                cancelled = status.equals("CANCELLED");
                if (status.equals("RUNNING")) cancellation = action;
            }
            if (cancelled) action.run();
        }
        public void cancel() {
            Runnable action;
            synchronized (this) {
                if (!status.equals("RUNNING")) return;
                status = "CANCELLED"; action = cancellation; cancellation = null;
            }
            if (action != null) action.run();
        }
        public synchronized Map<String, Object> view() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", id); result.put("requestId", input.requestId());
            result.put("sessionId", input.sessionId()); result.put("status", status);
            result.put("createdAt", created.toString());
            if (status.equals("SUCCEEDED")) result.put("reply", answer);
            return result;
        }
    }
}
