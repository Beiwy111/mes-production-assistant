package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Versioned public event boundary. Input is already authorized and projected by the gateway. */
public final class TaskEventStream {
    enum Type {task_started,task_planned,task_completed,task_failed,task_cancelled,
        agent_started,agent_progress,agent_completed,agent_failed,
        tool_started,tool_completed,tool_failed,message_started,message_delta,message_completed,
        approval_required,heartbeat,stream_error}
    record Event(String event_id,String task_id,String conversation_id,long sequence,String timestamp,
                 Type type,String agent_id,String tool_call_id,JsonNode payload) {
        Event {if(event_id==null||task_id==null||conversation_id==null||sequence<1||timestamp==null||type==null||payload==null||!payload.isObject())throw new IllegalArgumentException("Invalid task event");}
    }
    private final ObjectMapper json=new ObjectMapper();
    private final String task,conversation;
    private final Clock clock;
    private final OutputStream output;
    private final java.util.function.Consumer<Event> observer;
    private long sequence,workflowSequence;
    private boolean terminal,messageStarted;
    private String messageSource="model_stream";
    private final Map<String,String> agentStates=new HashMap<>();
    public TaskEventStream(String task,String conversation,Clock clock,OutputStream output) {
        this(task,conversation,clock,output,event->{});
    }
    TaskEventStream(String task,String conversation,Clock clock,OutputStream output,java.util.function.Consumer<Event> observer) {
        this.task=task;this.conversation=conversation;this.clock=clock;this.output=output;
        this.observer=observer;
    }
    long sequence(){return sequence;}
    void heartbeat() throws IOException {send(Type.heartbeat,null,null,json.createObjectNode());}
    public synchronized void accept(JsonNode legacy) throws IOException {
        if(legacy==null||legacy.isNull())return;
        String type=legacy.path("type").asText();
        ObjectNode payload=json.createObjectNode();payload.set("legacy",legacy);
        switch(type) {
            case "run" -> send(Type.task_started,null,null,payload.put("status","running"));
            case "workflow" -> {
                if(legacy.path("invalidated").asBoolean()) {send(Type.agent_progress,"SUP",null,payload);return;}
                for(JsonNode item:legacy.path("events")) {
                    long seq=item.path("seq").asLong();if(seq<=workflowSequence)continue;workflowSequence=seq;
                    String kind=item.path("kind").asText(),status=item.path("status").asText();
                    boolean tool=item.hasNonNull("toolCallId");
                    Type eventType=switch(kind) {
                        case "PLAN_CONFIRMED" -> Type.task_planned;
                        case "AGENT_STARTED" -> Type.agent_started;
                        case "AGENT_COMPLETED" -> status.equals("FAILED")?Type.agent_failed:status.equals("COMPLETED")?Type.agent_completed:Type.agent_progress;
                        case "STEP_STARTED" -> tool?Type.tool_started:Type.agent_progress;
                        case "STEP_COMPLETED" -> tool?(status.equals("COMPLETED")?Type.tool_completed:Type.tool_failed):Type.agent_progress;
                        case "CANCELLED" -> Type.agent_progress;
                        default -> Type.agent_progress;
                    };
                    ObjectNode fragment=legacy.deepCopy();fragment.putArray("events").add(item);
                    ObjectNode data=json.createObjectNode();data.set("legacy",fragment);data.set("observation",item);
                    if(kind.equals("PLAN_CONFIRMED"))data.set("plan",legacy.path("plan"));
                    if(kind.equals("AGENT_STARTED")||kind.equals("AGENT_COMPLETED"))agentStates.put(item.path("agentId").asText(),status);
                    send(eventType,item.path("agentId").asText(),tool?item.path("toolCallId").asText():null,data);
                }
            }
            case "agent" -> {
                String agent=legacy.path("agentId").asText(),state=legacy.path("status").asText();
                String previous=agentStates.put(agent,state);
                Type eventType=state.equals(previous)?Type.agent_progress:switch(state) {
                    case "RUNNING" -> Type.agent_started;
                    case "COMPLETED" -> Type.agent_completed;
                    case "FAILED" -> Type.agent_failed;
                    default -> Type.agent_progress;
                };
                send(eventType,agent,null,payload);
            }
            case "snapshot" -> {messageStarted=true;send(Type.message_started,"SUP",null,payload.put("source","model_stream"));}
            case "delta" -> {
                if(!messageStarted){messageStarted=true;messageSource=legacy.path("source").asText("model_stream");send(Type.message_started,"SUP",null,json.createObjectNode().put("source",messageSource));}
                payload.put("text",legacy.path("text").asText());send(Type.message_delta,"SUP",null,payload);
            }
            case "result" -> send(Type.message_completed,"SUP",null,payload.put("source",messageStarted?messageSource:"complete_response"));
            case "error" -> {
                terminal=true;String code=legacy.path("message").asText();payload.put("code",code);
                payload.put("category",code.contains("DIFY")?"model":code.contains("RUNTIME")?"business_api":code.contains("TIMED_OUT")?"timeout":code.equals("RUN_CANCELLED")?"cancelled":"workflow");
                send(code.equals("RUN_CANCELLED")?Type.task_cancelled:Type.task_failed,null,null,payload);
            }
            case "done" -> {if(!terminal){terminal=true;send(Type.task_completed,null,null,payload);}}
            default -> throw new IOException("Unknown projected stream event");
        }
    }
    private void send(Type type,String agent,String call,JsonNode payload)throws IOException {
        Event event=new Event(UUID.randomUUID().toString(),task,conversation,++sequence,clock.instant().toString(),type,agent,call,payload);
        byte[] bytes=json.writeValueAsBytes(event);if(bytes.length>2*1024*1024)throw new IOException("Task event exceeds limit");
        observer.accept(event);
        output.write(("id: "+event.event_id()+"\nevent: "+type+"\ndata: ").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);output.write('\n');output.write('\n');output.flush();
    }
}
