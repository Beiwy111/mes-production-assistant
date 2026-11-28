package com.upc.computer.assistant.v2.runtime;

import org.apache.ibatis.session.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Separate JVM; never starts Spring or restarts the MES application. */
public final class WorkbenchRuntimeMain {
    private WorkbenchRuntimeMain(){}
    public static void main(String[] args){
        try {
            Map<String,String> env=System.getenv();
            SqlSessionFactory identities=createIdentityFactory(env);
            URI dify=URI.create(env.getOrDefault("MES_WORKBENCH_DIFY_BASE_URL","http://127.0.0.1:18080/v1"));
            if(!Set.of("127.0.0.1","localhost").contains(dify.getHost())||!dify.getScheme().equals("http")||dify.getUserInfo()!=null||dify.getQuery()!=null||dify.getFragment()!=null||!dify.getPath().equals("/v1"))throw new IllegalArgumentException("Local Dify v1 endpoint required");
            var cloud=workflowServices(env,identities,dify,env.get("MES_WORKBENCH_DIFY_API_KEY"));
            Supplier<WorkbenchGatewayServer.Services> local = new Supplier<>() {
                private WorkbenchGatewayServer.Services published;
                @Override public synchronized WorkbenchGatewayServer.Services get() {
                    if (published == null) {
                        String key = localWorkflowKey(env);
                        if (key != null) published = workflowServices(env, identities, dify, key);
                    }
                    return published;
                }
            };
            Set<String> orders=new HashSet<>(Arrays.asList(env.getOrDefault("MES_AGENT_ALLOWED_ORDER_IDS","").split(",")));
            var server=new WorkbenchGatewayServer(cloud,local,new EverydayChat(),EverydayChat.localV2(env),orders,Clock.systemUTC(),Duration.ofMinutes(5),18083,
                new ConversationScopeStore(java.nio.file.Path.of("backend/assistant/rag/data/conversation-context"),"gateway",Clock.systemUTC(),env.get("MES_AGENT_BOOTSTRAP_KEY")),
                new RunAuditStore(java.nio.file.Path.of("backend/assistant/rag/data/run-audit"),Clock.systemUTC(),env.get("MES_AGENT_BOOTSTRAP_KEY")));
            Runtime.getRuntime().addShutdownHook(new Thread(server::close,"workbench-shutdown"));server.start();
            System.out.println("MES assistant workbench listening on 127.0.0.1:18083; Dify configured="+cloud.configured());
        }catch(Exception failure){System.err.println("Workbench startup failed; verify private configuration and port availability.");System.exit(1);}
    }
    static String localWorkflowKey(Map<String,String> env) {
        String key = env.get("MES_WORKBENCH_V2_DIFY_API_KEY");
        if (key != null && key.matches("app-[a-zA-Z0-9]+")) return key;
        String file = env.get("MES_WORKBENCH_V2_BINDING_FILE");
        if (file == null) return null;
        try {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || Files.size(path) > 16_384) return null;
            var settings = new Properties();
            try (var reader = Files.newBufferedReader(path)) { settings.load(reader); }
            if (!Objects.equals(env.get("MES_WORKFLOW_V2_BASE_URL"), settings.getProperty("MES_WORKFLOW_V2_BASE_URL"))) return null;
            key = settings.getProperty("MES_WORKBENCH_V2_DIFY_API_KEY");
            return key != null && key.matches("app-[a-zA-Z0-9]+") ? key : null;
        } catch (java.io.IOException | IllegalArgumentException e) { return null; }
    }
    private static WorkbenchHttpServices workflowServices(Map<String,String> env,SqlSessionFactory identities,URI dify,String key) {
        return new WorkbenchHttpServices(URI.create("http://127.0.0.1:8088"),URI.create("http://127.0.0.1:18082"),dify,
                env.get("MES_AGENT_BOOTSTRAP_KEY"),key,owner->activeRole(identities,owner,false),
                owner->activeRole(identities,owner,true),owner->readPolicy(identities,owner));
    }
    public static SqlSessionFactory createIdentityFactory(Map<String,String> env){
        var factory=Stage5RuntimeMain.createFactory(env);if(!factory.getConfiguration().hasMapper(WorkbenchIdentityReadMapper.class))factory.getConfiguration().addMapper(WorkbenchIdentityReadMapper.class);return factory;
    }
    private static AssistantReadPolicy readPolicy(SqlSessionFactory factory,long owner) {
        try(SqlSession session=factory.openSession(false)) {
            try {
                var c=session.getConnection();c.setReadOnly(true);c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                if(!c.isReadOnly()||c.getAutoCommit())throw new IllegalStateException();
                return AssistantReadPolicy.load(session.getMapper(WorkbenchIdentityReadMapper.class),owner);
            } finally {session.rollback(true);}
        } catch(org.springframework.web.server.ResponseStatusException denied){throw new WorkbenchGatewayServer.Failure(403,"BUSINESS_PERMISSION_REQUIRED");}
        catch(Exception unavailable){throw new WorkbenchGatewayServer.Failure(503,"IDENTITY_CHECK_UNAVAILABLE");}
    }
    private static boolean activeRole(SqlSessionFactory factory,long owner,boolean intake){
        try(SqlSession session=factory.openSession(false)){
            try{
                Connection connection=session.getConnection();connection.setReadOnly(true);connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                if(!connection.isReadOnly()||connection.getAutoCommit())throw new IllegalStateException("Read-only identity transaction unavailable");
                var mapper=session.getMapper(WorkbenchIdentityReadMapper.class);
                return (intake?mapper.activeOrderIntakeUser(owner):mapper.activeAdmin(owner))==1;
            }finally{session.rollback(true);}
        }catch(Exception failure){throw new WorkbenchGatewayServer.Failure(503,"IDENTITY_CHECK_UNAVAILABLE");}
    }
}
