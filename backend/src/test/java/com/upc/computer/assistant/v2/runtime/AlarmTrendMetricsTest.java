package com.upc.computer.assistant.v2.runtime;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AlarmTrendMetricsTest {
    Map<String,Object> row(String id,String time,String equipment,String status){return Map.of("record_id",id,"event_at",time,"equipment_id",equipment,"equipment_name","点胶机","alarm_type","设备异常","alarm_level","HIGH","status",status);}
    @Test void dailyCountsUseShanghaiDaysAndSeparateEquipmentTypeLevelAndCurrentStatus() {
        var rows=List.of(row("1","2026-11-14T18:00:00Z","1","OPEN"),row("2","2026-11-15 03:00:00","1","OPEN"),row("3","2026-11-15 04:00:00","2","CLOSED"));
        var result=AlarmTrendMetrics.summarize(rows,true);assertEquals(2,result.size());
        assertEquals("2026-11-15",result.get(0).get("alarm_day"));assertEquals(2,result.get(0).get("alarm_record_count"));
        assertEquals("OPEN",result.get(0).get("status"));assertEquals("CLOSED",result.get(1).get("status"));
        assertTrue(result.get(0).get("alarm_count_basis").toString().contains("当前状态"));
    }
    @Test void duplicatesMissingDateAndPartialQueryCannotClaimCompleteCounts() {
        var duplicate=row("1","2026-11-15 03:00:00","1","OPEN");
        var result=AlarmTrendMetrics.summarize(List.of(duplicate,duplicate),true).get(0);
        assertNull(result.get("alarm_record_count"));assertEquals(2,result.get("duplicate_record_count"));
        assertNull(AlarmTrendMetrics.summarize(List.of(duplicate),false).get(0).get("alarm_record_count"));
        assertNull(AlarmTrendMetrics.summarize(List.of(row("2","unknown","1","OPEN")),true).get(0).get("alarm_record_count"));
        assertTrue(AlarmTrendMetrics.summarize(List.of(),true).isEmpty());
    }
}
