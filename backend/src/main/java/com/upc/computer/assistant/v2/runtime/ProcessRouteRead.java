package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.service.ProductionWorkshopCatalog;
import java.util.*;

/** Versioned factory routing master data, independent of historical dispatch status. */
final class ProcessRouteRead {
    static Map<String,Object> read(NaturalLanguageRuntime.Data data,String keyword) {
        data.policy().require("PROCESS_ROUTE");
        var rows=data.overview().activeRouteSteps(keyword);
        var steps=new LinkedHashMap<String,List<String>>();
        for(var row:rows) {
            String name=row.get("route_name")+"（"+row.get("route_code")+"，"+row.get("version_no")+"）";
            var sequence=steps.computeIfAbsent(name,ignored->new ArrayList<>());
            if(row.get("step_id")!=null)sequence.add(String.valueOf(row.get("step_name")));
        }
        String summary;
        if(steps.isEmpty())summary="在授权的启用工艺路线中未查到匹配记录，请核对产品或路线名称。";
        else if(steps.size()==1) {
            var route=steps.entrySet().iterator().next();
            summary=route.getKey()+"的启用工序共 "+route.getValue().size()+" 道："+String.join(" → ",route.getValue())+"。";
        } else {
            var counts=new TreeSet<Integer>();
            steps.values().forEach(s->counts.add(s.size()));
            summary="当前有 "+steps.size()+" 条匹配的启用路线，各路线实际工序数为 "+
                String.join("、",counts.stream().map(String::valueOf).toList())+"。请按产品和路线版本查看下表的工序顺序。";
        }
        var stages=ProductionWorkshopCatalog.PRODUCTION_STAGES.stream()
            .sorted(Comparator.comparingInt(ProductionWorkshopCatalog.ProcessStageDef::stepOrder)).toList();
        var stageRows=stages.stream().map(s->Map.of("step_no",s.stepOrder(),"step_key",s.stepKey(),"step_name",s.stepName())).toList();
        if(keyword==null){
            var names=stages.stream().map(s->s.stepOrder()+". "+s.stepName()).toList();
            summary="车间派工使用的 "+stages.size()+" 道生产工序依次为："+String.join(" → ",names)+"。\n"
                +"完整产品路线另计路线中的检验、检测等步骤。"+summary;
        }
        return Map.of("rows",rows,"productionStages",stageRows,"summary",summary,
            "stageSource","ProductionWorkshopCatalog.PRODUCTION_STAGES","source","process_route / process_step");
    }
}
