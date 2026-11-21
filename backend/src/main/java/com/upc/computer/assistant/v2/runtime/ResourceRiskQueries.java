package com.upc.computer.assistant.v2.runtime;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.upc.computer.assistant.v2.runtime.ResourceRiskFacts.*;

/** Internal fixed read tools. Called in the freshly authorized read-only transaction, never from browser SQL. */
final class ResourceRiskQueries {
    static boolean allowed(AssistantReadPolicy p,String kind) {
        return p.customer()==null&&p.operator()==null&&(kind.equals("RESOURCE_MATERIAL")?List.of("ORDER","PRODUCTION","INVENTORY","STOCK_MOVEMENT"):List.of("ORDER","PRODUCTION","DISPATCH")).stream().allMatch(p::allows);
    }
    static Map<String,Object> execute(NaturalBusinessReadMapper mapper,AssistantReadPolicy policy,String kind,List<Map<String,Object>> orders,LocalDateTime now) {
        if(!Set.of("RESOURCE_MATERIAL","RESOURCE_CAPACITY").contains(kind)||!allowed(policy,kind))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"BUSINESS_PERMISSION_REQUIRED");
        List<Long> ids=orders.stream().map(o->Long.parseLong(text(o,"record_id"))).distinct().sorted().toList();
        var plans=read(ids,mapper::resourcePlans);var works=read(ids,mapper::resourceWorks);
        var lines=read(ids,part->mapper.riskLines(part,null));
        Map<String,Object> result;var sources=new LinkedHashMap<String,Object>();sources.put("orders",orders);sources.put("plans",plans);sources.put("workOrders",works);sources.put("orderLines",lines);
        if(kind.equals("RESOURCE_MATERIAL")) {
            var parents=plans.stream().map(p->Long.parseLong(text(p,"material_id"))).distinct().sorted().toList();
            var boms=read(parents,part->mapper.resourceBoms(part,now.toLocalDate().toString()));
            var children=boms.stream().map(b->Long.parseLong(text(b,"child_material_id"))).distinct().sorted().toList();
            var stock=read(children,mapper::resourceStock);var issues=read(ids,mapper::resourceIssues);
            result=SharedMaterialRisk.calculate(plans,boms,stock,works,issues,now.toLocalDate());sources.put("bom",boms);sources.put("inventory",stock);sources.put("issues",issues);
        } else {
            var schedules=read(ids,mapper::resourceSchedules);var dispatch=read(ids,mapper::resourceDispatches);
            result=CapacityConflictRisk.calculate(schedules,dispatch,works,now);sources.put("schedules",schedules);sources.put("dispatches",dispatch);
        }
        @SuppressWarnings("unchecked") var coverage=(List<Map<String,Object>>)result.get("coverage");
        for(var line:lines) {
            var order=orders.stream().filter(o->text(o,"record_id").equals(text(line,"order_id"))).findFirst().orElse(Map.of());
            if(Set.of("CANCELLED","REJECTED","SHIPPED").contains(text(order,"status"))||"CANCELLED".equals(text(line,"item_status")))continue;
            var linked=plans.stream().filter(p->text(p,"order_item_id").equals(text(line,"order_item_id"))&&"LINKED".equals(text(p,"mapping_status"))&&!"CANCELLED".equals(text(p,"plan_status"))).toList();
            var source=new LinkedHashMap<>(line);source.put("order_no",order.get("order_no"));
            if(linked.isEmpty())coverage.add(issue(source,"UNPLANNED_ORDER_LINE","订单行没有可核验的关联计划；资源分析未覆盖此行，不能按零需求处理"));
            else if(number(line,"quantity")!=null&&linked.stream().allMatch(p->number(p,"planned_quantity")!=null)&&linked.stream().map(p->number(p,"planned_quantity")).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add).compareTo(number(line,"quantity"))<0)
                coverage.add(issue(source,"PARTIAL_PLAN_COVERAGE","关联计划数量未覆盖订购量；未覆盖部分与已发货量需另核，不据此推定待生产量"));
        }
        if(kind.equals("RESOURCE_CAPACITY")) {
            @SuppressWarnings("unchecked") var schedules=(List<Map<String,Object>>)sources.get("schedules");
            for(var p:plans)if(!inactive(p)&&!Set.of("CANCELLED","COMPLETED").contains(text(p,"item_status"))&&schedules.stream().noneMatch(s->text(s,"plan_id").equals(text(p,"plan_id"))&&time(s.get("planned_start"))!=null&&time(s.get("planned_end"))!=null&&time(s.get("planned_end")).isAfter(time(s.get("planned_start")))&&time(s.get("planned_end")).isAfter(now)))
                coverage.add(issue(p,"NO_CURRENT_SCHEDULE","活动计划无当前/未来有效排程；即使尚未创建工单也不能判定无产能风险"));
        }
        for(var order:orders)if(!Set.of("CANCELLED","REJECTED","SHIPPED").contains(text(order,"status"))&&plans.stream().noneMatch(p->text(p,"order_id").equals(text(order,"record_id"))&&!inactive(p)))
            coverage.add(row("order_id",order.get("record_id"),"order_no",order.get("order_no"),"risk_status","NO_ACTIVE_PLAN","risk_reason","订单缺少活动生产计划行；未计入资源需求不代表没有风险"));
        result.put("sourceEvidence",sources);return result;
    }
    private static List<Map<String,Object>> read(List<Long> ids,Function<List<Long>,List<Map<String,Object>>> query) {
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<ids.size();i+=100)for(var raw:query.apply(ids.subList(i,Math.min(ids.size(),i+100)))) {
            Map<String,Object> row=new LinkedHashMap<>();raw.forEach((key,value)->row.put(key,value==null?null:value.toString()));rows.add(row);
        }
        return rows;
    }
}
