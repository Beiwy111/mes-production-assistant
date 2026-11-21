package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.tools.delivery.AiDeliveryQueryService;
import java.time.LocalDate;
import java.util.*;

/** Closed evidence tools. Callers supply server identity; arguments cannot override it. */
public final class EvidenceToolRegistry {
    public record Definition(String kind,String agent,String readOrWrite,List<String> dependencies,
                             Map<String,Object> parametersSchema,Map<String,Object> resultSchema) {}
    private static final Map<String,String> OWNERS=Map.of("E0","SUP","E1","ORDER","E4","ORDER","E2","SUPPLY","E3","PRODUCTION","E5","SUP","Q1","QUALITY","D1","EQUIPMENT","A1","AFTERSALES");
    private static final Set<String> RESOLVE=Set.of("orderPk","orderNo","targetDate","targetKind");
    private static final Set<String> QUERY=Set.of("orderPk","scopeRef","contextRef","fulfillmentRef","materialRef");
    private static final Set<String> VERIFY=Set.of("orderPk","scopeRef","queryRefs","scopeVersion","calculationVersion");
    private EvidenceToolRegistry() {}
    public static List<Definition> catalog() {
        return OWNERS.keySet().stream().sorted().map(kind->{
            Set<String> fields=fields(kind);Map<String,Object> properties=new TreeMap<>();
            fields.forEach(f->properties.put(f,f.equals("queryRefs")?Map.of("type","array","minItems",1,"maxItems",32,"uniqueItems",true,"items",Map.of("type","string","format","uuid")):Map.of("type",List.of("string","null"))));
            Map<String,Object> schema=new LinkedHashMap<>(Map.of("type","object","additionalProperties",false,"properties",properties,"required",required(kind)));
            if(kind.equals("E0"))schema.put("anyOf",List.of(Map.of("required",List.of("orderPk")),Map.of("required",List.of("orderNo"))));
            return new Definition(kind,OWNERS.get(kind),"READ",kind.equals("E2")?List.of("E1","E4"):kind.equals("E3")?List.of("E1","E2"):List.of(),schema,
                Map.of("type","object","required",List.of("queryRef","scopeRef","data")));
        }).toList();
    }
    private static Set<String> fields(String kind) {return kind.equals("E0")?RESOLVE:kind.equals("E5")?VERIFY:QUERY;}
    private static List<String> required(String kind) {
        return switch(kind) {
            case "E0" -> List.of();
            case "E2" -> List.of("orderPk","scopeRef","contextRef","fulfillmentRef");
            case "E3" -> List.of("orderPk","scopeRef","contextRef","materialRef");
            case "E5" -> List.of("orderPk","scopeRef","queryRefs","scopeVersion","calculationVersion");
            default -> List.of("orderPk","scopeRef");
        };
    }
    public static Map<String,Object> execute(AiDeliveryQueryService service,long owner,String agent,String kind,Map<String,?> args) {
        if(owner<=0||kind==null||!OWNERS.containsKey(kind)||!OWNERS.get(kind).equals(agent)||args==null||!fields(kind).containsAll(args.keySet()))
            throw new IllegalArgumentException("Unregistered evidence tool, role or arguments");
        for(String field:required(kind))if(args.get(field)==null)throw new IllegalArgumentException("Required evidence argument: "+field);
        for(var entry:args.entrySet()) {
            Object value=entry.getValue();if(value==null||entry.getKey().equals("queryRefs"))continue;
            if(!(value instanceof String text)||text.isBlank()||text.length()>100||text.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid evidence text");
            if(entry.getKey().endsWith("Ref"))uuid(text);
        }
        String order=(String)args.get("orderPk");if(order!=null)AiDeliveryQueryService.id(order);
        if(kind.equals("E0")) {
            String number=(String)args.get("orderNo");if(order==null&&number==null)throw new IllegalArgumentException("Exact order required");
            String date=(String)args.get("targetDate"),target=(String)args.get("targetKind");
            if(target!=null&&!Set.of("FACTORY_SHIPMENT","CUSTOMER_RECEIPT").contains(target))throw new IllegalArgumentException("Invalid delivery target");
            return service.resolve(owner,order,number,date==null?null:LocalDate.parse(date),target);
        }
        if(kind.equals("E5")) {
            if(!(args.get("queryRefs") instanceof List<?> refs)||refs.isEmpty()||refs.size()>32||new HashSet<>(refs).size()!=refs.size())throw new IllegalArgumentException("Invalid evidence references");
            for(Object ref:refs) {if(!(ref instanceof String text))throw new IllegalArgumentException("Invalid reference");uuid(text);}
            return service.revalidate(owner,order,(String)args.get("scopeRef"),refs.stream().map(String.class::cast).toList(),(String)args.get("scopeVersion"),(String)args.get("calculationVersion"));
        }
        return service.query(owner,order,(String)args.get("scopeRef"),kind,(String)args.get("contextRef"),(String)args.get("fulfillmentRef"),(String)args.get("materialRef"));
    }
    private static void uuid(String value) {if(!value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException("Invalid evidence reference");}
    private static Map<String,Object> args(Object... values) {Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)if(values[i+1]!=null)result.put((String)values[i],values[i+1]);return result;}
    public static Map<String,Object> resolve(AiDeliveryQueryService service,long owner,String order,String number,LocalDate date,String target) {
        return execute(service,owner,"SUP","E0",args("orderPk",order,"orderNo",number,"targetDate",date==null?null:date.toString(),"targetKind",target));
    }
    public static Map<String,Object> query(AiDeliveryQueryService service,long owner,String agent,String order,String scope,String kind,String context,String fulfillment,String material) {
        return execute(service,owner,agent,kind,args("orderPk",order,"scopeRef",scope,"contextRef",context,"fulfillmentRef",fulfillment,"materialRef",material));
    }
    public static Map<String,Object> revalidate(AiDeliveryQueryService service,long owner,String order,String scope,List<String> refs,String version,String calculation) {
        return execute(service,owner,"SUP","E5",args("orderPk",order,"scopeRef",scope,"queryRefs",refs,"scopeVersion",version,"calculationVersion",calculation));
    }
    public static String role(String kind) {if(!OWNERS.containsKey(kind))throw new IllegalArgumentException("Unknown evidence tool");return OWNERS.get(kind);}
}
