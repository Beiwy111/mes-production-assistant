package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Only return graph paths that still match the live, authorized business joins. */
final class WorkTraceGraph {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static Map<String,Object> read(NaturalLanguageRuntime.Data data,long work){
        if(!data.policy().allows("ORDER")||data.policy().customer()!=null||data.policy().operator()!=null)
            return Map.of("status","NOT_AUTHORIZED","paths",List.of());
        for(String domain:List.of("PRODUCTION","QUALITY","DISPATCH","ORDER"))data.policy().require(domain);
        var live=data.overview().liveTraceLinks(work);
        if(live.isEmpty())return Map.of("status","NO_LIVE_PATH","paths",List.of());
        String order=live.get(0).get("order_id").toString();
        Path receipt=Path.of("backend/assistant/rag/data/business-graphs",order+".json");
        try {
            if(!Files.isRegularFile(receipt))return Map.of("status","NO_SNAPSHOT","paths",List.of());
            byte[] before=Files.readAllBytes(receipt);var doc=JSON.readTree(before);
            if(!"READY".equals(doc.path("status").asText())||!"business_reference".equals(doc.path("domain").asText())||
                !order.equals(doc.path("scope_order_id").asText())||doc.path("synthetic").asBoolean(true))throw new IllegalStateException();
            var generation=doc.path("graph_snapshot_id").asText();
            String query="""
                MATCH path=(o:Order)-[:HAS_ITEM]->(i:OrderItem)-[:PLANNED_AS]->(p:Plan)-[:HAS_WORK_ORDER]->(w:WorkOrder)
                WHERE w.source_id=$work AND o.source_id=$order AND all(n IN nodes(path) WHERE n.domain='business_reference' AND n.graph_release=$release AND n.scope_order_id=$order)
                AND all(r IN relationships(path) WHERE r.graph_release=$release AND r.domain='business_reference' AND r.scope_order_id=$order)
                OPTIONAL MATCH (w)-[r:HAS_INSPECTION]->(q:Inspection)
                WHERE q.graph_release=$release AND q.domain='business_reference' AND q.scope_order_id=$order
                AND r.graph_release=$release AND r.domain='business_reference' AND r.scope_order_id=$order
                RETURN i.source_id,p.source_id,q.source_id ORDER BY q.source_id
                """;
            String auth=System.getenv("MES_GRAPH_USER")+":"+Objects.requireNonNull(System.getenv("MES_GRAPH_PASSWORD"));
            String body=JSON.writeValueAsString(Map.of("statements",List.of(Map.of("statement",query,"parameters",Map.of("work",""+work,"order",order,"release",generation)))));
            var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:17474/db/neo4j/tx/commit")).timeout(Duration.ofSeconds(4))
                .header("Content-Type","application/json").header("Authorization","Basic "+Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=CLIENT.send(req,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||!Arrays.equals(before,Files.readAllBytes(receipt)))throw new IllegalStateException();
            var res=JSON.readTree(response.body());if(!res.path("errors").isArray()||!res.path("errors").isEmpty())throw new IllegalStateException();
            var valid=new HashSet<List<String>>();for(var row:live)valid.add(List.of(row.get("order_item_id").toString(),row.get("plan_id").toString(),Objects.toString(row.get("inspection_id"),"")));
            var paths=new ArrayList<Map<String,String>>();
            var matched=new HashSet<List<String>>();
            boolean stale=false;
            for(var item:res.path("results").path(0).path("data")){
                var r=item.path("row");var tuple=List.of(r.path(0).asText(),r.path(1).asText(),r.path(2).asText(""));
                if(!valid.contains(tuple)){stale=true;continue;}
                if(!matched.add(tuple))continue;
                var path=new LinkedHashMap<>(Map.of("order_id",order,"order_item_id",tuple.get(0),"plan_id",tuple.get(1),"work_order_id",""+work));
                if(!tuple.get(2).isEmpty())path.put("inspection_id",tuple.get(2));
                paths.add(path);
            }
            return Map.of("status",!stale&&matched.equals(valid)?"VERIFIED":"PARTIAL","release",generation,"paths",paths);
        }catch(InterruptedException ex){Thread.currentThread().interrupt();}catch(Exception unavailable){/* Graph is supplementary. */}
        return Map.of("status","UNAVAILABLE","paths",List.of());
    }
}
