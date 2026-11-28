package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP boundary tests: an external workflow is the only substituted dependency. */
class WorkbenchGatewayServerTest {
    @Test void reconnectPicksUpOnlyThePublishedKeyForTheConfiguredUbuntu(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception {
        var path=dir.resolve("local.env");
        String base="http://127.0.0.1:21434/v1";
        var env=Map.of("MES_WORKBENCH_V2_BINDING_FILE",path.toString(),"MES_WORKFLOW_V2_BASE_URL",base);
        assertNull(WorkbenchRuntimeMain.localWorkflowKey(env));
        java.nio.file.Files.writeString(path,"MES_WORKFLOW_V2_BASE_URL="+base+"\n");
        assertNull(WorkbenchRuntimeMain.localWorkflowKey(env));
        java.nio.file.Files.writeString(path,"MES_WORKFLOW_V2_BASE_URL="+base+"\nMES_WORKBENCH_V2_DIFY_API_KEY=app-testV2\n");
        assertEquals("app-testV2",WorkbenchRuntimeMain.localWorkflowKey(env));
        java.nio.file.Files.writeString(path,"MES_WORKFLOW_V2_BASE_URL=http://different/v1\nMES_WORKBENCH_V2_DIFY_API_KEY=app-testOther\n");
        assertNull(WorkbenchRuntimeMain.localWorkflowKey(env));
    }
    @Test void twoPublishedModelBindingsAreIndependentAndStopUsesTheIssuingApp()throws Exception {
        server.close();
        FakeServices local=new FakeServices();
        local.block=true;
        server=new WorkbenchGatewayServer(services,() -> local,new EverydayChat(),EverydayChat.localV2(Map.of("MES_WORKFLOW_V2_BASE_URL","http://127.0.0.1:1/v1")),
                Set.of("100"),Clock.fixed(now.get(),ZoneOffset.UTC),Duration.ofSeconds(4),0,ConversationScopeStore.disabled(),RunAuditStore.disabled());
        server.start();base="http://127.0.0.1:"+server.port()+"/assistant-workbench";
        var inputs=json.readTree(input("local")).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)inputs).put("workflowModel","LOCAL");
        var running=http.sendAsync(builder("POST","/runs/stream","owner",inputs.toString()),HttpResponse.BodyHandlers.ofString());
        assertTrue(local.started.await(3,TimeUnit.SECONDS));
        String localRun=services.lastRun;
        var cloud=request("POST","/runs/stream","owner",input("cloud"));
        assertEquals(200,cloud.statusCode());assertTrue(cloud.body().contains("\"workflowModel\":\"API\""));
        var cancel=request("POST","/runs/"+localRun+"/cancel","owner","{}");
        assertEquals(200,cancel.statusCode());assertTrue(local.stopped.get());assertFalse(services.stopped.get());
        assertEquals(200,running.get(5,TimeUnit.SECONDS).statusCode());
        ((com.fasterxml.jackson.databind.node.ObjectNode)inputs).put("workflowModel","mes-auto");
        assertEquals(400,request("POST","/runs/stream","owner",inputs.toString()).statusCode());
        assertEquals(1,local.streamed.get());assertEquals(1,services.streamed.get());
    }
    @Test void auditSurvivesRestartAndStillRequiresFreshOwnerAuthorization(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception {
        Clock fixed=Clock.fixed(now.get(),ZoneOffset.UTC);String key="audit-only-test-signing-key-123456";
        server.close();server=new WorkbenchGatewayServer(services,Set.of("100"),fixed,Duration.ofSeconds(3),0,ConversationScopeStore.disabled(),new RunAuditStore(dir,fixed,key));
        server.start();base="http://127.0.0.1:"+server.port()+"/assistant-workbench";
        var req=HttpRequest.newBuilder(builder("POST","/runs/stream","owner",input("audit")),(a,b)->true).header("Accept","text/event-stream").build();
        var response=http.send(req,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());String id=services.lastRun;
        assertEquals(404,request("GET","/runs/"+id+"/audit","other",null).statusCode());
        server.close();server=new WorkbenchGatewayServer(services,Set.of("100"),fixed,Duration.ofSeconds(3),0,ConversationScopeStore.disabled(),new RunAuditStore(dir,fixed,key));
        server.start();base="http://127.0.0.1:"+server.port()+"/assistant-workbench";
        var saved=request("GET","/runs/"+id+"/audit","owner",null);assertEquals(200,saved.statusCode());
        var audit=json.readTree(saved.body());assertEquals("COMPLETED",audit.path("status").asText());
        assertEquals("PERSISTED",audit.path("persistence").asText());
        for(JsonNode event:audit.path("events"))assertTrue(response.body().contains(event.path("eventId").asText()));
        assertEquals(404,request("GET","/runs/"+id,"owner",null).statusCode());
        assertEquals(404,request("GET","/runs/"+id+"/audit","other",null).statusCode());
        assertEquals(403,request("GET","/runs/"+id+"/audit","nonadmin",null).statusCode());
    }
    @Test void sseNegotiationHasScopedOrderedLifecycleAndNativeDeltas() throws Exception {
        services.liveTokens=true;
        var req=HttpRequest.newBuilder(builder("POST","/runs/stream","owner",input("sse-live")),(a,b)->true)
            .header("Accept","text/event-stream").build();
        var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
        var executor=Executors.newSingleThreadExecutor();
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            var received=executor.submit(()->{long previous=0;String line;while((line=reader.readLine())!=null)if(line.startsWith("data: ")) {
                JsonNode event=json.readTree(line.substring(6));assertEquals("s1",event.path("conversation_id").asText());
                assertEquals(++previous,event.path("sequence").asLong());assertFalse(event.path("event_id").asText().isBlank());
                if(event.path("type").asText().equals("message_delta"))return event;
            }return null;});
            assertEquals("实际文字片段",received.get(2,TimeUnit.SECONDS).at("/payload/text").asText());
            JsonNode partial=json.readTree(request("GET","/runs/"+services.lastRun,"owner",null).body());
            assertEquals("实际文字片段",partial.path("partialText").asText());
            assertEquals("s1",partial.path("sessionId").asText());
            assertEquals("RUNNING",partial.path("status").asText());
            assertEquals(1,services.release.getCount());
        } finally {services.releaseFinalObservation.countDown();services.release.countDown();executor.shutdownNow();}
    }
    @Test void sseDeadlineReportsFailureWithoutCompletedMessageOrTask() throws Exception {
        services.block=true;
        var req=HttpRequest.newBuilder(builder("POST","/runs/stream","owner",input("sse-timeout")),(a,b)->true)
            .header("Accept","text/event-stream").build();
        var response=http.send(req,HttpResponse.BodyHandlers.ofString());
        assertTrue(response.body().contains("event: task_failed"));
        assertTrue(response.body().contains("RUN_TIMED_OUT"));
        assertFalse(response.body().contains("event: task_completed"));
        assertFalse(response.body().contains("event: message_completed"));
        assertTrue(services.cancelled.get());assertTrue(services.stopped.get());
    }
    @Test void liveTokensReachClientBeforeSlowObservationOrWorkflowCompletes() throws Exception {
        services.liveTokens=true;
        var response=http.send(builder("POST","/runs/stream","owner",input("live-tokens")),HttpResponse.BodyHandlers.ofInputStream());
        var executor=Executors.newSingleThreadExecutor();
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
            var received=executor.submit(()->{String line;while((line=reader.readLine())!=null)if(line.contains("\"type\":\"delta\""))return json.readTree(line);return null;});
            JsonNode token=received.get(1,TimeUnit.SECONDS);
            assertEquals("实际文字片段",token.path("text").asText());
            assertEquals(1,services.releaseFinalObservation.getCount());
            assertEquals(1,services.release.getCount());
        } finally {services.releaseFinalObservation.countDown();services.release.countDown();executor.shutdownNow();}
    }
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newHttpClient();
    final FakeServices services=new FakeServices();
    final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-09T00:00:00Z"));
    WorkbenchGatewayServer server; String base;
    @BeforeEach void start() throws Exception {
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        server=new WorkbenchGatewayServer(services,Set.of("100","101"),clock,Duration.ofSeconds(3),0);
        server.start(); base="http://127.0.0.1:"+server.port()+"/assistant-workbench";
    }
    @AfterEach void close(){server.close();}
    @Test void orderIntakeUsesItsOwnRoleGateWithoutCreatingReadonlyRun() throws Exception {
        String input=json.writeValueAsString(Map.of("text","下100台显示器","context","{\"currentDate\":\"2026-11-14\"}"));
        assertEquals(401,request("POST","/order-intake",null,input).statusCode());
        var response=request("POST","/order-intake","nonadmin",input);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals(100,json.readTree(response.body()).at("/fields/quantity").asInt());
        assertEquals(403,request("GET","/status","nonadmin",null).statusCode());
        assertEquals(403,request("POST","/runs/stream","nonadmin",input("blocked")).statusCode());
        assertEquals(0,services.created.get());assertEquals(0,services.streamed.get());
    }
    @Test void orderIntakeRejectsUnboundedOrInvalidContextAndExtraFields() throws Exception {
        for(String context:List.of("[]","not-json","null","{\"x\":\""+"x".repeat(16000)+"\"}"))
            assertEquals(400,request("POST","/order-intake","owner",json.writeValueAsString(Map.of("text","下单","context",context))).statusCode());
        assertEquals(400,request("POST","/order-intake","owner","{\"text\":\"下单\",\"context\":\"{}\",\"approved\":true}").statusCode());
        assertEquals(0,services.intakes.get());
    }
    @Test void intakeContextLimitCountsCharactersAndSupportsChineseDraftAddresses() throws Exception {
        String context="{\"x\":\""+"中".repeat(15990)+"\"}";
        assertEquals(200,request("POST","/order-intake","owner",json.writeValueAsString(Map.of("text","修改地址","context",context))).statusCode());
        assertEquals(400,request("POST","/order-intake","owner",json.writeValueAsString(Map.of("text","x".repeat(2001),"context","{}"))).statusCode());
        assertEquals(1,services.intakes.get());
    }
    @Test void requiresSessionAndFreshAdminBeforeConfigurationOrExecution() throws Exception {
        assertEquals(401,request("GET","/status",null,null).statusCode());
        assertEquals(403,request("GET","/status","nonadmin",null).statusCode());
        services.configured=false;
        var status=request("GET","/status","owner",null);
        assertEquals(200,status.statusCode()); assertFalse(json.readTree(status.body()).path("configured").asBoolean());
        assertEquals(503,request("POST","/runs/stream","owner",input("r1")).statusCode());
        assertEquals(0,services.created.get());
    }
    @Test void naturalQuestionNeedsNoManualOrderAndFollowupIsOwnerAndSessionBound() throws Exception {
        String initial="{\"text\":\"查库存\",\"sessionId\":\"chat\",\"requestId\":\"natural1\"}";
        var response=request("POST","/runs/stream","owner",initial);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals("查库存",services.naturalQuery);
        String run=json.readTree(response.body().lines().filter(s->!s.isBlank()).findFirst().orElseThrow()).path("runId").asText();
        String followup="{\"text\":\"看它的质量\",\"sessionId\":\"chat\",\"requestId\":\"natural2\",\"previousRunId\":\""+run+"\"}";
        assertEquals(404,request("POST","/runs/stream","other",followup).statusCode());
        assertEquals(409,request("POST","/runs/stream","owner",followup.replace("\"chat\"","\"different\"")).statusCode());
        assertEquals(200,request("POST","/runs/stream","owner",followup).statusCode());
        assertEquals(run,services.previousRun);
        assertNull(services.previousGeneralAnswer);
        assertTrue(json.readTree(request("GET","/status","owner",null).body()).path("naturalLanguage").asBoolean());
    }
    @Test void onlyServerSuppliedGeneralAnswerTravelsToNextTurn() throws Exception {
        services.general=true;
        String first=json.writeValueAsString(Map.of("text","写一条活动标题","requestId","g1","sessionId","s1"));
        var packet=request("POST","/runs/stream","owner",first);
        String ref=json.readTree(packet.body().lines().filter(s->!s.isBlank()).findFirst().orElseThrow()).path("runId").asText();
        var msg=new LinkedHashMap<String,Object>(Map.of("text","改成疑问句","requestId","g2","sessionId","s1","previousRunId",ref));
        msg.put("previousGeneralAnswer","forged");
        assertEquals(400,request("POST","/runs/stream","owner",json.writeValueAsString(msg)).statusCode());
        msg.remove("previousGeneralAnswer");
        assertEquals(200,request("POST","/runs/stream","owner",json.writeValueAsString(msg)).statusCode());
        assertEquals("Final answer",services.previousGeneralAnswer);
    }
    @Test void restartContinuationStillRequiresSameOwnerAndSession(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        Clock clock=Clock.fixed(now.get(),ZoneOffset.UTC);
        var store=new ConversationScopeStore(directory,"gateway",clock,"test-only-scope-signing-key-123456");
        server.close();server=new WorkbenchGatewayServer(services,Set.of("100"),clock,Duration.ofSeconds(3),0,store);server.start();base="http://127.0.0.1:"+server.port()+"/assistant-workbench";
        var initial=request("POST","/runs/stream","owner",json.writeValueAsString(Map.of("text","查库存","sessionId","same","requestId","before")));
        String ref=json.readTree(initial.body().lines().filter(line->!line.isBlank()).findFirst().orElseThrow()).path("runId").asText();
        server.close();server=new WorkbenchGatewayServer(services,Set.of("100"),clock,Duration.ofSeconds(3),0,new ConversationScopeStore(directory,"gateway",clock,"test-only-scope-signing-key-123456"));server.start();base="http://127.0.0.1:"+server.port()+"/assistant-workbench";
        String follow=json.writeValueAsString(Map.of("text","其中库存不足","sessionId","same","requestId","after","previousRunId",ref));
        assertEquals(404,request("POST","/runs/stream","other",follow).statusCode());
        assertEquals(409,request("POST","/runs/stream","owner",follow.replace("same","different")).statusCode());
        assertEquals(403,request("POST","/runs/stream","nonadmin",follow).statusCode());
        assertEquals(404,request("GET","/runs/"+ref,"owner",null).statusCode());
        assertEquals(200,request("POST","/runs/stream","owner",follow).statusCode());
        assertEquals(ref,services.previousRun);
    }
    @Test void rejectsUnknownFieldsDisallowedOrdersInvalidProfilesDuplicateJsonAndOversizeBeforeCreate() throws Exception {
        for(String body:List.of(input("r").replace("\"100\"","\"102\""),input("r").replace("ORDER","AUTO"),
                input("r").replace("}",",\"ownerId\":2}"),input("r").replace("}",",\"text\":\"extra\"}"),
                input("r").replace("question","x".repeat(2001)))) {
            int status=request("POST","/runs/stream","owner",body).statusCode();
            assertTrue(status==400||status==403,"Unexpected status "+status);
        }
        assertEquals(413,request("POST","/runs/stream","owner"," ".repeat(33000)).statusCode());
        assertEquals(0,services.created.get());
    }
    @Test void streamsObservedTasksAndAuthoritativeResultAndDeduplicatesWithoutNewWorkflow() throws Exception {
        var response=request("POST","/runs/stream","owner",input("r1"));
        assertEquals(200,response.statusCode(),response.body());
        var events=response.body().lines().filter(s->!s.isBlank()).map(s->{try{return json.readTree(s);}catch(Exception e){throw new RuntimeException(e);}}).toList();
        assertEquals("run",events.get(0).path("type").asText());
        assertTrue(events.stream().anyMatch(e->e.path("agentId").asText().equals("SUP")&&e.path("status").asText().equals("COMPLETED")));
        assertTrue(events.stream().anyMatch(e->e.path("agentId").asText().equals("ORDER")&&e.path("status").asText().equals("COMPLETED")));
        JsonNode result=events.stream().filter(e->e.path("type").asText().equals("result")).findFirst().orElseThrow().path("data");
        assertEquals("Final answer",result.path("reply").asText()); assertEquals(7,result.path("facts").path("count").asInt());
        assertEquals("done",events.get(events.size()-1).path("type").asText());
        assertEquals(409,request("POST","/runs/stream","owner",input("r1")).statusCode());
        assertEquals(1,services.created.get());
        assertEquals(1,services.streamed.get());
        String id=events.get(0).path("runId").asText();
        assertEquals(404,request("GET","/runs/"+id,"other",null).statusCode());
        assertEquals(404,request("POST","/runs/"+id+"/cancel","other","{}").statusCode());
        assertEquals("COMPLETED",json.readTree(request("GET","/runs/"+id,"owner",null).body()).path("status").asText());
        assertEquals("Final answer",json.readTree(request("GET","/runs/"+id,"owner",null).body()).path("result").path("reply").asText());
        assertFalse(json.readTree(request("GET","/runs/"+id,"owner",null).body()).has("workflow"));
    }
    @Test void workflowSnapshotsAreWhitelistedDeduplicatedAndRecovered() throws Exception {
        services.includeWorkflow=true;
        String response=request("POST","/runs/stream","owner",input("workflow")).body();
        var events=response.lines().filter(s->!s.isBlank()).map(s->{try{return json.readTree(s);}catch(Exception e){throw new RuntimeException(e);}}).toList();
        var workflows=events.stream().filter(e->"workflow".equals(e.path("type").asText())).toList();
        assertEquals(1,workflows.size());
        JsonNode workflow=workflows.get(0);
        assertEquals(events.get(0).path("runId"),workflow.path("runId"));
        assertEquals("PLAN_CONFIRMED",workflow.at("/events/0/kind").asText());
        assertFalse(workflow.toString().contains("SECRET"));
        assertTrue(response.indexOf("\"type\":\"workflow\"")<response.indexOf("\"type\":\"result\""));
        JsonNode snapshot=json.readTree(request("GET","/runs/"+workflow.path("runId").asText(),"owner",null).body());
        assertEquals(workflow,snapshot.path("workflow"));
    }
    @Test void cancellationIsOwnerBoundStopsUpstreamAndDoesNotPublishResult() throws Exception {
        services.block=true;
        var future=http.sendAsync(builder("POST","/runs/stream","owner",input("r2")),HttpResponse.BodyHandlers.ofString());
        assertTrue(services.started.await(2,TimeUnit.SECONDS));
        String id=services.lastRun;
        assertEquals(404,request("POST","/runs/"+id+"/cancel","other","{}").statusCode());
        assertFalse(services.cancelled.get());
        assertEquals(200,request("POST","/runs/"+id+"/cancel","owner","{}").statusCode());
        String body=future.get(3,TimeUnit.SECONDS).body();
        assertTrue(services.cancelled.get()); assertTrue(services.stopped.get());
        assertFalse(body.contains("\"type\":\"result\""));
        assertEquals("CANCELLED",json.readTree(request("GET","/runs/"+id,"owner",null).body()).path("status").asText());
    }
    @Test void finalObservationReplacesSnapshotCapturedWhileLastTaskWasRunning() throws Exception {
        services.finishDuringFirstObservation=true;
        var response=request("POST","/runs/stream","owner",input("final-race"));
        assertEquals(200,response.statusCode());
        var events=response.body().lines().filter(s->!s.isBlank()).map(s->{try{return json.readTree(s);}catch(Exception e){throw new RuntimeException(e);}}).toList();
        var orderEvents=events.stream().filter(e->e.path("agentId").asText().equals("ORDER")).toList();
        assertEquals("RUNNING",orderEvents.get(0).path("status").asText());
        assertEquals("COMPLETED",orderEvents.get(orderEvents.size()-1).path("status").asText());
        int resultIndex=java.util.stream.IntStream.range(0,events.size()).filter(i->events.get(i).path("type").asText().equals("result")).findFirst().orElseThrow();
        assertTrue(events.indexOf(orderEvents.get(orderEvents.size()-1))<resultIndex);
        String id=events.get(0).path("runId").asText();
        JsonNode stored=json.readTree(request("GET","/runs/"+id,"owner",null).body());
        for(JsonNode agent:stored.path("agents"))if(agent.path("agentId").asText().equals("ORDER"))assertEquals("COMPLETED",agent.path("status").asText());
    }
    @Test void dataChangedClearsCachedAndStreamedAgentEvidenceBeforeFinalResult() throws Exception {
        services.dataChanged=true;
        services.includeWorkflow=true;
        var response=request("POST","/runs/stream","owner",input("changed"));
        var events=response.body().lines().filter(s->!s.isBlank()).map(s->{try{return json.readTree(s);}catch(Exception e){throw new RuntimeException(e);}}).toList();
        var orderEvents=events.stream().filter(e->e.path("agentId").asText().equals("ORDER")).toList();
        JsonNode last=orderEvents.get(orderEvents.size()-1);
        assertEquals(0,last.path("evidence").size());assertEquals("",last.path("summary").asText());
        JsonNode stored=json.readTree(request("GET","/runs/"+events.get(0).path("runId").asText(),"owner",null).body());
        assertEquals("DATA_CHANGED",stored.path("result").path("verdict").asText());
        assertFalse(stored.toString().contains("OLD_EVIDENCE"));assertFalse(stored.toString().contains("Observed"));
        assertTrue(stored.at("/workflow/invalidated").asBoolean());
        assertTrue(stored.at("/workflow/plan").isNull());
        assertEquals(0,stored.at("/workflow/events").size());
        assertTrue(response.body().lastIndexOf("\"invalidated\":true")<response.body().indexOf("\"type\":\"result\""));
        int invalidation=response.body().indexOf("\"invalidated\":true");
        int clearedAgent=response.body().indexOf("\"summary\":\"\"");
        assertTrue(invalidation>=0&&invalidation<clearedAgent,response.body());
    }
    @Test void knownDataChangeClearsRecoveryEvenWhenFinalObserveFails() throws Exception {
        services.block=true;
        services.includeWorkflow=true;
        services.dataChanged=true;
        services.failObserveAfterResult=true;
        var response=http.sendAsync(builder("POST","/runs/stream","owner",input("changed-observe-fails")),HttpResponse.BodyHandlers.ofString());
        awaitCachedEvidence();
        services.release.countDown();
        String body=response.get(5,TimeUnit.SECONDS).body();
        JsonNode stored=json.readTree(request("GET","/runs/"+services.lastRun,"owner",null).body());
        assertTrue(stored.at("/workflow/invalidated").asBoolean(),body);
        assertFalse(stored.toString().contains("OLD_EVIDENCE"));
        assertTrue(body.contains("\"invalidated\":true"));
    }
    @Test void cancellationSnapshotRedactsAgentsWhenCancelObservationInvalidatesWorkflow() throws Exception {
        services.block=true;
        services.includeWorkflow=true;
        services.dataChanged=true;
        services.invalidateOnCancel=true;
        var response=http.sendAsync(builder("POST","/runs/stream","owner",input("cancel-invalidation")),HttpResponse.BodyHandlers.ofString());
        awaitCachedEvidence();
        JsonNode cancelled=json.readTree(request("POST","/runs/"+services.lastRun+"/cancel","owner","{}").body());
        assertTrue(cancelled.at("/workflow/invalidated").asBoolean());
        assertFalse(cancelled.toString().contains("OLD_EVIDENCE"));
        assertEquals("CANCELLED",supervisor(cancelled).path("status").asText());
        assertFalse(supervisor(cancelled).has("completedAt"));
        response.get(5,TimeUnit.SECONDS);
    }
    @Test void cancellationDuringFinalObserveCannotPublishCompletedSupervisor() throws Exception {
        services.block=true;
        services.dataChanged=true;
        services.blockFinalObservation=true;
        var response=http.sendAsync(builder("POST","/runs/stream","owner",input("cancel-terminal-race")),HttpResponse.BodyHandlers.ofString());
        awaitCachedEvidence();
        services.release.countDown();
        assertTrue(services.finalObservation.await(2,TimeUnit.SECONDS));
        request("POST","/runs/"+services.lastRun+"/cancel","owner","{}");
        services.releaseFinalObservation.countDown();
        String body=response.get(5,TimeUnit.SECONDS).body();
        assertFalse(body.contains("\"agentId\":\"SUP\",\"status\":\"COMPLETED\""),body);
        JsonNode stored=json.readTree(request("GET","/runs/"+services.lastRun,"owner",null).body());
        assertEquals("CANCELLED",supervisor(stored).path("status").asText());
        assertFalse(supervisor(stored).has("completedAt"));
    }
    @Test void delayedRunningPollCannotReplaceNewerCancelledTasksWithEqualWorkflowLength() throws Exception {
        services.block=true;
        services.includeWorkflow=true;
        services.delayRunningPoll=true;
        var response=http.sendAsync(builder("POST","/runs/stream","owner",input("stale-running-poll")),HttpResponse.BodyHandlers.ofString());
        awaitCachedEvidence();
        assertTrue(services.finalObservation.await(2,TimeUnit.SECONDS));
        var cancelled=json.readTree(request("POST","/runs/"+services.lastRun+"/cancel","owner","{}").body());
        assertEquals("CANCELLED",businessAgent(cancelled).path("status").asText());
        services.releaseFinalObservation.countDown();
        String body=response.get(5,TimeUnit.SECONDS).body();
        var recovered=json.readTree(request("GET","/runs/"+services.lastRun,"owner",null).body());
        assertEquals("CANCELLED",businessAgent(recovered).path("status").asText(),body);
    }
    private static JsonNode businessAgent(JsonNode snapshot) {
        for(JsonNode agent:snapshot.path("agents"))if("ORDER".equals(agent.path("agentId").asText()))return agent;
        return snapshot.path("missingAgent");
    }
    private void awaitCachedEvidence() throws Exception {
        assertTrue(services.started.await(2,TimeUnit.SECONDS));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<end) {
            if(request("GET","/runs/"+services.lastRun,"owner",null).body().contains("OLD_EVIDENCE"))return;
            Thread.sleep(20);
        }
        fail("Expected previously cached evidence");
    }
    private static JsonNode supervisor(JsonNode snapshot) {
        for(JsonNode agent:snapshot.path("agents"))if("SUP".equals(agent.path("agentId").asText()))return agent;
        return snapshot.path("missingSupervisor");
    }
    @Test void controlledDependencyFailureCodeSurvivesFutureWrappingWithoutRawErrorText() throws Exception {
        services.dependencyFailure=true;
        String body=request("POST","/runs/stream","owner",input("dependency-failure")).body();
        assertTrue(body.contains("DIFY_EVENT_LIMIT"),body);
        assertFalse(body.contains("SECRET"));assertFalse(body.contains("\"type\":\"result\""));
    }
    @Test void deadlineCancelsBothDependenciesAndSanitizesFailures() throws Exception {
        services.block=true;
        String body=request("POST","/runs/stream","owner",input("r3")).body();
        assertTrue(services.cancelled.get()); assertTrue(services.stopped.get());
        assertTrue(body.contains("\"type\":\"error\"")); assertFalse(body.contains("SECRET"));
    }
    @Test void finalObservationCannotReleaseAResultPastTheOverallDeadline() throws Exception {
        server.close();
        server=new WorkbenchGatewayServer(services,Set.of("100"),Clock.systemUTC(),Duration.ofMillis(700),0);
        server.start();base="http://127.0.0.1:"+server.port()+"/assistant-workbench";
        services.blockFinalObservation=true;
        var response=http.sendAsync(builder("POST","/runs/stream","owner",input("slow-final-observe")),HttpResponse.BodyHandlers.ofString());
        try{
            assertTrue(services.finalObservation.await(2,TimeUnit.SECONDS));
            String body=response.get(2,TimeUnit.SECONDS).body();
            assertTrue(body.contains("RUN_TIMED_OUT"),body);
            assertFalse(body.contains("\"type\":\"result\""));
        }finally{services.releaseFinalObservation.countDown();}
    }
    @Test void activeCapacityRejectsThirdRunWithoutStartingIt() throws Exception {
        services.block=true;
        var a=http.sendAsync(builder("POST","/runs/stream","owner",input("a")),HttpResponse.BodyHandlers.ofString());
        var b=http.sendAsync(builder("POST","/runs/stream","owner",input("b")),HttpResponse.BodyHandlers.ofString());
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(services.streamed.get()<2&&System.nanoTime()<until)Thread.sleep(10);
        assertEquals(429,request("POST","/runs/stream","owner",input("c")).statusCode());
        assertEquals(2,services.created.get());
        services.release.countDown();a.get(3,TimeUnit.SECONDS);b.get(3,TimeUnit.SECONDS);
    }
    @Test void duplicateRequestDoesNotRestartAfterRuntimeCreateFailure() throws Exception {
        services.failCreation=true;
        assertEquals(503,request("POST","/runs/stream","owner",input("failed-create")).statusCode());
        assertEquals(409,request("POST","/runs/stream","owner",input("failed-create")).statusCode());
        assertEquals(1,services.created.get());
    }
    @Test void failedReservationsHaveBoundedCapacityAndExpireAfterTenMinutes() throws Exception {
        services.failCreation=true;
        for(int i=0;i<64;i++)assertEquals(503,request("POST","/runs/stream","owner",input("failed-"+i)).statusCode());
        assertEquals(429,request("POST","/runs/stream","owner",input("over-capacity")).statusCode());
        assertEquals(64,services.created.get());
        now.set(now.get().plusSeconds(601));
        assertEquals(503,request("POST","/runs/stream","owner",input("after-expiry")).statusCode());
        assertEquals(65,services.created.get());
    }
    @Test void abruptClientDisconnectCancelsRuntimeAndUpstream() throws Exception {
        services.block=true;String data=input("disconnect");
        try(Socket socket=new Socket("127.0.0.1",server.port())){
            socket.setSoLinger(true,0);
            String request="POST /assistant-workbench/runs/stream HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer owner\r\nContent-Type: application/json\r\nContent-Length: "+data.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+data;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            assertTrue(services.started.await(2,TimeUnit.SECONDS));
        }
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!services.stopped.get()&&System.nanoTime()<until)Thread.sleep(10);
        assertTrue(services.cancelled.get());assertTrue(services.stopped.get());
    }
    String input(String id){return "{\"text\":\"question\",\"orderPk\":\"100\",\"profile\":\"ORDER\",\"sessionId\":\"s1\",\"requestId\":\""+id+"\"}";}
    HttpRequest builder(String method,String path,String token,String body){
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> request(String method,String path,String token,String body)throws Exception{return http.send(builder(method,path,token,body),HttpResponse.BodyHandlers.ofString());}
    static class FakeServices implements WorkbenchGatewayServer.Services {
        final AtomicInteger intakes=new AtomicInteger();
        public long authenticateOrderIntake(String authorization){return 1;}
        public JsonNode orderIntake(long owner,String text,String context){intakes.incrementAndGet();return new ObjectMapper().valueToTree(Map.of("action","ORDER","fields",Map.of("quantity",100),"clearFields",List.of(),"question",""));}
        String naturalQuery,previousRun,previousGeneralAnswer;
        boolean general;
        public boolean naturalLanguage(){return true;}
        public String createNatural(long owner,String text,String previous)throws Exception {naturalQuery=text;previousRun=previous;return create(owner,"","AUTO");}
        public String createNatural(long owner,String text,String previous,String generalAnswer)throws Exception {
            previousGeneralAnswer=generalAnswer;return createNatural(owner,text,previous);
        }
        final AtomicInteger created=new AtomicInteger(),streamed=new AtomicInteger(),observations=new AtomicInteger();
        final AtomicBoolean cancelled=new AtomicBoolean(),stopped=new AtomicBoolean();
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final CountDownLatch firstObservation=new CountDownLatch(1),workflowReturning=new CountDownLatch(1);
        final CountDownLatch finalObservation=new CountDownLatch(1),releaseFinalObservation=new CountDownLatch(1);
        volatile boolean configured=true,block,failCreation,finishDuringFirstObservation,dataChanged,dependencyFailure,includeWorkflow; volatile String lastRun;
        volatile boolean failObserveAfterResult,invalidateOnCancel,blockFinalObservation;
        volatile boolean delayRunningPoll;
        volatile boolean liveTokens;
        final AtomicBoolean finalObservationBlocked=new AtomicBoolean();
        final AtomicInteger delayedPolls=new AtomicInteger();
        public long authenticate(String authorization){if(authorization.equals("Bearer nonadmin"))throw new WorkbenchGatewayServer.Failure(403,"FORBIDDEN");return authorization.equals("Bearer other")?2:1;}
        public boolean configured(){return configured;}
        public String create(long owner,String order,String profile){assertEquals(1,owner);created.incrementAndGet();if(failCreation)throw new IllegalStateException("SECRET");lastRun=UUID.randomUUID().toString();return lastRun;}
        public JsonNode observe(String id)throws Exception{
            if(liveTokens) {firstObservation.countDown();releaseFinalObservation.await(3,TimeUnit.SECONDS);}
            int poll=delayRunningPoll?delayedPolls.incrementAndGet():0;
            if(poll>3)throw new IllegalStateException("SECRET");
            if(workflowReturning.getCount()==0) {
                if(failObserveAfterResult)throw new IllegalStateException("SECRET");
                if(blockFinalObservation&&finalObservationBlocked.compareAndSet(false,true)) {
                    finalObservation.countDown();
                    assertTrue(releaseFinalObservation.await(3,TimeUnit.SECONDS));
                }
            }
            boolean capturedRunning=finishDuringFirstObservation&&observations.incrementAndGet()==1;
            var snapshot=new ObjectMapper().createObjectNode();
            snapshot.putArray("tasks").add(new ObjectMapper().valueToTree(Map.of("agentId","ORDER","status",delayRunningPoll&&cancelled.get()?"CANCELLED":block||capturedRunning?"RUNNING":"COMPLETED","summary","Observed","evidence",dataChanged||delayRunningPoll?List.of(Map.of("value","OLD_EVIDENCE")):List.of(),"startedAt","2026-11-09T00:00:00Z")));
            if(includeWorkflow) {
                var workflow=snapshot.putObject("workflow").put("invalidated",invalidateOnCancel&&cancelled.get()).put("rawPrompt","SECRET").put("runId","SECRET");
                var plan=workflow.putObject("plan").put("action","QUERY").put("confirmedAt","2026-11-09T00:00:00Z").put("question","SECRET");
                plan.putArray("requiredAgents").add("ORDER");
                plan.putObject("dependencies").putArray("ORDER");
                workflow.putArray("events").addObject().put("seq",1).put("kind","PLAN_CONFIRMED").put("agentId","SUP")
                    .put("status","COMPLETED").put("label","确认计划").put("detail","已确认")
                    .put("at","2026-11-09T00:00:00Z").put("secret","SECRET");
            }
            if(capturedRunning){firstObservation.countDown();assertTrue(workflowReturning.await(2,TimeUnit.SECONDS));Thread.sleep(100);}
            if(poll==2) {
                finalObservation.countDown();
                assertTrue(releaseFinalObservation.await(3,TimeUnit.SECONDS));
            }
            return snapshot;
        }
        public JsonNode workflow(String id,String text,long owner,Consumer<String> task,Consumer<JsonNode> progress)throws Exception {
            if(liveTokens) {
                task.accept("task-1");assertTrue(firstObservation.await(1,TimeUnit.SECONDS));
                progress.accept(new ObjectMapper().valueToTree(Map.of("type","delta","text","实际文字片段")));
                release.await(3,TimeUnit.SECONDS);
            }
            return workflow(id,text,owner,task);
        }
        public JsonNode workflow(String id,String text,long owner,Consumer<String> task)throws Exception{
            streamed.incrementAndGet();task.accept("task-1");started.countDown();if(block)release.await(10,TimeUnit.SECONDS);
            if(dependencyFailure)throw new WorkbenchGatewayServer.Failure(502,"DIFY_EVENT_LIMIT");
            if(finishDuringFirstObservation)assertTrue(firstObservation.await(2,TimeUnit.SECONDS));
            JsonNode result=new ObjectMapper().valueToTree(Map.of("reply","Final answer","facts",dataChanged?Map.of():Map.of("count",7),"verdict",dataChanged?"DATA_CHANGED":"EVIDENCE_READY"));
            if(general) result=new ObjectMapper().valueToTree(Map.of("reply","Final answer","facts",Map.of(),"verdict","EVIDENCE_READY","queryMode","NATURAL","intents",List.of("GENERAL")));
            workflowReturning.countDown();return result;
        }
        public void cancel(String id){cancelled.set(true);}
        public void stop(String id,String task,long owner){stopped.set(true);release.countDown();}
    }
}
