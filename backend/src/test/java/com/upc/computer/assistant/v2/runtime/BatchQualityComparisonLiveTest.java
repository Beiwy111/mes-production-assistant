package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

class BatchQualityComparisonLiveTest {
    @Test @EnabledIfEnvironmentVariable(named="MES_TRACE_LIVE",matches="1")
    void compareActualOriginalAndReinspectionUsingTheirOwnSampleDenominators() throws Exception {
        var directory=Path.of("").toAbsolutePath();
        var envFile=Path.of("backend/assistant/deploy/.env.agent-read.local");
        while(directory!=null&&!Files.isRegularFile(directory.resolve(envFile)))directory=directory.getParent();
        assertNotNull(directory);
        var conf=new HashMap<String,String>();
        for(String line:Files.readAllLines(directory.resolve(envFile))){
            String[] pair=line.split("=",2);if(pair.length==2)conf.put(pair[0],pair[1]);
        }
        try(var session=Stage5RuntimeMain.createFactory(conf).openSession(false)){
            session.getConnection().setReadOnly(true);
            session.getConnection().setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            var data=new NaturalLanguageRuntime.Data(session.getMapper(NaturalBusinessReadMapper.class),null);
            var windows=BusinessDateRange.comparison("2026-07-11 与 2026-07-12",LocalDate.now());
            var facts=new ObjectMapper().valueToTree(BatchQualityComparison.read(data,"BATCH-WO202607002-3201",windows));
            assertEquals(2,facts.at("/overview/rows").size());
            assertEquals(2,facts.path("batchComparison").size());
            var types=new HashSet<String>();
            for(var pair:facts.path("batchComparison")){
                String type=pair.get("inspection_type").asText();types.add(type);
                assertTrue(pair.get("difference_percentage_points").isNull());
                if(type.equals("FINAL"))assertTrue(pair.get("before").asText().contains("记录待核对"),
                    "Three header rejects and two registered units require reconciliation");
                if(type.equals("RECHECK"))assertTrue(pair.get("after").asText().contains("1/3（33.33%）"));
            }
            assertEquals(Set.of("FINAL","RECHECK"),types);
            var scope=new BusinessToolRegistry.Request("QUALITY",null,null,"2026-07-01 00:00:00","2026-08-01 00:00:00");
            var overview=BusinessToolRegistry.execute(data.overview(),scope,data.policy());
            var stats=new ObjectMapper().valueToTree(QualityOverviewStatistics.read(data,overview.rows(),overview.total()==overview.rows().size(),
                    scope,true,LocalDate.parse("2026-08-01")));
            assertEquals(overview.total(),stats.path("inspectionCount").asLong());
            assertTrue(stats.path("validRecordCount").asLong()>0);
            assertEquals(2,stats.path("periodComparisons").size());
            for(var group:stats.path("sampleGroups"))if(group.path("sample_fail_percent").isTextual()){
                var fail=new java.math.BigDecimal(group.path("valid_unqualified_quantity").asText());
                var total=new java.math.BigDecimal(group.path("valid_sample_quantity").asText());
                assertEquals(fail.movePointRight(2).divide(total,2,java.math.RoundingMode.HALF_UP).toPlainString(),group.path("sample_fail_percent").asText());
            }
            session.rollback(true);
        }
    }
}
