package com.upc.computer.assistant.v2.runtime;

import java.math.*;
import java.time.*;
import java.util.*;

/** Deterministic trends of returned inspection samples, never lot release or factory yield. */
public final class QualityTrendMetrics {
    private QualityTrendMetrics() {}
    public static List<Map<String,Object>> summarize(List<Map<String,Object>> rows,boolean complete) {
        Map<String,Integer> ids=new HashMap<>();
        for(var row:rows) {String id=text(row,"record_id");if(id!=null)ids.merge(id,1,Integer::sum);}
        Map<String,List<Map<String,Object>>> groups=new TreeMap<>();int index=0;
        for(var row:rows) {
            String material=text(row,"material_id"),unit=text(row,"unit");
            String key=day(text(row,"event_at"))+"\u0000"+material+"\u0000"+unit;
            // Unknown products/units are not combined into a fictional common product or unit.
            if(material==null||unit==null)key+="\u0000"+(index++);
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(var group:groups.values()) {
            var first=group.get(0);String date=day(text(first,"event_at"));
            BigDecimal sample=BigDecimal.ZERO,good=BigDecimal.ZERO,bad=BigDecimal.ZERO;
            int valid=0,invalid=0,duplicates=0;
            for(var row:group) {
                String id=text(row,"record_id");boolean duplicate=id!=null&&ids.getOrDefault(id,0)>1;
                if(duplicate)duplicates++;
                BigDecimal s=number(row.get("sample_quantity")),g=number(row.get("qualified_quantity")),b=number(row.get("unqualified_quantity"));
                if(duplicate||id==null||!Set.of("QUALIFIED","UNQUALIFIED","PASS","FAIL").contains(String.valueOf(row.get("status")))
                        ||s==null||g==null||b==null||g.add(b).compareTo(s)!=0) {invalid++;continue;}
                sample=sample.add(s);good=good.add(g);bad=bad.add(b);valid++;
            }
            boolean reliable=complete&&invalid==0&&!date.equals("未提供有效检验日期")&&text(first,"material_id")!=null&&text(first,"unit")!=null;
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("inspection_day",date);item.put("material_id",first.get("material_id"));item.put("material_name",first.get("material_name"));item.put("unit",first.get("unit"));
            item.put("inspection_record_count",group.size());item.put("valid_sample_record_count",valid);item.put("invalid_sample_record_count",invalid);item.put("duplicate_record_count",duplicates);
            item.put("valid_sample_quantity",valid==0?null:decimal(sample));item.put("valid_qualified_quantity",valid==0?null:decimal(good));item.put("valid_unqualified_quantity",valid==0?null:decimal(bad));
            item.put("sample_pass_percent",reliable&&sample.signum()>0?good.multiply(BigDecimal.valueOf(100)).divide(sample,2,RoundingMode.HALF_UP).toPlainString():null);
            item.put("trend_status",reliable?(sample.signum()>0?"样本口径完整":"无有效抽检分母"):"存在未确认或缺失数据");
            item.put("sample_basis","按上海时区检验日期、物料及单位分组；有效抽检样本加权，不代表批次放行、整批良率或缺陷原因。");
            result.add(item);
        }
        return result;
    }
    private static String text(Map<String,Object> row,String key) {Object value=row.get(key);return value==null||value.toString().isBlank()?null:value.toString();}
    private static BigDecimal number(Object value) {try {var n=new BigDecimal(String.valueOf(value));return n.signum()<0?null:n;}catch(NumberFormatException invalid){return null;}}
    private static String decimal(BigDecimal number) {return number.stripTrailingZeros().toPlainString();}
    private static String day(String value) {
        if(value!=null) {
            try {return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();}catch(DateTimeException ignored){}
            try {return LocalDateTime.parse(value.replace(' ','T')).toLocalDate().toString();}catch(DateTimeException ignored){}
            try {return LocalDate.parse(value).toString();}catch(DateTimeException ignored){}
        }
        return "未提供有效检验日期";
    }
}
