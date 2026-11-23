package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-side recipe for rerunning a read. Carries predicates and bindings, never result rows. */
record ReadReplay(String query,JsonNode plan,Map<String,Object> prior, String referenceDay,JsonNode scope) {
    private static final ObjectMapper JSON=new ObjectMapper();

    static ReadReplay capture(String query,JsonNode input,Map<String,Object> previous,LocalDate day,Map<String,Object> scope) {
        var origin=new LinkedHashMap<String,Object>();
        if(previous!=null) for(String key:List.of("userQuery","plan","scope","businessSelection","displayedWorkOrders","displayedOrders","displayedInspections","awaitingOrderInput"))
            if(previous.containsKey(key))origin.put(key,previous.get(key));
        return new ReadReplay(query,input.deepCopy(),origin,day.toString(),binding(scope));
    }

    static ReadReplay load(Object saved) {
        if(saved==null)return null;
        ReadReplay recipe=JSON.convertValue(saved, ReadReplay.class);
        if(recipe.plan()==null || !recipe.plan().path("intents").isArray() || recipe.plan().path("intents").isEmpty())return null;
        for(JsonNode intent:recipe.plan().path("intents"))if("RECAP".equals(intent.asText()))return null;
        return recipe;
    }

    static JsonNode binding(Map<String,Object> source) {
        var values=new LinkedHashMap<>(source);
        values.remove("scopeRef");
        values.remove("expiresAt");
        for(String key:List.of("qualityWorkOrders","qualityOrders"))if(List.of().equals(values.get(key)))values.remove(key);
        return JSON.valueToTree(values);
    }
}
