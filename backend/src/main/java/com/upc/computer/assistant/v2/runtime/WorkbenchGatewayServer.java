package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Loopback end-user adapter. The supervisor and experts remain scheduled by Dify. */
public final class WorkbenchGatewayServer implements AutoCloseable {
    public static final Set<String> PROFILES=Set.of("DELIVERY","ORDER","SUPPLY","PRODUCTION","QUALITY","EQUIPMENT","AFTERSALES","FULL_ASSESSMENT");
    public interface Services {
        long authenticate(String authorization) throws Exception;
        default Map<String,Object> capabilities(long owner){return Map.of();}
        default String authorizationStamp(long owner){return "ADMIN";}
        default boolean legacyAllowed(long owner){return true;}
        default long authenticateOrderIntake(String authorization)throws Exception {return authenticate(authorization);}
        default JsonNode orderIntake(long owner,String text,String context)throws Exception {throw new Failure(503,"ORDER_INTAKE_UNAVAILABLE");}
        boolean configured();
        String create(long owner,String order,String profile) throws Exception;
        default boolean naturalLanguage(){return false;}
        default String createNatural(long owner,String text,String previousRun)throws Exception {throw new Failure(503,"NATURAL_LANGUAGE_UNAVAILABLE");}
        default String createNatural(long owner,String text,String previousRun,String previousGeneralAnswer)throws Exception {
            return createNatural(owner,text,previousRun);
        }
        JsonNode observe(String run) throws Exception;
        JsonNode workflow(String run,String text,long owner,Consumer<String> taskId) throws Exception;
        default JsonNode workflow(String run,String text,long owner,Consumer<String> taskId,Consumer<JsonNode> progress) throws Exception {
            return workflow(run,text,owner,taskId);
        }
        void cancel(String run) throws Exception;
        void stop(String run,String taskId,long owner) throws Exception;
    }
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Services services;
    private final Supplier<Services> localServices;
    private final EverydayChat cloudChat;
    private final EverydayChat localChat;
    private final Semaphore chatSlots = new Semaphore(2);
    private final ConversationScopeStore contextStore;
    private final RunAuditStore audit;
    private final Set<String> orders;
    private final Clock clock;
    private final Duration deadline;
    private final HttpServer server;
    private final ExecutorService requests=new ThreadPoolExecutor(6,6,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService workflows=Executors.newFixedThreadPool(2);
    private final ExecutorService observations=Executors.newFixedThreadPool(2);
    private final Semaphore slots=new Semaphore(2);
    private final Map<String,Run> runs=new LinkedHashMap<>();
    private final Map<String,Instant> requestsById=new HashMap<>();

    public WorkbenchGatewayServer(Services services,Set<String> orders,Clock clock,Duration deadline,int port)throws IOException {
        this(services,orders,clock,deadline,port,ConversationScopeStore.disabled());
    }
    public WorkbenchGatewayServer(Services services,Set<String> orders,Clock clock,Duration deadline,int port,ConversationScopeStore contextStore)throws IOException {
        this(services,orders,clock,deadline,port,contextStore,RunAuditStore.disabled());
    }
    public WorkbenchGatewayServer(Services services,Set<String> orders,Clock clock,Duration deadline,int port,ConversationScopeStore contextStore,RunAuditStore audit)throws IOException {
        this(services, () -> null, new EverydayChat(), null, orders, clock, deadline, port, contextStore, audit);
    }
    WorkbenchGatewayServer(Services services, Supplier<Services> localServices, EverydayChat cloudChat, EverydayChat localChat,
            Set<String> orders, Clock clock, Duration deadline, int port, ConversationScopeStore contextStore, RunAuditStore audit)throws IOException {
        this.localServices=Objects.requireNonNull(localServices);
        this.cloudChat=Objects.requireNonNull(cloudChat);
        this.localChat=localChat;
        this.audit=Objects.requireNonNull(audit);
        this.contextStore=Objects.requireNonNull(contextStore);
        this.services=Objects.requireNonNull(services);this.orders=Set.copyOf(orders);this.clock=clock;this.deadline=deadline;
        if(orders.isEmpty()||orders.stream().anyMatch(o->!o.matches("[1-9][0-9]{0,18}")))throw new IllegalArgumentException("Explicit order allowlist required");
        if(deadline.isNegative()||deadline.isZero()||deadline.compareTo(Duration.ofMinutes(8))>0)throw new IllegalArgumentException("Invalid run deadline");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);server.setExecutor(requests);server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    @Override public void close(){server.stop(0);requests.shutdownNow();workflows.shutdownNow();observations.shutdownNow();audit.close();}

    private Services binding(WorkflowModel model) {
        Services selected = model == WorkflowModel.LOCAL ? localServices.get() : services;
        if (selected == null || !selected.configured()) throw new Failure(503, model == WorkflowModel.LOCAL
                ? "LOCAL_WORKFLOW_NOT_CONFIGURED" : "WORKBENCH_NOT_CONFIGURED");
        return selected;
    }
    private EverydayChat chat(WorkflowModel model) {
        if (model == WorkflowModel.API) return cloudChat;
        binding(model);
        if (localChat == null || !localChat.available()) throw new Failure(503,"LOCAL_WORKFLOW_NOT_CONFIGURED");
        return localChat;
    }
    private static WorkflowModel model(JsonNode body) {
        if (!body.has("workflowModel")) return WorkflowModel.API;
        try { return WorkflowModel.fromRequest(field(body, "workflowModel", 8)); }
        catch (IllegalArgumentException e) { throw new Failure(400,"INVALID_WORKFLOW_MODEL"); }
    }

    private void handle(HttpExchange exchange)throws IOException {
        try {
            String path=exchange.getRequestURI().getRawPath();
            if(exchange.getRequestURI().getRawQuery()!=null||path.contains("%"))throw new Failure(400,"INVALID_REQUEST");
            List<String> auth=exchange.getRequestHeaders().get("Authorization");
            if(auth==null||auth.size()!=1||!auth.get(0).startsWith("Bearer ")||auth.get(0).length()<8||auth.get(0).length()>8192)throw new Failure(401,"UNAUTHORIZED");
            boolean intake=path.equals("/assistant-workbench/order-intake");
            long owner=intake?services.authenticateOrderIntake(auth.get(0)):services.authenticate(auth.get(0));
            if(owner<=0)throw new Failure(401,"UNAUTHORIZED");
            if(path.equals("/assistant-workbench/task-plan")) {
                method(exchange,"POST");
                JsonNode input=body(exchange,Set.of("question","state","observations","catalog","history","workflowModel"),131072);
                WorkflowModel model = model(input);
                EverydayChat everyday = chat(model);
                ((ObjectNode)input).remove("workflowModel");
                field(input,"question",4000);
                if(!chatSlots.tryAcquire())throw new Failure(429,"CAPACITY_REACHED");
                try {
                    exchange.getResponseHeaders().set("Content-Type","application/x-ndjson; charset=utf-8");
                    exchange.getResponseHeaders().set("Cache-Control","no-store");
                    exchange.getResponseHeaders().set("X-Accel-Buffering","no");exchange.sendResponseHeaders(200,0);
                    var out=exchange.getResponseBody();
                    try{everyday.event(out,"result",everyday.planTask(input,out));}
                    catch(Exception e){everyday.event(out,"error",model == WorkflowModel.LOCAL ? "Ubuntu 微调 v2 任务规划失败，未执行生产操作" : "任务模型连接中断或返回格式无效；未执行生产操作");}
                } finally {chatSlots.release();}
                return;
            }
            if(path.equals("/assistant-workbench/chat-entry")) {
                method(exchange,"POST");
                var input=body(exchange,Set.of("text","history","context","workflowModel"));
                WorkflowModel model = model(input);
                EverydayChat everyday = chat(model);
                ((ObjectNode)input).remove("workflowModel");
                field(input,"text",2000);
                if(!Set.of("NEW","GENERAL","BUSINESS","ORDER","PRODUCTION_ACTION").contains(field(input,"context",24)))throw new Failure(400,"INVALID_REQUEST");
                if(input.has("history")){
                    var history=input.get("history");
                    if(!"GENERAL".equals(input.path("context").asText()) || !history.isObject() || history.size()!=2)
                        throw new Failure(400,"INVALID_REQUEST");
                    field(history,"user",2000);field(history,"assistant",4000);
                }
                if(!chatSlots.tryAcquire())throw new Failure(429,"CAPACITY_REACHED");
                try {
                    exchange.getResponseHeaders().set("Content-Type","application/x-ndjson; charset=utf-8");
                    exchange.getResponseHeaders().set("Cache-Control","no-store");
                    exchange.getResponseHeaders().set("X-Accel-Buffering","no");
                    exchange.sendResponseHeaders(200,0);
                    var out=exchange.getResponseBody();
                    try { everyday.event(out,"result",Map.of("route",everyday.route(input,out),"workflowModel",model.name())); }
                    catch(Exception e) { everyday.event(out,"error",model == WorkflowModel.LOCAL ? "Ubuntu 微调 v2 暂不可用，请检查远端连接或手动选择 API模型。" : "对话模型连接中断，请重试。"); }
                } finally {chatSlots.release();}
                return;
            }
            if(intake) {
                method(exchange,"POST");JsonNode input=body(exchange,Set.of("text","context","workflowModel"),131072);
                String text=field(input,"text",2000),context=field(input,"context",16000);
                try{if(!json.readTree(context).isObject())throw new Failure(400,"INVALID_REQUEST");}
                catch(IOException|NullPointerException invalid){throw new Failure(400,"INVALID_REQUEST");}
                Services selected=binding(model(input));
                if(!slots.tryAcquire())throw new Failure(429,"CAPACITY_REACHED");
                try{respond(exchange,200,WorkbenchHttpServices.validateIntake(selected.orderIntake(owner,text,context)));}
                finally{slots.release();}
                return;
            }
            if(path.equals("/assistant-workbench/status")) {
                method(exchange,"GET");boolean configured=services.configured();
                var status=new LinkedHashMap<String,Object>(Map.of("configured",configured,"available",configured,"message",configured?"READY":"请配置新工作台的 Dify SUP 应用 API 密钥。",
                        "directChat",cloudChat.available(),"productionActions",true,"capabilities",services.capabilities(owner),"authorizationStamp",services.authorizationStamp(owner),"naturalLanguage",services.naturalLanguage(),"allowedOrderIds",services.legacyAllowed(owner)?orders.stream().sorted().toList():List.of(),"profiles",services.legacyAllowed(owner)?PROFILES.stream().sorted().toList():List.of()));
                Services local = localServices.get();
                status.put("workflowModels",Map.of("API", configured, "LOCAL",
                        local != null && local.configured() && localChat != null && localChat.available()));
                respond(exchange,200,status);return;
            }
            if(path.equals("/assistant-workbench/runs/stream")) {
                method(exchange,"POST");JsonNode input=body(exchange,Set.of("text","orderPk","profile","sessionId","requestId","previousRunId","workflowModel"));
                WorkflowModel model = model(input);
                String text=field(input,"text",2000),order=null,profile=null,previous=null,generalAnswer=null;
                String session=identifier(input,"sessionId"),request=identifier(input,"requestId");
                boolean legacy=input.has("orderPk")||input.has("profile");
                if(legacy){
                    if(!services.legacyAllowed(owner))throw new Failure(403,"ADMIN_REQUIRED");
                    order=field(input,"orderPk",19);profile=field(input,"profile",32);
                    if(!PROFILES.contains(profile))throw new Failure(400,"INVALID_PROFILE");
                    if(!orders.contains(order))throw new Failure(403,"ORDER_NOT_ALLOWED");
                    if(input.has("previousRunId"))throw new Failure(400,"INVALID_REQUEST");
                }else if(!services.naturalLanguage())throw new Failure(503,"NATURAL_LANGUAGE_UNAVAILABLE");
                if(input.has("previousRunId")){
                    previous=field(input,"previousRunId",36);
                    try {
                        Run parent=owned(previous,owner);
                        if(parent.model!=model||!parent.session.equals(session)||!parent.status.equals("COMPLETED"))throw new Failure(409,"INVALID_PREVIOUS_RUN");
                        synchronized(parent){generalAnswer=generalAnswer(parent);}
                    } catch(Failure absent) {
                        if(absent.status!=404)throw absent;
                        Map<String,Object> saved=contextStore.load(previous,owner);
                        if(saved==null)throw new Failure(404,"PREVIOUS_RUN_UNAVAILABLE");
                        if(!model.name().equals(saved.getOrDefault("workflowModel","API"))||!session.equals(saved.get("sessionId")))throw new Failure(409,"INVALID_PREVIOUS_RUN");
                        if(Objects.equals(services.authorizationStamp(owner),saved.get("authorizationStamp"))
                                && saved.get("generalAnswer") instanceof String answer)generalAnswer=answer;
                    }
                }
                Services selected=binding(model);
                if(!slots.tryAcquire())throw new Failure(429,"CAPACITY_REACHED");
                try { Run run=create(owner,order,profile,session,request,text,previous,generalAnswer,model,selected);stream(exchange,run,text); } finally {slots.release();}
                return;
            }
            String[] parts=path.split("/",-1);
            if((parts.length==4||parts.length==5)&&parts[1].equals("assistant-workbench")&&parts[2].equals("runs")&&parts[3].matches("[a-f0-9-]{36}")) {
                if(parts.length==5&&parts[4].equals("audit")) {
                    method(exchange,"GET");var saved=audit.load(parts[3],owner);
                    if(saved==null)throw new Failure(404,"AUDIT_UNAVAILABLE");
                    respond(exchange,200,saved);return;
                }
                Run run=owned(parts[3],owner);
                if(parts.length==4){method(exchange,"GET");respond(exchange,200,snapshot(run));return;}
                if(parts[4].equals("cancel")){
                    method(exchange,"POST");body(exchange,Set.of());cancel(run,"CANCELLED");
                    if(run.status.equals("CANCELLED")) {
                        try {observe(run);}catch(Exception ignored){}
                    }
                    respond(exchange,200,snapshot(run));return;
                }
            }
            throw new Failure(404,"NOT_FOUND");
        }catch(Failure failure){respond(exchange,failure.status,Map.of("message",failure.code));}
        catch(Exception failure){respond(exchange,503,Map.of("message","WORKBENCH_UNAVAILABLE"));}
        finally{exchange.close();}
    }
    private synchronized Run create(long owner,String order,String profile,String session,String request,String text,String previous,String generalAnswer,WorkflowModel model,Services selected)throws Exception {
        runs.values().removeIf(run->!run.expires.isAfter(clock.instant())&&!run.status.equals("RUNNING"));
        requestsById.values().removeIf(expires->!expires.isAfter(clock.instant()));
        String key=owner+":"+session+":"+request;
        if(requestsById.containsKey(key))throw new Failure(409,"REQUEST_ALREADY_SUBMITTED");
        if(runs.size()>=64||requestsById.size()>=64)throw new Failure(429,"CAPACITY_REACHED");
        // Reserve before any remote call: a timeout may still have created an upstream run.
        requestsById.put(key,clock.instant().plusSeconds(600));
        String id=order==null?services.createNatural(owner,text,previous,generalAnswer):services.create(owner,order,profile);
        if(!id.matches("[a-f0-9-]{36}"))throw new Failure(503,"RUNTIME_UNAVAILABLE");
        Run run=new Run(id,owner,session,clock.instant().plusSeconds(600),model,selected);run.authorizationStamp=services.authorizationStamp(owner);runs.put(id,run);return run;
    }
    private synchronized Run owned(String id,long owner){
        Run run=runs.get(id);if(run==null||run.owner!=owner||!run.expires.isAfter(clock.instant()))throw new Failure(404,"RUN_UNAVAILABLE");if(!Objects.equals(run.authorizationStamp,services.authorizationStamp(owner)))throw new Failure(403,"PERMISSION_CHANGED");return run;
    }
    private final Map<HttpExchange,TaskEventStream> eventStreams=new ConcurrentHashMap<>();
    private Map<String,Object> snapshot(Run run){synchronized(run){
        Map<String,Object> result=new LinkedHashMap<>();result.put("runId",run.id);result.put("workflowModel",run.model.name());result.put("status",run.status);result.put("sessionId",run.session);result.put("partialText",run.partialText.toString());result.put("agents",List.copyOf(run.agents.values()));
        if(run.result!=null)result.put("result",run.result);
        if(run.workflow!=null)result.put("workflow",run.workflow);
        return result;
    }}
    private void stream(HttpExchange exchange,Run run,String text)throws IOException {
        boolean sse=Optional.ofNullable(exchange.getRequestHeaders().getFirst("Accept")).orElse("").contains("text/event-stream");
        eventStreams.put(exchange,new TaskEventStream(run.id,run.session,clock,sse?exchange.getResponseBody():OutputStream.nullOutputStream(),event->audit.record(run.owner,event)));
        exchange.getResponseHeaders().set("Content-Type",sse?"text/event-stream; charset=utf-8":"application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Accel-Buffering","no");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.sendResponseHeaders(200,0);
        Future<JsonNode> future=null;
        Future<?> observation=null;
        BlockingQueue<JsonNode> progress=new ArrayBlockingQueue<>(2048);
        long streamSequence=0,nextObservation=0,nextHeartbeat=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        try {
            emit(exchange,Map.of("type","run","runId",run.id,"workflowModel",run.model.name()));
            future=workflows.submit(()->{
                if(!run.status.equals("RUNNING"))throw new Failure(409,"RUN_CANCELLED");
                JsonNode data=run.services.workflow(run.id,text,run.owner,task->{
                    synchronized(run) {
                        run.taskId=task;run.supervisorStarted=clock.instant().toString();cacheSupervisor(run);
                    }
                    if(!run.status.equals("RUNNING"))bestEffortStop(run);
                },event->{if(run.status.equals("RUNNING")&&!progress.offer(event))throw new Failure(502,"DIFY_STREAM_LIMIT");});
                if(data==null||!data.isObject()||!data.path("reply").isTextual())throw new Failure(502,"WORKFLOW_INVALID_RESULT");
                if("DATA_CHANGED".equals(data.path("verdict").asText()))invalidateEvidence(run,false);
                ((ObjectNode)data).put("workflowModel",run.model.name());
                return data;
            });
            long end=System.nanoTime()+deadline.toNanos();
            while(true){
                if(!run.status.equals("RUNNING"))throw new Failure(409,"RUN_CANCELLED");
                if(System.nanoTime()>end)throw new Failure(504,"RUN_TIMED_OUT");
                // A slow evidence poll must never hold back already received model tokens.
                if(observation!=null&&observation.isDone()) {
                    observation.get();observation=null;
                    publishChanges(exchange,run);
                }
                if(observation==null&&System.nanoTime()>=nextObservation) {
                    observation=observations.submit(()->{observe(run);return null;});
                    nextObservation=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);
                }
                JsonNode update=progress.poll(50,TimeUnit.MILLISECONDS);
                while(update!=null) {
                    if(!run.status.equals("RUNNING"))throw new Failure(409,"RUN_CANCELLED");
                    if(!run.evidenceInvalidated) {
                        ObjectNode outgoing=update.deepCopy();outgoing.put("runId",run.id);outgoing.put("seq",++streamSequence);
                        rememberText(run,outgoing);emit(exchange,outgoing);
                    }
                    update=progress.poll();
                }
                if(future.isDone()) {
                    JsonNode data=future.get();
                    if(observation!=null){awaitObservation(observation,end);publishChanges(exchange,run);}
                    // The workflow may finish after the preceding snapshot was captured.
                    observation=observations.submit(()->{observe(run);return null;});
                    awaitObservation(observation,end);
                    publishChanges(exchange,run);
                    if(run.evidenceInvalidated&&!"DATA_CHANGED".equals(data.path("verdict").asText()))
                        throw new Failure(409,"RUN_EVIDENCE_INVALIDATED");
                    while((update=progress.poll())!=null) {
                        if(run.evidenceInvalidated)continue;
                        ObjectNode outgoing=update.deepCopy();outgoing.put("runId",run.id);outgoing.put("seq",++streamSequence);rememberText(run,outgoing);emit(exchange,outgoing);
                    }
                    if(!finish(run,"COMPLETED",data))throw new Failure(409,"RUN_CANCELLED");
                    publishChanges(exchange,run);
                    emit(exchange,Map.of("type","result","data",data));emit(exchange,Map.of("type","done"));return;
                }
                // Transport heartbeat is never represented as agent activity.
                if(System.nanoTime()>=nextHeartbeat) {
                    if(sse)eventStreams.get(exchange).heartbeat();
                    else {exchange.getResponseBody().write('\n');exchange.getResponseBody().flush();}
                    nextHeartbeat=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                }
            }
        }catch(Exception failure){
            if(run.status.equals("COMPLETED"))return;
            if(failure instanceof Failure f&&f.code.equals("RUN_TIMED_OUT"))invalidateEvidence(run,true);
            cancel(run,"FAILED");
            boolean cancelled=run.status.equals("CANCELLED");
            try {publishChanges(exchange,run);}catch(IOException ignored){}
            Throwable cause=failure instanceof ExecutionException?failure.getCause():failure;
            String code=cause instanceof Failure f?f.code:"WORKFLOW_FAILED";
            if (run.model == WorkflowModel.LOCAL && (code.equals("WORKFLOW_FAILED") || code.startsWith("DIFY_"))) code="LOCAL_WORKFLOW_FAILED";
            try {emit(exchange,Map.of("type","error","message",cancelled?"RUN_CANCELLED":code));emit(exchange,Map.of("type","done"));}catch(IOException disconnected){}
        }finally{audit.finish(run.id,run.owner,run.status);eventStreams.remove(exchange);if(future!=null&&!future.isDone())future.cancel(true);if(observation!=null&&!observation.isDone())observation.cancel(true);}
    }
    private void awaitObservation(Future<?> poll,long deadlineNanos)throws Exception {
        try{
            poll.get(Math.max(0,deadlineNanos-System.nanoTime()),TimeUnit.NANOSECONDS);
        }catch(TimeoutException timeout){
            throw new Failure(504,"RUN_TIMED_OUT");
        }
    }
    private void observe(Run run)throws Exception {
        long poll;
        synchronized(run){poll=++run.observationStarted;}
        JsonNode observation;
        try {observation=services.observe(run.id);}
        catch(Exception failure){invalidateEvidence(run,true);throw failure;}
        synchronized(run){
            JsonNode workflow=observation.path("workflow");
            if("DATA_CHANGED".equals(observation.path("result").path("verdict").asText())
                ||workflow.path("invalidated").asBoolean(false))invalidateEvidence(run,observation.hasNonNull("workflow"));
            if(run.evidenceInvalidated&&run.workflow==null&&observation.hasNonNull("workflow"))run.workflow=workflowEnvelope(run,true);
            if(poll<run.observationAccepted||(!run.status.equals("RUNNING")&&"RUNNING".equals(observation.path("status").asText())))return;
            JsonNode nextWorkflow=run.evidenceInvalidated?run.workflow:projectWorkflow(run,workflow);
            if(!run.evidenceInvalidated&&run.workflow!=null&&(nextWorkflow==null
                ||run.workflow.path("events").size()>nextWorkflow.path("events").size()
                ||run.workflow.path("truncated").asBoolean()&&!nextWorkflow.path("truncated").asBoolean()))return;
            Map<String,JsonNode> agents=new LinkedHashMap<>();
            for(JsonNode task:observation.path("tasks")) {
                String agent=task.path("agentId").asText(),status=task.path("status").asText();
                if(!WORKFLOW_ROLES.contains(agent)||!WORKFLOW_STATUSES.contains(status))throw new Failure(502,"RUNTIME_INVALID_OBSERVATION");
                JsonNode previous=run.agents.get(agent);
                if(status.equals("RUNNING")&&(!run.status.equals("RUNNING")||(previous!=null&&!"RUNNING".equals(previous.path("status").asText()))))return;
                ObjectNode event=json.createObjectNode();event.put("type","agent");
                for(String field:List.of("agentId","status","summary","evidence","steps","startedAt","completedAt"))if(task.has(field))event.set(field,task.get(field));
                if(task.path("taskId").asText().matches("[a-f0-9-]{36}"))event.set("taskId",task.get("taskId"));
                if(run.evidenceInvalidated)clearAgentEvidence(event);
                agents.put(agent,event);
            }
            run.workflow=nextWorkflow;
            run.agents.putAll(agents);
            run.observationAccepted=poll;
        }
    }
    private void publishChanges(HttpExchange exchange,Run run)throws IOException {
        List<JsonNode> changes=new ArrayList<>();
        synchronized(run) {
            if(!Objects.equals(run.sentWorkflow,run.workflow)) {
                changes.add(run.workflow);run.sentWorkflow=run.workflow;
            }
            for(var entry:run.agents.entrySet()) {
                if(!entry.getValue().equals(run.sentAgents.put(entry.getKey(),entry.getValue())))changes.add(entry.getValue());
            }
        }
        for(JsonNode change:changes)emit(exchange,change);
    }
    private static final Set<String> WORKFLOW_ROLES=Set.of("ORDER","SUPPLY","PRODUCTION","QUALITY","EQUIPMENT","AFTERSALES");
    private static final Set<String> WORKFLOW_KINDS=Set.of("PLAN_CONFIRMED","AGENT_STARTED","STEP_STARTED","STEP_COMPLETED",
        "HANDOFF","AGENT_COMPLETED","SUMMARIZING","CANCELLED");
    private static final Set<String> WORKFLOW_STATUSES=Set.of("RUNNING","COMPLETED","FAILED","CANCELLED");

