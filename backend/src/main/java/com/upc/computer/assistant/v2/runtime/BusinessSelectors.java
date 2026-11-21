package com.upc.computer.assistant.v2.runtime;

import java.util.*;
import java.util.regex.Pattern;

/** Grounded, exact selectors. Neither model text nor column names can become SQL. */
public record BusinessSelectors(Map<String,String> values,String remainingText) {
    private static final Set<String> STOCK=Set.of("INVENTORY","STOCK_MOVEMENT");
    private static final Pattern FOLLOWUP=Pattern.compile("其中|这些|上述|刚才|刚刚|上面|同一范围");
    public BusinessSelectors { values=Map.copyOf(values); }
    public static BusinessSelectors parse(String query,List<String> intents,Map<String,Object> previous) {
        Map<String,String> values=new LinkedHashMap<>();
        if(previous!=null&&FOLLOWUP.matcher(query).find()&&previous.get("scope") instanceof Map<?,?> scope
            &&Boolean.TRUE.equals(scope.get("confirmed"))&&scope.get("selectors") instanceof Map<?,?> prior) {
            prior.forEach((key,value)->{if(key instanceof String k&&value instanceof String v)values.put(k,v);});
        }
        String text=query;SortedSet<String> customers=new TreeSet<>();
        // Strip customer literals first so names cannot become warehouse/date/negative filters.
        for(String expression:List.of(
            "客户(?:名称|名)?\\s*[:：为是]?\\s*([“\"「‘][^”\"」’]{1,100}[”\"」’](?:\\s*(?:和|或|以及|与|及|、|,|，)\\s*(?:客户(?:名称|名)?\\s*[:：为是]?\\s*)?[“\"「‘][^”\"」’]{1,100}[”\"」’])*)",
            "客户(?:名称|名)?\\s*[:：为是]?\\s*([^\\s，,；;“\"「」”‘’]{1,100}?)(?=的(?:待审核|已审核|已作废|已发货|所有|全部)?订单|[，,；;]|$)")) {
            var matcher=Pattern.compile(expression).matcher(text);StringBuffer rest=new StringBuffer();
            while(matcher.find()) {
                String prefix=text.substring(0,matcher.start()),suffix=text.substring(matcher.end());
                if(Pattern.compile("(?:非|不只|不仅|不查|不看|不含|不要|不包括|排除|剔除|去掉|除去|不在|除了|不属于|除)\\s*$").matcher(prefix).find()
                    ||Pattern.compile("^\\s*(?:的)?(?:订单)?(?:除外|之外|以外)").matcher(suffix).find())throw new IllegalArgumentException("Customer exclusion needs clarification");
                String value=matcher.group(1).trim();
                if(value.matches("^[“\"「‘].*")&&Pattern.compile("^\\s*(?:(?:和|或|以及|与|及|、)\\s*(?!客户)(?![“\"「‘])[^\\s，,；;]+|(?:,|，)\\s*(?!客户)(?![“\"「‘])[^\\s，,；;]+(?:公司|订单))").matcher(suffix).find())
                    throw new IllegalArgumentException("Mixed customer list needs clarification");
                var quoted=Pattern.compile("[“\"「‘]([^”\"」’]{1,100})[”\"」’]").matcher(value);boolean found=false;
                while(quoted.find()){customers.add(quoted.group(1).trim());found=true;}
                if(!found) {
                    if(Pattern.compile("(?:和|或|以及|与|及|、)客户").matcher(value).find())throw new IllegalArgumentException("Quote multiple customer names explicitly");
                    customers.add(value);
                }
                if(customers.size()>8)throw new IllegalArgumentException("At most eight customers");
                matcher.appendReplacement(rest,"");
            }
            matcher.appendTail(rest);text=rest.toString();
        }
        if(!customers.isEmpty()) {
            values.remove("customer_name");values.remove("customer_names");
            if(customers.size()==1)values.put("customer_name",customers.first());
            else try{values.put("customer_names",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(customers));}
            catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid customers");}
        }
        String code="[A-Za-z0-9][A-Za-z0-9_-]{1,63}";
        String codes=code+"(?:\\s*(?:和|或|以及|、|,|，)\\s*"+code+")*";
        String negativeWords="排除|不含|不要|除去|不在|不包括|不看|剔除";
        for(String[] pair:List.of(new String[]{"仓库|warehouse","库位|location"},new String[]{"库位|location","仓库|warehouse"}))
            if(Pattern.compile("(?:"+negativeWords+")\\s*(?:"+pair[0]+")[^，,；;]*?(?:和|或|以及|、|的)\\s*(?:"+pair[1]+")",Pattern.CASE_INSENSITIVE).matcher(text).find())
                throw new IllegalArgumentException("Separate exclusions for different dimensions explicitly");
        Map<String,SortedSet<String>> extracted=new LinkedHashMap<>();
        for(String dimension:List.of("warehouse","location")) {
            String label=dimension.equals("warehouse")?"(?:仓库(?:编码|编号)?|warehouse)":"(?:库位(?:编码|编号)?|location)";
            for(String expression:List.of("("+negativeWords+")?\\s*"+label+"\\s*[:：为是]?\\s*("+codes+")",
                    "("+negativeWords+")?\\s*(?<![A-Za-z0-9_-])("+codes+")\\s*"+label)) {
                var matcher=Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(text);StringBuffer rest=new StringBuffer();
                int previousEnd=0;boolean negative=false;
                while(matcher.find()) {
                    negative=matcher.group(1)!=null||(negative&&text.substring(previousEnd,matcher.start()).matches("\\s*(?:和|或|以及|、|,|，)\\s*"));
                    String key=(negative?"excluded_":"")+dimension+"_codes";
                    var selected=extracted.computeIfAbsent(key,k->new TreeSet<>());
                    selected.addAll(Arrays.asList(matcher.group(2).split("\\s*(?:和|或|以及|、|,|，)\\s*")));
                    if(selected.size()>8)throw new IllegalArgumentException("At most eight codes per selector");
                    matcher.appendReplacement(rest,"");previousEnd=matcher.end();
                }
                matcher.appendTail(rest);text=rest.toString();
            }
        }
        for(var entry:extracted.entrySet()) {
            String plural=entry.getKey(),single=plural.substring(0,plural.length()-1);
            values.remove(single);values.remove(plural);
            if(!plural.startsWith("excluded_")&&entry.getValue().size()==1)values.put(single,entry.getValue().first());
            else values.put(plural,String.join(",",entry.getValue()));
        }
        if(Pattern.compile("仓库|库位|客户|warehouse|location|(?<![A-Za-z0-9_-])WH-[A-Za-z0-9_-]+",Pattern.CASE_INSENSITIVE).matcher(text).find())
            throw new IllegalArgumentException("Unresolved selector");
        if(!extracted.isEmpty()&&Pattern.compile("除外|之外|除了|非|不只|不仅|不查|不看|不含|不要|不包括|排除|剔除|去掉|除去|不在").matcher(text).find())
            throw new IllegalArgumentException("Unresolved exclusion");
        for(String domain:intents)validate(domain,values);
        return new BusinessSelectors(values,text);
    }
    public static void validate(String domain,Map<String,String> values) {
        if(values==null)throw new IllegalArgumentException("Missing selector object");
        for(var entry:values.entrySet()) {
            String key=entry.getKey(),value=entry.getValue();
            if(value==null||value.isBlank()||value.length()>(key.equals("customer_names")?2048:520)||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid selector");
            if(Set.of("warehouse_code","location_code","warehouse_codes","location_codes","excluded_warehouse_codes","excluded_location_codes").contains(key)&&STOCK.contains(domain)) {
                String[] codes=value.split(",",-1);
                if(codes.length>8||(!key.endsWith("codes")&&codes.length!=1)||new HashSet<>(Arrays.asList(codes)).size()!=codes.length)
                    throw new IllegalArgumentException("Invalid code list");
                for(String code:codes)if(!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,63}"))throw new IllegalArgumentException("Invalid code");
                if(key.endsWith("_code")&&values.containsKey(key+"s"))throw new IllegalArgumentException("Conflicting selector forms");
            } else if(key.equals("customer_names")&&domain.equals("ORDER")) {
                customerNames(value);if(values.containsKey("customer_name"))throw new IllegalArgumentException("Conflicting customer selectors");
            } else if(!(key.equals("customer_name")&&domain.equals("ORDER")&&value.length()<=100))throw new IllegalArgumentException("Unsupported selector domain");
        }
    }
    /** Fixed MyBatis foreach source; values remain individual bound parameters, never SQL fragments. */
    public static List<String> customerNames(String encoded) {
        try {
            if(encoded==null||encoded.length()>2048)throw new IllegalArgumentException("Invalid customers");
            var array=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(encoded);
            if(!array.isArray()||array.size()<2||array.size()>8)throw new IllegalArgumentException("Invalid customers");
            List<String> names=new ArrayList<>();
            for(var item:array) {
                if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>100||item.asText().chars().anyMatch(Character::isISOControl)||names.contains(item.asText()))throw new IllegalArgumentException("Invalid customer");
                names.add(item.asText());
            }
            return List.copyOf(names);
        }catch(java.io.IOException bad){throw new IllegalArgumentException("Invalid customers");}
    }
}
