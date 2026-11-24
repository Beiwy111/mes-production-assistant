package com.upc.computer.assistant.v2.runtime;

import java.util.*;
import java.util.regex.Pattern;

/** Identity of the complete table shown in one verified answer. Never accept row IDs from the planner. */
record BusinessSelection(String domain, List<Long> ids) {
    static final Set<String> DOMAINS=Set.of("ORDER","INVENTORY","PRODUCTION");
    static final Set<String> INTENTS=Set.of("ROWS_RESUME","ROWS_FILTER","ROWS_DISPATCH","ROWS_MATERIAL");
    private static final Pattern REFERENCE=Pattern.compile("这几|那几|这些|那些|这批|那批|其中|上面|上述|刚才|前面|上一轮");

    static BusinessSelection displayed(Map<String,Object> facts) {
        BusinessSelection found=null;
        for(String domain:DOMAINS){
            if(!(facts.get(domain.toLowerCase(Locale.ROOT)) instanceof Map<?,?> f)
                    || !(f.get("overview") instanceof Map<?,?> o) || !(o.get("rows") instanceof List<?> rows))continue;
            if(found!=null || rows.size()>500 || !(o.get("totalRecords") instanceof Number count) || count.longValue()!=rows.size() || !Boolean.FALSE.equals(o.get("truncated")))return null;
            var ids=new LinkedHashSet<Long>();
            for(Object raw:rows){
                if(!(raw instanceof Map<?,?> row)||row.get("record_id")==null)return null;
                if(!ids.add(Long.valueOf(row.get("record_id").toString())))return null;
            }
            found=new BusinessSelection(domain,List.copyOf(ids));
        }
        return found;
    }
    Map<String,Object> stored(){return Map.of("domain",domain,"ids",ids);}
    static BusinessSelection previous(Map<String,Object> previous){
        if(previous==null || !(previous.get("scope") instanceof Map<?,?> s) || !Boolean.TRUE.equals(s.get("confirmed"))
                || !(previous.get("businessSelection") instanceof Map<?,?> m) || !(m.get("domain") instanceof String domain)
                || !DOMAINS.contains(domain) || !(m.get("ids") instanceof List<?> ids) || ids.size()>500)return null;
        return new BusinessSelection(domain,ids.stream().map(i->Long.valueOf(i.toString())).toList());
    }
    static boolean refers(String q){return REFERENCE.matcher(q).find();}
    static boolean dispatch(String q){return Pattern.compile("派给|派工|分派|分配给|谁.{0,4}(负责|在做|接手|接单)|由谁|谁来做").matcher(q).find();}
    static boolean material(String q){return Pattern.compile("缺.{0,8}(料|物资)|缺料|物料.{0,6}(缺|齐|够)|齐套|备料").matcher(q).find();}
    static boolean resume(String q){return q.matches("(?s).*(?:回到|接着看|回头看|再看|继续看|重新列出).*(?:刚才|这几|那几|这批|那批|之前|前面|上一轮).*[单料存行项记录][呢吧。？?]*");}
}
