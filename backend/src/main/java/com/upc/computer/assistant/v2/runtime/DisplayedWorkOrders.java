package com.upc.computer.assistant.v2.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class DisplayedWorkOrders {
    private static final Pattern QUALITY = Pattern.compile(
        "(?:(?:请|帮我|那|再|想|我想|查一下|查查|看看|看一下|查询)\\s*)*(?:这批|这一批|这些|这几张|这几笔|这张|这个|该|上述|上面|刚才|上一轮|前面)(?:的|提到的|显示的|列出的|这几张|那几张)?(?:生产)?(?:工单|订单)(?:的)?(?:质检|检验|质量)(?:记录|结果|情况|表现)?(?:分别)?(?:如何|怎么样|怎样|是什么|呢|有异常吗|有没有异常)?[？?。！!]*");

    static boolean qualityFollowup(String query) { return QUALITY.matcher(query.strip()).matches(); }

    static List<Long> fromFacts(Map<String,Object> facts) {
        return fromFacts(facts,"production","work_order_no");
    }

    static List<Long> fromFacts(Map<String,Object> facts,String domain,String numberColumn) {
        if(!(facts.get(domain) instanceof Map<?,?> production)
                || !(production.get("overview") instanceof Map<?,?> overview)
                || !(overview.get("rows") instanceof List<?> rows) || rows.size()>500 || Boolean.TRUE.equals(overview.get("truncated")))return List.of();
        var ids=new LinkedHashSet<Long>();
        for(Object row:rows){
            if(!(row instanceof Map<?,?> fields)||fields.get(numberColumn)==null)return List.of();
            try {ids.add(Long.parseLong(String.valueOf(fields.get("record_id"))));}
            catch(NumberFormatException invalid){return List.of();}
        }
        return List.copyOf(ids);
    }

    static List<Long> storedIds(Map<String,Object> context,String key) {
        if(context!=null && context.get("scope") instanceof Map<?,?> scope && Boolean.TRUE.equals(scope.get("confirmed"))
                && context.get(key) instanceof List<?> values && values.size()<=500)
            return values.stream().map(value->Long.valueOf(value.toString())).toList();
        return List.of();
    }

    static List<Long> previous(Map<String,Object> context) {
        if(context==null||!(context.get("scope") instanceof Map<?,?> scope)||!Boolean.TRUE.equals(scope.get("confirmed")))return List.of();
        if(context.containsKey("displayedWorkOrders"))return storedIds(context,"displayedWorkOrders");
        // Older signed contexts may only contain the resolved work order.
        if(scope.get("workOrderNo")!=null && scope.get("workOrderPk")!=null)
            return List.of(Long.valueOf(scope.get("workOrderPk").toString()));
        return List.of();
    }
}
