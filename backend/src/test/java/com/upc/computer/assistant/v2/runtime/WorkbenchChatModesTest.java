package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.upc.computer.assistant.v2.chat.*;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.service.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class WorkbenchChatModesTest {
    final ObjectMapper json = new ObjectMapper();
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<JsonNode> payloads = new CopyOnWriteArrayList<>();
    final List<String> headers = new CopyOnWriteArrayList<>();
    final String release = "b".repeat(32);
    final String marker = "MODULE-REFERENCE-729";
    WorkbenchChatProperties settings = new WorkbenchChatProperties();
    DirectBusinessQueries business = mock(DirectBusinessQueries.class);
    HttpServer upstream;
    WorkbenchModelChat chat;
    MockMvc mvc;
    int modelCode = 200, knowledgeCode = 200;
    boolean emptyHits;
    String reply = "虚构模型回答";

    @BeforeEach void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", this::respond);
        upstream.start();
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        settings.getApi().setBaseUrl(base + "/api/v1");
        settings.getApi().setApiKey("fixture-cloud");
        settings.getApi().setModel("fixture-api-model");
        settings.getLocal().setBaseUrl(base + "/local/v1");
        settings.getLocal().setModel("fixture-local-model");
        settings.setKnowledgeKey("fixture-index");
        var knowledge = new KnowledgeEvidenceClient(URI.create(base), "fixture-index", Clock.systemUTC(), Duration.ofSeconds(3));
        when(business.policy(7L)).thenReturn(AssistantReadPolicy.of(7L, "ORDER", null, List.of("order:list")));
        chat = new WorkbenchModelChat(settings, new WorkbenchModelClient(settings,json), business, json, knowledge);
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.extractTokenFromHeader("Bearer fixture-session")).thenReturn("fixture-session");
        when(jwt.validateToken("fixture-session")).thenReturn(true);
        AuthService auth = mock(AuthService.class);
        LoginResponse session = new LoginResponse();
        session.setUserId(7L);
        when(auth.getLoginSession("fixture-session")).thenReturn(session);
        mvc = MockMvcBuilders.standaloneSetup(new WorkbenchChatController(jwt, auth, chat)).build();
    }

    @AfterEach void close() { upstream.stop(0); }

    private void respond(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            payloads.add(json.readTree(exchange.getRequestBody()));
            headers.add(Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), ""));
            boolean retrieval = path.equals("/internal/knowledge/query");
            Map<String,Object> data;
            if (retrieval) {
                String chunk = "paper-p2";
                var hit = Map.ofEntries(Map.entry("document_id","paper"), Map.entry("document_version","c".repeat(64)),
                        Map.entry("chunk_id",chunk), Map.entry("title","检测方法"), Map.entry("text",marker), Map.entry("page_start",2),
                        Map.entry("page_end",2), Map.entry("source_url","https://example.org/paper"), Map.entry("authority_type","PUBLIC_RESEARCH"),
                        Map.entry("release_id",release), Map.entry("evidence_ref",release + ":paper:" + chunk),
                        Map.entry("evidence_path","/internal/knowledge/evidence/" + release + "/" + chunk), Map.entry("retrieval_source","vector"));
                data = Map.of("status",emptyHits ? "NO_MATCHES" : "MATCHES","release_id",release,"knowledge_domain","display_reference",
                        "mode_used","vector","graph_status","NO_MATCHES","graph_paths",List.of(),"hits",emptyHits ? List.of() : List.of(hit));
            } else {
                data = Map.of("choices",List.of(Map.of("message",Map.of("content",reply))));
            }
            byte[] bytes = json.writeValueAsBytes(data);
            exchange.sendResponseHeaders(retrieval ? knowledgeCode : modelCode, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally { exchange.close(); }
    }

    private JsonNode turn(Map<String,Object> body) throws Exception {
        var result = mvc.perform(post("/assistant-v2/chat/turn").header("Authorization", "Bearer fixture-session")
                .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
    private Map<String,Object> request(String source, boolean rag) {
        return Map.of("text","说明显示器检测方法","modelSource",source,"ragEnabled",rag,"history",List.of());
    }

    @ParameterizedTest @CsvSource({"API,false","API,true","LOCAL,false","LOCAL,true"})
    void routesModelAndRetrievalIndependently(String source, boolean rag) throws Exception {
        JsonNode response = turn(request(source,rag));
        assertEquals(200, response.path("code").asInt(), response::toString);
        JsonNode data = response.path("data");
        assertEquals(reply,data.path("reply").asText());
        assertEquals(source,data.path("modelSource").asText());
        assertEquals(rag,data.path("ragEnabled").asBoolean());
        assertEquals(rag ? "MATCHES" : "DISABLED",data.path("ragStatus").asText());
        String path = source.equals("API") ? "/api/v1/chat/completions" : "/local/v1/chat/completions";
        assertEquals(rag ? List.of("/internal/knowledge/query",path) : List.of(path), paths);
        int gen = paths.size() - 1;
        assertEquals(source.equals("API") ? "Bearer fixture-cloud" : "",headers.get(gen));
        assertEquals(source.equals("API") ? "fixture-api-model" : "fixture-local-model",payloads.get(gen).path("model").asText());
        assertEquals(rag,payloads.get(gen).path("messages").toString().contains(marker));
        assertEquals(rag ? 1 : 0,data.path("knowledgeEvidence").size());
        assertFalse(response.toString().contains("fixture-cloud"));
    }

    @Test void unavailableRagStopsBeforeGenerationAndEmptySearchIsReported() throws Exception {
        knowledgeCode=503;
        assertEquals(503,turn(request("LOCAL",true)).path("code").asInt());
        assertEquals(List.of("/internal/knowledge/query"),paths);
        knowledgeCode=200; emptyHits=true;
        assertEquals("NO_MATCHES",turn(request("LOCAL",true)).at("/data/ragStatus").asText());
    }

    @Test void invalidOrMissingSelectionAndClientProviderOverridesAreRejectedBeforeUpstream() throws Exception {
        for(var body:List.of(Map.<String,Object>of("text","x","modelSource","LOCAL"),
                Map.<String,Object>of("text","x","modelSource","LOCAL","ragEnabled","false"),
                request("OTHER",false),Map.<String,Object>of("text","x","modelSource","LOCAL","ragEnabled",false,"baseUrl","http://example.org"),
                Map.<String,Object>of("text","x","modelSource","API","ragEnabled",false,"history",List.of(Map.of("role","system","content","x"))))) {
            assertEquals(400,turn(body).path("code").asInt());
        }
        assertTrue(paths.isEmpty());
    }

    @Test void missingOrFailedLocalModelNeverFallsBackToCloud() throws Exception {
        settings.getLocal().setModel("");
        assertEquals(503,turn(request("LOCAL",true)).path("code").asInt());
        assertTrue(paths.isEmpty());
        settings.getLocal().setModel("fixture-local-model"); modelCode=503;
        assertEquals(502,turn(request("LOCAL",false)).path("code").asInt());
        assertEquals(List.of("/local/v1/chat/completions"),paths);
    }

    @Test void explicitReadPlanCarriesTheSelectedModeAndVerifiedSourcesToBusinessQueries() throws Exception {
        reply="{\"action\":\"QUERY\",\"intents\":[\"ORDER\"],\"orderPk\":null,\"orderNo\":null,\"keyword\":null,\"period\":\"CURRENT\",\"question\":\"\"}";
        var mode=ChatMode.parse("LOCAL",true);
        when(business.answer(eq(7L),anyString(),any(),eq(mode),anyMap()))
                .thenReturn(Map.of("reply","已读取授权订单","queryMode","NATURAL","verdict","EVIDENCE_READY"));
        var data=turn(request("LOCAL",true)).path("data");
        assertEquals("NATURAL",data.path("queryMode").asText());
        assertEquals("LOCAL",data.path("modelSource").asText());
        verify(business).answer(eq(7L),anyString(),argThat(p -> "QUERY".equals(p.path("action").asText())),eq(mode),
                argThat(bundle -> "MATCHES".equals(bundle.get("status"))));
    }

    @Test void requiresRealMesSessionAndRechecksScopeOnReturn() throws Exception {
        var anonymous=mvc.perform(post("/assistant-v2/chat/turn").contentType("application/json")
                .content(json.writeValueAsBytes(request("API",false)))).andReturn();
        assertEquals(401,json.readTree(anonymous.getResponse().getContentAsByteArray()).path("code").asInt());
        assertTrue(paths.isEmpty());
        when(business.policy(7L)).thenReturn(AssistantReadPolicy.admin(), AssistantReadPolicy.of(7L,"ORDER",null,List.of()));
        assertEquals(403,turn(request("API",false)).path("code").asInt());
    }

    @ParameterizedTest @CsvSource({"API,false","API,true","LOCAL,false","LOCAL,true"})
    void orderInterpretationUsesSelectedProviderAndKeepsDraftOutOfKnowledgeIndex(String source, boolean rag) {
        reply="{\"action\":\"ORDER\",\"fields\":{\"quantity\":8},\"clearFields\":[],\"question\":\"\"}";
        var parsed=chat.interpretOrder(ChatMode.parse(source,rag),"订购8台",Map.of("draft","fictional shipping address"));
        assertEquals(8,parsed.at("/fields/quantity").asInt());
        String path=source.equals("LOCAL")?"/local/v1/chat/completions":"/api/v1/chat/completions";
        assertEquals(rag?List.of("/internal/knowledge/query",path):List.of(path),paths);
        if(rag)assertFalse(payloads.get(0).toString().contains("fictional"));
        assertEquals(rag,payloads.get(paths.size()-1).toString().contains(marker));
    }
}
