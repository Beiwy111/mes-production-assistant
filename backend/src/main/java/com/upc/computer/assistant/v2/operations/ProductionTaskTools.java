package com.upc.computer.assistant.v2.operations;

import com.upc.computer.ai.action.AgentActionCatalog;
import com.upc.computer.common.BusinessException;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.mapper.*;
import com.upc.computer.service.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Explicit read-only adapters over existing MES services. Never accepts SQL or arbitrary endpoints. */
@Component
public class ProductionTaskTools {
    private final AgentActionCatalog catalog;
    private final CustomerOrderMapper orders;
    private final ProductionPlanMapper plans;
    private final EquipmentMapper equipment;
    private final MesPlannerSchedulingService scheduling;
    private final MesDispatchRecommendService dispatching;
    public ProductionTaskTools(AgentActionCatalog catalog,CustomerOrderMapper orders,ProductionPlanMapper plans,
            EquipmentMapper equipment,MesPlannerSchedulingService scheduling,MesDispatchRecommendService dispatching){
        this.catalog=catalog;this.orders=orders;this.plans=plans;this.equipment=equipment;this.scheduling=scheduling;this.dispatching=dispatching;
    }
    static final Map<String,String> LABELS=Map.of("orders","读取可计划订单","plans","读取生产计划",
        "orderContext","核对订单、库存、BOM 与工艺路线","planContext","核对计划、工单与派工条件","resources","核对在岗人员与设备状态","orderStaffing","按订单初排工序人员与设备");
    public List<Map<String,Object>> catalog(LoginResponse session){
        List<Map<String,Object>> result=new ArrayList<>();
        for(String tool:LABELS.keySet())try{
            allowed(tool,session);
            Map<String,Object> properties=tool.equals("orderContext")?Map.of("orderId",Map.of("type","string","description","完整 CO 订单编号")):
                tool.equals("orderStaffing")?Map.of("orderId",Map.of("type","string","description","真实 CO 订单编号"),"plannedQty",Map.of("type","string","description","用户明确的新增生产数量，正整数")):
                tool.equals("planContext")?Map.of("planId",Map.of("type","string","description","完整 PP 计划编号")):Map.of();
            result.add(Map.of("tool",tool,"description",LABELS.get(tool),"parameters",Map.of("type","object","properties",properties,"required",properties.keySet(),"additionalProperties",false)));
        }catch(BusinessException ignored){}
        var actions=new ArrayList<Map<String,Object>>();
        for(var command:ProductionCommand.values())try{
            var definition=catalog.requireAllowed(command.code,session.getRoleCode());
            actions.add(Map.of("actionCode",command.code,"description",definition.title(),"allowedInputFields",command.inputFields.stream().filter(f->!Set.of("planningTask","idleOnly").contains(f)).sorted().toList()));
        }catch(BusinessException ignored){}
        Map<String,Object> fieldProperties=new LinkedHashMap<>();
        for(var command:ProductionCommand.values())for(String name:command.inputFields)if(!Set.of("planningTask","idleOnly").contains(name))fieldProperties.put(name,Map.of("type","string"));
        fieldProperties.put("assignmentOverrides",Map.of("type","string","description","JSON 对象字符串：真实工序名到真实人员账号，如 {\"主板装配\":\"wu_operator\"}。修改一行保留其余工序，不要用 processStep 截成单工序。空对象恢复自动推荐。"));
        fieldProperties.put("excludedOperators",Map.of("type","string","description","JSON 数组字符串，禁止分配的真实账号，如 [\"li_operator\"]；空数组清除排除条件"));
        fieldProperties.put("staffingPolicy",Map.of("type","string","enum",List.of("IDLE_FIRST","IDLE_ONLY"),"description","默认 IDLE_FIRST：空闲优先，允许追加待接收队列。IDLE_ONLY：严格禁止安排任何有在手任务的人，仅在用户明确不允许排队时选择。"));
        fieldProperties.put("orderIds",Map.of("type","string","description","明确选择的一个或多个订单编号 JSON 数组字符串，例如 [\"CO123\",\"CO124\"]；整体核算共享库存和交期，与 orderId 二选一"));
        fieldProperties.put("fulfillment",Map.of("type","string","enum",List.of("STOCK_FIRST","NEW_PRODUCTION"),"description","库存优先只生产缺口；只有用户明确全量新生产时选择 NEW_PRODUCTION"));
        fieldProperties.put("schedulingMode",Map.of("type","string","enum",List.of("DELIVERY","BALANCE","COST")));
        if(!actions.isEmpty())result.add(Map.of("tool","preview","description","仅准备一个操作方案；写入必须另行人工确认。缺少订单时可以 fields={} 返回选择表单，不要给每个候选分别准备方案。",
            "parameters",Map.of("type","object","properties",Map.of("actionCode",Map.of("type","string","enum",actions.stream().map(a->a.get("actionCode")).toList()),
                "fields",Map.of("type","object","properties",fieldProperties,"additionalProperties",false)),"required",List.of("actionCode","fields"),"additionalProperties",false),"actions",actions,
            "fieldRules",Map.of("schedulingMode",List.of("DELIVERY","BALANCE","COST"),"planStart","YYYY-MM-DD","planEnd","YYYY-MM-DD","plannedQty","正整数字符串；明确增产时按用户数量，否则使用业务服务建议","orderId","CO开头完整编号","planId","PP开头完整编号","operator","真实操作员账号","processStep","真实工序名称","action",List.of("pass","reject"))));
        return result;
    }
    private void allowed(String tool,LoginResponse session){
        if(!LABELS.containsKey(tool))throw new BusinessException(400,"工具不在白名单中");
        String code=Set.of("orders","orderContext","orderStaffing").contains(tool)?"saveProductionPlan": "confirmSmartDispatch";
        if(Set.of("plans","planContext").contains(tool) && "PLANNER".equals(session.getRoleCode()))code="publishPlan";
        if(tool.equals("resources") && "PLANNER".equals(session.getRoleCode()))code="saveProductionPlan";
        catalog.requireAllowed(code,session.getRoleCode());
    }
    @Transactional(readOnly=true,timeout=15)
    public Object read(String tool,Map<String,Object> args,LoginResponse session){
        allowed(tool,session);
        Set<String> keys=tool.equals("orderStaffing")?Set.of("orderId","plannedQty"):tool.equals("orderContext")?Set.of("orderId"):tool.equals("planContext")?Set.of("planId"):Set.of();
        if(!args.keySet().equals(keys))throw new BusinessException(400,"查询工具参数不正确");
        return switch(tool){
            case "orders" -> orders.customerOrderList().stream().filter(o->Set.of("APPROVED","PLAN_PENDING").contains(Objects.toString(o.getAuditStatus(),"")))
                .map(o->Map.of("orderId",o.getOrderNo(),"customer",Objects.toString(o.getCustomerName(),""),"deliveryDate",Objects.toString(o.getRequiredDeliveryDate(),""),"status",o.getAuditStatus())).toList();
            case "plans" -> plans.planList().stream().map(p->Map.of("planId",p.getPlanNo(),"name",Objects.toString(p.getPlanName(),""),"status",Objects.toString(p.getPlanStatus(),""))).toList();
            case "orderStaffing" -> {
                String quantity=Objects.toString(args.get("plannedQty"),"");
                if(!quantity.matches("[1-9][0-9]{0,6}"))throw new BusinessException(400,"初排数量必须是正整数且不超过9999999");
                yield dispatching.previewOrderAssignments(id(args,"orderId","CO"),Integer.parseInt(quantity));
            }
            case "orderContext" -> {
                String number=id(args,"orderId","CO");var context=new LinkedHashMap<>(scheduling.previewOrderContext(number));
                var order=orders.customerOrderList().stream().filter(o->number.equals(o.getOrderNo())).findFirst().orElseThrow(()->new BusinessException(404,"订单不存在"));
                var related=plans.planList().stream().filter(p->Objects.equals(order.getOrderId(),p.getSourceOrderId()))
                    .map(p->Map.<String,Object>of("planId",p.getPlanNo(),"status",Objects.toString(p.getPlanStatus(),""))).toList();
                context.put("existingPlans",related);context.put("hasSavedPlan",!related.isEmpty());
                yield context;
            }
            case "planContext" -> {
                String number=id(args,"planId","PP");var context=new LinkedHashMap<>(dispatching.loadPlanContext(number));
                plans.planList().stream().filter(p->number.equals(p.getPlanNo())).findFirst().ifPresent(p->context.put("status",p.getPlanStatus()));
                yield context;
            }
            case "resources" -> Map.of("operators",dispatching.operatorAvailability(),
                "equipment",equipment.equipmentList().stream().map(e->Map.of("code",e.getEquipmentCode(),"name",Objects.toString(e.getEquipmentName(),""),"status",Objects.toString(e.getStatus(),""),"workshop",Objects.toString(e.getWorkshop(),""))).toList(),
                "missing","在岗账号不等于具备所有技能；当前适配器没有个人班次和技能认证记录，派工仍须遵守原系统车间绑定与负载校验。");
            default -> throw new BusinessException(400,"不支持的查询工具");
        };
    }
    private String id(Map<String,Object> args,String key,String prefix){
        Object value=args.get(key);
        if(!(value instanceof String s)||!s.matches(prefix+"[A-Za-z0-9_-]{1,78}"))throw new BusinessException(400,"请提供完整业务编号");
        return (String)value;
    }
}
