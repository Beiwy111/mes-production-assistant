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
    private static final Map<String,String> COVERAGE=Map.of(
        "INVENTORY","当前物料的现存量、预留量、可用量、单位、仓库、库位和批号；数量不能推算历史时点库存。",
        "ORDER","客户订单、产品与数量、订单状态及日期，按当前可见客户范围查询。",
        "PRODUCTION","生产工单、计划量、已完成量与合格量、工单状态；具体工单可沿授权关系查实际派工。",
        "QUALITY","按检验时间查检验单、抽样及不合格数量与批次趋势；单据的详细关联使用 INSPECTION_TRACE。",
        "EQUIPMENT","已登记设备编号、名称和运行状态；实时传感器读数需另有来源。",
        "AFTERSALES","可见售后记录、问题描述及处理状态；具体订单可查其已登记的追溯关系。",
        "MAINTENANCE","已登记维修或保养事项及停机时长；未登记的停机和故障原因未知。",
        "ALARMS","设备和工单的安灯报警记录、报警类型及处理状态。",
        "STOCK_MOVEMENT","物料的出入库流水、发生时间及变动数量。");
    private static final Set<String> DOMAINS=COVERAGE.keySet();
    private BusinessToolRegistry() {}
    public static List<Definition> catalog() {
        return DOMAINS.stream().sorted().map(domain->new Definition("mes."+domain.toLowerCase(Locale.ROOT)+".query",
            COVERAGE.get(domain),Set.of("READ_"+domain),"READ",15,
            Map.of("type","object","additionalProperties",false,"properties",Map.of(
                "domain",Map.of("const",domain),"orderId",Map.of("type",List.of("integer","null"),"minimum",1),
                "keyword",Map.of("type",List.of("string","null"),"maxLength",100),
                "startInclusive",Map.of("type",List.of("string","null")),"endExclusive",Map.of("type",List.of("string","null")),"selectors",Map.of("type","object","additionalProperties",false,"properties",selectorProperties(domain)))),
            "{total: integer, rows: object[]}","NaturalBusinessReadMapper.rows/count")).toList();
    }
    private static Map<String,Object> selectorProperties(String domain) {
        if(Set.of("INVENTORY","STOCK_MOVEMENT").contains(domain)){
            var properties=new LinkedHashMap<String,Object>(Map.of(
            "warehouse_code",Map.of("type","string","minLength",2,"maxLength",64),"location_code",Map.of("type","string","minLength",2,"maxLength",64),
            "warehouse_codes",Map.of("type","string","maxLength",520),"location_codes",Map.of("type","string","maxLength",520),
            "excluded_warehouse_codes",Map.of("type","string","maxLength",520),"excluded_location_codes",Map.of("type","string","maxLength",520)));
            if(domain.equals("INVENTORY"))properties.put("excluded_material_keyword",Map.of("type","string","minLength",1,"maxLength",100));
            return properties;
        }
        if(domain.equals("ORDER"))return Map.of("customer_name",Map.of("type","string","minLength",1,"maxLength",100),"customer_names",Map.of("type","string","maxLength",2048,"description","JSON array of 2 to 8 exact customer names"));
        if(domain.equals("QUALITY"))return Map.of("batch_no",Map.of("type","string","maxLength",100));
        if(BusinessSelectors.EQUIPMENT_DOMAINS.contains(domain))return Map.of("equipment_code",Map.of("type","string","maxLength",100));
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
