package com.upc.computer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.common.BusinessException;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

/** Shared-stock, deadline-ordered planning. Creates drafts only, never dispatches people. */
@Service
public class ProductionPortfolioPlanner {
    private final CustomerOrderMapper orders;
    private final CustomerOrderItemMapper items;
    private final InventoryMapper inventory;
    private final BomMapper bom;
    private final MaterialMapper materials;
    private final ProductionPlanMapper plans;
    private final MesPlannerSchedulingService scheduling;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json=new ObjectMapper();
    public ProductionPortfolioPlanner(CustomerOrderMapper orders,CustomerOrderItemMapper items,InventoryMapper inventory,
        BomMapper bom,MaterialMapper materials,ProductionPlanMapper plans,MesPlannerSchedulingService scheduling,JdbcTemplate jdbc){
        this.orders=orders;this.items=items;this.inventory=inventory;this.bom=bom;this.materials=materials;this.plans=plans;this.scheduling=scheduling;this.jdbc=jdbc;
    }
    public static List<String> orderNumbers(Object raw){
        try {
            var node=new ObjectMapper().readTree(Objects.toString(raw,"[]"));
            if(!node.isArray()||node.isEmpty()||node.size()>12)throw new IllegalArgumentException();
            var result=new LinkedHashSet<String>();
            for(var id:node){if(!id.isTextual()||!id.asText().matches("CO[A-Za-z0-9_-]{1,78}")||!result.add(id.asText()))throw new IllegalArgumentException();}
            return List.copyOf(result);
        }catch(Exception error){throw new BusinessException(400,"请选择 1–12 个不重复的完整 CO 订单编号");}
    }
    @Transactional(readOnly=true,timeout=30)
    public Map<String,Object> preview(Map<String,Object> inputs){
        List<String> ids=orderNumbers(inputs.get("orderIds"));
        String mode=Objects.toString(inputs.getOrDefault("schedulingMode","DELIVERY"));
        String fulfillment=Objects.toString(inputs.getOrDefault("fulfillment","STOCK_FIRST"));
        if(!Set.of("DELIVERY","BALANCE","COST").contains(mode)||!Set.of("STOCK_FIRST","NEW_PRODUCTION").contains(fulfillment))
            throw new BusinessException(400,"无效的计划策略或库存使用方式");
        LocalDate start=parse(inputs,"planStart",LocalDate.now().plusDays(1));
        LocalDate end=parse(inputs,"planEnd",null);
        var selected=ProductionCalculationProgress.stage("portfolio_orders","读取所选订单与交期",()->{
            var rows=orders.customerOrderList().stream().filter(o->ids.contains(o.getOrderNo())).sorted(Comparator.comparing(CustomerOrder::getRequiredDeliveryDate,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(CustomerOrder::getOrderNo)).toList();
            if(rows.size()!=ids.size())throw new BusinessException(404,"所选订单中有不存在或不可读取的编号");
            for(var o:rows)if(!Set.of("APPROVED","PLAN_PENDING").contains(o.getAuditStatus()))throw new BusinessException(409,o.getOrderNo()+" 已进入后续流程，请检查已有计划");
            return rows;
        },v->"已核对 "+v.size()+" 张订单；按交期排序");
        var allItems=items.orderItemList();
        var pool=ProductionCalculationProgress.stage("portfolio_stock","读取共享可用库存",()->{
            var quantities=new HashMap<Long,BigDecimal>();
            for(var row:inventory.inventoryList()){
                // Only explicitly usable stock. Held, rejected and unknown states cannot cover demand.
                if(!Set.of("NORMAL","AVAILABLE").contains(Objects.toString(row.getInventoryStatus(),"")))continue;
                quantities.merge(row.getMaterialId(),Objects.requireNonNullElse(row.getQuantityAvailable(),BigDecimal.ZERO).max(BigDecimal.ZERO),BigDecimal::add);
            }
            return quantities;
        },v->"读取 "+v.size()+" 种可用库存；本方案内只分配一次，尚未预留或发货");
        var allBom=ProductionCalculationProgress.stage("portfolio_bom","读取有效 BOM",()->bom.bomList(),v->"读取 "+v.size()+" 条 BOM，按计划日期核对有效期与损耗");
        var names=new HashMap<Long,String>();for(var m:materials.materialList())names.put(m.getMaterialId(),m.getMaterialName());
        // Conservative capacity baseline: selected orders cannot overlap existing unfinished plans or one another.
        var openPlans=plans.planList().stream().filter(p->Set.of("DRAFT","PUBLISHED","SUBMITTED","EXECUTING").contains(Objects.toString(p.getPlanStatus(),""))).toList();
        for(var p:openPlans)if(selected.stream().anyMatch(o->Objects.equals(o.getOrderId(),p.getSourceOrderId())))
            throw new BusinessException(409,"所选订单已有计划 "+p.getPlanNo()+"，请先调整原计划，避免重复计划");
        LocalDate cursor=start;
        for(var p:openPlans)if(p.getPlannedEndDate()!=null&&!p.getPlannedEndDate().isBefore(cursor))cursor=p.getPlannedEndDate().plusDays(1);
        var rows=new ArrayList<Map<String,Object>>();var problems=new ArrayList<String>();
        var requirements=new LinkedHashMap<Long,BigDecimal>();
        for(var order:selected){
            var details=allItems.stream().filter(i->Objects.equals(i.getOrderId(),order.getOrderId())).toList();
            if(details.size()!=1||details.get(0).getMaterialId()==null)throw new BusinessException(409,order.getOrderNo()+" 暂不支持未明确唯一产品的订单，请先补全明细");
            var item=details.get(0);int quantity;
            try{quantity=item.getQuantity().intValueExact();if(quantity<=0)throw new ArithmeticException();}catch(Exception ex){throw new BusinessException(409,"产品数量须为正整数");}
            int available=pool.getOrDefault(item.getMaterialId(),BigDecimal.ZERO).min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
            int fromStock="NEW_PRODUCTION".equals(fulfillment)?0:Math.min(quantity,available);
            pool.merge(item.getMaterialId(),BigDecimal.valueOf(-fromStock),BigDecimal::add);
            int produce=quantity-fromStock;
            LocalDate deadline=end!=null?end:order.getRequiredDeliveryDate();
            if(deadline==null)throw new BusinessException(409,"订单缺少交期，请填写计划截止日期");
            var row=new LinkedHashMap<String,Object>();rows.add(row);
            row.put("orderId",order.getOrderNo());row.put("productName",Objects.toString(item.getProductName(),""));row.put("quantity",quantity);
            row.put("availableStock",available);row.put("stockAllocation",fromStock);row.put("plannedQty",produce);row.put("deliveryDate",deadline.toString());
            row.put("planStart",cursor.toString());row.put("planEnd","");row.put("schedules",List.of());
            if(produce==0){row.put("status","库存可覆盖，无需新建生产计划");continue;}
            var validBom=allBom.stream().filter(b->Objects.equals(b.getParentMaterialId(),item.getMaterialId())&&Integer.valueOf(1).equals(b.getStatus()))
                .filter(b->b.getEffectiveDate()==null||!b.getEffectiveDate().isAfter(LocalDate.parse(row.get("planStart").toString())))
                .filter(b->b.getExpireDate()==null||!b.getExpireDate().isBefore(LocalDate.parse(row.get("planStart").toString()))).toList();
            if(validBom.isEmpty())problems.add(order.getOrderNo()+" 缺少有效 BOM");
            for(var b:validBom){
                if(b.getQuantity()==null||b.getQuantity().signum()<=0)throw new BusinessException(409,"BOM 单位用量无效");
                BigDecimal loss=Objects.requireNonNullElse(b.getLossRate(),BigDecimal.ZERO);
                if(loss.signum()<0)throw new BusinessException(409,"BOM 损耗率无效");
                var demand=b.getQuantity().multiply(BigDecimal.ONE.add(loss)).multiply(BigDecimal.valueOf(produce)).setScale(0,RoundingMode.CEILING);
                requirements.merge(b.getChildMaterialId(),demand,BigDecimal::add);
            }
            var comparison=scheduling.compareSchemes(order.getOrderNo(),cursor,deadline.isBefore(cursor)?cursor:deadline,produce);
            var scheme=maps(comparison.get("schemes")).stream().filter(s->mode.equals(s.get("key"))).findFirst().orElseThrow(()->new BusinessException(409,"排程算法未返回所选策略"));
            var schedules=maps(scheme.get("schedules"));if(schedules.isEmpty())throw new BusinessException(409,"没有可执行的工艺路线");
            LocalDate finish=LocalDate.parse(scheme.get("finishDate").toString());
            row.put("planEnd",finish.toString());row.put("schedules",schedules);row.put("schedulingMode",mode);row.put("saveAction","draft");
            row.put("status",finish.isAfter(deadline)?"交期冲突":"待确认计划草稿");
            if(finish.isAfter(deadline))problems.add(order.getOrderNo()+" 预计 "+finish+" 完工，晚于 "+deadline);
            if(Boolean.FALSE.equals(scheme.get("canSubmit")))problems.add(order.getOrderNo()+" 存在工序或设备阻断冲突");
            cursor=finish.plusDays(1);
        }
        var materialChecks=ProductionCalculationProgress.stage("portfolio_materials","核算共享物料需求",()->{
            var checks=new ArrayList<Map<String,Object>>();
            requirements.forEach((id,required)->{
                var available=pool.getOrDefault(id,BigDecimal.ZERO);var gap=required.subtract(available).max(BigDecimal.ZERO);
                checks.add(Map.of("materialId",id,"materialName",names.getOrDefault(id,"物料 "+id),"required",required,"available",available,"shortage",gap));
                if(gap.signum()>0)problems.add(names.getOrDefault(id,"物料 "+id)+" 共享需求缺口 "+gap.toPlainString());
            });return checks;
        },v->"核对 "+v.size()+" 种物料；发现 "+problems.size()+" 项待解决约束");
        var result=new LinkedHashMap<String,Object>();result.put("orderPlans",rows);result.put("materialChecks",materialChecks);result.put("conflicts",problems);
        result.put("canSubmit",problems.isEmpty());result.put("mode",mode);result.put("fulfillment",fulfillment);
        result.put("note","按交期排序，订单依次排程并避开已有未完成计划周期；同一份成品/原料仅核算一次。这是保守产能估算，未锁定个人班次、库存或资源；保存草稿后再单独派工。库存使用仅为建议，不代表已发货。");
        return result;
    }
    @Transactional(rollbackFor=Exception.class,timeout=60)
    public Map<String,Object> saveReviewed(Map<String,Object> payload,String operator){
        var inputs=map(payload.get("portfolioInputs"));
        // Lock affected orders and stock before the final read; SQL is fixed and IDs are parameters.
        for(String id:orderNumbers(inputs.get("orderIds")).stream().sorted().toList())jdbc.queryForList("SELECT order_id FROM customer_order WHERE order_no = ? FOR UPDATE",id);
        jdbc.queryForList("SELECT inventory_id FROM inventory ORDER BY inventory_id FOR UPDATE");
        var fresh=preview(inputs);
        if(!Boolean.TRUE.equals(fresh.get("canSubmit"))||!sameJson(json.valueToTree(fresh),json.valueToTree(payload.get("planning"))))
            throw new BusinessException(409,"订单、库存或排程条件已变化，请重新预览后确认");
        var created=new ArrayList<Map<String,Object>>();
        for(var row:maps(fresh.get("orderPlans")))if(((Number)row.get("plannedQty")).intValue()>0){
            var saved=new LinkedHashMap<>(scheduling.savePlanWithSchedule(row,operator));saved.put("orderId",row.get("orderId"));created.add(saved);
        }
        return Map.of("createdPlans",created,"message","已保存 "+created.size()+" 份生产计划草稿；尚未发布或派工","status","DRAFT");
    }
    // Redis JSON round trips can change Integer/BigDecimal/Double representations, not quantities.
    private static boolean sameJson(com.fasterxml.jackson.databind.JsonNode a,com.fasterxml.jackson.databind.JsonNode b){
        if(a.isNumber()&&b.isNumber())return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isObject()&&b.isObject()){
            if(a.size()!=b.size())return false;
            var fields=a.fields();while(fields.hasNext()){var e=fields.next();if(!b.has(e.getKey())||!sameJson(e.getValue(),b.get(e.getKey())))return false;}return true;
        }
        if(a.isArray()&&b.isArray()){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!sameJson(a.get(i),b.get(i)))return false;return true;}
        return a.equals(b);
    }
    private static LocalDate parse(Map<String,Object> p,String key,LocalDate fallback){try{return p.containsKey(key)?LocalDate.parse(p.get(key).toString()):fallback;}catch(Exception e){throw new BusinessException(400,"计划日期格式无效");}}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    private static List<Map<String,Object>> maps(Object value){return value instanceof List<?> list?list.stream().map(ProductionPortfolioPlanner::map).toList():List.of();}
}
