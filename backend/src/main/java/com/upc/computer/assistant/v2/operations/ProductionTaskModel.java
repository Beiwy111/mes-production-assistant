package com.upc.computer.assistant.v2.operations;

import com.fasterxml.jackson.databind.*;
import com.upc.computer.common.BusinessException;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Uses the existing workbench model configuration; no additional credential store. */
@Component
public class ProductionTaskModel {
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public ProductionTaskModel(ObjectMapper json){this.json=json;}
    public JsonNode plan(String authorization, Map<String,Object> input,Consumer<String> onText) {
        try {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083/assistant-workbench/task-plan"))
                .timeout(Duration.ofSeconds(40)).header("Authorization",authorization).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))){
                if(response.statusCode()!=200)throw new BusinessException(503,"任务规划服务暂不可用；未执行生产操作，请稍后重试");
                int size=0;
                for(String line;(line=reader.readLine())!=null;){
                    if((size+=line.length())>100000)throw new BusinessException(502,"任务规划结果过大");
                    JsonNode event=json.readTree(line);
                    if("delta".equals(event.path("type").asText()))onText.accept(event.path("text").asText());
                    else if("result".equals(event.path("type").asText()))return event.path("data");
                    else if("error".equals(event.path("type").asText()))throw new BusinessException(503,"任务规划中断，请重试；未执行生产操作");
                }
                throw new BusinessException(503,"任务规划连接中断，未执行生产操作");
            }
        } catch(BusinessException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(503,"任务规划已中断，未执行生产操作");}
        catch(Exception e){throw new BusinessException(503,"任务规划连接失败，未执行生产操作");}
    }
}
