package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.tools.delivery.DeliveryQuantityCalculator;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Recorded shipment risk only; does not promise dates, allocate stock or infer production capacity. */
public final class CrossOrderRiskAnalysis {
    public static List<Map<String,Object>> summarize(List<Map<String,Object>> orders,List<Map<String,Object>> lines,List<Map<String,Object>> deliveries,LocalDate today) {
        var result=new ArrayList<Map<String,Object>>();
        for(var order:orders) {
            String id=String.valueOf(order.get("record_id"));
            var own=lines.stream().filter(l->id.equals(String.valueOf(l.get("order_id")))).toList();
            var shipped=deliveries.stream().filter(l->id.equals(String.valueOf(l.get("order_id")))).toList();
            if(own.isEmpty()) {var row=base(order);row.put("risk_status","UNKNOWN");row.put("risk_reason","缺少订单行，不能确定剩余交付量");result.add(row);continue;}
            var fulfillment=new DeliveryQuantityCalculator().fulfillment(normalize(own),normalize(shipped),true);
            for(int i=0;i<fulfillment.size();i++) {
                var row=base(order);var source=own.get(i);var quantity=fulfillment.get(i);
                for(String key:List.of("orderItemPk","materialId","unit","orderedQty","recordedShippedQty","remainingDeliveryQty","mappingStatus"))row.put(key,quantity.get(key));
                row.put("product_name",source.get("product_name"));
                Object due=source.get("delivery_date");if(due==null)due=order.get("required_delivery_date");
                row.put("due_date",due==null?null:String.valueOf(due));
                LocalDate date=null;try{if(due!=null)date=LocalDate.parse(String.valueOf(due).substring(0,10));}catch(RuntimeException ignored){}
                var remaining=quantity.get("remainingDeliveryQty");String status,reason;
                if("CANCELLED".equals(String.valueOf(source.get("item_status")))||"CANCELLED".equals(String.valueOf(order.get("status")))){status="EXCLUDED";reason="已取消订单行，不计入待交付";}
                else if(remaining==null){status="UNKNOWN";reason="发货归属或数量不完整，无法确认剩余量";}
                else if(new BigDecimal(remaining.toString()).signum()==0){status="RECORDED_FULFILLED";reason="记录发货数量已覆盖订购数量；不等于客户验收完成";}
                else if(date==null){status="UNKNOWN";reason="缺少有效要求交付日期";}
                else if(date.isBefore(today)){status="OVERDUE";reason="要求日期已过且仍有记录待交付数量";row.put("overdue_days",ChronoUnit.DAYS.between(date,today));}
                else if(Set.of("PENDING","REJECTED").contains(String.valueOf(order.get("status")))){status="AUDIT_BLOCKED";reason="订单尚未审核通过，存在交付前置阻塞";}
                else {status="PENDING_DELIVERY";reason="尚有待交付数量；产能、物料与承诺交期需另行核实";}
                row.put("risk_status",status);row.put("risk_reason",reason);result.add(row);
            }
        }
        return result;
    }
    private static Map<String,Object> base(Map<String,Object> order){var row=new LinkedHashMap<String,Object>();row.put("order_no",order.get("order_no"));row.put("order_id",order.get("record_id"));row.put("status",order.get("status"));return row;}
    private static List<Map<String,String>> normalize(List<Map<String,Object>> rows){return rows.stream().map(row->{Map<String,String> out=new LinkedHashMap<>();row.forEach((k,v)->{if(v!=null)out.put(k,String.valueOf(v));});return out;}).toList();}
}
