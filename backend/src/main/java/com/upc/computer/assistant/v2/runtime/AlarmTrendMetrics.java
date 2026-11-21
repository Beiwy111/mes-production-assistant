package com.upc.computer.assistant.v2.runtime;

import java.time.*;
import java.util.*;

/** Counts authorized alarm records, not downtime, incident causation or historical open backlog. */
public final class AlarmTrendMetrics {
    private AlarmTrendMetrics(){}
    public static List<Map<String,Object>> summarize(List<Map<String,Object>> rows,boolean complete) {
        Map<String,Integer> ids=new HashMap<>();
        for(var row:rows){String id=text(row,"record_id");if(id!=null)ids.merge(id,1,Integer::sum);}
        Map<List<String>,List<Map<String,Object>>> groups=new LinkedHashMap<>();int unknown=0;
        for(var row:rows) {
            List<String> key=new ArrayList<>();key.add(day(row.get("event_at")));
            String equipment=text(row,"equipment_id");key.add(equipment==null?"unknown-"+(unknown++):equipment);
            for(String field:List.of("alarm_type","alarm_level","status"))key.add(Objects.toString(row.get(field),""));
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(var records:groups.values()) {
            var first=records.get(0);String date=day(first.get("event_at"));int invalid=0,duplicates=0;
            for(var row:records) {
                String id=text(row,"record_id");boolean duplicate=id!=null&&ids.getOrDefault(id,0)>1;
                if(duplicate)duplicates++;
                if(id==null||duplicate)invalid++;
            }
            boolean reliable=complete&&invalid==0&&!date.equals("未提供有效报警日期")
                &&text(first,"equipment_id")!=null&&text(first,"alarm_type")!=null&&text(first,"alarm_level")!=null&&text(first,"status")!=null;
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("alarm_day",date);
            for(String field:List.of("equipment_id","equipment_code","equipment_name","alarm_type","alarm_level","status"))summary.put(field,first.get(field));
            summary.put("alarm_record_count",reliable?records.size():null);summary.put("returned_alarm_record_count",records.size());
            summary.put("invalid_alarm_record_count",invalid);summary.put("duplicate_record_count",duplicates);
            summary.put("trend_status",reliable?"记录口径完整":"存在未确认或缺失数据");
            summary.put("alarm_count_basis","按上海时区上报日期、设备、报警类型、等级及当前状态统计记录数；不代表当日未关闭存量、独立故障次数、设备停机时长或故障原因。未返回的日期不补零。");result.add(summary);
        }
        result.sort(Comparator.comparing(r->String.valueOf(r.get("alarm_day"))));return result;
    }
    private static String text(Map<String,Object> row,String field){Object v=row.get(field);return v==null||v.toString().isBlank()?null:v.toString();}
    private static String day(Object value) {
        if(value!=null) {
            String text=value.toString();
            try{return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();}catch(DateTimeException ignored){}
            try{return LocalDateTime.parse(text.replace(' ','T')).toLocalDate().toString();}catch(DateTimeException ignored){}
            try{return LocalDate.parse(text).toString();}catch(DateTimeException ignored){}
        }
        return "未提供有效报警日期";
    }
}
