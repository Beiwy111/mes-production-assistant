package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import java.time.Clock;
import java.util.*;

/** Separate Java entry point. No Spring startup and no automatic discovery of existing MES services. */
public final class Stage5RuntimeMain {
    private Stage5RuntimeMain() {}

    public static void main(String[] args) {
        try {
            Map<String,String> env=System.getenv();
            int port=18082; // Published Stage5 Dify nodes use this fixed loopback endpoint.
            Clock clock=Clock.systemUTC();
            var factory=createFactory(env);
            var knowledge=KnowledgeEvidenceClient.fromEnvironment(env,clock);
            var natural=new NaturalLanguageRuntime(clock,new ReadOnlyNaturalAccess(factory,clock),new NaturalLanguageRuntime.Research() {
                public Map<String,Object> retrieve(String role,List<Map<String,Object>> evidence) {return knowledge.retrieve(role,evidence);}
                public Map<String,Object> retrieveQuestion(String query) {return knowledge.retrieveQuestion(query);}
            },new ConversationScopeStore(java.nio.file.Path.of("backend/assistant/rag/data/conversation-context"),"runtime",clock,env.get("MES_AGENT_BOOTSTRAP_KEY")));
            var runtime=new Stage5RuntimeServer(env,clock,new ReadOnlyDeliveryAccess(factory,clock),natural,port);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close,"stage5-shutdown"));
            runtime.start();
            System.out.println("Stage5 LOCAL_READONLY_PREVIEW listening on 127.0.0.1:"+port);
        } catch (Exception failure) {
            // Never print a JDBC URL, credential, SQL error or raw exception cause.
            System.err.println("Stage5 runtime startup failed; verify private configuration and port availability.");
            System.exit(1);
        }
    }

    public static SqlSessionFactory createFactory(Map<String,String> env) {
        if (env.containsKey("MES_AGENT_PORT") && !"18082".equals(env.get("MES_AGENT_PORT")))
            throw new IllegalArgumentException("Stage5 published workflow requires port 18082");
        String url=required(env,"MES_AGENT_JDBC_URL");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MySQL JDBC configuration required");
        // URL properties override driver properties; disallow overriding the mandatory safety settings.
        String lower=url.toLowerCase(Locale.ROOT);
        if (lower.contains("readonlypropagatestoserver=") || lower.contains("allowmultiqueries="))
            throw new IllegalArgumentException("Reserved JDBC options must not be supplied in the URL");
        String password=env.get("MES_AGENT_JDBC_PASSWORD");
        if (password==null) throw new IllegalArgumentException("Missing private runtime configuration");
        var datasource=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,
                required(env,"MES_AGENT_JDBC_USER"),password);
        Properties properties=new Properties();
        properties.setProperty("readOnlyPropagatesToServer","true");
        properties.setProperty("allowMultiQueries","false");
        properties.setProperty("connectTimeout","5000");
        properties.setProperty("socketTimeout","20000");
        datasource.setDriverProperties(properties);
        Configuration configuration=new Configuration(new Environment("stage5-local-readonly-preview",new JdbcTransactionFactory(),datasource));
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setDefaultStatementTimeout(15);
        configuration.addMapper(AiDeliveryReadMapper.class);
        configuration.addMapper(AiBusinessReadMapper.class);
        configuration.addMapper(NaturalBusinessReadMapper.class);
        configuration.addMapper(WorkbenchIdentityReadMapper.class);
        for (String name:configuration.getMappedStatementNames()) {
            if (name.contains(".") && configuration.getMappedStatement(name).getSqlCommandType()!=SqlCommandType.SELECT)
                throw new IllegalStateException("Only fixed SELECT statements are permitted");
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static String required(Map<String,String> env,String name) {
        String value=env.get(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing private runtime configuration");
        return value;
    }
}
