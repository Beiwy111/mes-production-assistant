package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.upc.computer.assistant.v2.tools.delivery.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Independent, loopback-only maintenance preview. Never a MES end-user authentication adapter. */
public final class Stage5RuntimeServer implements AutoCloseable {
    private static final String SOURCE = "LOCAL_READONLY_PREVIEW";
    // A private process-local registry identity, deliberately outside MES positive user IDs.
    private static final long PREVIEW_IDENTITY = -5L;
    private static final List<String> REQUIRED = List.of("ORDER", "SUPPLY", "PRODUCTION");
    private static final Set<String> ROLES = Set.of("ORDER", "SUPPLY", "PRODUCTION", "QUALITY", "EQUIPMENT", "AFTERSALES");
    private static final Map<String,List<String>> PROFILES=Map.of(
            "DELIVERY",REQUIRED,"ORDER",List.of("ORDER"),"SUPPLY",List.of("ORDER","SUPPLY"),
            "PRODUCTION",REQUIRED,"QUALITY",List.of("QUALITY"),"EQUIPMENT",List.of("EQUIPMENT"),
            "AFTERSALES",List.of("AFTERSALES"),"FULL_ASSESSMENT",List.of("ORDER","SUPPLY","PRODUCTION","QUALITY","EQUIPMENT","AFTERSALES"));
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Clock clock;
    private final Function<Function<AiDeliveryQueryService,Map<String,Object>>,Map<String,Object>> access;
    private final Map<String,byte[]> keys = new LinkedHashMap<>();
    private final Set<String> allowedOrders;
    private final Map<String,Run> runs = new LinkedHashMap<>();
    private final HttpServer server;
    private final ExecutorService executor;
    private final NaturalLanguageRuntime natural;

    public Stage5RuntimeServer(Map<String,String> env, Clock clock,
            Function<Function<AiDeliveryQueryService,Map<String,Object>>,Map<String,Object>> access, int port) throws IOException {
        this(env,clock,access,null,port);
    }
    public Stage5RuntimeServer(Map<String,String> env, Clock clock,
            Function<Function<AiDeliveryQueryService,Map<String,Object>>,Map<String,Object>> access,
            NaturalLanguageRuntime natural,int port) throws IOException {
        this.natural=natural;
        this.clock = Objects.requireNonNull(clock); this.access = Objects.requireNonNull(access);
        Set<String> unique = new HashSet<>();
        for (String role : List.of("BOOTSTRAP", "SUP", "ORDER", "SUPPLY", "PRODUCTION", "QUALITY", "EQUIPMENT", "AFTERSALES")) {
            String key = env.get("MES_AGENT_" + role + "_KEY");
            if (key == null || !key.matches("[A-Za-z0-9_-]{32,256}") || !unique.add(key))
                throw new IllegalArgumentException("Missing, invalid or duplicate runtime credentials");
            keys.put(role, key.getBytes(StandardCharsets.UTF_8));
        }
        Set<String> orders = new HashSet<>();
        String allowlist = env.getOrDefault("MES_AGENT_ALLOWED_ORDER_IDS", "");
        if (allowlist.isBlank() || allowlist.length() > 20000) throw new IllegalArgumentException("Invalid order allowlist");
        for (String order : allowlist.split(",", -1)) {
            String value = order.trim();
            try { AiDeliveryQueryService.id(value); } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid order allowlist"); }
            orders.add(value);
        }
        allowedOrders = Set.copyOf(orders);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        int status = 200; Map<String,Object> response;
        try {
            Response result = exchange.getRequestURI().getRawPath().endsWith("/research")?researchRoute(exchange):route(exchange); status = result.status; response = result.body;
        } catch (Failure failure) { status = failure.status; response = object("error", failure.code); }
        catch (ResponseStatusException failure) {
            status = failure.getStatusCode().value();
            response = object("error", switch (status) {
                case 400 -> "INVALID_REQUEST"; case 404 -> "EVIDENCE_UNAVAILABLE";
                case 409 -> "DATA_CHANGED"; case 429 -> "CAPACITY_REACHED"; default -> "BUSINESS_READ_FAILED";
            });
        } catch (Exception failure) { status = 503; response = object("error", "BUSINESS_READ_FAILED"); }
        byte[] data = json.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        try { exchange.sendResponseHeaders(status, data.length); exchange.getResponseBody().write(data); }
        finally { exchange.close(); }
    }

