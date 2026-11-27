package com.upc.computer.assistant.v2.runtime;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** Assembles the provider's native tool-call fragments separately from public answer text. */
final class TaskToolStream {
    private final ObjectMapper json;
    private final SortedMap<Integer,StringBuilder[]> calls=new TreeMap<>();
    private final StringBuilder reply=new StringBuilder();
    private String finishReason="";
    TaskToolStream(ObjectMapper json){this.json=json;}
    static boolean readyToRespond(JsonNode decision)throws IOException{
        JsonNode calls=decision.path("calls");
        if(!calls.isArray()||calls.isEmpty())throw new IOException("Missing tool decision");
        boolean responds=false;
        for(JsonNode call:calls)if("respond".equals(call.path("tool").asText()))responds=true;
        if(responds && (calls.size()!=1 || !calls.get(0).path("args").isObject() || !calls.get(0).path("args").isEmpty()))
            throw new IOException("Answer decision must be separate from business tools");
        return responds;
    }
    String accept(JsonNode chunk)throws IOException{
        JsonNode choice=chunk.path("choices").path(0),delta=choice.path("delta");
        if(choice.hasNonNull("finish_reason"))finishReason=choice.path("finish_reason").asText();
        if(delta.path("tool_calls").isArray())for(JsonNode item:delta.path("tool_calls")){
            int index=item.path("index").asInt(-1);if(index<0||index>2)throw new IOException("Tool count exceeds limit");
            var parts=calls.computeIfAbsent(index,k->new StringBuilder[]{new StringBuilder(),new StringBuilder()});
            parts[0].append(item.path("function").path("name").asText(""));parts[1].append(item.path("function").path("arguments").asText(""));
            if(parts[0].length()>80||parts[1].length()>16000)throw new IOException("Tool arguments too large");
        }
        String text=delta.path("content").asText("");
        if(!calls.isEmpty())return "";
        reply.append(text);if(reply.length()>12000)throw new IOException("Task answer too large");
        return text;
    }
    JsonNode finish(String goal)throws IOException{
        if(!Set.of("stop","tool_calls").contains(finishReason))throw new IOException("Incomplete task model response");
        var result=new ArrayList<Map<String,Object>>();
        for(var parts:calls.values()){
            JsonNode args=json.readTree(parts[1].toString());
            if(!args.isObject())throw new IOException("Tool arguments must be an object");
            result.add(Map.of("tool",parts[0].toString(),"args",args));
        }
        return json.valueToTree(Map.of("goal",goal,"reply",reply.toString(),"calls",result));
    }
}
