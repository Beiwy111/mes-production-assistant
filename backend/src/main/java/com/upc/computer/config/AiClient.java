package com.upc.computer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 视觉模型 HTTP 客户端。
 * 使用 JDK 11+ 内置 HttpClient，不引入额外依赖。
 * 支持 base64 图片发送给兼容 OpenAI vision API 的接口。
 */
@Component
public class AiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    private final AiProperties props;
    private final HttpClient httpClient;

    public AiClient(AiProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * 发送图片（字节数组）和提示词，返回模型的文本回复。
     *
     * @param imageBytes  图片字节
     * @param mimeType    image/jpeg 或 image/png
     * @param systemPrompt 系统提示词
     * @return 模型回复文本
     */
    public String callVision(byte[] imageBytes, String mimeType, String systemPrompt) throws IOException, InterruptedException {
        return callVision(imageBytes,mimeType,systemPrompt,props.getApiKey(),props.getModel());
    }

    public String callWorkbenchVision(byte[] imageBytes,String mimeType,String prompt) throws IOException,InterruptedException {
        return callVision(imageBytes,mimeType,prompt,visionKey(),visionModel());
    }

    private String visionKey() throws IOException {
        String key=props.getApiKey();
        if("https://dashscope.aliyuncs.com/compatible-mode/v1".equals(props.getBaseUrl())){
            String providerKey=System.getenv("DASHSCOPE_API_KEY");
            if(providerKey!=null&&!providerKey.isBlank())key=providerKey;
        }
        if(key==null||key.isBlank())throw new IOException("Vision credential unavailable");
        return key;
    }
    private String visionModel(){
        String model=System.getenv("MES_CHAT_VISION_MODEL");
        if(model==null||model.isBlank()){
            model=props.getModel();
            if("qwen-vl-plus".equals(model)&&"https://dashscope.aliyuncs.com/compatible-mode/v1".equals(props.getBaseUrl()))
                model="qwen3-vl-plus";
        }
        return model;
    }

    private String callVision(byte[] imageBytes,String mimeType,String systemPrompt,String apiKey,String model) throws IOException,InterruptedException {
        HttpRequest request=visionRequest(imageBytes,mimeType,systemPrompt,apiKey,model,false);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String body = response.body() == null ? "" : response.body();
            String detail = extractApiError(body);
            throw new IOException("AI 接口返回异常状态码: " + response.statusCode()
                    + (detail.isBlank() ? "" : "，" + detail));
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("AI 接口返回格式异常，无 choices 字段");
        }
        return choices.get(0).path("message").path("content").asText("");
    }

    private HttpRequest visionRequest(byte[] bytes,String mime,String prompt,String key,String model,boolean stream) throws IOException {
        var image=java.util.Map.of("type","image_url","image_url",java.util.Map.of("url","data:"+mime+";base64,"+Base64.getEncoder().encodeToString(bytes)));
        var content=java.util.List.of(java.util.Map.of("type","text","text",prompt),image);
        var body=java.util.Map.of("model",model,"stream",stream,"max_tokens",4096,"messages",java.util.List.of(java.util.Map.of("role","user","content",content)));
        return HttpRequest.newBuilder(URI.create(props.getBaseUrl()+"/chat/completions"))
            .timeout(Duration.ofSeconds(props.getTimeoutSeconds())).header("Content-Type","application/json")
            .header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))).build();
    }

    @FunctionalInterface public interface VisionText {void accept(String text) throws IOException;}

    public void streamWorkbenchVision(byte[] image,String mime,String question,VisionText sink) throws IOException,InterruptedException {
        var response=httpClient.send(visionRequest(image,mime,question,visionKey(),visionModel(),true),HttpResponse.BodyHandlers.ofInputStream());
        try(var lines=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),java.nio.charset.StandardCharsets.UTF_8))){
            if(response.statusCode()!=200)throw new IOException("Vision service unavailable");
            boolean produced=false;
            for(String line;(line=lines.readLine())!=null;){
                if(!line.startsWith("data:"))continue;
                String payload=line.substring(5).trim();
                if(payload.equals("[DONE]")&&produced)return;
                if(payload.isEmpty())continue;
                var event=MAPPER.readTree(payload);
                if(event.has("error"))throw new IOException("Vision stream failed");
                String delta=event.path("choices").path(0).path("delta").path("content").asText("");
                if(!delta.isEmpty()){sink.accept(delta);produced=true;}
            }
            throw new IOException("Incomplete vision stream");
        }
    }

    /**
     * 从模型返回的文本中提取 JSON 字符串。
     * 优先提取 ```json ... ``` 代码块，其次提取第一个完整 { } 对象。
     */
    public String extractJson(String text) {
        if (text == null || text.isBlank()) return null;

        Matcher blockMatcher = JSON_BLOCK.matcher(text);
        if (blockMatcher.find()) {
            return blockMatcher.group(1).trim();
        }

        Matcher objectMatcher = JSON_OBJECT.matcher(text);
        if (objectMatcher.find()) {
            return objectMatcher.group().trim();
        }

        return null;
    }

    private String extractApiError(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = MAPPER.readTree(body);
            String message = root.path("error").path("message").asText("");
            if (!message.isBlank()) return message;
            message = root.path("message").asText("");
            return message;
        } catch (Exception ignored) {
            return body.length() > 180 ? body.substring(0, 180) + "..." : body;
        }
    }
}
