package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Local bounded metadata audit. Never stores prompts, answers, tool arguments or business rows.
 * A single coalescing writer keeps disk IO off the SSE thread. Not a workflow recovery engine. */
public final class RunAuditStore implements AutoCloseable {
    private final ConversationScopeStore disk;
    private final Clock clock;
    private final ExecutorService writer;
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private final Set<String> pending=new LinkedHashSet<>();
    private String writing;
    private boolean draining,closed;
    private static final Set<TaskEventStream.Type> SKIPPED=Set.of(TaskEventStream.Type.heartbeat,TaskEventStream.Type.message_delta,TaskEventStream.Type.message_started);
    private RunAuditStore(){disk=null;clock=Clock.systemUTC();writer=null;}
    public static RunAuditStore disabled(){return new RunAuditStore();}
    public RunAuditStore(Path root,Clock clock,String secret)throws IOException {
        this.clock=clock;disk=new ConversationScopeStore(root,"audit",clock,secret);
        writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"assistant-audit-writer");t.setDaemon(true);return t;});
    }
    public synchronized void record(long owner,TaskEventStream.Event event) {
        if(disk==null||closed||owner<=0||event==null||SKIPPED.contains(event.type())||!validRef(event.task_id()))return;
        Entry entry=entries.get(event.task_id());
        if(entry==null) {
            // Completed failures may be evicted so a recovered disk can accept new runs.
            // Never evict a snapshot currently being written, pending, or still executing.
            if(entries.size()>=128)entries.entrySet().removeIf(e->!e.getValue().status.equals("RUNNING")&&!pending.contains(e.getKey())&&!e.getKey().equals(writing)&&(e.getValue().persisted==e.getValue().revision||e.getValue().failed));
            if(entries.size()>=128)return;
            entry=new Entry(owner,event.conversation_id(),clock.instant().plus(java.time.Duration.ofDays(7)));entries.put(event.task_id(),entry);
        }
        if(entry.owner!=owner||!entry.session.equals(event.conversation_id())||event.sequence()<=entry.sequence)return;
        entry.sequence=event.sequence();
        if(entry.events.size()<256) {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("eventId",event.event_id());row.put("sequence",event.sequence());row.put("timestamp",event.timestamp());row.put("type",event.type().name());
            safe(row,"agentId",event.agent_id(),"SUP|ORDER|SUPPLY|PRODUCTION|QUALITY|EQUIPMENT|AFTERSALES");
            safe(row,"toolCallId",event.tool_call_id(),"[a-f0-9-]{36}");
            JsonNode observation=event.payload().path("observation");
            safe(row,"toolId",observation.path("toolId").asText(),"[A-Z0-9_]{1,40}");
            for(String key:List.of("queryRef","taskId","stepId"))safe(row,key,observation.path(key).asText(),"[a-f0-9-]{36}");
            safe(row,"status",observation.path("status").asText(),"RUNNING|COMPLETED|FAILED|CANCELLED|SKIPPED|WAITING");
            safe(row,"verdict",event.payload().path("legacy").path("data").path("verdict").asText(),"[A-Z_]{1,40}");
            safe(row,"errorCode",event.payload().path("code").asText(),"[A-Z_]{1,60}");
            if(event.payload().path("legacy").path("invalidated").asBoolean())row.put("evidenceInvalidated",true);
            entry.events.add(Map.copyOf(row));
        }else entry.truncated=true;
        entry.updatedAt=clock.instant().toString();queue(event.task_id(),entry);
    }
    public synchronized void finish(String run,long owner,String status) {
        Entry entry=entries.get(run);
        if(closed||entry==null||entry.owner!=owner||!Set.of("COMPLETED","FAILED","CANCELLED").contains(status))return;
        if(!entry.status.equals("RUNNING")&&!entry.status.equals(status))return;
        entry.status=status;entry.updatedAt=clock.instant().toString();queue(run,entry);
    }
    public Map<String,Object> load(String run,long owner) {
        if(disk==null||owner<=0||!validRef(run))return null;
        synchronized(this) {
            Entry entry=entries.get(run);
            if(entry!=null) {
                if(entry.owner!=owner||!entry.expiresAt.isAfter(clock.instant()))return null;
                var result=snapshot(run,entry);result.put("persistence",entry.failed?"FAILED":entry.persisted==entry.revision?"PERSISTED":"PENDING");
                result.put("executionState",entry.status);return result;
            }
        }
        var saved=disk.load(run,owner);if(saved==null)return null;
        try{if(!java.time.Instant.parse(String.valueOf(saved.get("expiresAt"))).isAfter(clock.instant()))return null;}
        catch(java.time.DateTimeException invalid){return null;}
        saved.put("persistence","PERSISTED");saved.put("executionState","RUNNING".equals(saved.get("status"))?"UNKNOWN_AFTER_RESTART":saved.get("status"));return saved;
    }
    private void queue(String run,Entry entry) {
        entry.revision++;pending.add(run);
        if(!draining){draining=true;writer.execute(this::drain);}
    }
    private void drain() {
        while(true) {
            String run;long owner,revision;Map<String,Object> payload;
            synchronized(this) {
                if(pending.isEmpty()){draining=false;return;}
                run=pending.iterator().next();pending.remove(run);writing=run;Entry entry=entries.get(run);
                owner=entry.owner;revision=entry.revision;payload=snapshot(run,entry);
            }
            boolean saved=disk.save(run,owner,payload);
            synchronized(this) {Entry entry=entries.get(run);entry.failed=!saved;if(saved)entry.persisted=revision;writing=null;}
            if(!saved)System.err.println("Assistant audit persistence unavailable; business execution continues.");
        }
    }
    private Map<String,Object> snapshot(String run,Entry entry) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("runId",run);result.put("sessionId",entry.session);
        result.put("status",entry.status);result.put("updatedAt",entry.updatedAt);result.put("events",List.copyOf(entry.events));
        result.put("expiresAt",entry.expiresAt.toString());
        result.put("truncated",entry.truncated);result.put("lastSequence",entry.sequence);result.put("retentionDays",7);return result;
    }
    private static void safe(Map<String,Object> row,String key,String value,String pattern){if(value!=null&&value.matches(pattern))row.put(key,value);}
    private static boolean validRef(String value){return value!=null&&value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    @Override public void close() {
        synchronized(this){closed=true;}
        if(writer!=null){writer.shutdown();try{if(!writer.awaitTermination(10,TimeUnit.SECONDS))System.err.println("Assistant audit flush timed out.");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    private static final class Entry {
        final long owner;final String session;final java.time.Instant expiresAt;final List<Map<String,Object>> events=new ArrayList<>();
        String status="RUNNING",updatedAt;long sequence,revision,persisted;boolean truncated,failed;
        Entry(long owner,String session,java.time.Instant expiresAt){this.owner=owner;this.session=session;this.expiresAt=expiresAt;}
    }
}
