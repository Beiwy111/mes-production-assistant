package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunAuditStoreTest {
    @TempDir Path root;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"),ZoneOffset.UTC);
    final String secret="test-only-audit-signing-key-123456";
    final String run=UUID.randomUUID().toString();
    final ObjectMapper json=new ObjectMapper();
    @Test void memoryAuditExpiresWithoutServiceRestart() throws Exception {
        var now=new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        Clock moving=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        try(var store=new RunAuditStore(root,moving,secret)) {
            store.record(8,event(1,TaskEventStream.Type.task_started));store.finish(run,8,"COMPLETED");
            assertNotNull(store.load(run,8));now.set(now.get().plus(Duration.ofDays(8)));assertNull(store.load(run,8));
        }
    }
    @Test void recoveredDiskAcceptsNewRunsAfterBoundedFailedAudits() throws Exception {
        try(var store=new RunAuditStore(root,clock,secret)) {
            Path directory=root.resolve("audit");Files.delete(directory);Files.writeString(directory,"unavailable directory");
            for(int i=0;i<129;i++) {
                String id=UUID.randomUUID().toString();var source=event(1,TaskEventStream.Type.task_started);
                store.record(8,new TaskEventStream.Event(source.event_id(),id,"chat",1,source.timestamp(),source.type(),"SUP",null,source.payload()));
                store.finish(id,8,"FAILED");awaitPersistence(store,id,"FAILED");
            }
            Files.delete(directory);Files.createDirectory(directory);
            store.record(8,event(1,TaskEventStream.Type.task_started));store.finish(run,8,"COMPLETED");
            awaitPersistence(store,run,"PERSISTED");assertTrue(Files.isRegularFile(directory.resolve(run+".json")));
        }
    }
    void awaitPersistence(RunAuditStore store,String id,String expected)throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) {
            var saved=store.load(id,8);if(saved!=null&&expected.equals(saved.get("persistence")))return;
            Thread.sleep(5);
        }
        fail("Audit did not reach persistence state "+expected);
    }
    TaskEventStream.Event event(int seq,TaskEventStream.Type type) {
        return new TaskEventStream.Event(UUID.randomUUID().toString(),run,"chat",seq,clock.instant().toString(),type,"SUP",null,
            json.valueToTree(Map.of("text","private answer","legacy",Map.of("password","secret","data",Map.of("verdict","EVIDENCE_READY")),
                "observation",Map.of("toolId","Q1","queryRef",UUID.randomUUID().toString(),"summary","private customer details"))));
    }
    @Test void durableOrderedMetadataIsOwnerScopedAndDoesNotPersistBusinessPayloads() throws Exception {
        try(var store=new RunAuditStore(root,clock,secret)) {
            store.record(8,event(1,TaskEventStream.Type.task_started));
            store.record(8,event(2,TaskEventStream.Type.tool_started));
            store.record(8,event(3,TaskEventStream.Type.message_delta));
            store.record(8,event(4,TaskEventStream.Type.tool_completed));
            store.finish(run,8,"COMPLETED");
            assertNull(store.load(run,9));
        }
        try(var restarted=new RunAuditStore(root,clock,secret)) {
            var saved=restarted.load(run,8);assertNotNull(saved);
            assertEquals("COMPLETED",saved.get("status"));
            assertEquals(3,((List<?>)saved.get("events")).size());
            assertEquals("PERSISTED",saved.get("persistence"));
            String contents=Files.readString(root.resolve("audit").resolve(run+".json"));
            for(String privateValue:List.of("private answer","private customer details","password","secret"))assertFalse(contents.contains(privateValue));
            assertNull(restarted.load("../elsewhere",8));assertNull(restarted.load(run,9));
        }
    }
    @Test void unfinishedRunAfterRestartIsNotShownAsStillExecutingOrCompleted() throws Exception {
        try(var store=new RunAuditStore(root,clock,secret)){store.record(8,event(1,TaskEventStream.Type.task_started));}
        try(var store=new RunAuditStore(root,clock,secret)) {
            var saved=store.load(run,8);assertEquals("RUNNING",saved.get("status"));
            assertEquals("UNKNOWN_AFTER_RESTART",saved.get("executionState"));
        }
    }
    @Test void tamperingExpiryAndSigningKeyChangeRejectStoredAudit() throws Exception {
        try(var store=new RunAuditStore(root,clock,secret)){store.record(8,event(1,TaskEventStream.Type.task_started));store.finish(run,8,"FAILED");}
        try(var store=new RunAuditStore(root,Clock.offset(clock,Duration.ofDays(8)),secret)){assertNull(store.load(run,8));}
        try(var store=new RunAuditStore(root,clock,secret+"changed")){assertNull(store.load(run,8));}
        Path file=root.resolve("audit").resolve(run+".json");Files.writeString(file,Files.readString(file).replace("FAILED","COMPLETED"));
        try(var store=new RunAuditStore(root,clock,secret)){assertNull(store.load(run,8));}
    }
    @Test void boundedHistoryDisclosesTruncationButKeepsActualTerminalStatus() throws Exception {
        try(var store=new RunAuditStore(root,clock,secret)) {
            for(int i=1;i<=300;i++)store.record(8,event(i,TaskEventStream.Type.agent_progress));
            store.finish(run,8,"CANCELLED");
            var saved=store.load(run,8);assertEquals(true,saved.get("truncated"));
            assertTrue(((List<?>)saved.get("events")).size()<=256);assertEquals("CANCELLED",saved.get("status"));
        }
    }
}
