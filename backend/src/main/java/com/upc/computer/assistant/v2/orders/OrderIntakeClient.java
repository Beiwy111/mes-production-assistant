package com.upc.computer.assistant.v2.orders;

import com.upc.computer.assistant.v2.runtime.WorkflowModel;
import com.fasterxml.jackson.databind.*;
import com.upc.computer.common.BusinessException;
import com.upc.computer.assistant.v2.chat.ChatMode;
import com.upc.computer.assistant.v2.chat.WorkbenchModelChat;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@Component
public class OrderIntakeClient {
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final WorkbenchModelChat models;
    public OrderIntakeClient(ObjectMapper json, WorkbenchModelChat models) { this.json=json; this.models=models; }

    public JsonNode interpret(ChatMode mode, String text, Map<String,Object> context) {
        return models.interpretOrder(mode, text, context);
    }
    public JsonNode interpret(String authorization,String text,Map<String,Object> context) {
        return interpret(authorization,text,context,WorkflowModel.API);
    }
    public JsonNode interpret(String authorization,String text,Map<String,Object> context,WorkflowModel workflow) {
        try {
            String body=json.writeValueAsString(Map.of("text",text,"context",json.writeValueAsString(context),"workflowModel",workflow.name()));
            HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18083/assistant-workbench/order-intake"))
                    .timeout(Duration.ofSeconds(110)).header("Content-Type","application/json").header("Authorization",authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>16000)throw new BusinessException(503,"订单理解服务暂不可用，请稍后重试；尚未提交订单");
            JsonNode parsed=json.readTree(response.body());
            if(!parsed.isObject())throw new IllegalArgumentException();return parsed;
        }catch(BusinessException ex){throw ex;}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new BusinessException(503,"订单理解已中断，尚未提交订单");}
        catch(Exception ex){throw new BusinessException(503,"订单理解服务暂不可用，草稿和已有订单未被提交或修改");}
    }
}
