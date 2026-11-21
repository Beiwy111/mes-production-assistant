package com.upc.computer.assistant.v2.runtime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.upc.computer.assistant.v2.runtime.ResourceRiskFacts.*;

/** One shared ledger, explicitly a current-BOM / unfinished-plan supply scenario, never a reservation. */
public final class SharedMaterialRisk {
    public static Map<String,Object> calculate(List<Map<String,Object>> plans,List<Map<String,Object>> boms,List<Map<String,Object>> inventory,List<Map<String,Object>> works,List<Map<String,Object>> issues,LocalDate day) {
        var coverage=new ArrayList<Map<String,Object>>();var demands=new ArrayList<Map<String,Object>>();
        var seenPlans=new HashSet<String>();
        for(var p:plans) {
            if(inactive(p)||Set.of("CANCELLED","COMPLETED").contains(text(p,"item_status")))continue;
            String pid=text(p,"plan_item_id"),parent=text(p,"material_id");
            var planned=number(p,"planned_quantity");var done=number(p,"completed_quantity");
            boolean alternatives=plans.stream().filter(other->!inactive(other)&&Objects.equals(other.get("order_item_id"),p.get("order_item_id"))).map(other->text(other,"plan_id")).distinct().count()>1;
            if(pid.isBlank()||!seenPlans.add(pid)||!"LINKED".equals(text(p,"mapping_status"))||alternatives||planned==null||done==null||done.compareTo(planned)>0||text(p,"material_unit").isBlank()||!text(p,"material_unit").equals(text(p,"order_unit"))) {
                coverage.add(issue(p,"UNKNOWN_PLAN","计划归属、版本、单位或完成量待核实；不计为零需求"));continue;
            }
            BigDecimal remaining=planned.subtract(done);
            var ownWorks=works.stream().filter(w->pid.equals(text(w,"plan_item_id"))&&!"CANCELLED".equals(text(w,"status"))).toList();
            boolean workConflict=ownWorks.stream().anyMatch(w->!"LINKED".equals(text(w,"mapping_status"))||number(w,"completed_quantity")==null);
            if(!ownWorks.isEmpty()&&!workConflict)workConflict=ownWorks.stream().map(w->number(w,"completed_quantity")).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(done)!=0;
            if(workConflict)coverage.add(issue(p,"PLAN_WORK_PROGRESS_CONFLICT","计划与工单完成量或归属不一致；本行只采用计划记录做供料情景"));
            if(remaining.signum()==0)continue;
            var active=new ArrayList<Map<String,Object>>();boolean bad=false;
            for(var b:boms)if(parent.equals(text(b,"parent_material_id"))&&"1".equals(text(b,"status"))) {
                var start=date(b.get("effective_date"));var end=date(b.get("expire_date"));
                if(start==null||(!text(b,"expire_date").isBlank()&&end==null)||text(b,"version_no").isBlank()){bad=true;continue;}
                if(!start.isAfter(day)&&(end==null||!end.isBefore(day)))active.add(b);
            }
            if(bad||active.isEmpty()||active.stream().map(b->text(b,"version_no")).distinct().count()!=1||active.stream().map(b->text(b,"bom_id")).distinct().count()!=active.size()) {
                coverage.add(issue(p,"UNKNOWN_BOM","没有唯一可核验的当前有效 BOM 版本；不猜测物料需求"));continue;
            }
            // Validate the entire product BOM before adding any lines. Partial BOM must not look complete.
            if(active.stream().anyMatch(b->number(b,"quantity")==null||number(b,"quantity").signum()==0||number(b,"loss_rate")==null||text(b,"unit").isBlank()||text(b,"child_material_id").isBlank()||"1".equals(text(b,"has_child_bom")))) {
                coverage.add(issue(p,"UNKNOWN_BOM","BOM 数量/单位无效或存在多层 BOM；当前一层供料情景未覆盖"));continue;
            }
            for(var b:active) {
                var need=remaining.multiply(number(b,"quantity")).multiply(BigDecimal.ONE.add(number(b,"loss_rate")));
                demands.add(row("order_id",p.get("order_id"),"order_no",p.get("order_no"),"plan_id",p.get("plan_id"),"plan_item_id",pid,"plan_status",p.get("plan_status"),"material_id",b.get("child_material_id"),"material_name",b.get("material_name"),"unit",b.get("unit"),"due_date",p.get("due_date"),"bom_id",b.get("bom_id"),"bom_version",b.get("version_no"),"unfinished_plan_qty",decimal(remaining),"bom_quantity",b.get("quantity"),"loss_rate",b.get("loss_rate"),"scenario_demand_qty",decimal(need),"progress_basis",workConflict?"PLAN_WORK_CONFLICT":"PLAN_RECORDED"));
            }
        }
        var grouped=new TreeMap<String,List<Map<String,Object>>>();for(var d:demands)grouped.computeIfAbsent(text(d,"material_id"),k->new ArrayList<>()).add(d);
        var rows=new ArrayList<Map<String,Object>>();
        for(var entry:grouped.entrySet()) {
            String material=entry.getKey();var demand=entry.getValue();String unit=text(demand.get(0),"unit");
            var stocks=inventory.stream().filter(v->material.equals(text(v,"material_id"))).toList();
            boolean valid=demand.stream().allMatch(d->unit.equals(text(d,"unit")));var seen=new HashSet<String>();BigDecimal pool=BigDecimal.ZERO;
            for(var stock:stocks) {
                if(!"NORMAL".equals(text(stock,"inventory_status")))continue;
                var hand=number(stock,"quantity_on_hand");var reserved=number(stock,"quantity_reserved");var available=number(stock,"quantity_available");
                if(text(stock,"inventory_id").isBlank()||!seen.add(text(stock,"inventory_id"))||hand==null||reserved==null||available==null||hand.subtract(reserved).compareTo(available)!=0||!unit.equals(text(stock,"unit"))){valid=false;continue;}
                pool=pool.add(available);
            }
            var byOrder=new LinkedHashMap<String,List<Map<String,Object>>>();
            var ordered=demand.stream().sorted(Comparator.comparing((Map<String,Object> d)->Optional.ofNullable(date(d.get("due_date"))).orElse(LocalDate.MAX)).thenComparing(d->new java.math.BigInteger(text(d,"order_id")))).toList();
            BigDecimal total=demand.stream().map(d->number(d,"scenario_demand_qty")).reduce(BigDecimal.ZERO,BigDecimal::add),left=pool;
            var allocations=new HashMap<String,BigDecimal>();
            for(var d:ordered) {
                String orderId=text(d,"order_id");byOrder.computeIfAbsent(orderId,k->new ArrayList<>()).add(d);
                if(valid){var allocated=left.min(number(d,"scenario_demand_qty"));left=left.subtract(allocated);allocations.merge(orderId,allocated,BigDecimal::add);}
            }
            for(var order:byOrder.entrySet()) {
                var first=order.getValue().get(0);BigDecimal need=order.getValue().stream().map(d->number(d,"scenario_demand_qty")).reduce(BigDecimal.ZERO,BigDecimal::add);
                BigDecimal allocation=valid?allocations.get(order.getKey()):null,gap=valid?need.subtract(allocation):null;
                var movements=issues.stream().filter(t->order.getKey().equals(text(t,"order_id"))&&material.equals(text(t,"material_id"))).toList();
                BigDecimal issued=BigDecimal.ZERO;boolean issueKnown=true;var transactionIds=new HashSet<String>();
                for(var t:movements)if("MATERIAL_OUT".equals(text(t,"transaction_type"))) {
                    if(!"LINKED".equals(text(t,"mapping_status"))||number(t,"quantity")==null||text(t,"transaction_id").isBlank()||!transactionIds.add(text(t,"transaction_id"))){issueKnown=false;continue;}
                    issued=issued.add(number(t,"quantity"));
                }
                rows.add(row("order_id",order.getKey(),"order_no",first.get("order_no"),"material_id",material,"material_name",first.get("material_name"),"unit",unit,"competing_order_nos",byOrder.values().stream().map(ds->ds.get(0).get("order_no")).toList(),"scenario_demand_qty",decimal(need),"scoped_total_demand_qty",decimal(total),"public_available_qty",valid?decimal(pool):null,"scenario_allocated_qty",decimal(allocation),"scenario_gap_qty",decimal(gap),"recorded_issued_qty",issueKnown?decimal(issued):null,"inventory_ids",ids(stocks,"inventory_id"),"plan_item_ids",ids(order.getValue(),"plan_item_id"),"bom_ids",ids(order.getValue(),"bom_id"),"transaction_ids",ids(movements,"transaction_id"),"risk_status",!valid?"UNKNOWN_STOCK_POOL":gap.signum()>0?(byOrder.size()>1?"SHARED_POOL_SCENARIO_GAP":"SCENARIO_GAP"):byOrder.size()>1?"SHARED_POOL_COMPETITION":"SCENARIO_COVERED","risk_reason",!valid?"库存数量公式或单位待核实，未分配库存":gap.signum()>0?"按计划交期优先的公共库存情景存在缺口；已领料与订单专属预留尚未核清":"当前情景已分配公共库存；不等于实际齐套或可交付"));
            }
        }
        return row("status","SCENARIO_NOT_CONFIRMED_SHORTAGE","basis","未完成计划×当前唯一有效一层BOM（含损耗）；交期/订单ID优先；公共可用库存每批仅计一次；已领料不盲目冲减","scope","仅本次授权及筛选范围，不含范围外竞争；不是历史BOM或返工净需求","rows",rows,"demands",demands,"coverage",coverage);
    }
}