    private Response researchRoute(HttpExchange exchange) throws IOException {
        String path=exchange.getRequestURI().getRawPath();String[] parts=path.split("/",-1);
        if(exchange.getRequestURI().getRawQuery()!=null||path.contains("%")||parts.length!=7||!parts[1].equals("stage5")||!parts[2].equals("runs")
            ||!parts[3].matches("[a-f0-9-]{36}")||!parts[4].equals("tasks")||!Set.of("QUALITY","AFTERSALES").contains(parts[5])||!parts[6].equals("research"))throw fail(404,"NOT_FOUND");
        requireRole(authenticate(exchange),parts[5]);method(exchange,"POST");body(exchange,Set.of());
        if(natural!=null&&natural.contains(parts[3]))return ok(natural.research(parts[3],parts[5]));
        // Legacy previews keep their established knowledge tool and immutable authorized scope.
        synchronized(this){return ok(begin(requireRun(parts[3]),parts[5]));}
    }

    /** Natural runs synchronize their own transitions; slow tools must not block observe/cancel HTTP requests. */
    private Response route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (exchange.getRequestURI().getRawQuery() != null || path.contains("%")) throw fail(400, "INVALID_REQUEST");
        if (path.equals("/health")) { method(exchange, "GET"); return ok(object("status", "OK")); }
        String identity = authenticate(exchange);
        if(path.equals("/stage5/natural/runs")) {
            requireRole(identity,"BOOTSTRAP");method(exchange,"POST");
            if(natural==null)throw fail(503,"NATURAL_RUNTIME_UNAVAILABLE");
            return new Response(201,natural.create(body(exchange,Set.of("query","ownerUserId","previousRunRef","previousGeneralAnswer"))));
        }
        if (path.equals("/stage5/runs")) {
            requireRole(identity, "BOOTSTRAP"); method(exchange, "POST");
            synchronized(this) { return new Response(201, create(body(exchange, Set.of("orderPk", "targetDate", "targetKind", "workflowProfile", "ownerUserId")))); }
        }
        String[] parts = path.split("/", -1);
        if (parts.length < 5 || !parts[1].equals("stage5") || !parts[2].equals("runs") || !parts[3].matches("[a-f0-9-]{36}"))
            throw fail(404, "NOT_FOUND");
        String action = parts[4];
        if(natural!=null && natural.contains(parts[3])) {
            String ref=parts[3];
            if(parts.length==5&&Set.of("context","observe","plan","finalize","cancel").contains(action)) {
                requireRole(identity,Set.of("observe","cancel").contains(action)?"BOOTSTRAP":"SUP");
                method(exchange,Set.of("context","observe").contains(action)?"GET":"POST");
                JsonNode input=null;
                if(!Set.of("context","observe").contains(action))input=body(exchange,action.equals("plan")?Set.of("action","intents","orderPk","orderNo","keyword","period","question"):Set.of());
                return ok(switch(action) {case "context"->natural.context(ref);case "observe"->natural.observe(ref);case "plan"->natural.plan(ref,input);case "cancel"->natural.cancel(ref);default->natural.finalizeRun(ref);});
            }
            if(parts.length==7&&action.equals("tasks")&&ROLES.contains(parts[5])&&Set.of("begin","complete").contains(parts[6])) {
                String role=parts[5];requireRole(identity,role);method(exchange,"POST");
                JsonNode input=body(exchange,parts[6].equals("complete")?Set.of("summary"):Set.of());
                return ok(parts[6].equals("begin")?natural.begin(ref,role):natural.complete(ref,role,input));
            }
            throw fail(404,"NOT_FOUND");
        }
        synchronized(this) {
        if(parts.length==5&&action.equals("plan")) {
            requireRole(identity,"SUP");method(exchange,"POST");JsonNode input=body(exchange,Set.of("action"));
            if(!"LEGACY".equals(requiredText(input,"action")))throw fail(400,"INVALID_PLAN");
            return ok(context(requireRun(parts[3])));
        }
        if (parts.length == 5 && action.equals("observe")) {
            requireRole(identity,"BOOTSTRAP"); method(exchange,"GET");
            Run run=runs.get(parts[3]);
            if(run==null || !run.expires.isAfter(clock.instant())) throw fail(404,"RUN_UNAVAILABLE");
            return ok(observe(run));
        }
        if (parts.length == 5 && Set.of("context", "finalize", "cancel").contains(action)) {
            requireRole(identity, action.equals("cancel") ? "BOOTSTRAP" : "SUP");
            method(exchange, action.equals("context") ? "GET" : "POST");
            if (!action.equals("context")) body(exchange, Set.of());
            Run run = requireRun(parts[3]);
            if (action.equals("context")) return ok(context(run));
            if (action.equals("cancel")) { run.cancelled = true; return ok(object("runRef", run.ref, "status", "CANCELLED")); }
            return ok(finalizeRun(run));
        }
        if (parts.length == 7 && action.equals("tasks") && ROLES.contains(parts[5]) && Set.of("begin", "complete").contains(parts[6])) {
            String role = parts[5]; requireRole(identity, role); method(exchange, "POST");
            JsonNode input = body(exchange, parts[6].equals("complete") ? Set.of("summary") : Set.of());
            Run run = requireRun(parts[3]);
            return ok(parts[6].equals("begin") ? begin(run, role) : complete(run, role, input));
        }
        throw fail(404, "NOT_FOUND");
        }
    }

    private Map<String,Object> create(JsonNode input) {
        String order = requiredText(input, "orderPk");
        if (!allowedOrders.contains(order)) throw fail(403, "ORDER_NOT_ALLOWED");
        String kind = optionalText(input, "targetKind", "FACTORY_SHIPMENT");
        String profile=optionalText(input,"workflowProfile","DELIVERY");
        if(!PROFILES.containsKey(profile))throw fail(400,"INVALID_PROFILE");
        if (!Set.of("FACTORY_SHIPMENT", "CUSTOMER_RECEIPT").contains(kind)) throw fail(400, "INVALID_REQUEST");
        LocalDate date = null;
        if (input.has("targetDate")) {
            try { date = LocalDate.parse(requiredText(input,"targetDate")); }
            catch (RuntimeException badDate) { throw fail(400, "INVALID_REQUEST"); }
        }
        runs.values().removeIf(run -> !run.expires.isAfter(clock.instant()));
        if (runs.size() >= 64) throw fail(429, "CAPACITY_REACHED");
        Instant expires = clock.instant().plusSeconds(600);
        LocalDate targetDate = date;
        long owner=PREVIEW_IDENTITY;
        if(input.has("ownerUserId")) {
            try { owner=Long.parseLong(requiredText(input,"ownerUserId")); if(owner<=0) throw new IllegalArgumentException(); }
            catch(RuntimeException invalid) { throw fail(400,"INVALID_REQUEST"); }
        }
        final long runOwner=owner;
        var evidence = access.apply(service -> service.resolve(runOwner, order, null, targetDate, kind));
        Run run = new Run(UUID.randomUUID().toString(), order, kind, date, expires, evidence,profile,owner);
        runs.put(run.ref, run);
        return context(run);
    }

    private Map<String,Object> context(Run run) {
        return object("runRef", run.ref, "scope", object("orderPk", run.order, "targetKind", run.kind,
                "targetDate", run.targetDate == null ? null : run.targetDate.toString(), "scopeRef", run.scopeRef,
                "expiresAt", run.expires.toString()), "workflowProfile",run.profile,"requiredAgents", PROFILES.get(run.profile), "sourceMode", SOURCE,
                "taskReceipts", run.tasks.values().stream().filter(task -> task.receipt != null).map(task -> task.receipt).toList());
    }

    private Map<String,Object> observe(Run run) {
        List<Map<String,Object>> tasks=new ArrayList<>();
        for(var entry:run.tasks.entrySet()) {
            Task task=entry.getValue();
            var item=object("agentId",entry.getKey(),"status",task.receipt!=null?"COMPLETED":run.cancelled?"CANCELLED":"RUNNING",
                    "startedAt",task.started.get("startedAt"),"evidence",task.started.get("evidence"));
            if(task.receipt!=null){item.put("summary",task.receipt.get("modelCommentary"));item.put("completedAt",task.receipt.get("completedAt"));}
            tasks.add(item);
        }
        return object("runRef",run.ref,"status",run.cancelled?"CANCELLED":run.finalResult!=null?"COMPLETED":"RUNNING",
                "tasks",tasks,"result",run.lastResult);
    }

    private Map<String,Object> begin(Run run, String role) {
        Task existing = run.tasks.get(role);
        if (existing != null) return existing.started;
        if (run.finalResult != null) throw fail(409, "RUN_FINALIZED");
        List<String> dependencies = switch (role) { case "SUPPLY" -> List.of("ORDER"); case "PRODUCTION" -> List.of("ORDER","SUPPLY"); default -> List.of(); };
        List<String> upstream = new ArrayList<>();
        for (String dependency : dependencies) {
            Task task = run.tasks.get(dependency);
            if (task == null || task.receipt == null) throw fail(409, "DEPENDENCY_INCOMPLETE");
            upstream.add((String) task.receipt.get("resultRef"));
        }
        Map<String,Map<String,Object>> newEvidence = new LinkedHashMap<>();
        if (role.equals("ORDER")) {
            // E1 and E4 share one repeatable-read transaction for a coherent ORDER task package.
            access.apply(service -> {
                newEvidence.put("E1", query(service,run,"E1")); newEvidence.put("E4", query(service,run,"E4"));
                return Map.of();
            });
        } else if (role.equals("SUPPLY") || role.equals("PRODUCTION")) {
            String kind = role.equals("SUPPLY") ? "E2" : "E3";
            newEvidence.put(kind, access.apply(service -> query(service, run, kind)));
        } else {
            String kind=switch(role){case "QUALITY"->"Q1";case "EQUIPMENT"->"D1";case "AFTERSALES"->"A1";default->throw fail(400,"INVALID_ROLE");};
            access.apply(service -> {if(service.hasBusinessEvidence())newEvidence.put(kind,query(service,run,kind));return Map.of();});
        }
        String status = !newEvidence.isEmpty() ? "PARTIAL" : "UNSUPPORTED_BUSINESS_SOURCE";
        var started = object("taskId", UUID.randomUUID().toString(), "agentId", role, "runRef", run.ref,
                "status", status, "evidence", List.copyOf(newEvidence.values()), "upstreamResultRefs", List.copyOf(upstream),
                "sourceMode", SOURCE, "startedAt", clock.instant().toString());
        run.evidence.putAll(newEvidence);
        run.tasks.put(role, new Task(started));
        return started;
    }

    private Map<String,Object> query(AiDeliveryQueryService service, Run run, String kind) {
        return service.query(run.owner, run.order, run.scopeRef, kind, queryRef(run,"E1"), queryRef(run,"E4"), queryRef(run,"E2"));
    }

    private Map<String,Object> complete(Run run, String role, JsonNode input) {
        String summary = requiredText(input,"summary");
        if (summary.length() > 6000) throw fail(400, "INVALID_REQUEST");
        Task task = run.tasks.get(role);
        if (task == null) throw fail(409, "TASK_NOT_BEGUN");
        if (task.receipt != null) {
            if (!summary.equals(task.receipt.get("modelCommentary"))) throw fail(409, "RESULT_CONFLICT");
            return task.receipt;
        }
        var receipt = new LinkedHashMap<>(task.started);
        receipt.put("resultRef", UUID.randomUUID().toString()); receipt.put("modelCommentary", summary);
        receipt.put("commentaryTrust", "UNTRUSTED_MODEL_COMMENTARY"); receipt.put("completedAt", clock.instant().toString());
        task.receipt = Collections.unmodifiableMap(receipt);
        return task.receipt;
    }

    private Map<String,Object> finalizeRun(Run run) {
        if (run.finalResult != null) return run.finalResult;
        List<String> absent = PROFILES.get(run.profile).stream().filter(role -> !run.tasks.containsKey(role) || run.tasks.get(role).receipt == null
                || "UNSUPPORTED_BUSINESS_SOURCE".equals(run.tasks.get(role).started.get("status"))).toList();
        List<String> refs = run.evidence.values().stream().map(value -> (String)value.get("queryRef")).toList();
        Map<String,Object> revalidation = access.apply(service -> service.revalidate(run.owner, run.order,
                run.scopeRef, refs, "1", DeliveryEvidenceRegistry.VERSION));
        Map<String,Object> revalidationData = new LinkedHashMap<>(data(revalidation));
        revalidationData.remove("replacementEvidence");
        revalidation = new LinkedHashMap<>(revalidation); revalidation.put("data",revalidationData);
        boolean changed = "CHANGED".equals(revalidationData.get("comparedFactsStatus"));
        String verdict = changed ? "DATA_CHANGED" : !absent.isEmpty() ? "INCOMPLETE" : Set.of("DELIVERY","FULL_ASSESSMENT").contains(run.profile) ? "INSUFFICIENT_EVIDENCE" : "EVIDENCE_READY";
        Set<String> missing = new LinkedHashSet<>();
        for (Map<String,Object> evidence : run.evidence.values()) {
            for (Object issue : (List<?>)evidence.get("missing")) missing.add(issue.toString());
        }
        for (Object issue : (List<?>)revalidation.get("missing")) missing.add(issue.toString());
        absent.forEach(role -> missing.add("REQUIRED_AGENT_" + role));
        if (changed) missing.add("REFRESH_RUN_REQUIRED");
        var facts = object("fulfillmentLines", changed ? null : fact(run,"E4","lines"),
                "materials", changed ? null : fact(run,"E2","materials"),
                "productionWorkOrders", changed ? null : fact(run,"E3","workOrders"), "estimatedShipmentAt", null,
                "quality",changed?null:domainFact(run,"Q1"),"equipment",changed?null:domainFact(run,"D1"),"aftersales",changed?null:domainFact(run,"A1"));
        // Public-method explanations are not business evidence and never enter the verdict or facts.
        var allExplanations=run.tasks.entrySet().stream()
                .filter(entry -> entry.getValue().receipt != null)
                .map(entry -> object("agentId",entry.getKey(),"status",entry.getValue().receipt.get("status"),
                        "resultRef",entry.getValue().receipt.get("resultRef"),"authority","UNVERIFIED_MODEL_EXPLANATION",
                        "text",entry.getValue().receipt.get("modelCommentary"))).toList();
        var explanations=allExplanations.stream().filter(e -> !REQUIRED.contains(e.get("agentId"))).toList();
        var result = object("runRef",run.ref,"workflowProfile",run.profile,"verdict",verdict,"sourceMode",SOURCE,"facts",facts,
                "knowledgeExplanations",changed?List.of():explanations,"expertExplanations",changed?List.of():allExplanations,
                "executedAgents",List.copyOf(run.tasks.keySet()),"missing",List.copyOf(missing),
                "checkedAt",revalidationData.get("checkedAt"),"queryRefs",refs,
                "resultRefs",run.tasks.values().stream().filter(task -> task.receipt != null).map(task -> task.receipt.get("resultRef")).toList(),
                "revalidation",revalidation);
        // An incomplete assessment can continue; terminal assessments are stable/idempotent until TTL.
        run.lastResult=result;
        if (changed || absent.isEmpty()) run.finalResult = result;
        return result;
    }

    private String authenticate(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null || headers.size() != 1 || !headers.get(0).startsWith("Bearer ") || headers.get(0).length() > 263)
            throw fail(401, "UNAUTHORIZED");
        byte[] supplied = headers.get(0).substring(7).getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (var entry : keys.entrySet()) if (MessageDigest.isEqual(entry.getValue(), supplied)) match = entry.getKey();
        if (match == null) throw fail(401, "UNAUTHORIZED");
        return match;
    }
    private JsonNode body(HttpExchange exchange, Set<String> allowed) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";",2)[0].trim().equalsIgnoreCase("application/json")) throw fail(415, "JSON_REQUIRED");
        byte[] bytes = exchange.getRequestBody().readNBytes(32769);
        if (bytes.length > 32768) throw fail(413, "BODY_TOO_LARGE");
        JsonNode value;
        try { value = json.readTree(bytes); } catch (IOException invalid) { throw fail(400, "INVALID_REQUEST"); }
        if (value == null || !value.isObject()) throw fail(400, "INVALID_REQUEST");
        value.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw fail(400, "INVALID_REQUEST"); });
        return value;
    }
    private Run requireRun(String ref) {
        Run run = runs.get(ref);
        if (run == null) throw fail(404, "RUN_UNAVAILABLE");
        if (run.cancelled || !run.expires.isAfter(clock.instant())) throw fail(410, "RUN_UNAVAILABLE");
        return run;
    }
    private static String requiredText(JsonNode input,String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw fail(400,"INVALID_REQUEST");
        return value.asText();
    }
    private static String optionalText(JsonNode input,String field,String fallback) { return input.has(field) ? requiredText(input,field) : fallback; }
    private static void method(HttpExchange exchange,String expected) { if (!exchange.getRequestMethod().equals(expected)) throw fail(405,"METHOD_NOT_ALLOWED"); }
    private static void requireRole(String actual,String expected) { if (!expected.equals(actual)) throw fail(403,"ROLE_FORBIDDEN"); }
    private static String queryRef(Run run,String kind) { return run.evidence.containsKey(kind) ? (String)run.evidence.get(kind).get("queryRef") : null; }
    private static Object fact(Run run,String kind,String field) { return run.evidence.containsKey(kind) ? data(run.evidence.get(kind)).get(field) : null; }
    private static Object domainFact(Run run,String kind) { return run.evidence.containsKey(kind) ? data(run.evidence.get(kind)) : null; }
    @SuppressWarnings("unchecked") private static Map<String,Object> data(Map<String,Object> envelope) { return (Map<String,Object>)envelope.get("data"); }
    private static Map<String,Object> object(Object... fields) { Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<fields.length;i+=2) result.put((String)fields[i],fields[i+1]); return result; }
    private static Response ok(Map<String,Object> body) { return new Response(200,body); }
    private static Failure fail(int status,String code) { return new Failure(status,code); }
    private record Response(int status,Map<String,Object> body) {}
    private static final class Failure extends RuntimeException {
        final int status; final String code;
        Failure(int status,String code) { super(code);this.status=status;this.code=code; }
    }
    private static final class Task {
        final Map<String,Object> started; Map<String,Object> receipt;
        Task(Map<String,Object> started) { this.started=Collections.unmodifiableMap(started); }
    }
    private static final class Run {
        final String ref,order,kind,scopeRef,profile; final LocalDate targetDate; final Instant expires;
        final long owner;
        final Map<String,Map<String,Object>> evidence=new LinkedHashMap<>(); final Map<String,Task> tasks=new LinkedHashMap<>();
        boolean cancelled; Map<String,Object> finalResult,lastResult;
        Run(String ref,String order,String kind,LocalDate targetDate,Instant expires,Map<String,Object> e0,String profile,long owner) {
            this.ref=ref;this.order=order;this.kind=kind;this.targetDate=targetDate;this.expires=expires;
            this.scopeRef=(String)e0.get("scopeRef");evidence.put("E0",e0);
            this.profile=profile;
            this.owner=owner;
        }
    }
}
