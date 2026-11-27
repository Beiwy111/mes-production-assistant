package com.upc.computer.assistant.v2.runtime;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityOverviewStatisticsTest {
    private static Map<String,Object> sample(int id,String batch,int total,int rejects) {
        var row=new HashMap<String,Object>();
        row.put("record_id",id);row.put("material_id",7);row.put("material_name","面板");
        row.put("event_at","2026-11-12 14:00:00");row.put("batch_no",batch);row.put("unit","片");
        row.put("inspection_category","OQC");row.put("inspection_type","FINAL");
        row.put("inspection_status",rejects>0?"FAILED":"PASSED");row.put("status",rejects>0?"UNQUALIFIED":"QUALIFIED");
        row.put("sample_quantity",total);row.put("qualified_quantity",total-rejects);row.put("unqualified_quantity",rejects);
        return row;
    }

    @Test void aggregateUsesWeightedValidSamplesAndSeparatesDispositionFromSampleCounts(){
        var pending=sample(3,"B",5,0);pending.put("inspection_status","PENDING");pending.put("status","PENDING");
        var conflict=sample(4,"B",8,2);conflict.put("detail_count_conflict",1);
        var concession=sample(5,"B",7,2);concession.put("status","CONCESSION");
        var acceptance=sample(6,"B",10,3);acceptance.put("inspection_status","PASSED");acceptance.put("status","QUALIFIED");
        var repeated=sample(7,"A",4,1);repeated.put("inspection_type","RECHECK");
        var evidence=QualityOverviewStatistics.calculate(List.of(sample(1,"A",10,3),sample(2,"B",90,0),
            pending,conflict,concession,acceptance,repeated),true);
        assertEquals(3,evidence.get("validRecordCount"));
        for(String count:List.of("pendingCount","conflictCount","concessionCount","acceptanceReviewCount","recheckRecordCount"))
            assertEquals(1,evidence.get(count),count);
        var groups=rows(evidence,"sampleGroups");
        assertEquals(2,groups.size());
        var first=groups.stream().filter(g->"FINAL".equals(g.get("inspection_type"))).findFirst().orElseThrow();
        assertEquals("3.00",first.get("sample_fail_percent"));assertEquals("97.00",first.get("sample_pass_percent"));
        assertEquals("100",first.get("valid_sample_quantity"));assertEquals(2,first.get("valid_sample_record_count"));
        assertEquals(3,rows(evidence,"batchGroups").size());
        assertNull(rows(evidence,"batchGroups").stream().filter(b->"B".equals(b.get("batch_no"))).findFirst().orElseThrow().get("sample_fail_percent"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> data,String field){return (List<Map<String,Object>>)data.get(field);}

    @Test void zeroIncompleteOrDuplicateRowsNeverBecomePercentages(){
        var row=sample(1,"A",10,1);
        var stats=QualityOverviewStatistics.calculate(List.of(row,row,sample(2,"A",0,0)),true);
        assertEquals(0,stats.get("validRecordCount"));assertNull(rows(stats,"sampleGroups").get(0).get("sample_fail_percent"));
        stats=QualityOverviewStatistics.calculate(List.of(row),false);
        assertNull(rows(stats,"batchGroups").get(0).get("sample_fail_percent"));
    }

    @Test void windowsUseShanghaiCalendarAndDoNotClaimUnequalLeapCoverage(){
        var windows=QualityOverviewStatistics.windows(LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-25"));
        assertEquals(LocalDate.parse("2026-10-01"),windows.get(0).start());
        assertEquals(LocalDate.parse("2026-10-25"),windows.get(0).end());
        assertEquals("MOM",windows.get(0).basis());assertEquals("YOY",windows.get(1).basis());
        assertTrue(QualityOverviewStatistics.windows(LocalDate.parse("2024-02-29"),LocalDate.parse("2024-03-01"))
                .stream().noneMatch(w->"YOY".equals(w.basis())));
    }

    @Test void overviewBindsBaselineScopeAndCountsOnlyAttributedDefectRegistrations(){
        var mapper=mock(NaturalBusinessReadMapper.class);var db=new NaturalLanguageRuntime.Data(mapper,null);
        var scope=new BusinessToolRegistry.Request("QUALITY",null,null,"2026-11-01 00:00:00","2026-11-25 00:00:00",Map.of("batch_no","BATCH-A"));
        var prior=sample(9,"BATCH-A",20,2);prior.put("event_at","2026-10-18 14:00:00");
        when(mapper.qualityPeriodRows(null,null,"2026-10-01 00:00:00","2026-10-25 00:00:00",scope.selectors(),null,null)).thenReturn(List.of(prior));
        when(mapper.qualityDefectCounts(List.of(1L),null,null)).thenReturn(List.of(Map.of("defect_type","划伤","record_count",2),Map.of("record_count",1)));
        var stats=QualityOverviewStatistics.read(db,List.of(sample(1,"BATCH-A",100,4)),true,scope,true,LocalDate.parse("2026-11-24"));
        assertEquals(List.of(Map.of("defect_type","划伤","record_count",2L)),stats.get("defectDistribution"));
        assertEquals(1L,stats.get("excludedDefectRegistrationCount"));
        var comparisons=rows(stats,"periodComparisons");
        assertEquals("-6.00",rows(comparisons.get(0),"groups").get(0).get("difference_percentage_points"));
        assertNull(rows(comparisons.get(1),"groups").get(0).get("difference_percentage_points"));
        clearInvocations(mapper);
        stats=QualityOverviewStatistics.read(db,List.of(sample(1,"BATCH-A",10,1)),true,scope,false,LocalDate.parse("2026-11-24"));
        assertTrue(rows(stats,"periodComparisons").isEmpty());assertNull(rows(stats,"sampleGroups").get(0).get("sample_fail_percent"));
        verify(mapper,never()).qualityPeriodRows(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void fallbackExplainsComparableBatchesWithDenominatorsAndLeavesDirectListsIntact(){
        assertTrue(QualityOverviewAnswer.analytical("最近各批次质量有哪些差异？"));assertFalse(QualityOverviewAnswer.analytical("列出质检明细"));
        var db=new NaturalLanguageRuntime.Data(mock(NaturalBusinessReadMapper.class),null);
        var stats=QualityOverviewStatistics.read(db,List.of(sample(1,"A",20,1),sample(2,"B",10,3)),true,null,true,LocalDate.parse("2026-11-24"));
        var text=QualityOverviewAnswer.describe(Map.of("totalRecords",2),stats,List.of(),"批次质量差异");
        assertTrue(text.contains("3/10片，30.00%"));assertTrue(text.contains("1/20片，5.00%"));assertTrue(text.contains("25.00 个百分点"));
        var repeat=sample(3,"C",5,4);repeat.put("inspection_type","RECHECK");
        var mixed=QualityOverviewStatistics.calculate(List.of(sample(1,"A",20,1),repeat),true);
        assertTrue(rows(mixed,"batchDifferences").isEmpty());
    }
}
