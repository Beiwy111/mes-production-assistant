package com.upc.computer.assistant.v2.runtime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Plain-text fallback assembled only from returned, permission-scoped statistics. */
final class QualityOverviewAnswer {
    private static final Pattern ANALYSIS=Pattern.compile("分析|为什么|为何|原因|风险|趋势|分布|统计|比较|对比|差异|区别|环比|同比|合格率|良率|缺陷率|改善|建议");
    private static final Pattern OVERVIEW=Pattern.compile("概况|概览|总览|整体|总体|情况|怎么样|如何|表现");
    private static final Pattern LIST=Pattern.compile("列表|列出|清单|明细|逐条|哪些单|导出|CSV",Pattern.CASE_INSENSITIVE);
    static boolean analytical(String query){return ANALYSIS.matcher(query).find()||!LIST.matcher(query).find()&&OVERVIEW.matcher(query).find();}
    private QualityOverviewAnswer() {}

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> facts,String key){return (List<Map<String,Object>>)facts.getOrDefault(key,List.of());}
    private static String group(Map<String,Object> row){
        return Objects.toString(row.get("material_name"),"物料 "+row.get("material_id"))+"（"+row.get("inspection_category")+" / "+row.get("inspection_type")+"）";
    }
    private static String fraction(Map<String,Object> row){return row.get("valid_unqualified_quantity")+"/"+row.get("valid_sample_quantity")+row.get("unit")+"，"+row.get("sample_fail_percent")+"%";}
    private static String period(Object start,Object end){
        if(start==null||end==null)return "当前授权范围（不限检验日期）";
        LocalDate from=QualityOverviewStatistics.day(start),to=QualityOverviewStatistics.day(end);
        return from!=null&&to!=null?from+" 至 "+to.minusDays(1)+"（上海时区，按检验时间）":"当前授权范围";
    }
    private static boolean hasCount(Map<String,Object> stats,String key){return stats.get(key) instanceof Number n&&n.longValue()>0;}

    static String describe(Map<String,Object> overview,Map<String,Object> stats,List<Map<String,Object>> trend,String query){
        var lines=new ArrayList<String>();
        lines.add("质检概览，"+period(overview.get("startInclusive"),overview.get("endExclusive"))+"内共 "+overview.get("totalRecords")
                +" 张检验单。返回记录中可按物料和批号识别 "+stats.get("batchCount")+" 批，"+stats.get("validRecordCount")+" 张单具有可核对的抽样分母。");
        if(!hasCount(stats,"inspectionCount")){
            lines.add("本次未取得检验明细，样本合格率、批次差异和缺陷分布均无数据可算。");
            return String.join("\n",lines);
        }
        var exclusions=new ArrayList<String>();
        for(var pair:List.of(List.of("pendingCount","待检或待复检"),List.of("concessionCount","让步判定"),
                List.of("acceptanceReviewCount","判定合格且有不合格样品，接受准则待核"),List.of("conflictCount","件数、判定或关联矛盾"),List.of("incompleteCount","取样依据不全")))
            if(hasCount(stats,pair.get(0)))exclusions.add(stats.get(pair.get(0))+" 张"+pair.get(1));
        if(!exclusions.isEmpty())lines.add("分母排除"+String.join("、",exclusions)+"。");
        if(!Boolean.TRUE.equals(stats.get("complete")))lines.add("当前明细未完全取回，暂缓计算范围比例。");
        if("WITHHELD_VERDICT_FILTER".equals(stats.get("rateScope")))lines.add("当前仅含筛选后的判定记录，统计整体比例需要全部判定。");
        for(var row:rows(stats,"sampleGroups").stream().filter(r->r.get("sample_fail_percent")!=null).limit(3).toList())
            lines.add(group(row)+"，有效抽检 "+row.get("valid_sample_quantity")+row.get("unit")+"，合格 "+row.get("valid_qualified_quantity")
                    +row.get("unit")+"（"+row.get("sample_pass_percent")+"%），不合格 "+row.get("valid_unqualified_quantity")+row.get("unit")+"（"+row.get("sample_fail_percent")+"%）。");
        var differences=rows(stats,"batchDifferences");
        if(!differences.isEmpty()){
            for(var diff:differences.stream().limit(2).toList()){
                @SuppressWarnings("unchecked") var low=(Map<String,Object>)diff.get("lowest");
                @SuppressWarnings("unchecked") var high=(Map<String,Object>)diff.get("highest");
                lines.add(group(diff)+"有 "+diff.get("comparable_batch_count")+" 个可比批次。批号 "+high.get("batch_no")+" 的抽检不合格率为 "+fraction(high)
                        +"，批号 "+low.get("batch_no")+" 为 "+fraction(low)+"，两批相差 "+diff.get("difference_percentage_points")+" 个百分点。");
            }
        }else if(query.contains("批"))lines.add("未找到两批同物料、单位、检验类别和类型且记录完整的抽样，无法量化批间差异。");
        var defects=rows(stats,"defectDistribution");
        if(!defects.isEmpty()){
            var top=defects.stream().limit(3).map(d->d.get("defect_type")+" "+d.get("record_count")+" 条").toList();
            lines.add("关联缺陷登记按条数排序："+String.join("、",top)+"。同一样品可有多条登记。");
        }else lines.add("此范围未见关联一致的缺陷登记。");
        if(hasCount(stats,"excludedDefectRegistrationCount"))lines.add("另有 "+stats.get("excludedDefectRegistrationCount")+" 条登记关联待核，已从分布中剔除。");
        if(query.matches(".*(?:趋势|按日|每日|每天|走势).*"))describeDays(trend,lines);
        var comparisons=rows(stats,"periodComparisons");var reported=new HashSet<String>();
        for(var window:comparisons){
            var point=rows(window,"groups").stream().filter(p->p.get("difference_percentage_points")!=null).findFirst();
            if(point.isEmpty())continue;
            var p=point.get();var delta=new BigDecimal(p.get("difference_percentage_points").toString());
            String label=switch(window.get("basis").toString()){case "YOY"->"同比";case "MOM"->"月环比";default->"与前一等长时段比较";};
            lines.add(group(p)+"抽检不合格率"+label+"，"+period(window.get("previousStartInclusive"),window.get("previousEndExclusive"))
                    +"为 "+p.get("previous_fail_percent")+"%（抽检 "+p.get("previous_sample_quantity")+p.get("unit")+"），"
                    +period(window.get("currentStartInclusive"),window.get("currentEndExclusive"))+"为 "+p.get("current_fail_percent")+"%（抽检 "
                    +p.get("current_sample_quantity")+p.get("unit")+"），"+(delta.signum()==0?"显示精度内持平":(delta.signum()>0?"增加 ":"减少 ")+delta.abs()+" 个百分点")+"。");reported.add(window.get("basis").toString());
        }
        if(query.contains("同比")&&!reported.contains("YOY"))lines.add("未取得可计算同比变化的同期完整分母。");
        if(query.contains("环比")&&Collections.disjoint(reported,Set.of("MOM","PREVIOUS_PERIOD")))lines.add("未取得可计算环比变化的前期完整分母。");
        if(query.matches(".*(?:原因|为什么|为何|建议|改善|改进).*")){
            if(!exclusions.isEmpty())lines.add("建议先核对被排除检验单的样品标识、复检轮次和接受准则，再判断差异是否仍然存在。");
            else if(!defects.isEmpty())lines.add("可按登记最多的“"+defects.get(0).get("defect_type")+"”回查对应样品的工序、实测值和检验条件。汇总记录只能定位核查范围。");
            else lines.add("要判断原因，需要将异常样品与工序、实测值和检验条件对应。");
        }
        return String.join("\n",lines);
    }
    private static void describeDays(List<Map<String,Object>> trend,List<String> lines){
        var groups=new LinkedHashMap<List<Object>,List<Map<String,Object>>>();
        for(var day:trend){
            if(day.get("sample_fail_percent")==null||!Integer.valueOf(0).equals(day.get("invalid_sample_record_count")))continue;
            groups.computeIfAbsent(List.of(day.get("material_id"),day.get("unit"),day.get("inspection_category"),day.get("inspection_type")),k->new ArrayList<>()).add(day);
        }
        for(var group:groups.values())if(group.size()>1){
            var first=group.get(0);var last=group.get(group.size()-1);
            lines.add(group(first)+"有 "+group.size()+" 个可比检验日。首日 "+first.get("inspection_day")+" 抽检不合格 "+fraction(first)
                    +"，末日 "+last.get("inspection_day")+" 为 "+fraction(last)+"。缺少抽样的日期留空。");
            return;
        }
        lines.add("缺少至少两个同口径、分母完整的检验日，暂不能判定日趋势。");
    }
}
