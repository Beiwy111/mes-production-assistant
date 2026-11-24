package com.upc.computer.assistant.v2.runtime;

import java.util.*;
import java.util.regex.Pattern;

/** Follows the rows actually presented in the last authorized quality table. */
final class InspectionSelection {
    enum Operation { SET_UNQUALIFIED, SET_FAILED_STATUS, SET_TRIAGE, SET_RESUME }

    private static final String SET = "(?:(?:刚才|之前|前面|上面|上述|上轮|前一轮)(?:提到|显示|列出|筛出)?的?)?(?:这|那)?(?:一?[几些批组]|几[张笔个份]|[这那]些)?(?:张|个|笔|份)?(?:检验单|质检单|单子?|记录)";
    private static final Pattern RESUME = Pattern.compile("(?:请|那就)?(?:回到|回头看|接着看|继续看|再看|重看|切回|返回)(?:一下)?"+SET+"[吧呢。？?]?");
    private static final Pattern CHECK = Pattern.compile("(?:请|帮我)?"+SET+"(?:的)?(?:分别|应该|建议)?(?:先|优先|最值得先)(?:从)?(?:排查|检查|查|看)(?:哪[个道些]|什么)(?:生产)?(?:工序|环节)[呢？?。]?");
    private static final Pattern REJECTED = Pattern.compile("(?:其中|这里面|这些里面)?(?:有)?哪些(?:单子?|质检单|检验单)?(?:是|判定为|判定)?(?:没(?:有)?过|没(?:有)?通过|未通过|不合格)(?:的)?[呢？?。]?|(?:请)?把(?:判定)?不合格的(?:单子?)?(?:挑|筛|列)出来[吧。]?");

    // Scope invariant for unqualified references. Additional dates, IDs, negations
    // or conditions must still go through the semantic planner.
    static Operation explicitFollowup(String query) {
        String text=query.strip();
        if(RESUME.matcher(text).matches())return Operation.SET_RESUME;
        if(CHECK.matcher(text).matches())return Operation.SET_TRIAGE;
        if(REJECTED.matcher(text).matches())return Operation.SET_UNQUALIFIED;
        return null;
    }

    static List<Long> displayed(Map<String,Object> facts) {
        if (!(facts.get("quality") instanceof Map<?,?> quality) || !(quality.get("overview") instanceof Map<?,?> table)
                || !(table.get("rows") instanceof List<?> rows) || rows.size()>500 || Boolean.TRUE.equals(table.get("truncated")))
            return null;
        List<Long> ids=DisplayedWorkOrders.fromFacts(facts,"quality","inspection_no");
        return ids.size()==rows.size()?ids:null;
    }

    static String conclusion(Operation op,List<Map<String,Object>> rows,NaturalBusinessReadMapper mapper) {
        if(rows.isEmpty()) return "在上一轮显示的检验单范围内，本次筛选没有匹配记录。";
        if(op==Operation.SET_RESUME) return "已回到刚才的 "+rows.size()+" 张检验单，表中是重新读取的当前记录。";
        if(op==Operation.SET_UNQUALIFIED) return "按检验判定筛选，上轮清单中有 "+rows.size()+" 张不合格；流程状态单列在表中。";
        if(op==Operation.SET_FAILED_STATUS) return "按流程状态筛选，上轮清单中有 "+rows.size()+" 张标记为失败。";
        var checks=new ArrayList<String>();
        for(var r:rows) {
            long id=Long.parseLong(r.get("record_id").toString());
            var items=mapper.traceItems(id).stream().filter(i->Set.of("FAILED","UNQUALIFIED","NG").contains(Objects.toString(i.get("result"),""))).toList();
            var defects=mapper.traceDefects(id).stream().filter(n->List.of("material_id","batch_no","work_order_id").stream().allMatch(
                k->n.get(k)==null || Objects.toString(n.get(k)).equals(Objects.toString(r.get(k),"")))).toList();
            String item=items.stream().map(i->Objects.toString(i.get("item_name"),"检验项")+"（实测："+Objects.toString(i.get("measured_value"),"未登记")+"）").findFirst().orElse("");
            String defect=defects.stream().map(n->Objects.toString(n.get("nonconforming_no"),"缺陷单")+" "+Objects.toString(n.get("defect_description"),"")).findFirst().orElse("");
            String symptom=String.join("；",java.util.stream.Stream.of(item,defect).filter(s->!s.isBlank()).toList());
            String check;
            if("1".equals(r.get("detail_count_conflict")))
                check="先查质检复判环节，对照原始实测、缺陷单与表头件数，核实是否有正式复判，再定位生产工序。";
            else if(symptom.matches("(?s).*(亮点|暗点|坏点).*"))
                check="先查显示面板点缺陷复测，切换纯黑、纯白画面核实点位，再对比同一台面板装配前后的记录。";
            else if(symptom.matches("(?si).*(HDMI|无信号|接口).*"))
                check="先查信号通路测试，用已知正常的信号源和线缆交叉复测，故障随样机时再查接口板连接。";
            else if(symptom.matches("(?s).*(亮度|背光|漏光|均匀).*"))
                check="先查背光与光学检测，在相同测试画面和测点复测，并与来料和装配后的同项读数对照。";
            else if(symptom.contains("划痕"))
                check="先查外观检验，对照同一件在来料、装配和包装时的照片，以首次出现划痕的位置缩小范围。";
            else if(!item.isEmpty())
                check="先按该失败项目的检验标准复测同一件留样，再比对前后工序记录。";
            else check="先核对检验原始记录与留样，当前证据不足以点名生产工序。";
            checks.add("- "+r.get("inspection_no")+"："+check+(symptom.isBlank()?"":" 依据："+symptom+"。"));
        }
        return "建议逐单从以下环节排查，责任工序仍需复测确认。\n\n"+String.join("\n",checks);
    }
}
