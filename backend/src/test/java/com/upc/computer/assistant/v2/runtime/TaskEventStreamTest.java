package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskEventStreamTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void interleavedToolsKeepTheirIdsAndFailuresNeverBecomeSuccess() throws Exception {
        var output=new ByteArrayOutputStream();
        var stream=new TaskEventStream("run","conversation",Clock.systemUTC(),output);
        stream.accept(json.valueToTree(Map.of("type","run","runId","run")));
        var events=List.of(
            event(1,"STEP_STARTED","QUALITY","q","RUNNING"),
            event(2,"STEP_STARTED","EQUIPMENT","d","RUNNING"),
            event(3,"STEP_COMPLETED","QUALITY","q","FAILED"),
            event(4,"STEP_COMPLETED","EQUIPMENT","d","COMPLETED"));
        stream.accept(json.valueToTree(Map.of("type","workflow","events",events)));
        stream.accept(json.valueToTree(Map.of("type","workflow","events",events)));
        stream.accept(json.valueToTree(Map.of("type","error","message","RUN_TIMED_OUT")));
        stream.accept(json.valueToTree(Map.of("type","done")));
        var result=output.toString(StandardCharsets.UTF_8).lines().filter(s->s.startsWith("data: "))
            .map(s->{try{return json.readTree(s.substring(6));}catch(IOException e){throw new UncheckedIOException(e);}}).toList();
        assertEquals(List.of("task_started","tool_started","tool_started","tool_failed","tool_completed","task_failed"),result.stream().map(e->e.path("type").asText()).toList());
        assertEquals("q",result.get(3).path("tool_call_id").asText());
        assertEquals("d",result.get(4).path("tool_call_id").asText());
        for(int i=0;i<result.size();i++){assertEquals(i+1,result.get(i).path("sequence").asInt());assertEquals("conversation",result.get(i).path("conversation_id").asText());}
    }
    private Map<String,Object> event(int seq,String kind,String agent,String call,String status) {
        return Map.of("seq",seq,"kind",kind,"agentId",agent,"toolCallId",call,"status",status);
    }
}
