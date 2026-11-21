package com.upc.computer.assistant.v2.runtime;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Server-derived grants only; client selectors can narrow, never replace, these predicates. */
public record AssistantReadPolicy(long owner,String role,String customer,Long operator,Set<String> domains,String fingerprint) {
    private static final Map<String,Set<String>> MENUS=Map.ofEntries(
        Map.entry("ORDER",Set.of("order:list","order:track","order:audit")),
        Map.entry("PRODUCTION",Set.of("production:workOrder","production:progress","production:myDispatch")),
        Map.entry("DISPATCH",Set.of("production:dispatch","production:myDispatch")),
        Map.entry("INVENTORY",Set.of("warehouse:inventory")),
        Map.entry("STOCK_MOVEMENT",Set.of("warehouse:flow")),
        Map.entry("QUALITY",Set.of("quality:inspection","quality:records")),
        Map.entry("EQUIPMENT",Set.of("device:equipment","device:status")),
        Map.entry("MAINTENANCE",Set.of("device:maintenance","device:records")),
        Map.entry("ALARMS",Set.of("device:alarm")),
        Map.entry("AFTERSALES",Set.of("aftersale:work-order","aftersale:workbench")));
    public static AssistantReadPolicy admin() {return of(1,"ADMIN",null,List.of());}
    public static AssistantReadPolicy load(WorkbenchIdentityReadMapper mapper,long owner) {
        var identity=mapper.identity(owner);
        if(owner<=0||identity==null||!(identity.get("role_code") instanceof String code)||code.isBlank())throw denied();
        return of(owner,String.valueOf(identity.get("role_code")),(String)identity.get("customer_name"),mapper.menuCodes(owner));
    }
    public static AssistantReadPolicy of(long owner,String role,String customer,List<String> menus) {
        var grants=new TreeSet<String>();grants.add("KNOWLEDGE");
        if("ADMIN".equals(role)){grants.addAll(MENUS.keySet());grants.add("DELIVERY");}
        else if("CUSTOMER".equals(role)) {
            if(customer==null||customer.isBlank())throw denied();
            customer=customer.trim();
            if(menus.contains("customer:orders"))grants.add("ORDER");
            if(menus.contains("customer:feedbackList"))grants.add("AFTERSALES");
        } else {
            for(var entry:MENUS.entrySet())if(menus.stream().anyMatch(entry.getValue()::contains))grants.add(entry.getKey());
            if("OPERATOR".equals(role))grants.retainAll(Set.of("KNOWLEDGE","PRODUCTION","DISPATCH","ALARMS"));
        }
        if(!"CUSTOMER".equals(role))customer=null;
        Long operator="OPERATOR".equals(role)?owner:null;
        String signature;
        try {signature=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((owner+"\n"+role+"\n"+customer+"\n"+operator+"\n"+grants).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
        return new AssistantReadPolicy(owner,role,customer,operator,Set.copyOf(grants),signature);
    }
    public boolean adminRole(){return "ADMIN".equals(role);}
    public boolean allows(String domain){return domains.contains(domain);}
    public void require(String domain){if(!allows(domain))throw denied();}
    public Map<String,Object> publicCapabilities(){return Map.of("domains",domains.stream().sorted().toList(),"scope",customer!=null?"OWN_CUSTOMER":operator!=null?"OWN_DISPATCH":"GRANTED_DOMAINS");}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"BUSINESS_PERMISSION_REQUIRED");}
}
