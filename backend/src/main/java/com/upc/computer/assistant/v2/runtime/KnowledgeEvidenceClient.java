package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Read-only public research boundary. Business identifiers and model-written queries never leave Java. */
public final class KnowledgeEvidenceClient {
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:18081/internal/knowledge/query");
    private static final int MAX_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESEARCH_LIMIT = "公开文献仅供研究参考，不能证明工厂根因、验收标准或售后责任；须结合本任务业务证据验证。";
    private static final Set<String> HIT_FIELDS = Set.of("document_id", "document_version", "chunk_id", "title", "text", "page_start", "page_end", "source_url", "license", "authority_type", "release_id", "evidence_ref", "evidence_path", "score", "retrieval_source");
    private static final Set<String> PATH_FIELDS = Set.of("claim_id", "statement", "relation", "quote", "document_id", "document_version", "page", "subject_id", "subject_name", "object_id", "object_name", "hops", "graph_release_id", "evidence_refs", "authority_type", "scope", "review_status");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
            public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        }).build();
    private final URI endpoint;
    private final String apiKey;
    private final Clock clock;
    private final Duration timeout;

    /** Production callers may only select the fixed local service, with an optional query path. */
    public KnowledgeEvidenceClient(URI base, String apiKey, Clock clock) {
        this(base, apiKey, clock, Duration.ofSeconds(15), false);
    }

    /** Package-local transport seam for ephemeral loopback test servers; never exposed to requests. */
    KnowledgeEvidenceClient(URI base, String apiKey, Clock clock, Duration timeout) {
        this(base, apiKey, clock, timeout, true);
    }

    private KnowledgeEvidenceClient(URI base, String apiKey, Clock clock, Duration timeout, boolean localTest) {
        Objects.requireNonNull(base, "Local knowledge endpoint required");
        String path = base.getRawPath();
        boolean hostAllowed = "127.0.0.1".equals(base.getHost()) || (localTest && Set.of("localhost", "[::1]").contains(string(base.getHost())));
        if (!"http".equals(base.getScheme()) || !hostAllowed || base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || base.getPort() < 1 || (!localTest && base.getPort() != 18081)
                || !(path == null || path.isEmpty() || path.equals("/") || path.equals(ENDPOINT.getPath()))) {
            throw new IllegalArgumentException("Only the fixed local knowledge endpoint is allowed");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(15)) > 0)
            throw new IllegalArgumentException("Knowledge request deadline must be within 15 seconds");
        this.endpoint = base.resolve(ENDPOINT.getPath());
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.clock = Objects.requireNonNull(clock); this.timeout = timeout;
    }

    public static KnowledgeEvidenceClient fromEnvironment(Map<String,String> environment, Clock clock) {
        return new KnowledgeEvidenceClient(ENDPOINT, environment.get("MES_KNOWLEDGE_API_KEY"), clock);
    }

    public Map<String,Object> retrieve(String role, List<Map<String,Object>> businessEvidence) {
        String query = query(role, businessEvidence);
        if (!"QUALITY".equals(role) && !"AFTERSALES".equals(role))
            return unavailable("NO_MATCHES", query, "该任务不使用质量或售后公开研究检索。", "ROLE_NOT_SUPPORTED");
        return retrieveLocal(query);
    }

    public Map<String,Object> retrieveQuestion(String query) {
        if(query==null||query.isBlank()||query.length()>2000)throw new IllegalArgumentException("Invalid knowledge question");
        return retrieveLocal(query.strip());
    }

    private Map<String,Object> retrieveLocal(String query) {
        if (apiKey.isBlank()) return unavailable("NOT_CONFIGURED", query, "本地知识服务密钥尚未配置，未发送检索请求。", "KNOWLEDGE_NOT_CONFIGURED");
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            byte[] payload = JSON.writeValueAsBytes(Map.of("query", query, "mode", "graph_assisted", "top_k", 4));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey).POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            pending = HTTP.sendAsync(request, info -> new LimitedBody());
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) return unavailable("UNAVAILABLE", query, "本地知识服务暂不可用，未取得公开研究证据。", "KNOWLEDGE_SERVICE_UNAVAILABLE");
            Map<String,Object> body = JSON.readValue(response.body(), new TypeReference<Map<String,Object>>() {});
            // Also catches JSON-escaped credential echoes. No remote diagnostics are propagated.
            if (body == null || body.toString().contains(apiKey)) throw new IOException("Invalid knowledge response");
            return verified(body, query);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable("UNAVAILABLE", query, "公开知识检索已中断，未取得研究证据。", "KNOWLEDGE_INTERRUPTED");
        } catch (Exception failure) {
            return unavailable("UNAVAILABLE", query, "公开知识检索超时或响应无效，未取得研究证据；可稍后重试。", "KNOWLEDGE_UNAVAILABLE_OR_INVALID");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private Map<String,Object> verified(Map<String,Object> body, String query) throws IOException {
        String release = string(body.get("release_id"));
        if (!release.matches("[a-f0-9]{32}") || !"display_reference".equals(body.get("knowledge_domain"))
                || !(body.get("hits") instanceof List<?>) || !(body.get("graph_paths") instanceof List<?>)
                || !Set.of("MATCHES", "NO_MATCHES").contains(string(body.get("status")))) throw new IOException("Invalid research envelope");
        List<String> missing = strings(body.get("missing_information"));
        List<String> limitations = strings(body.get("limitations")); limitations.add(RESEARCH_LIMIT);
        var hits = new LinkedHashMap<String,Map<String,Object>>();
        List<?> rawHits = (List<?>) body.get("hits");
        for (Object raw : rawHits) {
            Map<String,Object> hit = fields(raw, HIT_FIELDS);
            if (hits.size() < 4 && validHit(hit, release)) hits.putIfAbsent(string(hit.get("evidence_ref")), hit);
        }
        String graphRelease = string(body.get("graph_release_id"));
        List<Map<String,Object>> paths = new ArrayList<>();
        for (Object raw : (List<?>) body.get("graph_paths")) {
            Map<String,Object> path = fields(raw, PATH_FIELDS);
            if (paths.size() < 8 && validPath(path, graphRelease, hits)) paths.add(path);
        }
        // Invalid evidence can disconnect a second hop. Keep only paths still reachable from returned roots.
        Set<String> reached = new HashSet<>(strings(body.get("resolved_entities")));
        List<Map<String,Object>> rooted = new ArrayList<>();
        for (int hop = 1; hop <= 2; hop++) {
            Set<String> frontier = Set.copyOf(reached);
            for (Map<String,Object> path : paths) {
                if (number(path.get("hops")) == hop && (frontier.contains(string(path.get("subject_id"))) || frontier.contains(string(path.get("object_id"))))) {
                    rooted.add(path); reached.add(string(path.get("subject_id"))); reached.add(string(path.get("object_id")));
                }
            }
        }
        String graphStatus = string(body.get("graph_status"));
        if (!Set.of("MATCHES", "NO_MATCHES", "NO_ENTITY_MATCH", "UNAVAILABLE_OR_STALE").contains(graphStatus)) graphStatus = "UNAVAILABLE_OR_STALE";
        if (!"MATCHES".equals(graphStatus) || !"graph_assisted".equals(body.get("mode_used"))) rooted.clear();
        if (rooted.isEmpty() && graphStatus.equals("MATCHES")) graphStatus = "NO_MATCHES";
        if (graphStatus.equals("UNAVAILABLE_OR_STALE")) {
            rooted.clear(); missing.add("GRAPH_UNAVAILABLE_OR_STALE_VECTOR_ONLY"); limitations.add("图谱不可用或版本过期，仅保留可核验的文献检索结果。");
        }
        Set<String> graphRefs = new HashSet<>();
        for (Map<String,Object> path : rooted) graphRefs.addAll(strings(path.get("evidence_refs")));
        hits.entrySet().removeIf(entry -> "graph".equals(entry.getValue().get("retrieval_source")) && !graphRefs.contains(entry.getKey()));
        boolean filtered = hits.size() != rawHits.size() || rooted.size() != ((List<?>)body.get("graph_paths")).size();
        if (filtered) { missing.add("INVALID_OR_UNSUPPORTED_EVIDENCE_REMOVED"); limitations.add("部分知识引用未通过版本、页码或原文核验，已移除。"); }
        if (hits.isEmpty()) { missing.add("NO_VERIFIED_KNOWLEDGE_MATCHES"); limitations.add("未取得可核验的公开知识命中，不应据此推定工厂根因。"); }
        Map<String,Object> bundle = base(query);
        bundle.put("status", hits.isEmpty() ? "NO_MATCHES" : "MATCHES"); bundle.put("release_id", release);
        bundle.put("mode_used", rooted.isEmpty() ? "vector" : "graph_assisted"); bundle.put("graph_status", graphStatus);
        bundle.put("graph_release_id", rooted.isEmpty() ? null : graphRelease);
        bundle.put("hits", List.copyOf(hits.values())); bundle.put("graph_paths", List.copyOf(rooted));
        bundle.put("limitations", List.copyOf(new LinkedHashSet<>(limitations)));
        bundle.put("missing_information", List.copyOf(new LinkedHashSet<>(missing)));
        return bundle;
    }

    private static boolean validHit(Map<String,Object> hit, String release) {
        String document = string(hit.get("document_id")), chunk = string(hit.get("chunk_id"));
        return document.matches("[A-Za-z0-9_-]{1,200}") && chunk.matches("[A-Za-z0-9_-]{1,200}")
            && string(hit.get("document_version")).matches("[a-f0-9]{64}") && release.equals(hit.get("release_id"))
            && (release + ":" + document + ":" + chunk).equals(hit.get("evidence_ref"))
            && ("/internal/knowledge/evidence/" + release + "/" + chunk).equals(hit.get("evidence_path"))
            && "PUBLIC_RESEARCH".equals(hit.get("authority_type")) && number(hit.get("page_start")) >= 1
            && number(hit.get("page_end")) >= number(hit.get("page_start"))
            && !string(hit.get("text")).isBlank() && !string(hit.get("title")).isBlank() && safeSource(hit.get("source_url"));
    }

    private static boolean validPath(Map<String,Object> path, String graphRelease, Map<String,Map<String,Object>> hits) {
        if (!graphRelease.matches("[a-f0-9]{32}") || !graphRelease.equals(path.get("graph_release_id"))
                || !"PUBLIC_RESEARCH".equals(path.get("authority_type")) || !"RESEARCH_ONLY".equals(path.get("scope"))
                || !Set.of("DETECTS", "USES", "CLASSIFIES").contains(string(path.get("relation")))
                || !"SOURCE_VERIFIED".equals(path.get("review_status")) || number(path.get("hops")) < 1 || number(path.get("hops")) > 2
                || string(path.get("claim_id")).isBlank() || string(path.get("subject_id")).isBlank() || string(path.get("object_id")).isBlank()
                || !(path.get("evidence_refs") instanceof List<?> refs) || refs.isEmpty() || string(path.get("quote")).isBlank()) return false;
        for (Object ref : (List<?>)path.get("evidence_refs")) {
            Map<String,Object> hit = hits.get(string(ref));
            if (hit == null || !Objects.equals(path.get("document_id"), hit.get("document_id"))
                    || !Objects.equals(path.get("document_version"), hit.get("document_version"))
                    || number(path.get("page")) != number(hit.get("page_start"))
                    || !normalized(hit.get("text")).contains(normalized(path.get("quote")))) return false;
        }
        return true;
    }

    private static boolean safeSource(Object value) {
        try {
            URI uri = URI.create(string(value));
            return Set.of("https", "http").contains(string(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static String normalized(Object value) { return string(value).replaceAll("(?U)\\s+", " ").trim(); }
    private static int number(Object value) { return value instanceof Number n && n.doubleValue() == n.intValue() ? n.intValue() : -1; }
    private static String string(Object value) { return value instanceof String s ? s : ""; }
    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) for (Object item : list) if (item instanceof String text && !text.isBlank() && result.size() < 32) result.add(text);
        return result;
    }
    private static Map<String,Object> fields(Object value, Set<String> allowed) {
        Map<String,Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?,?> map) for (String field : allowed) if (map.containsKey(field)) result.put(field, map.get(field));
        return result;
    }
    private Map<String,Object> base(String query) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", "UNAVAILABLE"); result.put("query", query); result.put("requested_mode", "graph_assisted");
        result.put("mode_used", null); result.put("graph_status", "NOT_REQUESTED"); result.put("release_id", null); result.put("graph_release_id", null);
        result.put("hits", List.of()); result.put("graph_paths", List.of()); result.put("limitations", List.of(RESEARCH_LIMIT)); result.put("missing_information", List.of());
        result.put("retrieved_at", clock.instant().toString()); result.put("authority_type", "PUBLIC_RESEARCH"); result.put("scope", "RESEARCH_ONLY");
        return result;
    }
    private Map<String,Object> unavailable(String status, String query, String reason, String missing) {
        Map<String,Object> result = base(query); result.put("status", status);
        result.put("limitations", List.of(RESEARCH_LIMIT, reason)); result.put("missing_information", List.of(missing)); return result;
    }
    private static String query(String role, List<Map<String,Object>> evidence) {
        StringBuilder input = new StringBuilder(); collect(evidence, input, 0, new int[]{2000});
        String text = input.toString().toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>(List.of("显示屏缺陷", "视觉检测"));
        if (text.contains("划痕") || text.contains("scratch")) terms.add("划痕");
        if (text.contains("亮点") || text.contains("暗点") || text.contains("亮暗点") || text.contains("bright spot") || text.contains("dark spot")) terms.add("亮暗点");
        if (text.contains("mura")) terms.add("Mura");
        if ("AFTERSALES".equals(role)) terms.add("售后质量追溯方法");
        return String.join(" ", terms);
    }
    private static void collect(Object value, StringBuilder text, int depth, int[] remaining) {
        if (depth > 12 || remaining[0]-- <= 0 || text.length() >= 64000) return;
        if (value instanceof String str) text.append(' ').append(str, 0, Math.min(str.length(), 64000 - text.length()));
        else if (value instanceof Map<?,?> map) for (Object nested : map.values()) collect(nested, text, depth + 1, remaining);
        else if (value instanceof List<?> list) for (Object nested : list) collect(nested, text, depth + 1, remaining);
    }

    /** Cancel while streaming, before an oversized response can be accumulated in memory. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) { subscription.cancel(); body.completeExceptionally(new IOException("Response size limit")); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
