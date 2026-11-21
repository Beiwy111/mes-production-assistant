package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.model.AssistantRequest;
import com.upc.computer.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AssistantRunRegistryTest {
    private final AssistantRunRegistry registry = new AssistantRunRegistry(Duration.ofHours(24), Clock.systemUTC());
    private AssistantRequest input(String text) { return AssistantRequest.from(Map.of("requestId", "r1", "sessionId", "s1", "text", text)); }

    @Test void recordsAreOwnedByServerUserNotClientSession() {
        var run = registry.open(7L, input("hello")).run();
        assertThatThrownBy(() -> registry.require(8L, run.id())).isInstanceOf(BusinessException.class);
        assertThat(registry.open(8L, input("hello")).run().id()).isNotEqualTo(run.id());
    }
    @Test void duplicateDoesNotTriggerAnotherExecutionAndChangedPayloadConflicts() {
        var run = registry.open(7L, input("hello")).run();
        assertThatThrownBy(() -> registry.open(7L, input("hello"))).isInstanceOf(BusinessException.class);
        run.succeed("answer");
        var replay = registry.open(7L, input("hello"));
        assertThat(replay.execute()).isFalse();
        assertThat(replay.run().answer()).isEqualTo("answer");
        assertThatThrownBy(() -> registry.open(7L, input("different"))).isInstanceOf(BusinessException.class);
    }
    @Test void cancellationBeforeHttpRegistrationStillCancelsOnceAndCannotBecomeSuccess() {
        var run = registry.open(7L, input("hello")).run();
        run.cancel();
        AtomicInteger cancelled = new AtomicInteger();
        run.bindCancellation(cancelled::incrementAndGet);
        run.cancel();
        assertThat(cancelled).hasValue(1);
        assertThatThrownBy(() -> run.succeed("late answer")).isInstanceOf(BusinessException.class);
        assertThat(run.view()).containsEntry("status", "CANCELLED").doesNotContainKey("reply");
    }
    @Test void finishedRunCannotBeCancelledAndUserHasOneActiveRequest() {
        var run = registry.open(7L, input("hello")).run();
        var other = AssistantRequest.from(Map.of("requestId", "r2", "text", "other"));
        assertThatThrownBy(() -> registry.open(7L, other)).isInstanceOf(BusinessException.class);
        run.succeed("answer"); run.cancel();
        assertThat(run.view()).containsEntry("status", "SUCCEEDED");
        assertThat(registry.open(7L, other).execute()).isTrue();
    }
    @Test void replayWithOmittedSessionIdUsesTheSameRequestIdentity() {
        var body = Map.<String, Object>of("requestId", "r1", "text", "hello");
        var run = registry.open(7L, AssistantRequest.from(body)).run();
        run.succeed("answer");
        assertThat(registry.open(7L, AssistantRequest.from(body)).execute()).isFalse();
    }
}
