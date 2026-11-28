package com.upc.computer.assistant.v2.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.orders.OrderSessionGuard;
import com.upc.computer.assistant.v2.runtime.TaskEventStream;
import com.upc.computer.common.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.time.Clock;
import java.io.*;
import java.util.*;

@RestController
@RequestMapping("/assistant-v2/tasks")
public class ProductionTaskController {
    private final OrderSessionGuard sessions;private final ProductionTaskService tasks;private final ObjectMapper json;
    public ProductionTaskController(OrderSessionGuard sessions,ProductionTaskService tasks,ObjectMapper json){this.sessions=sessions;this.tasks=tasks;this.json=json;}
    @PostMapping(value="/stream",produces="text/event-stream")
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody ProductionTaskService.Turn turn,
            @RequestHeader(value="Authorization",required=false)String authorization){
        sessions.requireActive(authorization);
        if(turn.sessionId()==null||!turn.sessionId().matches("[A-Za-z0-9_-]{1,160}"))throw new BusinessException(400,"无效会话编号");
        StreamingResponseBody body=output->{
            var stream=new TaskEventStream(UUID.randomUUID().toString(),turn.sessionId(),Clock.systemUTC(),output);
            stream.accept(json.valueToTree(Map.of("type","run")));
            try {
                var result=tasks.run(turn,authorization,event->{try{stream.accept(json.valueToTree(event));}catch(IOException e){throw new UncheckedIOException(e);}});
                if(!Boolean.TRUE.equals(result.get("answerStreamed")))stream.accept(json.valueToTree(Map.of("type","delta","text",Objects.toString(result.get("message"),""),"source","business_response")));
                var receipt=new LinkedHashMap<>(result);
                receipt.put("workflowModel",turn.workflowModel().name());
                stream.accept(json.valueToTree(Map.of("type","result","data",receipt)));
                stream.accept(json.valueToTree(Map.of("type","done")));
            }catch(UncheckedIOException disconnected){throw disconnected.getCause();}
            catch(Exception e){stream.accept(json.valueToTree(Map.of("type","error","message",e instanceof BusinessException?e.getMessage():"任务处理失败，请重新查询状态；未自动重试写入")));}
        };
        return ResponseEntity.ok().contentType(new MediaType("text","event-stream",java.nio.charset.StandardCharsets.UTF_8)).header("Cache-Control","no-store").header("X-Accel-Buffering","no").body(body);
    }
    @PostMapping("/{sessionId}/proposals/{proposalId}/decision")
    public Result<?> decide(@PathVariable String sessionId,@PathVariable String proposalId,@RequestBody Map<String,String> body,
            @RequestHeader(value="Authorization",required=false)String authorization){
        if(!body.keySet().equals(Set.of("decision")))throw new BusinessException(400,"确认请求格式无效");
        return Result.success(tasks.decide(sessionId,proposalId,body.get("decision"),authorization));
    }
}
