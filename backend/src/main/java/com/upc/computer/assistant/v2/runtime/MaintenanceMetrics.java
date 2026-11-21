package com.upc.computer.assistant.v2.runtime;

import java.math.BigDecimal;
import java.util.*;
import java.time.*;
import java.math.RoundingMode;

/** Sums recorded maintenance durations only; overlapping incidents are not plant downtime. */
public final class MaintenanceMetrics {
    private MaintenanceMetrics() {}
    public static List<Map<String,Object>> summarize(List<Map<String,Object>> rows) {
        Map<String,List<Map<String,Object>>> groups=new LinkedHashMap<>();
        for(var row:rows)groups.computeIfAbsent(Objects.toString(row.get("equipment_id"),"unknown"),id->new ArrayList<>()).add(row);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var records:groups.values()) {
            BigDecimal total=BigDecimal.ZERO;int known=0;
            for(var row:records)try {
                BigDecimal value=new BigDecimal(Objects.toString(row.get("downtime_minutes"),""));
                if(value.signum()<0)continue;total=total.add(value);known++;
            }catch(NumberFormatException ignored) { /* Unknown is not a zero-duration incident. */ }
            Map<String,Object> summary=new LinkedHashMap<>();
            for(String key:List.of("equipment_id","equipment_code","equipment_name"))summary.put(key,records.get(0).get(key));
            summary.put("maintenance_record_count",records.size());summary.put("known_duration_count",known);
            summary.put("missing_duration_count",records.size()-known);
            summary.put("recorded_downtime_minutes",known==0?null:total.stripTrailingZeros().toPlainString());
            summary.put("duration_basis","维修记录登记分钟数之和；未合并重叠区间，不代表设备净停机或OEE");
            summary.putAll(intervals(records));
            result.add(summary);
        }
        return result;
    }
    private record Interval(LocalDateTime start,LocalDateTime end) {}
    private static Map<String,Object> intervals(List<Map<String,Object>> records) {
        List<Interval> valid=new ArrayList<>();
        for(var row:records) {
            if(row.get("equipment_id")==null||row.get("equipment_id").toString().isBlank())continue;
            try {
                var start=LocalDateTime.parse(Objects.toString(row.get("start_time"),"").replace(' ','T'));
                var end=LocalDateTime.parse(Objects.toString(row.get("end_time"),"").replace(' ','T'));
                if(end.isBefore(start))continue;
                valid.add(new Interval(start,end));
            }catch(DateTimeException invalid) { /* Missing end is unknown, never the current clock time. */ }
        }
        valid.sort(Comparator.comparing(Interval::start));
        BigDecimal seconds=BigDecimal.ZERO;Interval merged=null;
        for(var interval:valid) {
            if(merged==null)merged=interval;
            else if(!interval.start.isAfter(merged.end))merged=new Interval(merged.start,interval.end.isAfter(merged.end)?interval.end:merged.end);
            else {seconds=seconds.add(seconds(merged));merged=interval;}
        }
        if(merged!=null)seconds=seconds.add(seconds(merged));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("interval_union_minutes",valid.isEmpty()?null:seconds.divide(BigDecimal.valueOf(60),2,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        out.put("valid_interval_count",valid.size());out.put("missing_interval_count",records.size()-valid.size());
        out.put("interval_basis","仅对本次返回记录的维修开始/结束时间按设备合并重叠区间（分钟，最多两位小数）；未结束或无效记录不计，未补查跨期记录，不代表设备真实净停机或OEE。");
        return out;
    }
    private static BigDecimal seconds(Interval interval) {
        var duration=Duration.between(interval.start,interval.end);
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(),9));
    }
}
