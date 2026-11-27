package com.upc.computer.assistant.v2.runtime;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QualityTrendMetricsTest {
    private Map<String,Object> row(String id,String material,String sample,String good,String bad,String time) {
        var data=new HashMap<String,Object>(Map.of("record_id",id,"material_id",material,"material_name","显示器","unit","台",
                "sample_quantity",sample,"qualified_quantity",good,"unqualified_quantity",bad,"event_at",time));
        boolean passed="0".equals(bad);data.put("status",passed?"QUALIFIED":"UNQUALIFIED");data.put("inspection_status",passed?"PASSED":"FAILED");
        data.put("inspection_category","OQC");data.put("inspection_type","FINAL");return data;
    }
    @Test void trendWeightsSamplesAndSeparatesDaysProductsAndUnits() {
        var result=QualityTrendMetrics.summarize(List.of(row("1","1","10","8","2","2026-11-01 10:00:00"),row("2","1","90","90","0","2026-11-01 11:00:00"),row("3","2","10","5","5","2026-11-01 12:00:00"),row("4","1","10","9","1","2026-11-02 10:00:00")),true);
        assertEquals(3,result.size());var first=result.get(0);
        assertEquals("98.00",first.get("sample_pass_percent"));assertEquals("100",first.get("valid_sample_quantity"));
        assertEquals("2026-11-01",first.get("inspection_day"));assertEquals(2,first.get("inspection_record_count"));
    }
    @Test void invalidMissingAndPendingDataNeverBecomeZeroOrCompleteRates() {
        var pending=new HashMap<>(row("2","1","10","10","0","2026-11-01 12:00:00"));pending.put("status","PENDING");
        var result=QualityTrendMetrics.summarize(List.of(row("1","1","10","11","0","2026-11-01 10:00:00"),pending),true).get(0);
        assertNull(result.get("sample_pass_percent"));assertNull(result.get("valid_sample_quantity"));assertEquals(2,result.get("invalid_sample_record_count"));
        assertNull(QualityTrendMetrics.summarize(List.of(row("3","1","0","0","0","2026-11-01")),true).get(0).get("sample_pass_percent"));
        assertNull(QualityTrendMetrics.summarize(List.of(row("3","1","10","10","0","2026-11-01")),false).get(0).get("sample_pass_percent"));
    }
    @Test void reinspectionAndOriginalSamplesDoNotShareARateDenominator(){
        var initial=new HashMap<>(row("8","1","12","9","3","2026-11-01"));
        var repeated=new HashMap<>(row("9","1","4","4","0","2026-11-01"));
        initial.put("inspection_type","FINAL");repeated.put("inspection_type","RECHECK");
        var rates=QualityTrendMetrics.summarize(List.of(initial,repeated),true);
        assertEquals(2,rates.size());
        assertEquals("25.00",rates.get(0).get("sample_fail_percent"));
        assertEquals("4",rates.get(1).get("valid_sample_quantity"));
    }
    @Test void duplicateAndUnknownDateRecordsAreVisibleButNotInventedMeasurements() {
        var row=row("1","1","10","9","1","not-a-date");
        var result=QualityTrendMetrics.summarize(List.of(row,row),true).get(0);
        assertEquals("未提供有效检验日期",result.get("inspection_day"));assertNull(result.get("sample_pass_percent"));
        assertEquals(2,result.get("duplicate_record_count"));
    }
    @Test void utcTimestampsUseShanghaiDayAndDifferentUnitsNeverMerge() {
        var a=row("1","1","10","9","1","2026-11-01T20:00:00Z");
        var b=new HashMap<>(row("2","1","20","18","2","2026-11-02 04:00:00"));b.put("unit","套");
        var result=QualityTrendMetrics.summarize(List.of(a,b),true);
        assertEquals(2,result.size());assertTrue(result.stream().allMatch(r->r.get("inspection_day").equals("2026-11-02")));
    }
}
