package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Read-only projection of locally reviewed, plant-wide SOPs; no model-supplied scope or filesystem path. */
final class ApprovedSopStore {
    private static final Path ACTIVE=Path.of("backend/assistant/rag/data/approved-sop/active.json");
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Match(int score, Map<String,Object> hit) {}

    static Map<String,Object> inspect(String query,AssistantReadPolicy policy,LocalDate asOf) {
        return inspect(ACTIVE,query,policy,asOf);
    }

    static Map<String,Object> inspect(Path active,String query,AssistantReadPolicy policy,LocalDate asOf) {
        if(policy==null || policy.customer()!=null || "CUSTOMER".equals(policy.role()))
            return result("NOT_AUTHORIZED",List.of());
        try {
            if(Files.notExists(active))return result("NO_MATCHES",List.of());
            if(!Files.isRegularFile(active,LinkOption.NOFOLLOW_LINKS)||Files.size(active)>12*1024*1024)
                return result("UNAVAILABLE",List.of());
            JsonNode index=JSON.readTree(Files.readAllBytes(active));
            if(index.path("schema").asInt()!=1 || !index.path("documents").isObject())
                return result("UNAVAILABLE",List.of());
            String q=query.toLowerCase(Locale.ROOT);
            var matches=new ArrayList<Match>();
            for(JsonNode doc:index.path("documents")){
                if(!doc.path("allow_model_processing").asBoolean(false)
                    || !"FACTORY_WIDE".equals(doc.path("scope").asText())
                    || !"APPROVED".equals(doc.path("status").asText())
                    || !policy.allows(doc.path("domain").asText())
                    || !(policy.adminRole() || contains(doc.path("allowed_roles"),policy.role()))
                    || asOf.isBefore(LocalDate.parse(doc.path("effective_from").asText()))
                    || (doc.hasNonNull("valid_to") && asOf.isAfter(LocalDate.parse(doc.path("valid_to").asText()))))continue;
                String id=doc.path("document_id").asText();
                String title=doc.path("title").asText(),version=doc.path("version").asText();
                int score=0;
                if(!id.isBlank() && q.contains(id.toLowerCase(Locale.ROOT)))score+=6;
                if(!title.isBlank() && q.contains(title.toLowerCase(Locale.ROOT)))score+=6;
                var terms=new ArrayList<String>();
                for(JsonNode word:doc.path("keywords")){
                    String term=word.asText().toLowerCase(Locale.ROOT);
                    if(term.length()>=2 && q.contains(term)){score+=2;terms.add(term);}
                }
                if(score==0)continue;
                for(JsonNode section:doc.path("sections")){
                    String text=section.path("text").asText();
                    int rank=score;
                    for(String term:terms)if(text.toLowerCase(Locale.ROOT).contains(term))rank+=2;
                    matches.add(new Match(rank,Map.of("document_id",id,"version",version,"title",title,
                        "evidence_ref",section.path("evidence_ref").asText(),"text",text,
                        "section",section.path("location").asText(),"scope","FACTORY_WIDE",
                        "effective_from",doc.path("effective_from").asText(),"authority_type","APPROVED_ENTERPRISE_SOP")));
                }
            }
            matches.sort(Comparator.comparingInt(Match::score).reversed());
            var hits=matches.stream().limit(4).map(Match::hit).toList();
            return result(hits.isEmpty()?"NO_MATCHES":"MATCHES",hits);
        } catch(Exception invalid){return result("UNAVAILABLE",List.of());}
    }
    private static boolean contains(JsonNode values,String value){
        for(JsonNode v:values)if(v.isTextual()&&v.textValue().equals(value))return true;
        return false;
    }
    private static Map<String,Object> result(String status,List<?> hits){
        return Map.of("status",status,"hits",hits,"authority_type","APPROVED_ENTERPRISE_SOP");
    }
}
