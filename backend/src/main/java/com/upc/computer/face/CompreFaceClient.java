package com.upc.computer.face;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class CompreFaceClient {
    private final FaceAuthProperties properties;
    private final ObjectMapper json;
    public CompreFaceClient(FaceAuthProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }
    public JsonNode detect(byte[] jpeg) {
        return call("POST", "/api/v1/detection/detect", properties.getDetectionApiKey(), jpeg,
                Map.of("limit", 0, "det_prob_threshold", properties.getDetectionThreshold()));
    }
    public String enroll(String subject, byte[] jpeg) {
        validateSubject(subject);
        JsonNode result = call("POST", "/api/v1/recognition/faces", properties.getRecognitionApiKey(), jpeg,
                Map.of("subject", subject, "det_prob_threshold", properties.getDetectionThreshold()));
        if (!subject.equals(result.path("subject").asText())) throw invalidResponse();
        return requireImageId(result.path("image_id").asText());
    }
    public JsonNode verify(String imageId, byte[] jpeg) {
        return call("POST", "/api/v1/recognition/faces/" + requireImageId(imageId) + "/verify",
                properties.getRecognitionApiKey(), jpeg,
                Map.of("limit", 0, "det_prob_threshold", properties.getDetectionThreshold()));
    }
    public void deleteSubject(String subject) {
        validateSubject(subject);
        call("DELETE", "/api/v1/recognition/subjects/" + subject, properties.getRecognitionApiKey(), null, Map.of());
    }
    private JsonNode call(String method, String path, String key, byte[] image, Map<String, Object> params) {
        properties.requireService();
        try {
            URI base = URI.create(properties.getBaseUrl());
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) {
                throw new FaceException(503, "INVALID_CONFIG", "人脸服务地址配置无效");
            }
            var uri = UriComponentsBuilder.fromUri(base).replacePath(path);
            params.forEach(uri::queryParam);
            var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
            factory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            var request = client.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(uri.build().encode().toUri()).header("x-api-key", key);
            if (image != null) {
                var body = new LinkedMultiValueMap<String, Object>();
                body.add("file", new ByteArrayResource(image) {
                    @Override public String getFilename() { return "capture.jpg"; }
                });
                request.contentType(MediaType.MULTIPART_FORM_DATA).body(body);
            }
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();
                // A missing, precisely-scoped subject is already deleted. Never use delete-all.
                if ("DELETE".equals(method) && status == 404) return json.createObjectNode();
                if (status == 401 || status == 403) throw new FaceException(503, "UPSTREAM_AUTH", "人脸服务鉴权失败，请联系管理员");
                if (status == 400 || status == 422) throw new FaceException(422, "IMAGE_REJECTED", "无法检测有效人脸，请正视镜头并重新采集");
                if (status < 200 || status >= 300) throw new FaceException(503, "UPSTREAM_FAILURE", "人脸服务暂不可用，请稍后重试或使用密码登录");
                byte[] bytes = response.getBody().readNBytes(262145);
                if (bytes.length > 262144) throw invalidResponse();
                if (bytes.length == 0 && "DELETE".equals(method)) return json.createObjectNode();
                JsonNode result = json.readTree(bytes);
                if (result == null || !result.isObject()) throw invalidResponse();
                return result;
            });
        } catch (FaceException ex) {
            throw ex;
        } catch (Exception ex) {
            // Do not attach upstream bodies, URLs containing credentials, or image data to exceptions/logs.
            throw new FaceException(503, "UPSTREAM_UNAVAILABLE", "人脸服务连接失败或超时，请稍后重试或使用密码登录");
        }
    }
    static void requireSubject(String subject) {
        if (subject == null || !subject.matches("mes-[1-9][0-9]*-[a-f0-9]{32}-[a-f0-9]{32}")) throw invalidResponse();
    }
    public void validateSubject(String subject) {
        requireSubject(subject);
        if (!subject.split("-")[2].equals(properties.serviceScope())) {
            throw new FaceException(503, "SERVICE_SCOPE_MISMATCH", "人脸绑定属于另一服务配置，请恢复原服务地址及 Key 后再操作");
        }
    }
    private static String requireImageId(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (Exception ex) { throw invalidResponse(); }
    }
    static FaceException invalidResponse() { return new FaceException(503, "INVALID_RESPONSE", "人脸服务返回异常，请联系管理员"); }
}
