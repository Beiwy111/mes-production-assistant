package com.upc.computer.assistant.v2.runtime;

import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Stage5RuntimeMainTest {
    @Test void rejectsPortOverridesThatWouldDisagreeWithPublishedWorkflowEndpoints() {
        var env=Map.of("MES_AGENT_JDBC_URL","jdbc:mysql://127.0.0.1:1/not_connected",
                "MES_AGENT_JDBC_USER","preview","MES_AGENT_JDBC_PASSWORD","test-only-password",
                "MES_AGENT_PORT","18083");
        assertThrows(IllegalArgumentException.class,()->Stage5RuntimeMain.createFactory(env));
    }
    @Test void explicitEmptyPasswordIsPreservedButMissingOrNullPasswordIsRejected() {
        Map<String,String> env=new HashMap<>(Map.of("MES_AGENT_JDBC_URL","jdbc:mysql://127.0.0.1:1/not_connected",
                "MES_AGENT_JDBC_USER","preview", "MES_AGENT_JDBC_PASSWORD",""));
        var factory=assertDoesNotThrow(()->Stage5RuntimeMain.createFactory(env));
        var datasource=(UnpooledDataSource)factory.getConfiguration().getEnvironment().getDataSource();
        assertEquals("",datasource.getPassword());
        env.remove("MES_AGENT_JDBC_PASSWORD");
        assertThrows(IllegalArgumentException.class,()->Stage5RuntimeMain.createFactory(env));
        env.put("MES_AGENT_JDBC_PASSWORD",null);
        assertThrows(IllegalArgumentException.class,()->Stage5RuntimeMain.createFactory(env));
    }

    @Test void isolatedFactoryRegistersOnlyFixedDeliverySelectsWithoutOpeningDatabase() throws Exception {
        var factory=Stage5RuntimeMain.createFactory(
                Map.of("MES_AGENT_JDBC_URL","jdbc:mysql://127.0.0.1:1/not_connected", "MES_AGENT_JDBC_USER","preview",
                        "MES_AGENT_JDBC_PASSWORD","test-only-password"));
        var config=factory.getConfiguration();
        assertEquals(4,config.getMapperRegistry().getMappers().size());
        var names=config.getMappedStatementNames().stream().filter(name->name.contains(".")).toList();
        assertTrue(names.size()>28);
        for(String name:names) assertEquals(SqlCommandType.SELECT,config.getMappedStatement(name).getSqlCommandType());
        assertFalse(config.isCacheEnabled());
        assertEquals(15,config.getDefaultStatementTimeout());
    }
}
