package com.upc.computer.assistant.v2.runtime;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MaintenanceMetricsTest {
    @Test void overlappingRecordedIntervalsAreMergedPerEquipmentWithoutInventingMissingEnds() {
        var rows=List.<Map<String,Object>>of(
            Map.of("equipment_id",1,"start_time","2026-11-01 08:00:00","end_time","2026-11-01 10:00:00","downtime_minutes",120),
            Map.of("equipment_id",1,"start_time","2026-11-01 09:00:00","end_time","2026-11-01 11:00:00","downtime_minutes",120),
            Map.of("equipment_id",1,"start_time","2026-11-01 12:00:00"),
            Map.of("equipment_id",2,"start_time","2026-11-01 09:00:00","end_time","2026-11-01 10:00:00"));
        var result=MaintenanceMetrics.summarize(rows);
        assertEquals("240",result.get(0).get("recorded_downtime_minutes"));
        assertEquals("180",result.get(0).get("interval_union_minutes"));
        assertEquals(2,result.get(0).get("valid_interval_count"));
        assertEquals(1,result.get(0).get("missing_interval_count"));
        assertEquals("60",result.get(1).get("interval_union_minutes"));
        assertTrue(result.get(0).get("interval_basis").toString().contains("不代表"));
    }
    @Test void invalidDatesReverseIntervalsAndUnknownEquipmentRemainUnknown() {
        var rows=List.<Map<String,Object>>of(
            Map.of("equipment_id",1,"start_time","2026-11-01 10:00:00","end_time","2026-11-01 08:00:00"),
            Map.of("equipment_id",1,"start_time","not-a-date","end_time","2026-11-01 08:00:00"),
            Map.of("start_time","2026-11-01 08:00:00","end_time","2026-11-01 10:00:00"));
        var result=MaintenanceMetrics.summarize(rows);
        assertNull(result.get(0).get("interval_union_minutes"));
        assertEquals(2,result.get(0).get("missing_interval_count"));
        assertNull(result.get(1).get("interval_union_minutes"));
    }
    @Test void unknownAndNegativeDurationsAreNotZerosAndEquipmentIsNeverCombined() {
        var rows=List.<Map<String,Object>>of(Map.of("equipment_id",1,"downtime_minutes","12.5"),Map.of("equipment_id",1,"downtime_minutes","7.25"),Map.of("equipment_id",1,"downtime_minutes","-1"),Map.of("equipment_id",2));
        var result=MaintenanceMetrics.summarize(rows);
        assertEquals(2,result.size());assertEquals("19.75",result.get(0).get("recorded_downtime_minutes"));
        assertEquals(1,result.get(0).get("missing_duration_count"));assertNull(result.get(1).get("recorded_downtime_minutes"));
        assertEquals(0,result.get(1).get("known_duration_count"));assertTrue(result.get(0).get("duration_basis").toString().contains("未合并"));
    }
}
