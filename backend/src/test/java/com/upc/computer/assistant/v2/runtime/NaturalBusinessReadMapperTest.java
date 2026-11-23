package com.upc.computer.assistant.v2.runtime;

import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalBusinessReadMapperTest {
    @Test void materialExclusionIsAppliedToBothRowsAndCountUsingLiteralBoundParameters() {
        Configuration config=new Configuration();config.addMapper(NaturalBusinessReadMapper.class);
        for(String method:List.of("rowsScoped","countScoped","rowsAuthorized","countAuthorized")) {
            var statement=config.getMappedStatement(NaturalBusinessReadMapper.class.getName()+"."+method);
            var bound=statement.getBoundSql(Map.of("domain","INVENTORY","selectors",Map.of("excluded_material_keyword","PCB%_")));
            assertTrue(bound.getSql().contains("LOCATE(?,t.search_text)=0"));
            assertTrue(bound.getSql().contains("THEN 'PCB板'"));
            assertFalse(bound.getSql().contains("PCB%_"));
        }
    }
    @Test void eachDomainUsesFixedSelectAndBindsEveryUserFilterWithoutSilentlyTruncatingRows() {
        Configuration config=new Configuration();config.addMapper(NaturalBusinessReadMapper.class);
        for(String domain:List.of("INVENTORY","PRODUCTION","QUALITY","ORDER","EQUIPMENT","AFTERSALES","MAINTENANCE","ALARMS","STOCK_MOVEMENT"))for(String method:List.of("rows","count")) {
            var statement=config.getMappedStatement(NaturalBusinessReadMapper.class.getName()+"."+method);
            var params=new HashMap<String,Object>();params.put("domain",domain);params.put("id",987L);params.put("keyword","' OR 1=1 -- %_");params.put("start","2026-11-01 00:00:00");params.put("end","2026-11-10 00:00:00");
            String sql=statement.getBoundSql(params).getSql();
            assertEquals(SqlCommandType.SELECT,statement.getSqlCommandType());assertTrue(sql.startsWith("SELECT"));
            assertFalse(sql.contains("987"));assertFalse(sql.contains("OR 1=1"));assertFalse(sql.contains("2026"));assertTrue(sql.contains("LOCATE(?,t.search_text)"));
            if(method.equals("rows")){assertFalse(sql.contains("LIMIT"));assertTrue(sql.endsWith("ORDER BY t.record_id"));}else assertTrue(sql.contains("COUNT(*)"));
            assertFalse(sql.contains("SUM("));assertFalse(sql.contains("SELECT *"));
            if(domain.equals("MAINTENANCE"))assertTrue(sql.contains("equipment_maintenance_record"));
            if(domain.equals("ALARMS"))assertTrue(sql.contains("andon_alarm"));
            if(domain.equals("STOCK_MOVEMENT"))assertTrue(sql.contains("inventory_transaction"));
        }
    }
}
