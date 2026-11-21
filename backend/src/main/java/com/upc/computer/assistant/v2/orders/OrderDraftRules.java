package com.upc.computer.assistant.v2.orders;

import com.upc.computer.common.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Deterministic validation; model output is an untrusted field patch, never an instruction. */
public final class OrderDraftRules {
    private OrderDraftRules() {}
    public static final Set<String> FIELDS=Set.of("materialId","productQuery","quantity","requiredDeliveryDate","receiverName","receiverPhone","receiverAddress","customerName","remark");
    private static final Map<String,String> REQUIRED=new LinkedHashMap<>();
    static {
        REQUIRED.put("materialId","产品型号"); REQUIRED.put("quantity","订购数量");
        REQUIRED.put("requiredDeliveryDate","要求交付日期"); REQUIRED.put("customerName","客户名称");
        REQUIRED.put("receiverName","收货人"); REQUIRED.put("receiverPhone","联系电话"); REQUIRED.put("receiverAddress","收货地址");
    }
    public static void merge(Map<String,Object> target, Map<String,Object> patch,List<String> clear,boolean customer,LocalDate today) {
        if(patch==null||clear==null||patch.size()>FIELDS.size()||clear.size()>FIELDS.size())throw invalid("订单字段格式错误");
        Map<String,Object> next=new LinkedHashMap<>(target);
        for(String key:clear) {
            if(!FIELDS.contains(key)||customer&&key.equals("customerName"))throw invalid("不能清除该字段");
            next.remove(key);
            if(key.equals("productQuery"))next.remove("materialId");
        }
        // Resolve product-query changes before an explicit product selection, independent of JSON key order.
        var entries=new ArrayList<>(patch.entrySet());
        entries.sort(Comparator.comparingInt(e->e.getKey().equals("productQuery")?0:1));
        for(var entry:entries) {
            String key=entry.getKey();Object value=entry.getValue();
            if(!FIELDS.contains(key)||value==null||!(value instanceof String||value instanceof Number))throw invalid("订单字段格式错误");
            String text=value.toString().trim();
            if(customer&&key.equals("customerName")) {
                if(!text.equals(Objects.toString(target.get(key),"")))throw invalid("客户名称由当前账号确定，不能代其他客户下单");
                continue;
            }
            if(text.isEmpty()){next.remove(key);continue;}
            int max=switch(key){case "receiverAddress","remark"->500;case "receiverName"->80;case "receiverPhone"->30;default->120;};
            if(text.length()>max||text.chars().anyMatch(c->c<32&&c!='\n'))throw invalid("字段内容过长或含无效字符："+key);
            switch(key) {
                case "quantity" -> {
                    try {
                        BigDecimal n=new BigDecimal(text);
                        if(n.signum()<=0||n.compareTo(new BigDecimal("1000000"))>0||n.stripTrailingZeros().scale()>0)throw new IllegalArgumentException();
                        text=n.toBigIntegerExact().toString();
                    }catch(Exception ex){throw invalid("显示器数量须为1至1000000的整数");}
                }
                case "materialId" -> {if(!text.matches("[1-9][0-9]{0,17}"))throw invalid("请选择有效产品");}
                case "requiredDeliveryDate" -> {
                    try {LocalDate date=LocalDate.parse(text);if(date.isBefore(today))throw new IllegalArgumentException();}
                    catch(Exception ex){throw invalid("请填写今天或之后的明确交付日期（YYYY-MM-DD）");}
                }
                case "receiverPhone" -> {if(!text.matches("[+0-9()（） -]{6,30}"))throw invalid("请填写有效联系电话");}
                case "productQuery" -> next.remove("materialId");
                default -> { }
            }
            next.put(key,text);
        }
        target.clear();target.putAll(next);
    }
    public static List<Map<String,String>> missing(Map<String,Object> fields) {
        return REQUIRED.entrySet().stream().filter(e->Objects.toString(fields.get(e.getKey()),"").isBlank())
                .map(e->Map.of("field",e.getKey(),"label",e.getValue())).toList();
    }
    static BusinessException invalid(String message){return new BusinessException(400,message);}
}
