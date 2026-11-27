package com.upc.computer.assistant.v2.runtime;

import static com.upc.computer.assistant.v2.runtime.InspectionTrace.rows;
import java.util.*;

/** Compare the authorized work order's route with distinct business documents at each step. */
final class InspectionProductionReview {
    private InspectionProductionReview() {}

    static void append(Map<String,Object> facts, List<String> findings, List<String> actions) {
        appendLotReports(facts,findings);
        var orders = rows(facts,"workOrder");
        if (orders.isEmpty()) return;
        var order = orders.get(0);
        var work = Objects.toString(order.get("work_order_id"),"");
        var reports = rows(facts,"reportRows").stream()
            .filter(r -> work.equals(Objects.toString(r.get("work_order_id"),""))
                && !"检验工单与报工工单矛盾".equals(r.get("link_basis"))).toList();
        var dispatches = rows(facts,"dispatchRows").stream()
            .filter(r -> work.equals(Objects.toString(r.get("work_order_id"),""))).toList();
        var steps = new ArrayList<Map<String,Object>>();
        var gaps = new ArrayList<String>();
        int confirmedCount = 0;
        int reportedCount = 0;
        int dispatchedCount = 0;
        for (var row : rows(facts,"routeSteps")) {
            if (row.get("step_id") == null) continue;
            String id = row.get("step_id").toString();
            String label = Objects.toString(row.get("step_no"),id) + " " + Objects.toString(row.get("step_name"),"未命名工序");
            var atStep = reports.stream().filter(r -> id.equals(Objects.toString(r.get("step_id"),""))).toList();
            var reportNos = refs(atStep,"report_no");
            var confirmed = refs(atStep.stream().filter(r -> "CONFIRMED".equals(r.get("report_status"))).toList(),"report_no");
            var tasks = refs(dispatches.stream().filter(r -> id.equals(Objects.toString(r.get("step_id"),""))).toList(),"dispatch_no");
            if (!reportNos.isEmpty()) reportedCount++;
            if (!tasks.isEmpty()) dispatchedCount++;
            String detail;
            if (!confirmed.isEmpty()) {
                confirmedCount++;
                detail = "已确认报工 " + String.join("、",confirmed);
            } else {
                detail = !reportNos.isEmpty() ? "已有报工 " + String.join("、",reportNos) + "，未见已确认记录"
                    : !tasks.isEmpty() ? "仅有派工 " + String.join("、",tasks) : "未查到报工";
                gaps.add(label);
            }
            steps.add(Map.of("stepId",id,"label",label,"reports",reportNos,"confirmedReports",confirmed,"dispatches",tasks,"detail",detail));
        }
        var coverage=new LinkedHashMap<String,Object>();
        coverage.put("routeStepCount",steps.size());coverage.put("reportedStepCount",reportedCount);
        coverage.put("confirmedStepCount",confirmedCount);coverage.put("steps",steps);
        if(facts.containsKey("dispatchRows"))coverage.put("dispatchedStepCount",dispatchedCount);
        facts.put("processCoverage",coverage);
        if (!steps.isEmpty()) {
            findings.add("工单 "+order.get("work_order_no")+" 的路线共 "+steps.size()+" 道工序，已有报工的工序为 "
                +reportedCount+"/"+steps.size()+"，其中有已确认报工的工序为 "+confirmedCount+"/"+steps.size()
                +"。覆盖率按工序 ID 去重。同一件产品可能经过多道工序，报工数量不能跨工序累加为批次产量。");
            findings.add(String.join("；",steps.stream().map(s -> s.get("label")+"："+s.get("detail")).toList())+"。");
            if (!gaps.isEmpty()) actions.add("请生产班组核对 "+String.join("、",gaps)+" 的报工原件及确认状态，区分漏报、待确认与实际未完成。");
        } else if (facts.containsKey("routeSteps")) {
            findings.add("该工单没有可核对的路线工序，无法计算逐工序报工覆盖率。");
        }
        for(var report:reports.stream().distinct().toList()){
            if(!Set.of("检验单直接报工","不合格品关联报工").contains(Objects.toString(report.get("link_basis"),"")))continue;
            findings.add(report.get("link_basis")+" "+report.get("report_no")+"，工序 "+Objects.toString(report.get("step_name"),"未登记")
                +"，状态 "+Objects.toString(report.get("report_status"),"未登记")+"，完成 "+InspectionTrace.quantity(report.get("completed_quantity"))
                +" 件、合格 "+InspectionTrace.quantity(report.get("qualified_quantity"))+" 件、不合格 "+InspectionTrace.quantity(report.get("unqualified_quantity"))
                +" 件。报工是该工序的记录，须按样品追溯才能与本单抽检逐件对应。");
        }
    }

    private static void appendLotReports(Map<String,Object> facts,List<String> findings) {
        var lotLinks = new LinkedHashMap<String,Set<String>>();
        for (var row : rows(facts,"lotReports")) {
            if (row.get("report_no")!=null && row.get("inspection_no")!=null)
                lotLinks.computeIfAbsent(row.get("report_no").toString(),k -> new LinkedHashSet<>()).add(row.get("inspection_no").toString());
        }
        if (!lotLinks.isEmpty()) {
            String links = String.join("；",lotLinks.entrySet().stream().map(e -> e.getKey()+" ← "+String.join("、",e.getValue())).toList());
            findings.add("同物料、同批号、同检验类别的检验单共关联 "+lotLinks.size()+" 张不同报工单："+links+"。同一报工重复被引用时只计一次。");
        }
    }

    static List<String> refs(List<Map<String,Object>> rows,String key) {
        return rows.stream().map(r -> Objects.toString(r.get(key),"")).filter(s -> !s.isBlank()).distinct().toList();
    }
}
