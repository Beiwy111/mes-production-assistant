package com.upc.computer.assistant.v2.runtime;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.upc.computer.assistant.v2.runtime.ResourceRiskFacts.*;

/** Saved scheduling overlaps are warnings, not inferred machine capacity or actual operator work times. */
public final class CapacityConflictRisk {
    private record Slot(String type,String resource,String order,String orderNo,String source,LocalDateTime start,LocalDateTime end) {}
    public static Map<String,Object> calculate(List<Map<String,Object>> schedules,List<Map<String,Object>> dispatch,List<Map<String,Object>> works,LocalDateTime now) {
        var coverage=new ArrayList<Map<String,Object>>();var slots=new ArrayList<Slot>();var active=new ArrayList<Map<String,Object>>();
        Set<String> seen=new HashSet<>();
        for(var s:schedules) {
            if(inactive(s))continue;
            LocalDateTime start=time(s.get("planned_start")),end=time(s.get("planned_end"));
            if(text(s,"schedule_id").isBlank()||!seen.add(text(s,"schedule_id"))||start==null||end==null||!end.isAfter(start)) {coverage.add(issue(s,"UNKNOWN_SCHEDULE","排程起止时间或记录标识无效，不能判断占用"));continue;}
            if(!end.isAfter(now))continue;
            active.add(s);
            if(text(s,"equipment_id").isBlank()){coverage.add(issue(s,"UNKNOWN_EQUIPMENT","排程未绑定设备，不能核对设备竞争"));continue;}
            slots.add(new Slot("EQUIPMENT",text(s,"equipment_id"),text(s,"order_id"),text(s,"order_no"),text(s,"schedule_id"),start.isBefore(now)?now:start,end));
        }
        Set<String> operatorIntervals=new HashSet<>();
        for(var d:dispatch) {
            if(inactive(d)||Set.of("COMPLETED","CANCELLED").contains(text(d,"status"))||Set.of("COMPLETED","CANCELLED").contains(text(d,"work_status")))continue;
            var candidates=active.stream().filter(s->text(s,"plan_id").equals(text(d,"plan_id"))&&text(s,"step_id").equals(text(d,"step_id"))&&text(s,"order_id").equals(text(d,"order_id"))).toList();
            if(!"LINKED".equals(text(d,"mapping_status"))||text(d,"operator_id").isBlank()||text(d,"step_id").isBlank()||candidates.size()!=1) {coverage.add(issue(d,"UNKNOWN_DISPATCH_WINDOW","派工缺少唯一且一致的工序排程窗口；不把派工时间当占用时长"));continue;}
            var s=candidates.get(0);
            if(!text(d,"equipment_id").isBlank()&&!text(s,"equipment_id").isBlank()&&!text(d,"equipment_id").equals(text(s,"equipment_id"))) {coverage.add(issue(d,"DISPATCH_RESOURCE_MISMATCH","派工与排程设备不一致，人员占用窗口待核实"));continue;}
            LocalDateTime start=time(s.get("planned_start")),end=time(s.get("planned_end"));
            String key=text(d,"operator_id")+":"+text(d,"order_id")+":"+text(s,"schedule_id");
            if(operatorIntervals.add(key))slots.add(new Slot("OPERATOR",text(d,"operator_id"),text(d,"order_id"),text(d,"order_no"),text(d,"dispatch_id"),start.isBefore(now)?now:start,end));
        }
        var rows=new ArrayList<Map<String,Object>>();
        slots.sort(Comparator.comparing(Slot::type).thenComparing(Slot::resource).thenComparing(Slot::start).thenComparing(Slot::source));
        for(int i=0;i<slots.size();i++)for(int j=i+1;j<slots.size();j++) {
            var a=slots.get(i);var b=slots.get(j);
            if(!a.type.equals(b.type)||!a.resource.equals(b.resource))break;
            if(!b.start.isBefore(a.end))break;
            if(a.order.equals(b.order))continue;
            var from=a.start.isAfter(b.start)?a.start:b.start;var to=a.end.isBefore(b.end)?a.end:b.end;
            if(from.isBefore(to))rows.add(row("resource_type",a.type,"resource_id",a.resource,"left_order_id",a.order,"left_order_no",a.orderNo,"right_order_id",b.order,"right_order_no",b.orderNo,"left_source_id",a.source,"right_source_id",b.source,"overlap_start",from.toString(),"overlap_end",to.toString(),"overlap_minutes",BigDecimal.valueOf(Duration.between(from,to).toSeconds()).divide(BigDecimal.valueOf(60),4,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),"risk_status","POTENTIAL_SCHEDULE_CONFLICT","risk_reason",a.type.equals("EQUIPMENT")?"同设备跨订单计划重叠；需核实设备并发能力":"同操作员跨订单匹配排程重叠；不代表已证实实际同时作业"));
        }
        for(var w:works)if(!inactive(w)&&!Set.of("COMPLETED","CANCELLED").contains(text(w,"status"))&&active.stream().noneMatch(s->text(s,"plan_id").equals(text(w,"plan_id"))))coverage.add(issue(w,"NO_CURRENT_SCHEDULE","活动工单无当前或未来可用排程，不能据此认定没有产能风险"));
        return row("status","SAVED_SCHEDULE_OVERLAP_NOT_CAPACITY_PROMISE","basis","当前/未来保存排程的半开区间；设备并发能力与完整班次产能未知；人员窗口仅取唯一匹配工序排程","scope","仅本次授权及筛选订单，不含范围外占用","rows",rows,"coverage",coverage,"schedule_evidence",schedules,"dispatch_evidence",dispatch);
    }
}
