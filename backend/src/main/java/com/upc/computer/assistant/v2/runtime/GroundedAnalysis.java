package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Validates reference provenance, not truth of model prose. No model field can change business facts. */
public final class GroundedAnalysis {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private GroundedAnalysis() {}
    public static Map<String,Object> parse(String role,String text,Set<String> business,Set<String> knowledge) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("agentId",role);out.put("status","INVALID_FORMAT");
        out.put("summary","");for(String key:List.of("findings","hypotheses","actions","missing"))out.put(key,List.of());
        try {
            String source=text.trim();if(source.startsWith("```"))source=source.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
            if(source.length()>12000)throw new IllegalArgumentException();
            JsonNode data=JSON.readTree(source);Set<String> fields=new HashSet<>();data.fieldNames().forEachRemaining(fields::add);
            if(!data.isObject()||!fields.equals(Set.of("summary","findings","hypotheses","actions","missing")))throw new IllegalArgumentException();
            String summary=string(data.get("summary"),600);
            Set<String> allowed=new HashSet<>(business);allowed.addAll(knowledge);boolean dropped=false;
            Map<String,Object> valid=new LinkedHashMap<>();
            for(String kind:List.of("findings","hypotheses","actions")) {
                JsonNode rows=data.get(kind);if(!rows.isArray()||rows.size()>8)throw new IllegalArgumentException();
                List<Map<String,Object>> kept=new ArrayList<>();
                for(JsonNode row:rows) {
                    Set<String> keys=new HashSet<>();row.fieldNames().forEachRemaining(keys::add);
                    if(!row.isObject()||!Set.of("text","evidenceRefs","verification").containsAll(keys))throw new IllegalArgumentException();
                    String statement=string(row.get("text"),1000);JsonNode refs=row.get("evidenceRefs");
                    if(refs==null||!refs.isArray()||refs.size()>8)throw new IllegalArgumentException();
                    List<String> ids=new ArrayList<>();for(JsonNode ref:refs)ids.add(string(ref,200));
                    boolean acceptable=!statement.isBlank()&&!ids.isEmpty()&&allowed.containsAll(ids)
                        &&(!kind.equals("findings")||role.equals("KNOWLEDGE")&&ids.stream().anyMatch(knowledge::contains)||ids.stream().anyMatch(business::contains));
                    String verification=row.has("verification")?string(row.get("verification"),1000):"";
                    if(kind.equals("hypotheses")&&verification.isBlank())acceptable=false;
                    if(!acceptable){dropped=true;continue;}
                    var item=new LinkedHashMap<String,Object>();item.put("text",statement);item.put("evidenceRefs",ids.stream().distinct().toList());
                    if(!verification.isBlank())item.put("verification",verification);kept.add(item);
                }
                valid.put(kind,kept);
            }
            JsonNode gaps=data.get("missing");if(!gaps.isArray()||gaps.size()>10)throw new IllegalArgumentException();
            List<String> missing=new ArrayList<>();for(JsonNode gap:gaps)missing.add(string(gap,500));
            if(dropped)missing.add("部分模型结论引用缺失或不属于本次证据，已隐藏；引用校验不代表原因已证实。");
            out.putAll(valid);out.put("summary",dropped?"部分分析缺少有效引用，请以保留的证据与待核查项为准。":summary);
            out.put("missing",missing);out.put("status",dropped?"PARTIAL_INVALID_REFERENCES":"VALIDATED_REFERENCES");
        } catch(Exception invalid) {out.put("missing",List.of("模型未返回有效的结构化分析，仅展示已取得的业务证据。"));}
        return out;
    }
    private static String string(JsonNode value,int max) {
        if(value==null||!value.isTextual()||value.asText().length()>max)throw new IllegalArgumentException();return value.asText();
    }
}
