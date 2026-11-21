package com.upc.computer.assistant.v2.runtime;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class ResourceRiskFacts {
    static String text(Map<String,Object> row,String key){Object v=row.get(key);return v==null?"":v.toString();}
    static BigDecimal number(Map<String,Object> row,String key){try{var n=new BigDecimal(text(row,key));return n.signum()<0?null:n;}catch(RuntimeException e){return null;}}
    static String decimal(BigDecimal n){return n==null?null:n.stripTrailingZeros().toPlainString();}
    static LocalDate date(Object value){try{return value==null?null:LocalDate.parse(value.toString().substring(0,10));}catch(RuntimeException e){return null;}}
    static LocalDateTime time(Object value){try{return value==null?null:LocalDateTime.parse(value.toString().replace(' ','T'));}catch(RuntimeException e){return null;}}
    static Map<String,Object> row(Object... pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m;}
    static boolean inactive(Map<String,Object> row){return Set.of("CANCELLED","COMPLETED","CLOSED").contains(text(row,"plan_status"))||Set.of("CANCELLED","REJECTED").contains(text(row,"order_status"));}
    static List<String> ids(List<Map<String,Object>> rows,String key){return rows.stream().map(r->text(r,key)).filter(v->!v.isBlank()).distinct().sorted().toList();}
    static Map<String,Object> issue(Map<String,Object> source,String code,String reason){return row("order_id",source.get("order_id"),"order_no",source.get("order_no"),"order_item_id",source.get("order_item_id"),"plan_id",source.get("plan_id"),"plan_item_id",source.get("plan_item_id"),"schedule_id",source.get("schedule_id"),"dispatch_id",source.get("dispatch_id"),"risk_status",code,"risk_reason",reason);}
}
