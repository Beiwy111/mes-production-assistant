package com.upc.computer.assistant.v2.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.runtime.KnowledgeEvidenceClient;
import com.upc.computer.assistant.v2.runtime.WorkbenchHttpServices;
import com.upc.computer.assistant.v2.runtime.WorkbenchGatewayServer;
import com.upc.computer.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class WorkbenchModelChat {
    private static final String SYSTEM = """
            你是显示器制造 MES 的生产协作助手。用中文回答用户的问题。对于工艺知识、方法或普通对话，直接给出回答。
            若问题需要查询实际业务记录，必须只输出如下 JSON，不可猜测数据或宣称已执行操作：
            {"action":"QUERY","intents":["ORDER"],"orderPk":null,"orderNo":null,"keyword":null,"period":"CURRENT","question":""}。
            intents 可选 ORDER、INVENTORY、PRODUCTION、QUALITY、DELIVERY、EQUIPMENT、AFTERSALES、MAINTENANCE、ALARMS、STOCK_MOVEMENT。
            period 可选 CURRENT、TODAY、LAST_7_DAYS、THIS_MONTH。订单编号和关键词须来自用户，业务权限由 MES 校验。
            你不能直接修改业务记录。文献片段是参考数据，不能替代工厂实测；引用时使用给定的 [S1] 等标记。
            """;
    private static final String INTAKE = """
            你是 MES 下单意图解析器。只返回 JSON 对象 {"action":"ORDER 或 QUERY 或 CANCEL","fields":{},"clearFields":[],"question":""}。
            ORDER 表示新下单或补充当前草稿；查询或闲聊用 QUERY；明确取消草稿用 CANCEL。
            fields 只提取本轮用户明确给出的变更，可用 quantity（正整数）、productQuery、requiredDeliveryDate（yyyy-MM-dd）、
            receiverName、receiverPhone、receiverAddress、customerName、remark。clearFields 只列用户明确要求清除的字段。
            QUERY 和 CANCEL 的 fields 和 clearFields 均须为空。不得从参考文献、例子或猜测补充订单。
            最终创建订单由用户在 MES 中另行确认。
            """;
    private final WorkbenchChatProperties settings;
    private final WorkbenchModelClient models;
    private final DirectBusinessQueries business;
    private final KnowledgeEvidenceClient knowledge;
    private final ObjectMapper json;
    private final Semaphore slots = new Semaphore(3);

    @Autowired
    public WorkbenchModelChat(WorkbenchChatProperties settings, WorkbenchModelClient models,
                              DirectBusinessQueries business, ObjectMapper json) {
        this(settings, models, business, json, new KnowledgeEvidenceClient(
                URI.create("http://127.0.0.1:18081"), settings.getKnowledgeKey(), Clock.systemUTC()));
    }

    public WorkbenchModelChat(WorkbenchChatProperties settings, WorkbenchModelClient models, DirectBusinessQueries business,
                              ObjectMapper json, KnowledgeEvidenceClient knowledge) {
        this.settings = settings;
        this.models = models;
        this.business = business;
        this.json = json;
        this.knowledge = knowledge;
    }

    public Map<String, Object> status(long owner) {
        var policy = business.policy(owner);
        return Map.of("apiConfigured", models.configured(ChatMode.Source.API), "localConfigured", models.configured(ChatMode.Source.LOCAL),
                "ragConfigured", !settings.getKnowledgeKey().isBlank(), "authorizationStamp", policy.fingerprint(), "capabilities", policy.publicCapabilities());
    }

    public Map<String, Object> turn(long owner, JsonNode body) {
        if (body == null || !body.isObject() || !allowed(body, Set.of("text", "modelSource", "ragEnabled", "history")))
            throw new BusinessException(400, "模型问答请求字段无效");
        ChatMode mode = ChatMode.parse(body.path("modelSource").textValue(), body.path("ragEnabled").isBoolean() ? body.get("ragEnabled").booleanValue() : null);
        String question = body.path("text").textValue();
        if (question == null || question.isBlank() || question.length() > 2000) throw new BusinessException(400, "问题须为 1 至 2000 个字符");
        List<Map<String, String>> history = history(body.get("history"));
        String stamp = business.policy(owner).fingerprint();
        acquire();
        try {
            var evidence = retrieve(mode, question);
            var messages = new ArrayList<Map<String, String>>();
            messages.add(message("system", SYSTEM));
            messages.addAll(history);
            messages.add(message("user", question + referenceText(evidence)));
            String answer = models.generate(mode.modelSource(), messages);
            JsonNode plan = readPlan(answer);
            var result = new LinkedHashMap<String, Object>();
            if (plan != null) {
                result.putAll(business.answer(owner, question, plan, mode, evidence));
            } else {
                result.put("reply", answer);
                result.put("queryMode", "MODEL_CHAT");
                result.put("intents", mode.ragEnabled() ? List.of("KNOWLEDGE") : List.of());
            }
            if (!stamp.equals(business.policy(owner).fingerprint())) throw new BusinessException(403, "业务权限已变化，请重新查询");
            result.put("modelSource", mode.modelSource());
            result.put("model", settings.endpoint(mode.modelSource()).getModel());
            result.put("ragEnabled", mode.ragEnabled());
            result.put("ragStatus", evidence.getOrDefault("status", "DISABLED"));
            result.put("knowledgeEvidence", !mode.ragEnabled() || "DATA_CHANGED".equals(result.get("verdict")) ? List.of() : List.of(evidence));
            return result;
        } finally {
            slots.release();
        }
    }

    public JsonNode interpretOrder(ChatMode mode, String text, Map<String, Object> draft) {
        acquire();
        try {
            // Addresses, phone numbers and draft data stay out of the public literature index.
            var evidence = retrieve(mode, "显示器产品规格、订单录入与交付要求");
            String input = "当前草稿上下文：" + json.valueToTree(draft) + "\n本轮用户输入：" + text + referenceText(evidence);
            String output = models.generate(mode.modelSource(), List.of(message("system", INTAKE), message("user", input)));
            try {
                return WorkbenchHttpServices.validateIntake(json.readTree(unfence(output)));
            } catch (JsonProcessingException | WorkbenchGatewayServer.Failure e) {
                throw new BusinessException(502, "所选模型未返回有效的订单字段，草稿未更新");
            }
        } finally {
            slots.release();
        }
    }

    private void acquire() {
        if (!slots.tryAcquire()) throw new BusinessException(429, "模型问答正在处理其他请求，请稍后再试");
    }

    private Map<String, Object> retrieve(ChatMode mode, String question) {
        models.requireConfigured(mode.modelSource());
        if (!mode.ragEnabled()) return Map.of();
        if (settings.getKnowledgeKey().isBlank()) throw new BusinessException(503, "RAG 已开启，后端尚未配置知识库密钥");
        var bundle = knowledge.retrieveQuestion(question);
        if (!Set.of("MATCHES", "NO_MATCHES").contains(bundle.get("status")))
            throw new BusinessException(503, "RAG 知识检索失败，请检查知识服务或关闭 RAG 后重试");
        return bundle;
    }

    static String referenceText(Map<String, Object> bundle) {
        if (bundle.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n\n本轮知识库检索结果（参考资料）：");
        List<?> hits = (List<?>) bundle.get("hits");
        if (hits.isEmpty()) return out.append("没有匹配的资料。").toString();
        int index = 0;
        for (Object item : hits) {
            Map<?, ?> hit = (Map<?, ?>) item;
            String excerpt = String.valueOf(hit.get("text"));
            out.append("\n[S").append(++index).append("] ").append(hit.get("title")).append(" 第")
                    .append(hit.get("page_start")).append("页，引用 ").append(hit.get("evidence_ref")).append("：")
                    .append(excerpt, 0, Math.min(1100, excerpt.length()));
        }
        return out.toString();
    }

    private JsonNode readPlan(String text) {
        String raw = unfence(text);
        if (!raw.startsWith("{")) return null;
        try {
            JsonNode value = json.readTree(raw);
            return "QUERY".equals(value.path("action").asText()) ? value : null;
        } catch (JsonProcessingException invalid) {
            if (raw.contains("\"action\"") && raw.contains("\"QUERY\"")) {
                throw new BusinessException(502, "模型返回的业务查询计划格式无效，请重新提问");
            }
            return null;
        }
    }

    private static String unfence(String value) {
        return value.strip().replaceFirst("(?i)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }

    private static boolean allowed(JsonNode object, Set<String> fields) {
        var names = object.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }

    private static List<Map<String, String>> history(JsonNode history) {
        if (history == null) return List.of();
        if (!history.isArray() || history.size() > 8 || history.size() % 2 != 0) throw new BusinessException(400, "会话历史格式无效");
        var result = new ArrayList<Map<String, String>>();
        int length = 0;
        for (JsonNode entry : history) {
            String role = result.size() % 2 == 0 ? "user" : "assistant";
            String content = entry.path("content").textValue();
            if (!entry.isObject() || !allowed(entry, Set.of("role", "content")) || !role.equals(entry.path("role").textValue())
                    || content == null || content.isBlank() || content.length() > 2000 || (length += content.length()) > 10000)
                throw new BusinessException(400, "会话历史格式无效");
            result.add(message(role, content));
        }
        return result;
    }

    private static Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }
}
