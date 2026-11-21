package com.upc.computer.assistant.v2.runtime;

import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchRuntimeMainTest {
    @Test void identityFactoryHasOnlyFixedSelectsAndChecksBothUserAndRoleStatus() {
        var factory=WorkbenchRuntimeMain.createIdentityFactory(Map.of("MES_AGENT_JDBC_URL","jdbc:mysql://127.0.0.1:1/not_connected",
                "MES_AGENT_JDBC_USER","readonly","MES_AGENT_JDBC_PASSWORD",""));
        var configuration=factory.getConfiguration();
        var statement=configuration.getMappedStatement(WorkbenchIdentityReadMapper.class.getName()+".activeAdmin");
        assertEquals(SqlCommandType.SELECT,statement.getSqlCommandType());
        String sql=statement.getBoundSql(Map.of("userId",7)).getSql().toLowerCase();
        assertTrue(sql.contains("u.status = 1"));assertTrue(sql.contains("r.status = 1"));assertTrue(sql.contains("r.role_code = 'admin'"));
        assertFalse(sql.contains("password"));assertFalse(sql.contains("7"));
        var intake=configuration.getMappedStatement(WorkbenchIdentityReadMapper.class.getName()+".activeOrderIntakeUser");
        assertEquals(SqlCommandType.SELECT,intake.getSqlCommandType());
        String intakeSql=intake.getBoundSql(Map.of("userId",7)).getSql().toLowerCase();
        assertTrue(intakeSql.contains("u.status = 1"));assertTrue(intakeSql.contains("r.status = 1"));
        assertTrue(intakeSql.contains("r.role_code in ('admin', 'order', 'customer')"));
    }
}
