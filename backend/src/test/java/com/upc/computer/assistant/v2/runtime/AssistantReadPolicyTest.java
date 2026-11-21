package com.upc.computer.assistant.v2.runtime;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantReadPolicyTest {
    @Test void boundSqlKeepsCustomerAndOperatorScopesIndependentOfUserSelectors() {
        var config=new org.apache.ibatis.session.Configuration();config.addMapper(NaturalBusinessReadMapper.class);
        var args=new HashMap<String,Object>();args.put("domain","ORDER");args.put("customer","甲公司");args.put("selectors",Map.of("customer_name","乙公司"));
        for(String method:List.of("rowsAuthorized","countAuthorized")) {
            var sql=config.getMappedStatement(NaturalBusinessReadMapper.class.getName()+"."+method).getBoundSql(args);
            assertEquals(2,sql.getParameterMappings().size());assertTrue(sql.getSql().contains("t.customer_name=?"));assertFalse(sql.getSql().contains("甲公司"));
        }
        args.put("domain","PRODUCTION");args.put("customer",null);args.put("operator",9L);args.put("selectors",Map.of());
        assertTrue(config.getMappedStatement(NaturalBusinessReadMapper.class.getName()+".rowsAuthorized").getBoundSql(args).getSql().contains("dispatch_task WHERE operator_id=?"));
        args.put("domain","QUALITY");args.put("customer","甲公司");
        assertTrue(config.getMappedStatement(NaturalBusinessReadMapper.class.getName()+".rowsAuthorized").getBoundSql(args).getSql().contains("1=0"));
    }
    @Test void revocationInvalidatesAlreadyCreatedRunBeforeReturningContext() {
        var policy=new java.util.concurrent.atomic.AtomicReference<>(AssistantReadPolicy.of(2,"QC",null,List.of("quality:records")));
        var runtime=new NaturalLanguageRuntime(java.time.Clock.systemUTC(),(owner,op)->op.apply(new NaturalLanguageRuntime.Data(mock(NaturalBusinessReadMapper.class),null,policy.get())));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        String ref=(String)runtime.create(json.valueToTree(Map.of("query","查质检","ownerUserId","2"))).get("runRef");
        policy.set(AssistantReadPolicy.of(2,"QC",null,List.of()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->runtime.context(ref));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->runtime.observe(ref));
    }
    @Test void menuGrantsAreDomainSpecificAndNoParentWildcardExists() {
        var p=AssistantReadPolicy.of(2,"QC",null,List.of("quality:records"));
        assertTrue(p.allows("QUALITY"));assertFalse(p.allows("ORDER"));assertFalse(p.allows("INVENTORY"));
        assertFalse(AssistantReadPolicy.of(2,"QC",null,List.of("quality")).allows("QUALITY"));
    }
    @Test void customerCannotEscapeOwnEnterpriseEvenWithInternalMenuGrants() {
        var p=AssistantReadPolicy.of(3,"CUSTOMER","甲公司",List.of("customer:orders","customer:feedbackList","warehouse:inventory"));
        assertEquals("甲公司",p.customer());assertTrue(p.allows("ORDER"));assertFalse(p.allows("INVENTORY"));
        assertThrows(RuntimeException.class,()->AssistantReadPolicy.of(3,"CUSTOMER"," ",List.of("customer:orders")));
        assertNotEquals(p.fingerprint(),AssistantReadPolicy.of(3,"CUSTOMER","乙公司",List.of("customer:orders","customer:feedbackList")).fingerprint());
    }
    @Test void operatorGetsOnlyOwnProductionAndAlarmScope() {
        var p=AssistantReadPolicy.of(4,"OPERATOR",null,List.of("production:workOrder","device:alarm","order:list"));
        assertEquals(4,p.operator());assertTrue(p.allows("PRODUCTION"));assertFalse(p.allows("ORDER"));
    }
    @Test void unauthorizedQueryNeverCallsMapperAndCustomerAlwaysUsesSqlScope() {
        var mapper=mock(NaturalBusinessReadMapper.class);
        var p=AssistantReadPolicy.of(3,"CUSTOMER","甲公司",List.of("customer:orders"));
        assertThrows(RuntimeException.class,()->BusinessToolRegistry.execute(mapper,new BusinessToolRegistry.Request("INVENTORY",null,null,null,null),p));
        verifyNoInteractions(mapper);
        when(mapper.rowsAuthorized(eq("ORDER"),isNull(),isNull(),isNull(),isNull(),anyMap(),eq("甲公司"),isNull())).thenReturn(List.of());
        BusinessToolRegistry.execute(mapper,new BusinessToolRegistry.Request("ORDER",null,null,null,null,Map.of("customer_name","乙公司")),p);
        verify(mapper).rowsAuthorized("ORDER",null,null,null,null,Map.of("customer_name","乙公司"),"甲公司",null);
        verify(mapper,never()).rows(any(),any(),any(),any(),any());
    }
}
