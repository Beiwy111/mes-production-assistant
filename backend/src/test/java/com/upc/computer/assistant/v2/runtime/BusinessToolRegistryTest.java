package com.upc.computer.assistant.v2.runtime;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessToolRegistryTest {
    @Test void customerSetIsBoundIdenticallyInCountAndRows() {
        String names="[\"O'Reilly%_\",\"甲,乙\"]";
        BusinessSelectors.validate("ORDER",Map.of("customer_names",names));
        var config=new org.apache.ibatis.session.Configuration();config.addMapper(NaturalBusinessReadMapper.class);
        var args=new HashMap<String,Object>();args.put("domain","ORDER");args.put("selectors",Map.of("customer_names",names));
        for(String method:List.of("rowsScoped","countScoped")) {
            var bound=config.getMappedStatement(NaturalBusinessReadMapper.class.getName()+"."+method).getBoundSql(args);
            assertTrue(bound.getSql().contains("t.customer_name IN"));assertFalse(bound.getSql().contains("O'Reilly"));
            assertEquals(2,bound.getParameterMappings().size());
            assertEquals("O'Reilly%_",bound.getAdditionalParameter(bound.getParameterMappings().get(0).getProperty()));
            assertEquals("甲,乙",bound.getAdditionalParameter(bound.getParameterMappings().get(1).getProperty()));
        }
        for(String invalid:List.of("[]","[1,2]","[\"a\",\"a\"]","[\"a\",\"b\"] []"))assertThrows(IllegalArgumentException.class,()->BusinessSelectors.customerNames(invalid));
    }
    @Test void statusUnionAndExclusionRetainUnknownAsUnknown() {
        var rows=List.<Map<String,Object>>of(Map.of("status","APPROVED"),Map.of("status","PENDING"),Map.of("status","SHIPPED"),Map.of(),Map.of("status","INVENTED"));
        assertEquals(rows.subList(0,2),BusinessQueryFilter.apply("ORDER","查询已审核和待审核订单",rows));
        assertEquals(rows.subList(1,3),BusinessQueryFilter.apply("ORDER","排除已审核的订单",rows));
        assertFalse(BusinessQueryFilter.ambiguous("ORDER","查询待审核、已审核订单"));
        assertTrue(BusinessQueryFilter.ambiguous("ORDER","查询已审核和已取消订单"));
        assertTrue(BusinessQueryFilter.ambiguous("ORDER","查询已审核之外的订单"));
        assertTrue(BusinessQueryFilter.ambiguous("ORDER","查询非已审核订单"));
        for(String word:List.of("不包括","不看","剔除"))
            assertEquals(rows.subList(1,3),BusinessQueryFilter.apply("ORDER","查询"+word+"已审核的订单",rows));
    }
    @Test void emptyStatusSetCannotAccidentallySelectUnknownBlankStatus() {
        assertTrue(BusinessQueryFilter.applyCriterion("SET:",List.of(Map.of("status",""))).isEmpty());
    }
    @Test void codeListsUseBoundSqlForCountAndRows() {
        var config=new org.apache.ibatis.session.Configuration();config.addMapper(NaturalBusinessReadMapper.class);
        var args=new HashMap<String,Object>();args.put("domain","INVENTORY");
        args.put("selectors",Map.of("warehouse_codes","WH-01,WH-02","excluded_location_codes","A-01,A-02"));
        for(String method:List.of("rowsScoped","countScoped")) {
            String sql=config.getMappedStatement(NaturalBusinessReadMapper.class.getName()+"."+method).getBoundSql(args).getSql();
            assertTrue(sql.contains("t.warehouse_code IN"));assertTrue(sql.contains("t.location_code NOT IN"));
            assertFalse(sql.contains("WH-01"));assertFalse(sql.contains("LIMIT"));
        }
    }
    @Test void approvedAndCompletedQueriesUseRecordedDomainStatuses() {
        var rows=List.<Map<String,Object>>of(Map.of("status","APPROVED"),Map.of("status","PENDING"),Map.of("status","COMPLETED"));
        assertEquals(List.of(rows.get(0)),BusinessQueryFilter.apply("ORDER","查询已审核订单",rows));
        assertEquals(List.of(rows.get(2)),BusinessQueryFilter.apply("PRODUCTION","查询已完成工单",rows));
    }
    @Test void exactSelectorsAreBoundParametersAndIllegalDomainsNeverReachSql() {
        var mapper=mock(NaturalBusinessReadMapper.class);
        var filters=Map.of("warehouse_code","WH-01","location_code","A-01-01");
        when(mapper.countScoped("INVENTORY",null,"PCB",null,null,filters)).thenReturn(1L);
        when(mapper.rowsScoped("INVENTORY",null,"PCB",null,null,filters)).thenReturn(List.of(Map.of("record_id",1)));
        assertEquals(1,BusinessToolRegistry.execute(mapper,new BusinessToolRegistry.Request("INVENTORY",null,"PCB",null,null,filters)).rows().size());
        verify(mapper).countScoped("INVENTORY",null,"PCB",null,null,filters);
        assertThrows(IllegalArgumentException.class,()->BusinessToolRegistry.execute(mapper,new BusinessToolRegistry.Request("QUALITY",null,null,null,null,filters)));
        assertThrows(IllegalArgumentException.class,()->BusinessToolRegistry.execute(mapper,new BusinessToolRegistry.Request("ORDER",null,null,null,null,Map.of("sql","1=1"))));
        var config=new org.apache.ibatis.session.Configuration();config.addMapper(NaturalBusinessReadMapper.class);
        var args=new HashMap<String,Object>();args.put("domain","ORDER");args.put("selectors",Map.of("customer_name","O'Reilly%_"));
        String sql=config.getMappedStatement(NaturalBusinessReadMapper.class.getName()+".rowsScoped").getBoundSql(args).getSql();
        assertTrue(sql.contains("t.customer_name=?"));assertFalse(sql.contains("O'Reilly"));assertFalse(sql.toUpperCase().contains("LIMIT"));
    }
    @Test void rejectsUnknownDomainBeforeAnySqlAndDeclaresOnlyReadTools() {
        var mapper=mock(NaturalBusinessReadMapper.class);
        assertThrows(IllegalArgumentException.class,()->BusinessToolRegistry.execute(mapper,
            new BusinessToolRegistry.Request("DROP TABLE",null,null,null,null)));
        verifyNoInteractions(mapper);
        assertEquals(9,BusinessToolRegistry.catalog().size());
        assertTrue(BusinessToolRegistry.catalog().stream().allMatch(t->t.readOrWrite().equals("READ")&&t.requiredPermissions().equals(Set.of("READ_"+t.toolName().split("\\.")[1].toUpperCase(Locale.ROOT)))));
    }
    @Test void preservesAllRowsAndReportsCountMismatch() {
        var mapper=mock(NaturalBusinessReadMapper.class);
        when(mapper.count("INVENTORY",null,null,null,null)).thenReturn(200L);
        when(mapper.rows("INVENTORY",null,null,null,null)).thenReturn(List.of(Map.of("quantity_available","0")));
        var result=BusinessToolRegistry.execute(mapper,new BusinessToolRegistry.Request("INVENTORY",null,null,null,null));
        assertEquals(200,result.total());assertEquals(1,result.rows().size());
    }
    @Test void filtersUseExactRecordedStatusesAndNeverUnknownAsZero() {
        var inventory=List.<Map<String,Object>>of(Map.of("quantity_available","0"),Map.of("quantity_available","-2"),Map.of("quantity_available","4"),Map.of());
        assertEquals(2,BusinessQueryFilter.apply("INVENTORY","其中哪些库存不足",inventory).size());
        assertEquals(1,BusinessQueryFilter.apply("INVENTORY","查询可用库存",inventory).size());
        var quality=List.<Map<String,Object>>of(Map.of("status","UNQUALIFIED"),Map.of("status","QUALIFIED"),Map.of("status","PENDING"));
        assertEquals(List.of(quality.get(0)),BusinessQueryFilter.apply("QUALITY","只看不合格记录",quality));
        assertEquals(quality,BusinessQueryFilter.apply("QUALITY","分析质量原因",quality));
        var work=List.<Map<String,Object>>of(Map.of("status","DRAFT"),Map.of("status","QC_PENDING"),Map.of("status","COMPLETED"));
        assertEquals(work.subList(0,2),BusinessQueryFilter.apply("PRODUCTION","未完成工单",work));
    }
}
