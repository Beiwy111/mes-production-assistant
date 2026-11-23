package com.upc.computer.assistant.v2.runtime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.upc.computer.assistant.v2.runtime.ResourceRiskFacts.*;

/** Compare remaining work with one shared pool per component, without inventing an allocation. */
final class WorkMaterialCoverage {
    static Map<String,Object> read(NaturalBusinessReadMapper db,List<Map<String,Object>> works,LocalDate day){
        var parents=works.stream().filter(w->w.get("material_id")!=null).map(w->Long.valueOf(text(w,"material_id"))).distinct().toList();
        var boms=parents.isEmpty()?List.<Map<String,Object>>of():db.resourceBoms(parents,day.toString());
        var needs=new TreeMap<Long,BigDecimal>();
        var names=new HashMap<Long,Map<String,Object>>();
        var contributors=new HashMap<Long,List<String>>();
        var uncovered=new ArrayList<String>();
        for(var w:works){
            String no=text(w,"work_order_no");
            if(Set.of("CANCELLED","COMPLETED","CLOSED").contains(text(w,"status")))continue;
            var planned=number(w,"planned_quantity");var done=number(w,"completed_quantity");
            if(planned==null||done==null||planned.compareTo(done)<0){uncovered.add(no+"（进度待核）");continue;}
            if(planned.compareTo(done)==0)continue;
            var active=boms.stream().filter(b->text(b,"parent_material_id").equals(text(w,"material_id"))
                    &&"1".equals(text(b,"status"))&&date(b.get("effective_date"))!=null&&!date(b.get("effective_date")).isAfter(day)
                    &&(b.get("expire_date")==null||!date(b.get("expire_date")).isBefore(day))).toList();
            if(active.isEmpty()||active.stream().map(b->text(b,"version_no")).distinct().count()!=1||
                    active.stream().anyMatch(b->text(b,"version_no").isBlank()||text(b,"unit").isBlank()||number(b,"quantity")==null||
                            number(b,"quantity").signum()==0||number(b,"loss_rate")==null||"1".equals(text(b,"has_child_bom")))){
                uncovered.add(no+"（缺少可核验的单层有效 BOM）");continue;
            }
            for(var b:active){
                var id=Long.valueOf(text(b,"child_material_id"));
                needs.merge(id,planned.subtract(done).multiply(number(b,"quantity")).multiply(BigDecimal.ONE.add(number(b,"loss_rate"))),BigDecimal::add);
                names.put(id,b);
                contributors.computeIfAbsent(id,k->new ArrayList<>()).add(no);
            }
        }
        var stock=needs.isEmpty()?List.<Map<String,Object>>of():db.resourceStock(List.copyOf(needs.keySet()));
        var rows=new ArrayList<Map<String,Object>>();var gaps=new ArrayList<String>();
        for(var entry:needs.entrySet()){
            var id=entry.getKey();var bom=names.get(id);var pool=BigDecimal.ZERO;
            var matching=stock.stream().filter(s->id.toString().equals(text(s,"material_id"))).toList();
            boolean valid=!matching.isEmpty();
            for(var s:matching){
                if(!"NORMAL".equals(text(s,"inventory_status")))continue;
                var avail=number(s,"quantity_available");var held=number(s,"quantity_reserved");var onHand=number(s,"quantity_on_hand");
                if(avail==null||held==null||onHand==null||onHand.subtract(held).compareTo(avail)!=0||!text(s,"unit").equals(text(bom,"unit"))){valid=false;break;}
                pool=pool.add(avail);
            }
            var gap=valid?entry.getValue().subtract(pool).max(BigDecimal.ZERO):null;
            if(gap!=null&&gap.signum()>0)gaps.add(text(bom,"material_name")+" "+decimal(gap)+text(bom,"unit"));
            rows.add(row("material_name",bom.get("material_name"),"scenario_demand_qty",decimal(entry.getValue()),"public_available_qty",valid?decimal(pool):null,
                    "scenario_gap_qty",decimal(gap),"unit",bom.get("unit"),"work_order_no",String.join("、",new LinkedHashSet<>(contributors.get(id))),
                    "risk_reason",valid?"按剩余工单数量和当前 BOM 估算":"库存记录缺失或数量待核"));
        }
        String summary="按所选工单剩余量和当前 BOM（含损耗）估算，共享可用量每种物料只计一次。"+
                (rows.isEmpty()?"没有可计算的物料行。":gaps.isEmpty()?"已核实的物料未发现账面缺口。":"账面缺口："+String.join("、",gaps)+"。")+
                (rows.stream().anyMatch(r->r.get("scenario_gap_qty")==null)?"部分物料缺少可靠库存，缺口未知。":"")+
                (uncovered.isEmpty()?"":"未覆盖："+String.join("、",uncovered)+"。")+"现场齐套还需核对已领未耗及专用预留。";
        return row("materialRows",rows,"traceConclusion",summary);
    }
}
