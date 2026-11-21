package com.upc.computer.assistant.v2.runtime;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BusinessDateRangeTest {
    final LocalDate today=LocalDate.of(2026,11,15);
    @Test void chineseSevenDaysIsAnExplicitNewRange() {
        for(String query:new String[]{"其中近七天的PCB库存","其中过去七天的PCB库存","最近七天库存"}) {
            var range=BusinessDateRange.parse(query,today);
            assertNotNull(range,query);assertEquals(LocalDate.of(2026,11,9),range.start());
        }
    }
    @Test void inclusiveUserEndBecomesExclusiveSqlBoundary() {
        var range=BusinessDateRange.parse("查询2026-11-01到2026-11-14未完成工单",today);
        assertEquals("2026-11-01 00:00:00",range.fromTimestamp());
        assertEquals("2026-11-15 00:00:00",range.toTimestamp());
        assertEquals("查询未完成工单",range.remainingText());
    }
    @Test void relativeRangesUseShanghaiBusinessDateAndCalendarBoundaries() {
        assertEquals(LocalDate.of(2026,11,14),BusinessDateRange.parse("昨天报警",today).start());
        assertEquals(LocalDate.of(2026,11,2),BusinessDateRange.parse("上周维修",today).start());
        assertEquals(LocalDate.of(2026,10,1),BusinessDateRange.parse("上月出入库",today).start());
        assertEquals(LocalDate.of(2026,10,17),BusinessDateRange.parse("最近30天质量",today).start());
        assertNull(BusinessDateRange.parse("查库存",today));
    }
    @Test void ambiguousInvalidAndReversedDatesNeverBroadenQuery() {
        for(String query:new String[]{"2026-02-30","2026-11-15到2026-11-01","昨天上月","最近0天","最近1000天","2026-11-01和2026-11-02","截至2026-11-01","今天2026-11-01"})
            assertThrows(RuntimeException.class,()->BusinessDateRange.parse(query,today),query);
    }
}
