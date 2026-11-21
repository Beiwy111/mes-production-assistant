package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper;
import com.upc.computer.assistant.v2.tools.delivery.AiDeliveryQueryService;
import com.upc.computer.assistant.v2.tools.delivery.DeliveryEvidenceRegistry;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP, registry and E0-E5 arithmetic; only external database reads are replaced. */
class Stage5RuntimeServerTest {
    @Test void naturalHttpObservationIsNotSerializedBehindBusinessTool() throws Exception {
        server.close();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var overview=mock(NaturalBusinessReadMapper.class);
        when(overview.rows("INVENTORY",null,null,null,null)).thenAnswer(call->{entered.countDown();release.await(4,TimeUnit.SECONDS);return List.of();});
        Clock clock=Clock.fixed(now.get(),ZoneOffset.UTC);
        var delivery=new AiDeliveryQueryService(mapper,new DeliveryEvidenceRegistry(clock),clock);
        var natural=new NaturalLanguageRuntime(clock,(owner,op)->op.apply(new NaturalLanguageRuntime.Data(overview,delivery)));
        server=new Stage5RuntimeServer(env,clock,op->op.apply(delivery),natural,0);server.start();base="http://127.0.0.1:"+server.port();
        String ref=body(request("POST","/stage5/natural/runs","BOOTSTRAP","{\"query\":\"查库存\",\"ownerUserId\":\"42\"}")).path("runRef").asText();
        assertEquals(200,request("POST","/stage5/runs/"+ref+"/plan","SUP","{\"action\":\"QUERY\",\"intents\":[\"INVENTORY\"],\"orderPk\":null,\"orderNo\":null,\"keyword\":null,\"period\":\"CURRENT\",\"question\":\"\"}").statusCode());
        var executor=Executors.newSingleThreadExecutor();
        var begin=executor.submit(()->request("POST","/stage5/runs/"+ref+"/tasks/SUPPLY/begin","SUPPLY","{}"));
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1),()->{
                JsonNode observed=body(request("GET","/stage5/runs/"+ref+"/observe","BOOTSTRAP",null));
                assertEquals("RUNNING",observed.at("/tasks/0/status").asText());
            });
        }finally{release.countDown();begin.get(3,TimeUnit.SECONDS);executor.shutdownNow();}
    }
    final ObjectMapper json = new ObjectMapper();
    final HttpClient client = HttpClient.newHttpClient();
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T04:00:00Z"));
    final AiDeliveryReadMapper mapper = mock(AiDeliveryReadMapper.class);
    final Map<String,String> env = keys();
    Stage5RuntimeServer server;
    String base;

    @BeforeEach void start() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(i -> now.get());
        var head = List.<Map<String,Object>>of(Map.of("order_id", 10L, "order_no", "O-10"));
        when(mapper.resolve(10L, null)).thenReturn(head);
        when(mapper.order(10L)).thenReturn(head);
        when(mapper.lines(10L)).thenReturn(List.of(Map.of("order_item_id", 1L, "material_id", 9L,
                "quantity", "100", "unit", "pcs", "material_unit", "pcs", "delivery_date", "2026-12-01")));
        var service = new AiDeliveryQueryService(mapper, new DeliveryEvidenceRegistry(clock), clock);
        Function<Function<AiDeliveryQueryService,Map<String,Object>>,Map<String,Object>> access = operation -> operation.apply(service);
        server = new Stage5RuntimeServer(env, clock, access, 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach void stop() throws Exception { if (server != null) server.close(); }

    @Test void allSixRolesReturnRegisteredBusinessEvidenceAndDomainResultsReachFinalAnswer() throws Exception {
        var domain=enableBusiness();
        when(domain.read(10L)).thenReturn(Map.of("quality_inspection",List.of(Map.of("inspection_id",8L,"inspection_status","PENDING"))));
        String run=create();finishCore(run);
        for(String role:List.of("QUALITY","EQUIPMENT","AFTERSALES")) {
            var started=body(post(run,role,"begin","{}"));
            assertEquals("PARTIAL",started.path("status").asText());
            assertEquals(1,started.path("evidence").size());
            assertFalse(started.path("evidence").get(0).path("queryRef").asText().isBlank());
            complete(run,role,"Explanation only; no new quantities.");
        }
        var result=finalizeRun(run);
        assertEquals(1,result.path("facts").path("quality").path("inspectionCount").asInt());
        assertEquals("EMPTY_RECORDED",result.path("facts").path("equipment").path("status").asText());
        assertEquals(6,result.path("expertExplanations").size());
        assertEquals(8,result.path("queryRefs").size());
    }
    @Test void domainChangesInvalidateDeliveryAndHideStaleDomainFacts() throws Exception {
        var domain=enableBusiness();when(domain.read(10L)).thenReturn(Map.of());
        String run=create();finishCore(run);post(run,"QUALITY","begin","{}");complete(run,"QUALITY","old comment");
        when(domain.read(10L)).thenReturn(Map.of("quality_inspection",List.of(Map.of("inspection_id",9L))));
        var result=finalizeRun(run);
        assertEquals("DATA_CHANGED",result.path("verdict").asText());
        assertTrue(result.path("facts").path("quality").isNull());
        assertEquals(0,result.path("expertExplanations").size());
        assertEquals(0,result.path("knowledgeExplanations").size());
    }
    @Test void qualityProfileRequiresOnlyQualityAndDoesNotPretendToAssessDelivery() throws Exception {
        var domain=enableBusiness();when(domain.read(10L)).thenReturn(Map.of());
        var response=request("POST","/stage5/runs","BOOTSTRAP","{\"orderPk\":\"10\",\"workflowProfile\":\"QUALITY\"}");
        assertEquals(201,response.statusCode());var created=body(response);
        assertEquals("QUALITY",created.path("workflowProfile").asText());
        assertEquals(1,created.path("requiredAgents").size());String run=created.path("runRef").asText();
        post(run,"QUALITY","begin","{}");complete(run,"QUALITY","No inspection records in this scope.");
        var result=finalizeRun(run);assertEquals("EVIDENCE_READY",result.path("verdict").asText());
        assertEquals("EMPTY_RECORDED",result.path("facts").path("quality").path("status").asText());
        assertEquals(1,result.path("executedAgents").size());
    }
    AiBusinessReadMapper enableBusiness() throws Exception {
        server.close();var domain=mock(AiBusinessReadMapper.class);
        Clock clock=Clock.fixed(now.get(),ZoneOffset.UTC);
        var service=new AiDeliveryQueryService(mapper,domain,new DeliveryEvidenceRegistry(clock),clock);
        server=new Stage5RuntimeServer(env,clock,operation->operation.apply(service),0);
        server.start();base="http://127.0.0.1:"+server.port();return domain;
    }

    @Test void authenticationAndAllowlistFailBeforeAnyDatabaseRead() throws Exception {
        assertEquals(401, request("POST", "/stage5/runs", null, "{\"orderPk\":\"10\"}").statusCode());
        assertEquals(403, request("POST", "/stage5/runs", "SUP", "{\"orderPk\":\"10\"}").statusCode());
        assertEquals(403, request("POST", "/stage5/runs", "BOOTSTRAP", "{\"orderPk\":\"11\"}").statusCode());
        verifyNoInteractions(mapper);
        assertEquals("OK", body(request("GET", "/health", null, null)).path("status").asText());
    }

    @Test void observerRequiresBootstrapAndReportsBegunCompletedCancelledWithoutInventingTasks() throws Exception {
        String run=create(); String path="/stage5/runs/"+run+"/observe";
        assertEquals(403,request("GET",path,"SUP",null).statusCode());
        assertEquals(0,body(request("GET",path,"BOOTSTRAP",null)).path("tasks").size());
        post(run,"ORDER","begin","{}");
        JsonNode running=body(request("GET",path,"BOOTSTRAP",null)).path("tasks").get(0);
        assertEquals("RUNNING",running.path("status").asText());
        assertFalse(running.path("startedAt").asText().isBlank());
        assertFalse(running.has("completedAt"));
        complete(run,"ORDER","Recorded order evidence");
        JsonNode completed=body(request("GET",path,"BOOTSTRAP",null)).path("tasks").get(0);
        assertEquals("COMPLETED",completed.path("status").asText());
        assertEquals("Recorded order evidence",completed.path("summary").asText());
        assertFalse(completed.path("completedAt").asText().isBlank());
        post(run,"SUPPLY","begin","{}");
        request("POST","/stage5/runs/"+run+"/cancel","BOOTSTRAP","{}");
        JsonNode cancelled=body(request("GET",path,"BOOTSTRAP",null));
        assertEquals("CANCELLED",cancelled.path("status").asText());
        assertEquals("COMPLETED",cancelled.path("tasks").get(0).path("status").asText());
        assertEquals("CANCELLED",cancelled.path("tasks").get(1).path("status").asText());
    }
    @Test void bootstrapCanBindEndUserIdentityAndAllSubsequentReadsUseThatIdentity() throws Exception {
        assertEquals(400,request("POST","/stage5/runs","BOOTSTRAP","{\"orderPk\":\"10\",\"ownerUserId\":\"-5\"}").statusCode());
        var response=request("POST","/stage5/runs","BOOTSTRAP","{\"orderPk\":\"10\",\"ownerUserId\":\"7\",\"workflowProfile\":\"ORDER\"}");
        assertEquals(201,response.statusCode());String run=body(response).path("runRef").asText();
        assertEquals(200,post(run,"ORDER","begin","{}").statusCode());complete(run,"ORDER","Scoped evidence");
        assertEquals("EVIDENCE_READY",finalizeRun(run).path("verdict").asText());
        assertEquals("EVIDENCE_READY",body(request("GET","/stage5/runs/"+run+"/observe","BOOTSTRAP",null)).path("result").path("verdict").asText());
    }

    @Test void strictBodiesDoNotAllowClientFactsRoleScopeOrResultReferences() throws Exception {
        assertEquals(400, request("POST", "/stage5/runs", "BOOTSTRAP", "{\"orderPk\":10}").statusCode());
        assertEquals(400, request("POST", "/stage5/runs", "BOOTSTRAP", "{\"orderPk\":\"10\",\"orderPk\":\"11\"}").statusCode());
        assertEquals(400, request("POST", "/stage5/runs", "BOOTSTRAP", "{\"orderPk\":\"10\",\"sql\":\"SELECT 1\"}").statusCode());
        String run = create();
        assertEquals(400, post(run, "ORDER", "begin", "{\"runRef\":\"other\"}").statusCode());
        assertEquals(200, post(run, "ORDER", "begin", "{}").statusCode());
        assertEquals(400, post(run, "ORDER", "complete", "{\"summary\":\"ok\",\"resultRef\":\"forged\"}").statusCode());
        assertEquals(400, post(run, "ORDER", "complete", "{\"summary\":\"ok\",\"shippableQty\":100}").statusCode());
        assertEquals(400, request("GET", "/stage5/runs/" + run + "/context?orderPk=11", "SUP", null).statusCode());
        assertEquals(400, post(run, "ORDER", "complete", json.writeValueAsString(Map.of("summary", "x".repeat(6001)))).statusCode());
    }

    @Test void dependenciesRequireServerReceiptsAndRolesCannotReadSiblings() throws Exception {
        String run = create();
        assertEquals(403, request("POST", path(run,"ORDER","begin"), "SUPPLY", "{}").statusCode());
        assertEquals(403, request("GET", "/stage5/runs/" + run + "/context", "ORDER", null).statusCode());
        assertEquals(409, post(run,"SUPPLY","begin","{}").statusCode());
        assertEquals(409, post(run,"ORDER","complete","{\"summary\":\"ok\"}").statusCode());
        assertEquals(200, post(run,"ORDER","begin","{}").statusCode());
        assertEquals(409, post(run,"SUPPLY","begin","{}").statusCode());
        JsonNode order = complete(run,"ORDER","ordered");
        JsonNode supply = body(post(run,"SUPPLY","begin","{}"));
        assertEquals(order.path("resultRef").asText(), supply.path("upstreamResultRefs").get(0).asText());
        assertEquals(409, post(run,"PRODUCTION","begin","{}").statusCode());
        complete(run,"SUPPLY","materials");
        assertEquals(200, post(run,"PRODUCTION","begin","{}").statusCode());
        String other = create();
        assertEquals(409, post(other,"SUPPLY","begin","{}").statusCode());
        assertEquals(404, post(UUID.randomUUID().toString(),"ORDER","begin","{}").statusCode());
    }

    @Test void concurrentBeginsAndExactCompletionRepeatsAreIdempotent() throws Exception {
        String run = create();
        try (var pool = new CloseablePool()) {
            var first = pool.executor.submit(() -> body(post(run,"ORDER","begin","{}")));
            var second = pool.executor.submit(() -> body(post(run,"ORDER","begin","{}")));
            assertEquals(first.get(10,TimeUnit.SECONDS), second.get(10,TimeUnit.SECONDS));
        }
        verify(mapper, times(3)).order(10L); // E0, E1, E4, no duplicate begin queries
        JsonNode receipt = complete(run,"ORDER","one interpretation");
        assertEquals(receipt, complete(run,"ORDER","one interpretation"));
        assertEquals(409, post(run,"ORDER","complete","{\"summary\":\"different\"}").statusCode());
        assertEquals("one interpretation", receipt.path("modelCommentary").asText());
    }

    @Test void unsupportedExpertsNeverInventBusinessFacts() throws Exception {
        String run = create();
        for (String role : List.of("QUALITY","EQUIPMENT","AFTERSALES")) {
            var task = body(post(run,role,"begin","{}"));
            assertEquals("UNSUPPORTED_BUSINESS_SOURCE", task.path("status").asText());
            assertEquals(0, task.path("evidence").size());
            assertEquals("UNSUPPORTED_BUSINESS_SOURCE", complete(run,role,"public knowledge only").path("status").asText());
        }
        verify(mapper,times(1)).order(10L);
    }

    @Test void finalizationRequiresCoreAgentsAndIgnoresModelPromises() throws Exception {
        String run = create();
        JsonNode incomplete = finalizeRun(run);
        assertEquals("INCOMPLETE", incomplete.path("verdict").asText());
        assertEquals(0, incomplete.path("executedAgents").size());
        finishCore(run);
        JsonNode result = finalizeRun(run);
        assertEquals("INSUFFICIENT_EVIDENCE", result.path("verdict").asText());
        assertEquals(3, result.path("executedAgents").size());
        JsonNode line = result.path("facts").path("fulfillmentLines").get(0);
        assertEquals("100", line.path("remainingDeliveryQty").asText());
        assertTrue(line.path("shippableQty").isNull());
        assertTrue(result.path("facts").path("estimatedShipmentAt").isNull());
        assertEquals("UNCHANGED", result.path("revalidation").path("data").path("comparedFactsStatus").asText());
        assertFalse(result.path("revalidation").path("data").has("replacementEvidence"));
        assertTrue(result.path("missing").size() > 0);
        assertEquals(result, finalizeRun(run));
    }

    @Test void finalizationDetectsChangedFactsAndDoesNotReuseOldFulfillment() throws Exception {
        String run = create();
        finishCore(run);
        when(mapper.deliveries(10L)).thenReturn(List.of(Map.of("delivery_id",1L,"material_id",9L,
                "delivery_quantity","20","delivery_status","SHIPPED")));
        var result = finalizeRun(run);
        assertEquals("DATA_CHANGED", result.path("verdict").asText());
        assertTrue(result.path("facts").path("fulfillmentLines").isNull());
        assertTrue(result.path("missing").toString().contains("REFRESH_RUN_REQUIRED"));
    }

    @Test void optionalExplanationSurvivesFinalizationWithoutBecomingBusinessEvidence() throws Exception {
        String run=create();
        finishCore(run);
        assertEquals(200,post(run,"QUALITY","begin","{}").statusCode());
        var receipt=complete(run,"QUALITY","Public PGC method explanation [P02, PDF page 16]; no real inspection data.");
        var result=finalizeRun(run);
        assertEquals("INSUFFICIENT_EVIDENCE",result.path("verdict").asText());
        assertEquals(1,result.path("knowledgeExplanations").size());
        var explanation=result.path("knowledgeExplanations").get(0);
        assertEquals(receipt.path("resultRef"),explanation.path("resultRef"));
        assertEquals("UNVERIFIED_MODEL_EXPLANATION",explanation.path("authority").asText());
        assertEquals("UNSUPPORTED_BUSINESS_SOURCE",explanation.path("status").asText());
        assertTrue(explanation.path("text").asText().contains("PDF page 16"));
        assertTrue(result.path("facts").path("fulfillmentLines").get(0).path("shippableQty").isNull());
    }

    @Test void expiredAndCancelledRunsCannotQueryAndCapacityIsBounded() throws Exception {
        String cancelled = create();
        assertEquals(200, request("POST","/stage5/runs/"+cancelled+"/cancel","BOOTSTRAP","{}").statusCode());
        assertEquals(410, post(cancelled,"ORDER","begin","{}").statusCode());
        String expired = create();
        now.set(now.get().plusSeconds(600));
        assertEquals(410, post(expired,"ORDER","begin","{}").statusCode());
        for (int i=0;i<64;i++) create();
        assertEquals(429, request("POST","/stage5/runs","BOOTSTRAP","{\"orderPk\":\"10\"}").statusCode());
    }

    @Test void failuresAreSanitizedAndFailedBeginDoesNotPublishPartialTask() throws Exception {
        String run = create();
        when(mapper.deliveries(10L)).thenThrow(new IllegalStateException("jdbc password=SECRET customer phone=123"));
        var failure = post(run,"ORDER","begin","{}");
        assertEquals(503,failure.statusCode());
        assertFalse(failure.body().contains("SECRET"));
        assertEquals(409,post(run,"ORDER","complete","{\"summary\":\"fake success\"}").statusCode());
    }

    void finishCore(String run) throws Exception {
        for (String role:List.of("ORDER","SUPPLY","PRODUCTION")) {
            assertEquals(200,post(run,role,"begin","{}").statusCode());
            complete(run,role,"Everything is ready: 999999 units, guaranteed on time.");
        }
    }
    String create() throws Exception {
        var response=request("POST","/stage5/runs","BOOTSTRAP","{\"orderPk\":\"10\"}");
        assertEquals(201,response.statusCode(),response.body());
        var node=body(response);
        assertEquals("LOCAL_READONLY_PREVIEW",node.path("sourceMode").asText());
        return node.path("runRef").asText();
    }
    JsonNode complete(String run,String role,String summary) throws Exception {
        var response=post(run,role,"complete",json.writeValueAsString(Map.of("summary",summary)));
        assertEquals(200,response.statusCode(),response.body()); return body(response);
    }
    JsonNode finalizeRun(String run) throws Exception {
        var response=request("POST","/stage5/runs/"+run+"/finalize","SUP","{}");
        assertEquals(200,response.statusCode(),response.body());return body(response);
    }
    HttpResponse<String> post(String run,String role,String action,String data) throws Exception { return request("POST",path(run,role,action),role,data); }
    String path(String run,String role,String action) { return "/stage5/runs/"+run+"/tasks/"+role+"/"+action; }
    JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    HttpResponse<String> request(String method,String path,String role,String data) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(role!=null) request.header("Authorization","Bearer "+env.get("MES_AGENT_"+role+"_KEY"));
        request.header("Content-Type","application/json");
        return client.send(request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static Map<String,String> keys() {
        Map<String,String> values=new HashMap<>();
        for(String role:List.of("BOOTSTRAP","SUP","ORDER","SUPPLY","PRODUCTION","QUALITY","EQUIPMENT","AFTERSALES"))
            values.put("MES_AGENT_"+role+"_KEY",role+"_"+"a".repeat(40));
        values.put("MES_AGENT_ALLOWED_ORDER_IDS","10"); return values;
    }
    static class CloseablePool implements AutoCloseable {
        final ExecutorService executor=Executors.newFixedThreadPool(2);
        public void close(){executor.shutdownNow();}
    }
}
