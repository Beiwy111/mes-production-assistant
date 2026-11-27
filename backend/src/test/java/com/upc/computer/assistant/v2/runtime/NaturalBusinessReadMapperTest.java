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
            // Reconcile each header against its own defect quantities. Cross-inspection sums remain forbidden here.
            String permitted="SELECT SUM(n.quantity) FROM nonconforming_product n WHERE n.inspection_id=q.inspection_id";
            if(domain.equals("QUALITY"))assertTrue(sql.contains(permitted));
            assertFalse(sql.replace(permitted,"").contains("SUM("));assertFalse(sql.contains("SELECT *"));
            if(domain.equals("MAINTENANCE"))assertTrue(sql.contains("equipment_maintenance_record"));
            if(domain.equals("ALARMS"))assertTrue(sql.contains("andon_alarm"));
            if(domain.equals("STOCK_MOVEMENT"))assertTrue(sql.contains("inventory_transaction"));
        }
    }
    @Test void qualityBaselinesAndDefectTotalsUseBoundAuthorizedScopes(){
        Configuration config=new Configuration();config.addMapper(NaturalBusinessReadMapper.class);
        String prefix=NaturalBusinessReadMapper.class.getName()+".";
        var prior=config.getMappedStatement(prefix+"qualityPeriodRows").getBoundSql(Map.of("selectors",Map.of("batch_no","BATCH-123"),
                "start","2026-10-01 00:00:00","end","2026-11-01 00:00:00","customer","test")).getSql().replaceAll("\\s+"," ").trim();
        assertTrue(prior.contains("AND t.batch_no=?"));assertTrue(prior.contains("AND 1=0"));assertTrue(prior.endsWith("LIMIT 2001"));
        assertFalse(prior.contains("BATCH-123"));assertTrue(prior.contains("t.event_at >= ? AND t.event_at < ?"));
        var details=config.getMappedStatement(prefix+"qualityDefectCounts").getBoundSql(Map.of("ids",List.of(71L,72L))).getSql();
        assertTrue(details.contains("n.inspection_id=q.inspection_id"));assertTrue(details.contains("n.material_id=q.material_id"));
        assertTrue(details.contains("COUNT(*) AS record_count"));assertTrue(details.contains("? IS NULL AND ? IS NULL"));
        assertFalse(details.contains("71"));assertFalse(details.contains("72"));
    }
}
