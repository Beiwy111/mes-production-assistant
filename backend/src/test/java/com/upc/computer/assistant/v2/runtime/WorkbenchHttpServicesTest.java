package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchHttpServicesTest {
    final ObjectMapper json=new ObjectMapper(); HttpServer upstream; String base;
    final AtomicReference<String> auth=new AtomicReference<>(),payload=new AtomicReference<>();
    final AtomicReference<String> difyPayload=new AtomicReference<>(),difyAuth=new AtomicReference<>();
    final AtomicInteger workflowCalls=new AtomicInteger();
    final AtomicInteger runtimeCalls=new AtomicInteger();
    volatile String intakeOutput="{\"action\":\"ORDER\",\"fields\":{\"quantity\":100},\"clearFields\":[],\"question\":\"\"}";
    final AtomicBoolean active=new AtomicBoolean(true); volatile String mode="success";
    WorkbenchHttpServices services;
    @BeforeEach void start()throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String path=exchange.getRequestURI().getPath(),body;
            if(mode.equals("observation-forbidden")&&path.endsWith("/observe")){
                exchange.sendResponseHeaders(403,-1);exchange.close();return;
            }
            if(mode.equals("slow-observation")&&path.endsWith("/observe")){
                try{Thread.sleep(5500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }
            if(path.startsWith("/stage5/"))runtimeCalls.incrementAndGet();
            if(path.equals("/auth/userInfo"))body=mode.equals("expired")?"{\"code\":401}":"{\"code\":200,\"data\":{\"userId\":7,\"roleCode\":\"ADMIN\"}}";
            else if(path.endsWith("/observe"))body=mode.equals("history-observation")?
                "{\"tasks\":[],\"result\":{\"history\":\""+"x".repeat(600000)+"\"}}":
                "{\"result\":{\"runRef\":\""+run()+"\",\"facts\":{\"trustedCount\":7},\"verdict\":\"EVIDENCE_READY\",\"missing\":[]},\"tasks\":[]}";
            else if(path.equals("/stage5/runs")||path.equals("/stage5/natural/runs"))body="{\"runRef\":\""+run()+"\"}";
            else if(path.equals("/v1/workflows/run")){
                workflowCalls.incrementAndGet();difyPayload.set(payload.get());difyAuth.set(auth.get());
                String event=mode.equals("failed")?"{\"event\":\"error\",\"message\":\"SECRET sql password\"}":
                        "{\"event\":\"workflow_finished\",\"task_id\":\"task-123\",\"data\":{\"status\":\"succeeded\",\"outputs\":{\"answer\":\"Final markdown\",\"decision\":\"{\\\"runRef\\\":\\\""+run()+"\\\",\\\"facts\\\":{\\\"trustedCount\\\":999},\\\"verdict\\\":\\\"EVIDENCE_READY\\\"}\"}}}";
                String intermediate=mode.equals("large-node")||mode.equals("oversized-node")?
                        "data: {\"event\":\"node_finished\",\"data\":{\"outputs\":\""+"x".repeat(mode.equals("large-node")?300000:1048577)+"\"}}\n\n":"";
                if(mode.equals("chunks")) {
                    intermediate="";
                    for(String[] part:List.of(new String[]{"planner_llm","SECRET planner JSON"},new String[]{"response_llm","Final "},new String[]{"response_llm","markdown"}))
                        intermediate+="data: "+json.writeValueAsString(Map.of("event","text_chunk","data",Map.of("from_variable_selector",List.of(part[0],"text"),"text",part[1])))+"\n\n";
                }
                body="data: {\"event\":\"workflow_started\",\"task_id\":\"task-123\"}\n\n"+intermediate+"data: "+event+"\n\n";
                if(mode.equals("intake"))body=json.writeValueAsString(Map.of("data",Map.of("status","succeeded","outputs",Map.of("order_intake_json",intakeOutput))));
            }else body="{}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });upstream.start();base="http://127.0.0.1:"+upstream.getAddress().getPort();
        services=new WorkbenchHttpServices(URI.create(base),URI.create(base),URI.create(base+"/v1"),"bootstrap-secret","app-secret",id->id==7&&active.get());
    }
    @AfterEach void close(){upstream.stop(0);}
    @Test void completeHistoryObservationsAreNotLimitedToHalfAMegabyte()throws Exception {
        mode="history-observation";
        assertEquals(600000,services.observe(run()).at("/result/history").asText().length());
    }
    @Test void finalAuthorizationAndEvidenceReadCanWaitForRuntimeRevalidation()throws Exception {
        mode="slow-observation";
        var result=services.workflow(run(),"核对同批两期质检",7,task->{});
        assertEquals("EVIDENCE_READY",result.path("verdict").asText());
        assertEquals(7,result.at("/facts/trustedCount").asInt());
        assertEquals(1,runtimeCalls.get());
    }
    @Test void revokedObservationNeverFallsBackToDifyAnswer() {
        mode="observation-forbidden";
        var denied=assertThrows(WorkbenchGatewayServer.Failure.class,()->services.workflow(run(),"查库存",7,task->{}));
        assertEquals(403,denied.status);assertEquals("PERMISSION_CHANGED",denied.code);
    }
    @Test void intakeUsesExistingSupKeyAndNoStage5RunAndValidatesModelOutput() throws Exception {
        mode="intake";
        JsonNode result=services.orderIntake(7,"下100台","{\"currentDate\":\"2026-11-14\"}");
        assertEquals(100,result.at("/fields/quantity").asInt());assertEquals(0,runtimeCalls.get());
        JsonNode request=json.readTree(difyPayload.get());
        assertEquals("Bearer app-secret",difyAuth.get());assertEquals("blocking",request.path("response_mode").asText());
        assertEquals("mes-workbench-user-7",request.path("user").asText());
        assertEquals("{\"currentDate\":\"2026-11-14\"}",request.at("/inputs/order_intake").asText());
        assertDoesNotThrow(()->UUID.fromString(request.at("/inputs/run_ref").asText()));
        for(String fields:List.of("{\"materialId\":1}","{\"approved\":true}","{\"quantity\":1.5}","{\"quantity\":true}","{\"requiredDeliveryDate\":\"2026-02-30\"}")) {
            intakeOutput="{\"action\":\"ORDER\",\"fields\":"+fields+",\"clearFields\":[],\"question\":\"\"}";
            assertEquals(502,assertThrows(WorkbenchGatewayServer.Failure.class,()->services.orderIntake(7,"下单","{}")).status);
        }
        for(String invalid:List.of(
                "{\"action\":\"ORDER\",\"action\":\"QUERY\",\"fields\":{},\"clearFields\":[],\"question\":\"\"}",
                "{\"action\":\"QUERY\",\"fields\":{\"quantity\":100},\"clearFields\":[],\"question\":\"\"}",
                "{\"action\":\"ORDER\",\"fields\":{\"quantity\":100},\"clearFields\":[\"quantity\"],\"question\":\"\"}",
                "{\"action\":\"ORDER\",\"fields\":{},\"clearFields\":[\"materialId\"],\"question\":\"\"}",
                "{\"action\":\"CONFIRM\",\"fields\":{},\"clearFields\":[],\"question\":\"\"}")) {
            intakeOutput=invalid;
            assertEquals(502,assertThrows(WorkbenchGatewayServer.Failure.class,()->services.orderIntake(7,"下单","{}")).status);
        }
        assertEquals(0,runtimeCalls.get());
    }
    @Test void intakeChecksAllowedRoleFreshAndDoesNotBroadenAdminAuthentication() throws Exception {
        AtomicBoolean allowed=new AtomicBoolean(true);
        var scoped=new WorkbenchHttpServices(URI.create(base),URI.create(base),URI.create(base+"/v1"),"bootstrap-secret","app-secret",id->false,id->id==7&&allowed.get());
        assertEquals(7,scoped.authenticateOrderIntake("Bearer mes-token"));
        assertEquals(403,assertThrows(WorkbenchGatewayServer.Failure.class,()->scoped.authenticate("Bearer mes-token")).status);
        allowed.set(false);
        assertEquals(403,assertThrows(WorkbenchGatewayServer.Failure.class,()->scoped.authenticateOrderIntake("Bearer mes-token")).status);
        allowed.set(true);mode="expired";
        assertEquals(401,assertThrows(WorkbenchGatewayServer.Failure.class,()->scoped.authenticateOrderIntake("Bearer mes-token")).status);
    }
    @Test void authenticatesWithMesAndRechecksLiveAdminNotCachedRole()throws Exception {
        assertEquals(7,services.authenticate("Bearer mes-token"));assertEquals("Bearer mes-token",auth.get());
        active.set(false);assertEquals(403,assertThrows(WorkbenchGatewayServer.Failure.class,()->services.authenticate("Bearer mes-token")).status);
        active.set(true);mode="expired";assertEquals(401,assertThrows(WorkbenchGatewayServer.Failure.class,()->services.authenticate("Bearer mes-token")).status);
    }
    @Test void naturalCreationTransmitsOnlyUserBoundQuestionAndPreviousReference()throws Exception {
        assertTrue(services.naturalLanguage());
        assertEquals(run(),services.createNatural(7,"查库存",null));
        JsonNode input=json.readTree(payload.get());
        assertEquals("7",input.path("ownerUserId").asText());assertEquals("查库存",input.path("query").asText());
        assertFalse(input.has("orderPk"));assertFalse(input.has("workflowProfile"));assertFalse(input.has("previousRunRef"));
        services.createNatural(7,"它的进度呢",run());
        assertEquals(run(),json.readTree(payload.get()).path("previousRunRef").asText());
    }
    @Test void createsScopeForActualUserAndCallsPublicDifyWithExactDslInputs()throws Exception {
        assertEquals(run(),services.create(7,"100","QUALITY"));
        JsonNode create=json.readTree(payload.get());assertEquals("7",create.path("ownerUserId").asText());assertEquals("QUALITY",create.path("workflowProfile").asText());
        AtomicReference<String> task=new AtomicReference<>();JsonNode result=services.workflow(run(),"question",7,task::set);
        assertEquals("task-123",task.get());assertEquals("Final markdown",result.path("reply").asText());
        // Workflow prose/decision cannot override authoritative finalized runtime evidence.
        assertEquals(7,result.path("facts").path("trustedCount").asInt());
        assertFalse(result.toString().contains("app-secret"));
        JsonNode sent=json.readTree(difyPayload.get());assertEquals("Bearer app-secret",difyAuth.get());
        assertEquals(run(),sent.path("inputs").path("run_ref").asText());assertEquals("question",sent.path("inputs").path("query").asText());
        assertEquals("streaming",sent.path("response_mode").asText());assertEquals("mes-workbench-user-7",sent.path("user").asText());
    }
    @Test void suppressesUpstreamErrorsWithoutRetry() {
        mode="failed";Exception failure=assertThrows(Exception.class,()->services.workflow(run(),"q",7,id->{}));
        assertFalse(failure.getMessage().contains("SECRET"));
        assertEquals(1,workflowCalls.get());
    }
    @Test void streamsOnlyFinalPresentationAfterAnAuthoritativeSnapshot() throws Exception {
        mode="chunks";List<JsonNode> events=new ArrayList<>();
        JsonNode result=services.workflow(run(),"q",7,id->{},events::add);
        assertEquals(3,events.size());assertEquals("snapshot",events.get(0).path("type").asText());
        assertEquals(7,events.get(0).at("/data/facts/trustedCount").asInt());
        assertEquals("Final ",events.get(1).path("text").asText());assertEquals("markdown",events.get(2).path("text").asText());
        assertFalse(events.toString().contains("SECRET"));assertEquals("Final markdown",result.path("reply").asText());
    }
    @Test void acceptsBoundedLargeIntermediateNodeEventWithoutExposingItsPayload() throws Exception {
        mode="large-node";
        JsonNode result=services.workflow(run(),"q",7,id->{});
        assertEquals("Final markdown",result.path("reply").asText());
        assertEquals(7,result.path("facts").path("trustedCount").asInt());
        assertTrue(result.toString().length()<1000);
    }
    @Test void rejectsIntermediateEventOverOneMiBWithControlledLimitCode() {
        mode="oversized-node";
        var failure=assertThrows(WorkbenchGatewayServer.Failure.class,()->services.workflow(run(),"q",7,id->{}));
        assertEquals("DIFY_EVENT_LIMIT",failure.code);
        assertEquals(1,workflowCalls.get());
    }
    static String run(){return "12345678-1234-1234-1234-123456789abc";}
}
