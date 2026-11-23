package com.upc.computer.assistant.v2.runtime;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;

/** Explicit phrases only. Unknown values never become zero, completed, or a confirmed shortage. */
public final class BusinessQueryFilter {
    private BusinessQueryFilter() {}
    public static String supportedTextRemoved(List<String> intents,String query) {
        String out=query.replace("全部状态","").replace("所有状态","").replace("不限状态","").replace("不筛选状态","");
        if(intents.contains("INVENTORY"))out=out.replace("库存不足","").replace("缺货","");
        if(intents.contains("PRODUCTION"))out=out.replace("未完成","");
        if(intents.contains("QUALITY")&&!criterion("QUALITY",query).equals("ALL"))out=out.replace("不合格","");
        if(intents.contains("EQUIPMENT")&&!criterion("EQUIPMENT",query).equals("ALL"))out=out.replace("异常","");
        return out;
    }
    private static Map<String,String> statuses(String domain) {
        return switch(domain) {
            case "ORDER" -> Map.of("待审核|待处理|未审核|等(?:待)?审核","PENDING","已审核","APPROVED","已作废","REJECTED","已发货","SHIPPED");
            case "PRODUCTION" -> Map.of("未完成","DRAFT,QC_PENDING,CREATED,PENDING,RELEASED,DISPATCHED,RUNNING,PRODUCING,PAUSED", "已完成","COMPLETED", "待质检","QC_PENDING", "已派工","DISPATCHED", "正在生产|生产中|执行中","RUNNING,PRODUCING");
            default -> Map.of();
        };
    }
    public static boolean ambiguous(String domain,String query) {
        if(!Set.of("ORDER","PRODUCTION").contains(domain))return false;
        if(java.util.regex.Pattern.compile("除外|之外|非已|非待|不查询|不只|不仅").matcher(query).find())return true;
        if(java.util.regex.Pattern.compile("已取消|已关闭|已报废|已驳回").matcher(query).find())return true;
        // A single row cannot be simultaneously in two distinct states; require OR semantics.
        return java.util.regex.Pattern.compile("同时.*(?:已审核|待审核|已完成|未完成).*且|既.*又").matcher(query).find();
    }
    private static String statusCriterion(String domain,String query) {
        if(domain.equals("PRODUCTION"))query=query.replaceAll("(?:还|尚)?(?:没(?:有)?(?:做完|完成|完工|做完工)|未(?:完工|做完)|尚未完成)","未完成");
        if(domain.equals("ORDER"))query=query.replaceAll("(?:还)?没(?:有)?(?:审(?:核)?|过审)(?:批)?","待审核");
        var aliases=statuses(domain);if(aliases.isEmpty())return null;
        var all=new TreeSet<String>();aliases.values().forEach(v->all.addAll(Arrays.asList(v.split(","))));
        String expression=String.join("|",aliases.keySet());
        var matcher=java.util.regex.Pattern.compile(expression).matcher(query);
        var included=new TreeSet<String>();var excluded=new TreeSet<String>();
        int previous=0,matches=0;boolean negative=false;String only=null;
        while(matcher.find()) {
            String gap=query.substring(previous,matcher.start());
            if(java.util.regex.Pattern.compile("排除|不要|不含|除了|不看|不包括|剔除").matcher(gap).find())negative=true;
            else if(java.util.regex.Pattern.compile("但包括|但包含|只看|仅看").matcher(gap).find())negative=false;
            String states=aliases.entrySet().stream().filter(e->matcher.group().matches(e.getKey())).findFirst().orElseThrow().getValue();
            (negative?excluded:included).addAll(Arrays.asList(states.split(",")));
            only=matcher.group();matches++;previous=matcher.end();
        }
        if(matches==0)return null;
        if(matches==1&&excluded.isEmpty())return only.equals("未完成")?"UNFINISHED":
            only.matches("正在生产|生产中|执行中")?"RUNNING":included.first();
        var selected=included.isEmpty()?all:included;selected.removeAll(excluded);
        return "SET:"+String.join(",",selected);
    }
    public static String intersect(String domain,String prior,String current) {
        var all=new TreeSet<String>();statuses(domain).values().forEach(v->all.addAll(Arrays.asList(v.split(","))));
        if(all.isEmpty())return current;
        List<Map<String,Object>> rows=all.stream().map(s->Map.<String,Object>of("status",s)).toList();
        return "SET:"+String.join(",",applyCriterion(current,applyCriterion(prior,rows)).stream().map(r->String.valueOf(r.get("status"))).toList());
    }
    public static String criterion(String domain,String query) {
        if(query.matches(".*(?:不筛选状态|不限状态|所有状态|全部状态).*"))return "ALL";
        String states=statusCriterion(domain,query);if(states!=null)return states;
        return switch(domain) {
            case "INVENTORY" -> query.matches(".*(?:没(?:有)?预留|未预留|无预留|预留(?:量)?为零).*")?"NO_RESERVED":query.matches(".*(?:有预留|已预留|预留量大于零).*")?"RESERVED":query.matches(".*(?:库存不足|缺货|无可用库存|没有可用库存).*")?"NO_AVAILABLE":
                query.contains("可用库存")?"AVAILABLE":"ALL";
            case "QUALITY" -> query.matches(".*(?:仅|只|筛选|列出|查询|查找|哪些).*不合格(?!率).*")?"UNQUALIFIED":"ALL";
            case "PRODUCTION" -> query.contains("未完成")?"UNFINISHED":query.contains("已完成")?"COMPLETED":query.contains("待质检")?"QC_PENDING":query.contains("已派工")?"DISPATCHED":query.matches(".*(?:正在生产|生产中|执行中).*")?"RUNNING":"ALL";
            case "ORDER" -> query.matches(".*(?:待审核|待处理|未审核).*")?"PENDING":query.contains("已审核")?"APPROVED":query.contains("已作废")?"REJECTED":query.contains("已发货")?"SHIPPED":"ALL";
            case "EQUIPMENT" -> query.matches(".*(?:异常设备|故障设备).*")?"ABNORMAL":"ALL";
            default -> "ALL";
        };
    }
    public static List<Map<String,Object>> apply(String domain,String query,List<Map<String,Object>> rows) {
        return applyCriterion(criterion(domain,query),rows);
    }
    public static List<Map<String,Object>> applyCriterion(String criterion,List<Map<String,Object>> rows) {
        if(criterion.startsWith("SET:")) {
            if(criterion.equals("SET:"))return List.of();
            var values=new HashSet<>(Arrays.asList(criterion.substring(4).split(",")));
            var allowed=new HashSet<String>();for(String domain:List.of("ORDER","PRODUCTION"))statuses(domain).values().forEach(v->allowed.addAll(Arrays.asList(v.split(","))));
            if(!criterion.equals("SET:")&&!allowed.containsAll(values))throw new IllegalArgumentException("Unknown status set");
            return rows.stream().filter(row->row.get("status")!=null&&values.contains(String.valueOf(row.get("status")))).toList();
        }
        Predicate<Map<String,Object>> accept=switch(criterion) {
            case "NO_RESERVED" -> row->number(row.get("quantity_reserved"))!=null&&number(row.get("quantity_reserved")).signum()==0;
            case "RESERVED" -> row->number(row.get("quantity_reserved"))!=null&&number(row.get("quantity_reserved")).signum()>0;
            case "NO_AVAILABLE" -> row->number(row.get("quantity_available"))!=null&&number(row.get("quantity_available")).signum()<=0;
            case "AVAILABLE" -> row->number(row.get("quantity_available"))!=null&&number(row.get("quantity_available")).signum()>0;
            case "UNQUALIFIED" -> row->Set.of("UNQUALIFIED","FAIL").contains(String.valueOf(row.get("status")));
            case "WORKFLOW_FAILED" -> row->"FAILED".equals(row.get("inspection_status"));
            case "UNFINISHED" -> row->Set.of("DRAFT","QC_PENDING","CREATED","PENDING","RELEASED","DISPATCHED","RUNNING","PRODUCING","PAUSED").contains(String.valueOf(row.get("status")));
            case "RUNNING" -> row->Set.of("RUNNING","PRODUCING").contains(String.valueOf(row.get("status")));
            case "PENDING","APPROVED","REJECTED","SHIPPED","COMPLETED","QC_PENDING","DISPATCHED" -> row->criterion.equals(row.get("status"));
            case "ABNORMAL" -> row->Set.of("FAULT","FAULTY","BROKEN","MAINTENANCE","DOWN","ALARM").contains(String.valueOf(row.get("status")));
            case "ALL" -> row->true;
            default -> throw new IllegalArgumentException("Unknown status criterion");
        };
        return rows.stream().filter(accept).toList();
    }
    private static BigDecimal number(Object value) {try{return value==null?null:new BigDecimal(value.toString());}catch(NumberFormatException ignored){return null;}}
}
