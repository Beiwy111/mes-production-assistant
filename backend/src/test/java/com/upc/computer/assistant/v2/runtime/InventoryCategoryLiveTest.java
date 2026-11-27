package com.upc.computer.assistant.v2.runtime;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

class InventoryCategoryLiveTest {
    @Test @EnabledIfEnvironmentVariable(named="MES_TRACE_LIVE",matches="1")
    void inventoryCategoriesMatchIndependentMaterialMasterCountsAndSelectedRows() throws Exception {
        Path config=Path.of("assistant/deploy/.env.agent-read.local");
        var env=new HashMap<String,String>();
        for(String line:Files.readAllLines(config)){
            var parts=line.split("=",2);if(parts.length==2)env.put(parts[0],parts[1]);
        }
        try(var session=Stage5RuntimeMain.createFactory(env).openSession(false)){
            var conn=session.getConnection();conn.setReadOnly(true);
            conn.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            var counts=new HashMap<String,Long>();
            try(var statement=conn.prepareStatement("SELECT m.material_type,COUNT(*) FROM inventory v LEFT JOIN material m ON m.material_id=v.material_id GROUP BY m.material_type");
                var rows=statement.executeQuery()){
                while(rows.next())counts.put(rows.getString(1),rows.getLong(2));
            }
            var mapper=session.getMapper(NaturalBusinessReadMapper.class);
            var all=BusinessToolRegistry.execute(mapper,new BusinessToolRegistry.Request("INVENTORY",null,null,null,null));
            assertEquals(counts.values().stream().mapToLong(Long::longValue).sum(),all.total());
            var allIds=all.rows().stream().map(r->((Number)r.get("record_id")).longValue()).toList();
            for(var category:InventoryCategory.values()){
                var selectors=Map.of("material_types",category.types);
                var expectedTypes=Set.of(category.types.split(","));
                long expectedCount=expectedTypes.stream().mapToLong(t->counts.getOrDefault(t,0L)).sum();
                var result=BusinessToolRegistry.execute(mapper,new BusinessToolRegistry.Request("INVENTORY",null,null,null,null,selectors));
                assertEquals(expectedCount,result.total(),category.name());
                assertEquals(expectedCount,result.rows().size());
                assertTrue(result.rows().stream().allMatch(r->expectedTypes.contains(r.get("material_type"))));
                assertEquals(expectedCount,mapper.countAuthorized("INVENTORY",null,null,null,null,selectors,null,null));
                if(!allIds.isEmpty())assertEquals(result.rows(),mapper.selectedRows("INVENTORY",allIds,null,null,selectors,null,null,null));
            }
            session.rollback(true);
        }
    }
}
