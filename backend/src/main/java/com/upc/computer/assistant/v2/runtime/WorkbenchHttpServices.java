package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.upc.computer.assistant.v2.runtime.WorkbenchGatewayServer.Failure;

/** Public Dify workflow API adapter. Credentials never enter browser events or exception messages. */
public final class WorkbenchHttpServices implements WorkbenchGatewayServer.Services {
    // FULL_ASSESSMENT agent_node inputs/outputs alone exceed 262,144 characters.
    // Keep intermediate diagnostics bounded without rejecting legitimate six-expert workflows.
    private static final int MAX_SSE_EVENT_CHARS=1_048_576;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI mes,runtime,dify;
    private final String bootstrap,key;
    private final LongPredicate activeAdmin;
    private final LongPredicate activeOrderIntake;
    private java.util.function.LongFunction<AssistantReadPolicy> policyReader;
    private final Map<String,HttpURLConnection> connections=new ConcurrentHashMap<>();
    public WorkbenchHttpServices(URI mes,URI runtime,URI dify,String bootstrap,String key,LongPredicate activeAdmin){
        this(mes,runtime,dify,bootstrap,key,activeAdmin,activeAdmin);
    }
    public WorkbenchHttpServices(URI mes,URI runtime,URI dify,String bootstrap,String key,LongPredicate activeAdmin,LongPredicate activeOrderIntake){
        this.mes=mes;this.runtime=runtime;this.dify=dify;this.bootstrap=bootstrap;this.key=key;this.activeAdmin=activeAdmin;this.activeOrderIntake=activeOrderIntake;
    }
    public WorkbenchHttpServices(URI mes,URI runtime,URI dify,String bootstrap,String key,LongPredicate admin,LongPredicate intake,java.util.function.LongFunction<AssistantReadPolicy> policyReader) {
        this(mes,runtime,dify,bootstrap,key,admin,intake);this.policyReader=policyReader;
    }
    @Override public Map<String,Object> capabilities(long owner){return policyReader==null?Map.of():policyReader.apply(owner).publicCapabilities();}
    @Override public String authorizationStamp(long owner){return policyReader==null?"ADMIN":policyReader.apply(owner).fingerprint();}
    @Override public boolean legacyAllowed(long owner){return activeAdmin.test(owner);}
    @Override public boolean configured(){return bootstrap!=null&&!bootstrap.isBlank()&&key!=null&&!key.isBlank();}
    @Override public boolean naturalLanguage(){return true;}
    @Override public String createNatural(long owner,String text,String previous)throws Exception {
        Map<String,Object> request=new LinkedHashMap<>();request.put("query",text);request.put("ownerUserId",Long.toString(owner));
        if(previous!=null)request.put("previousRunRef",previous);
        return call(runtime,"/stage5/natural/runs","POST","Bearer "+bootstrap,request).path("runRef").asText();
    }
    @Override public long authenticate(String authorization)throws Exception {
        long id=authenticatedUser(authorization);
        if(policyReader!=null){policyReader.apply(id);return id;}
        if(!activeAdmin.test(id))throw new Failure(403,"ADMIN_REQUIRED");return id;
    }
    @Override public long authenticateOrderIntake(String authorization)throws Exception {
        long id=authenticatedUser(authorization);
        if(!activeOrderIntake.test(id))throw new Failure(403,"ORDER_INTAKE_ROLE_REQUIRED");return id;
    }
    private long authenticatedUser(String authorization)throws Exception {
        JsonNode response=call(mes,"/auth/userInfo","GET",authorization,null);
        JsonNode user=response.path("data");
        if(response.path("code").asInt()!=200||!user.path("userId").canConvertToLong()||user.path("userId").asLong()<=0)throw new Failure(401,"UNAUTHORIZED");
        long id=user.path("userId").asLong();
        // The cached session establishes identity, while a fixed fresh DB read establishes current authorization.
        return id;
    }
    @Override public JsonNode orderIntake(long owner,String text,String context)throws Exception {
        // The dummy UUID is only used by the isolated intake branch; no runtime run is created.
        Map<String,Object> request=Map.of("inputs",Map.of("run_ref","00000000-0000-0000-0000-000000000000","query",text,"order_intake",context),
                "response_mode","blocking","user",user(owner));
        HttpURLConnection connection=open(dify,"/workflows/run","POST","Bearer "+key,request,90000);
        try {
            if(connection.getResponseCode()!=200)throw new Failure(502,"DIFY_REQUEST_FAILED");
            try(InputStream stream=connection.getInputStream()) {
                byte[] bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new Failure(502,"DIFY_INVALID_INTAKE");
                JsonNode data=json.readTree(bytes).path("data");
                if(!"succeeded".equals(data.path("status").asText()))throw new Failure(502,"DIFY_WORKFLOW_FAILED");
                JsonNode value=data.path("outputs").path("order_intake_json");
                if(!value.isTextual()||value.asText().length()>8000)throw new Failure(502,"DIFY_INVALID_INTAKE");
                return validateIntake(json.readTree(value.asText()));
            }
        }catch(Failure failure){throw failure;}
        catch(Exception failure){throw new Failure(502,"DIFY_INTAKE_UNAVAILABLE");}
        finally{connection.disconnect();}
    }
    private static final Map<String,Integer> INTAKE_FIELDS=Map.of("quantity",0,"productQuery",200,"requiredDeliveryDate",10,
            "receiverName",100,"receiverPhone",50,"receiverAddress",500,"customerName",100,"remark",1000);
    static JsonNode validateIntake(JsonNode value) {
        requireIntake(value!=null&&value.isObject()&&value.size()==4);
        value.fieldNames().forEachRemaining(f->requireIntake(Set.of("action","fields","clearFields","question").contains(f)));
        requireIntake(value.path("action").isTextual()&&Set.of("ORDER","QUERY","CANCEL").contains(value.path("action").asText()));
        JsonNode fields=value.path("fields"),clear=value.path("clearFields");
        requireIntake(fields.isObject()&&fields.size()<=INTAKE_FIELDS.size()&&clear.isArray()&&clear.size()<=INTAKE_FIELDS.size());
        fields.fields().forEachRemaining(entry->{
            String field=entry.getKey();JsonNode item=entry.getValue();requireIntake(INTAKE_FIELDS.containsKey(field));
            if(field.equals("quantity"))requireIntake(item.isIntegralNumber()&&item.canConvertToInt()&&item.asInt()>0);
            else {
                requireIntake(item.isTextual()&&!item.asText().isBlank()&&item.asText().length()<=INTAKE_FIELDS.get(field)&&item.asText().chars().noneMatch(Character::isISOControl));
                if(field.equals("requiredDeliveryDate")) {
                    requireIntake(item.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
                    try{LocalDate.parse(item.asText());}catch(Exception invalid){throw new Failure(502,"DIFY_INVALID_INTAKE");}
                }
            }
        });
        Set<String> cleared=new HashSet<>();
        clear.forEach(f->requireIntake(f.isTextual()&&INTAKE_FIELDS.containsKey(f.asText())&&cleared.add(f.asText())&&!fields.has(f.asText())));
        requireIntake(value.path("question").isTextual()&&value.path("question").asText().length()<=500&&value.path("question").asText().chars().noneMatch(Character::isISOControl));
        requireIntake("ORDER".equals(value.path("action").asText())||(fields.isEmpty()&&clear.isEmpty()));
        return value;
    }
    private static void requireIntake(boolean valid){if(!valid)throw new Failure(502,"DIFY_INVALID_INTAKE");}
    @Override public String create(long owner,String order,String profile)throws Exception {
        return call(runtime,"/stage5/runs","POST","Bearer "+bootstrap,Map.of("orderPk",order,"workflowProfile",profile,"ownerUserId",Long.toString(owner))).path("runRef").asText();
    }
    @Override public JsonNode observe(String run)throws Exception{return call(runtime,"/stage5/runs/"+run+"/observe","GET","Bearer "+bootstrap,null);}
    @Override public void cancel(String run)throws Exception{call(runtime,"/stage5/runs/"+run+"/cancel","POST","Bearer "+bootstrap,Map.of());}
    @Override public void stop(String run,String task,long owner)throws Exception {
        HttpURLConnection connection=connections.remove(run);if(connection!=null)connection.disconnect();
        if(task!=null&&task.matches("[A-Za-z0-9_-]{1,100}"))call(dify,"/workflows/tasks/"+task+"/stop","POST","Bearer "+key,Map.of("user",user(owner)));
    }
    @Override public JsonNode workflow(String run,String text,long owner,Consumer<String> taskId)throws Exception {
        return workflow(run,text,owner,taskId,event->{});
    }
    @Override public JsonNode workflow(String run,String text,long owner,Consumer<String> taskId,Consumer<JsonNode> progress)throws Exception {
        Map<String,Object> request=Map.of("inputs",Map.of("run_ref",run,"query",text),"response_mode","streaming","user",user(owner));
        HttpURLConnection connection=open(dify,"/workflows/run","POST","Bearer "+key,request,15000);
        connections.put(run,connection);
        try {
            if(connection.getResponseCode()!=200)throw new Failure(502,"DIFY_REQUEST_FAILED");
            int total=0,answerLength=0;boolean snapshotSent=false;
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8))){
                StringBuilder frame=new StringBuilder();String line;
                while((line=boundedLine(reader))!=null){
                    total+=line.length();if(total>4_194_304)throw new Failure(502,"DIFY_STREAM_LIMIT");
                    if(line.isEmpty()) {
                        if(frame.isEmpty())continue;
                        JsonNode event=json.readTree(frame.toString());frame.setLength(0);
                        String kind=event.path("event").asText(),task=event.path("task_id").asText();
                        if(kind.equals("workflow_started")&&!task.isBlank())taskId.accept(task);
                        if(kind.equals("error"))throw new Failure(502,"DIFY_WORKFLOW_FAILED");
                        // Only the dedicated final presentation node can emit user-visible text.
                        // Planner JSON, agent reasoning, tool inputs and credential-bearing diagnostics never pass through.
                        JsonNode selector=event.path("data").path("from_variable_selector");
                        if(kind.equals("text_chunk")&&selector.isArray()&&selector.size()==2&&
                            selector.get(0).asText().equals("response_llm")&&selector.get(1).asText().equals("text")) {
                            JsonNode chunk=event.path("data").path("text");
                            if(!chunk.isTextual())throw new Failure(502,"DIFY_INVALID_RESULT");
                            answerLength+=chunk.asText().length();if(answerLength>64000)throw new Failure(502,"DIFY_STREAM_LIMIT");
                            if(!snapshotSent) {
                                JsonNode authoritative=observe(run).path("result");
                                if(!authoritative.isObject()||!authoritative.path("runRef").asText().equals(run))throw new Failure(502,"RUNTIME_FINALIZATION_REQUIRED");
                                if("DATA_CHANGED".equals(authoritative.path("verdict").asText()))continue;
                                ObjectNode snapshot=authoritative.deepCopy();snapshot.put("reply","");snapshot.put("presentation","compact_stream");
                                progress.accept(json.createObjectNode().put("type","snapshot").set("data",snapshot));snapshotSent=true;
                            }
                            if(!chunk.asText().isEmpty())progress.accept(json.createObjectNode().put("type","delta").put("text",chunk.asText()));
                        }
                        if(kind.equals("workflow_finished")) {
                            if(!event.path("data").path("status").asText().equals("succeeded"))throw new Failure(502,"DIFY_WORKFLOW_FAILED");
                            JsonNode outputs=event.path("data").path("outputs");
                            if(!outputs.path("answer").isTextual()||outputs.path("answer").asText().isBlank()||outputs.path("answer").asText().length()>100000)throw new Failure(502,"DIFY_INVALID_RESULT");
                            JsonNode decision=outputs.path("decision").isTextual()?json.readTree(outputs.path("decision").asText()):outputs.path("decision");
                            if(decision==null||!decision.path("runRef").asText().equals(run))throw new Failure(502,"DIFY_RESULT_SCOPE_MISMATCH");
                            JsonNode authoritative=observe(run).path("result");
                            if(!authoritative.isObject()||!authoritative.path("runRef").asText().equals(run)||!authoritative.path("verdict").equals(decision.path("verdict")))throw new Failure(502,"RUNTIME_FINALIZATION_REQUIRED");
                            ObjectNode result=json.createObjectNode();result.put("reply",outputs.path("answer").asText());
                            if(outputs.path("presentation").asText().equals("compact_stream"))result.put("presentation","compact_stream");
                            if("DATA_CHANGED".equals(authoritative.path("verdict").asText()))result.put("reply",authoritative.path("reply").asText("查询期间业务记录已变化，请重新查询。"));
                            for(String field:List.of("runRef","workflowProfile","queryMode","scope","plan","intents","verdict","sourceMode","facts","missing","checkedAt","queryRefs","resultRefs","revalidation","executedAgents","expertExplanations","knowledgeExplanations","analyses","knowledgeEvidence"))
                                if(authoritative.has(field))result.set(field,authoritative.get(field));
                            if(decision.has("presentation_truncated"))result.set("presentation_truncated",decision.get("presentation_truncated"));
                            return result;
                        }
                    }else if(line.startsWith("data:")){
                        if(!frame.isEmpty())frame.append('\n');frame.append(line.substring(5).stripLeading());
                        if(frame.length()>MAX_SSE_EVENT_CHARS)throw new Failure(502,"DIFY_EVENT_LIMIT");
                    }
                }
            }
            throw new Failure(502,"DIFY_STREAM_INCOMPLETE");
        }catch(Failure failure){throw failure;}
        catch(Exception failure){throw new Failure(502,"DIFY_UNAVAILABLE");}
        finally{connections.remove(run,connection);connection.disconnect();}
    }
    private JsonNode call(URI base,String path,String method,String authorization,Object body)throws Exception {
        HttpURLConnection connection=open(base,path,method,authorization,body,5000);
        try {
            int code=connection.getResponseCode();
            if(code<200||code>=300)throw new Failure(code==401?401:503,code==401?"UNAUTHORIZED":"DEPENDENCY_UNAVAILABLE");
            try(InputStream stream=connection.getInputStream()){
                // Observations contain both role packets and the final factual snapshot.
                // Bound the envelope without treating ordinary history lists as failed queries.
                byte[] bytes=stream.readNBytes(4*1024*1024+1);if(bytes.length>4*1024*1024)throw new Failure(502,"DEPENDENCY_RESPONSE_LIMIT");return json.readTree(bytes);
            }
        }finally{connection.disconnect();}
    }
    private HttpURLConnection open(URI base,String path,String method,String authorization,Object body,int readTimeout)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)URI.create(base.toString().replaceAll("/$","")+path).toURL().openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(readTimeout);connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Accept","application/json, text/event-stream");
        if(body!=null){byte[] bytes=json.writeValueAsBytes(body);connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(bytes.length);try(OutputStream stream=connection.getOutputStream()){stream.write(bytes);}}
        return connection;
    }
    private static String boundedLine(Reader reader)throws IOException {
        StringBuilder line=new StringBuilder();int value;
        while((value=reader.read())!=-1){if(value=='\n')break;if(value!='\r')line.append((char)value);if(line.length()>MAX_SSE_EVENT_CHARS)throw new Failure(502,"DIFY_EVENT_LIMIT");}
        return value==-1&&line.isEmpty()?null:line.toString();
    }
    private static String user(long owner){return "mes-workbench-user-"+owner;}
}
