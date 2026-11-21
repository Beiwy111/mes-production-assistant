package com.upc.computer.assistant.v2.runtime;

import java.util.*;

/** Closed read tools, invoked only inside ReadOnlyNaturalAccess's freshly authorized transaction. */
public final class BusinessToolRegistry {
    public record Definition(String toolName,String description,Set<String> requiredPermissions,
            String readOrWrite,int timeoutSeconds,Map<String,Object> parametersSchema,String resultSchema,String executionHandler) {}
    public record Request(String domain,Long orderId,String keyword,String startInclusive,String endExclusive,Map<String,String> selectors) {
        public Request(String domain,Long orderId,String keyword,String startInclusive,String endExclusive) {this(domain,orderId,keyword,startInclusive,endExclusive,Map.of());}
        public Request { selectors=selectors==null?Map.of():Map.copyOf(selectors); }
    }
    public record Result(long total,List<Map<String,Object>> rows) {}
    private static final Set<String> DOMAINS=Set.of("INVENTORY","ORDER","PRODUCTION","QUALITY","EQUIPMENT","AFTERSALES","MAINTENANCE","ALARMS","STOCK_MOVEMENT");
    private BusinessToolRegistry() {}
    public static List<Definition> catalog() {
        return DOMAINS.stream().sorted().map(domain->new Definition("mes."+domain.toLowerCase(Locale.ROOT)+".query",
            "Read authorized current "+domain+" records; never writes",Set.of("READ_"+domain),"READ",15,
            Map.of("type","object","additionalProperties",false,"properties",Map.of(
                "domain",Map.of("const",domain),"orderId",Map.of("type",List.of("integer","null"),"minimum",1),
                "keyword",Map.of("type",List.of("string","null"),"maxLength",100),
                "startInclusive",Map.of("type",List.of("string","null")),"endExclusive",Map.of("type",List.of("string","null")),"selectors",Map.of("type","object","additionalProperties",false,"properties",selectorProperties(domain)))),
            "{total: integer, rows: object[]}","NaturalBusinessReadMapper.rows/count")).toList();
    }
    private static Map<String,Object> selectorProperties(String domain) {
        if(Set.of("INVENTORY","STOCK_MOVEMENT").contains(domain))return Map.of(
            "warehouse_code",Map.of("type","string","minLength",2,"maxLength",64),"location_code",Map.of("type","string","minLength",2,"maxLength",64),
            "warehouse_codes",Map.of("type","string","maxLength",520),"location_codes",Map.of("type","string","maxLength",520),
            "excluded_warehouse_codes",Map.of("type","string","maxLength",520),"excluded_location_codes",Map.of("type","string","maxLength",520));
        if(domain.equals("ORDER"))return Map.of("customer_name",Map.of("type","string","minLength",1,"maxLength",100),"customer_names",Map.of("type","string","maxLength",2048,"description","JSON array of 2 to 8 exact customer names"));
        return Map.of();
    }
    public static Result execute(NaturalBusinessReadMapper mapper,Request request) {
        return execute(mapper,request,AssistantReadPolicy.admin());
    }
    public static Result execute(NaturalBusinessReadMapper mapper,Request request,AssistantReadPolicy policy) {
        if(request==null||request.domain()==null||!DOMAINS.contains(request.domain())||request.orderId()!=null&&(request.orderId()<=0||Set.of("MAINTENANCE","ALARMS","STOCK_MOVEMENT").contains(request.domain()))
            ||request.keyword()!=null&&(request.keyword().isBlank()||request.keyword().length()>100)
            ||(request.startInclusive()==null)!=(request.endExclusive()==null))throw new IllegalArgumentException("Invalid registered tool request");
        for(String date:Arrays.asList(request.startInclusive(),request.endExclusive()))
            if(date!=null&&!date.matches("\\d{4}-\\d{2}-\\d{2} 00:00:00"))throw new IllegalArgumentException("Invalid date scope");
        if(request.startInclusive()!=null) {
            try {
                var start=java.time.LocalDate.parse(request.startInclusive().substring(0,10));
                var end=java.time.LocalDate.parse(request.endExclusive().substring(0,10));
                if(!end.isAfter(start))throw new IllegalArgumentException("Invalid date scope");
            }catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("Invalid date scope");}
        }
        BusinessSelectors.validate(request.domain(),request.selectors());
        policy.require(request.domain());
        if(!policy.adminRole()) {
            long total=mapper.countAuthorized(request.domain(),request.orderId(),request.keyword(),request.startInclusive(),request.endExclusive(),request.selectors(),policy.customer(),policy.operator());
            return new Result(total,List.copyOf(mapper.rowsAuthorized(request.domain(),request.orderId(),request.keyword(),request.startInclusive(),request.endExclusive(),request.selectors(),policy.customer(),policy.operator())));
        }
        if(!request.selectors().isEmpty()) {
            long total=mapper.countScoped(request.domain(),request.orderId(),request.keyword(),request.startInclusive(),request.endExclusive(),request.selectors());
            return new Result(total,List.copyOf(mapper.rowsScoped(request.domain(),request.orderId(),request.keyword(),request.startInclusive(),request.endExclusive(),request.selectors())));
        }
        long total=mapper.count(request.domain(),request.orderId(),request.keyword(),request.startInclusive(),request.endExclusive());
        return new Result(total,List.copyOf(mapper.rows(request.domain(),request.orderId(),request.keyword(),request.startInclusive(),request.endExclusive())));
    }
}