    private ObjectNode projectWorkflow(Run run,JsonNode source) {
        if(source.isMissingNode()||source.isNull())return null;
        requireWorkflow(source.isObject()&&source.path("invalidated").isBoolean());
        ObjectNode event=workflowEnvelope(run,false);
        JsonNode plan=source.path("plan");
        if(!plan.isNull())event.set("plan",workflowPlan(plan));
        JsonNode events=source.path("events");
        requireWorkflow(events.isArray()&&events.size()<=NaturalLanguageRuntime.MAX_WORKFLOW_EVENTS);
        long seq=0;
        for(JsonNode item:events) {
            JsonNode number=item.path("seq");
            requireWorkflow(number.isIntegralNumber()&&number.canConvertToLong()&&number.asLong()>seq);
            seq=number.asLong();
            event.withArray("events").add(workflowItem(item,seq));
        }
        if(source.has("truncated")) {
            requireWorkflow(source.path("truncated").isBoolean());
            event.put("truncated",source.path("truncated").asBoolean());
        }
        return event;
    }
    private ObjectNode workflowEnvelope(Run run,boolean invalidated) {
        ObjectNode event=json.createObjectNode().put("type","workflow").put("runId",run.id).put("invalidated",invalidated);
        event.putNull("plan");event.putArray("events");
        return event;
    }
    private ObjectNode workflowPlan(JsonNode plan) {
        requireWorkflow(plan.isObject());
        String action=workflowChoice(plan,"action",Set.of("QUERY","CLARIFY","UNSUPPORTED"));
        Set<String> roles=workflowRoles(plan.path("requiredAgents"),WORKFLOW_ROLES);
        JsonNode dependencies=plan.path("dependencies");
        requireWorkflow(dependencies.isObject());
        ObjectNode safe=json.createObjectNode().put("action",action).put("confirmedAt",workflowTime(plan,"confirmedAt"));
        roles.forEach(safe.putArray("requiredAgents")::add);
        ObjectNode safeDependencies=safe.putObject("dependencies");
        for(String role:roles) {
            Set<String> upstream=workflowRoles(dependencies.path(role),roles);
            requireWorkflow(!upstream.contains(role));
            upstream.forEach(safeDependencies.putArray(role)::add);
        }
        return safe;
    }
    private Set<String> workflowRoles(JsonNode list,Set<String> allowed) {
        requireWorkflow(list.isArray()&&list.size()<=allowed.size());
        Set<String> roles=new LinkedHashSet<>();
        for(JsonNode role:list)requireWorkflow(role.isTextual()&&allowed.contains(role.asText())&&roles.add(role.asText()));
        return roles;
    }
    private ObjectNode workflowItem(JsonNode source,long seq) {
        String kind=workflowChoice(source,"kind",WORKFLOW_KINDS);
        String agent=workflowText(source,"agentId",12);
        boolean supervisor=Set.of("PLAN_CONFIRMED","SUMMARIZING","CANCELLED").contains(kind);
        requireWorkflow(supervisor?"SUP".equals(agent):WORKFLOW_ROLES.contains(agent));
        ObjectNode item=json.createObjectNode().put("seq",seq).put("kind",kind).put("agentId",agent)
            .put("status",workflowChoice(source,"status",WORKFLOW_STATUSES))
            .put("label",workflowText(source,"label",80)).put("detail",workflowText(source,"detail",200))
            .put("at",workflowTime(source,"at"));
        for(String key:List.of("eventId","runRef","toolCallId"))if(source.has(key)) {
            String value=workflowText(source,key,36);
            requireWorkflow(value.matches("[a-f0-9-]{36}"));item.put(key,value);
        }
        if(source.has("toolId")) {String tool=workflowText(source,"toolId",40);requireWorkflow(tool.matches("[A-Z0-9_]+"));item.put("toolId",tool);}
        if(!supervisor&&source.has("taskId")) {
            String taskId=workflowText(source,"taskId",36);
            requireWorkflow(taskId.matches("[a-f0-9-]{36}"));
            item.put("taskId",taskId);
        }
        if(kind.equals("HANDOFF")) {
            String from=workflowChoice(source,"fromAgent",WORKFLOW_ROLES),to=workflowChoice(source,"toAgent",WORKFLOW_ROLES);
            requireWorkflow(to.equals(agent)&&!from.equals(to));
            item.put("fromAgent",from).put("toAgent",to);
        }
        return item;
    }
    private static String workflowChoice(JsonNode source,String field,Set<String> allowed) {
        String value=workflowText(source,field,30);
        requireWorkflow(allowed.contains(value));return value;
    }
    private static String workflowTime(JsonNode source,String field) {
        try{return Instant.parse(workflowText(source,field,40)).toString();}
        catch(DateTimeException invalid){throw new Failure(502,"RUNTIME_INVALID_OBSERVATION");}
    }
    private static String workflowText(JsonNode source,String field,int limit) {
        JsonNode value=source.path(field);
        requireWorkflow(value.isTextual()&&value.asText().length()<=limit&&value.asText().chars().noneMatch(Character::isISOControl));
        return value.asText();
    }
    private static void requireWorkflow(boolean valid) {if(!valid)throw new Failure(502,"RUNTIME_INVALID_OBSERVATION");}
    private void cacheSupervisor(Run run) {
        if(run.supervisorStarted==null)return;
        ObjectNode event=json.createObjectNode();event.put("type","agent");event.put("agentId","SUP");event.put("status",run.status);event.put("startedAt",run.supervisorStarted);
        if(run.supervisorCompleted!=null)event.put("completedAt",run.supervisorCompleted);
        run.agents.put("SUP",event);
    }
    private void invalidateEvidence(Run run,boolean workflowObserved) {
        synchronized(run){
            if(workflowObserved||run.workflow!=null)run.workflow=workflowEnvelope(run,true);
            if(run.evidenceInvalidated)return;
            run.evidenceInvalidated=true;run.partialText.setLength(0);
            for(var entry:run.agents.entrySet()){
                JsonNode previous=entry.getValue();
                if(previous.has("summary")||previous.has("evidence")||previous.has("steps"))entry.setValue(clearAgentEvidence(previous.deepCopy()));
            }
        }
    }
    private static ObjectNode clearAgentEvidence(ObjectNode event) {
        event.put("summary","");event.putArray("evidence");event.putArray("steps");
        return event;
    }
    private boolean finish(Run run,String status,JsonNode data) {
        synchronized(run) {
            if(!run.status.equals("RUNNING"))return false;
            run.status=status;
            run.result=data;
            if(status.equals("COMPLETED")) {
                run.supervisorCompleted=clock.instant().toString();
                var retained=new LinkedHashMap<String,Object>();
                retained.put("sessionId",run.session);
                retained.put("workflowModel",run.model.name());
                String answer=generalAnswer(run);
                if(answer!=null){retained.put("generalAnswer",answer);retained.put("authorizationStamp",run.authorizationStamp);}
                contextStore.save(run.id,run.owner,retained);
            }
            cacheSupervisor(run);
            return true;
        }
    }
    private static String generalAnswer(Run run) {
        var result=run.result;
        if(run.evidenceInvalidated||result==null||!"EVIDENCE_READY".equals(result.path("verdict").asText())
            ||!"NATURAL".equals(result.path("queryMode").asText())||result.path("intents").size()!=1
            ||!"GENERAL".equals(result.path("intents").path(0).asText())||!result.path("facts").isObject()
            ||!result.path("facts").isEmpty()||!result.path("reply").isTextual())return null;
        String answer=result.path("reply").asText();
        if(answer.isBlank())return null;
        if(answer.length()<=4000)return answer;
        int end=Character.isHighSurrogate(answer.charAt(3969))?3969:3970;
        return answer.substring(0,end)+"…（上轮回答过长，仅保留前段）";
    }
    private void cancel(Run run,String status){
        if(!finish(run,status,null))return;
        try{run.services.cancel(run.id);}catch(Exception ignored){} bestEffortStop(run);
    }
    private void bestEffortStop(Run run){try{run.services.stop(run.id,run.taskId,run.owner);}catch(Exception ignored){}}
    private void rememberText(Run run,ObjectNode update){synchronized(run){if(update.path("type").asText().equals("delta")&&run.partialText.length()+update.path("text").asText().length()<=64000)run.partialText.append(update.path("text").asText());}}
    private void emit(HttpExchange exchange,Object value)throws IOException {
        TaskEventStream writer=eventStreams.get(exchange);
        if(writer!=null){writer.accept(json.valueToTree(value));if(exchange.getResponseHeaders().getFirst("Content-Type").startsWith("text/event-stream"))return;}
        byte[] bytes=json.writeValueAsBytes(value);
        if(bytes.length>2*1024*1024)throw new Failure(502,"DEPENDENCY_RESPONSE_LIMIT");
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().write('\n');
        exchange.getResponseBody().flush();
    }
    private void respond(HttpExchange exchange,int status,Object value)throws IOException {byte[] bytes=json.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
    private JsonNode body(HttpExchange exchange,Set<String> fields)throws IOException {
        return body(exchange,fields,32768);
    }
    private JsonNode body(HttpExchange exchange,Set<String> fields,int maximum)throws IOException {
        String content=exchange.getRequestHeaders().getFirst("Content-Type");if(content==null||!content.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw new Failure(415,"JSON_REQUIRED");
        byte[] bytes=exchange.getRequestBody().readNBytes(maximum+1);if(bytes.length>maximum)throw new Failure(413,"BODY_TOO_LARGE");
        JsonNode node;try{node=json.readTree(bytes);}catch(IOException bad){throw new Failure(400,"INVALID_REQUEST");}
        if(node==null||!node.isObject())throw new Failure(400,"INVALID_REQUEST");node.fieldNames().forEachRemaining(f->{if(!fields.contains(f))throw new Failure(400,"INVALID_REQUEST");});return node;
    }
    private static String field(JsonNode node,String name,int max){JsonNode value=node.get(name);if(value==null||!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw new Failure(400,"INVALID_REQUEST");return value.asText();}
    private static String identifier(JsonNode node,String name){String value=field(node,name,80);if(!value.matches("[A-Za-z0-9_-]+"))throw new Failure(400,"INVALID_REQUEST");return value;}
    private static void method(HttpExchange exchange,String expected){if(!exchange.getRequestMethod().equals(expected))throw new Failure(405,"METHOD_NOT_ALLOWED");}
    public static final class Failure extends RuntimeException{final int status;final String code;public Failure(int status,String code){super(code);this.status=status;this.code=code;}}
    private static final class Run {
        final String id,session;final long owner;final Instant expires;final Map<String,JsonNode> agents=new LinkedHashMap<>();
        final Map<String,JsonNode> sentAgents=new LinkedHashMap<>();
        volatile String status="RUNNING",taskId;
        String authorizationStamp,supervisorStarted,supervisorCompleted;
        JsonNode result,workflow,sentWorkflow;final StringBuilder partialText=new StringBuilder();
        long observationStarted,observationAccepted;
        volatile boolean evidenceInvalidated;
        final WorkflowModel model;
        final Services services;
        Run(String id,long owner,String session,Instant expires,WorkflowModel model,Services services){
            this.id=id;this.owner=owner;this.session=session;this.expires=expires;this.model=model;this.services=services;
        }
    }
}
