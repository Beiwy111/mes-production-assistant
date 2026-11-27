package com.upc.computer.assistant.v2.operations;

import com.upc.computer.ai.action.*;
import com.upc.computer.common.BusinessException;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import com.upc.computer.service.MesPlannerSchedulingService;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class ProductionOperationService {
    @org.springframework.beans.factory.annotation.Autowired private com.upc.computer.service.ProductionPortfolioPlanner portfolio;
    private final AgentActionCatalog catalog;
    private final AgentActionPlanService plans;
    private final CustomerOrderMapper orders;
    private final ProductionPlanMapper productionPlans;
    private final DispatchTaskMapper dispatches;
    private final UserMapper users;
    private final MesPlannerSchedulingService scheduling;
    private final ProductionDispatchSelection dispatching;

    public ProductionOperationService(AgentActionCatalog catalog, AgentActionPlanService plans,
            CustomerOrderMapper orders, ProductionPlanMapper productionPlans, DispatchTaskMapper dispatches,
            UserMapper users, MesPlannerSchedulingService scheduling, ProductionDispatchSelection dispatching) {
        this.catalog = catalog;
        this.plans = plans;
        this.orders = orders;
        this.productionPlans = productionPlans;
        this.dispatches = dispatches;
        this.users = users;
        this.scheduling = scheduling;
        this.dispatching = dispatching;
    }

    public record Turn(String text, String sessionId, String actionCode, Map<String,Object> fields) {}
    public record Preview(String status, String message, String actionCode, Map<String,Object> fields,
                          List<AgentRequiredField> missingFields, Map<String,Object> confirmation,
                          Map<String,List<Map<String,Object>>> options, List<Map<String,Object>> schemes,
                          String dataNote, Map<String,Object> planning) {
        public Preview(String status,String message,String actionCode,Map<String,Object> fields,List<AgentRequiredField> missingFields,Map<String,Object> confirmation,Map<String,List<Map<String,Object>>> options,List<Map<String,Object>> schemes,String dataNote){
            this(status,message,actionCode,fields,missingFields,confirmation,options,schemes,dataNote,Map.of());
        }
        public Preview(String status, String message, String actionCode, Map<String,Object> fields,
                       List<AgentRequiredField> missingFields, Map<String,Object> confirmation) {
            this(status, message, actionCode, fields, missingFields, confirmation, Map.of(), List.of(), null);
        }
    }

    public Preview preview(Turn turn, LoginResponse session) {
        String text = Objects.toString(turn.text(), "").strip();
        if (text.length() > 4000 || turn.sessionId() == null || turn.sessionId().isBlank() || turn.sessionId().length() > 160)
            throw new BusinessException(400, "无效的对话请求");
        ProductionCommand prior = turn.actionCode() == null || turn.actionCode().isBlank()
                ? null : ProductionCommand.byCode(turn.actionCode());
        ProductionCommand command = ProductionCommand.detect(text);
        if (command == null && !ProductionCommand.isInquiry(text)) command = prior;
        if (command == null) return new Preview("UNHANDLED",
                "要创建销售订单并提交审核，请说「我要下单」；要下达已有订单，请说明是将订单 CO… 交给计划员、为订单排产，还是为已发布的计划 PP… 派工。",
                null, Map.of(), List.of(), null);
        Map<String,Object> inputs = prior == command || prior == null ? turn.fields() : Map.of();
        if (command == ProductionCommand.SCHEDULE) {
            String task = Objects.toString(inputs == null ? null : inputs.get("planningTask"), "");
            if (!Set.of("", "PLAN", "DISPATCH").contains(task)) throw new BusinessException(400, "请选择生产计划或智能派工");
            boolean explicitPlan = text.matches(".*(?:制定|创建|生成|新建|编制|生产计划).*")
                    || ProductionCommand.SCHEDULE.identifier.matcher(text).find()
                    || inputs != null && inputs.containsKey("orderId");
            boolean dispatch = "DISPATCH".equals(task) || !"PLAN".equals(task)
                    && (ProductionCommand.DISPATCH.identifier.matcher(text).find()
                    || !explicitPlan && "MANAGER".equalsIgnoreCase(session.getRoleCode()));
            if (dispatch) {
                command = ProductionCommand.DISPATCH;
                if (inputs != null && inputs.containsKey("planningTask")) {
                    inputs = new LinkedHashMap<>(inputs);
                    inputs.remove("planningTask");
                }
            } else if ("ADMIN".equalsIgnoreCase(session.getRoleCode()) && !explicitPlan && task.isBlank()) {
                Map<String,Object> fields = ProductionCommand.SCHEDULE.bind(text, inputs);
                return new Preview("NEEDS_INPUT", "请选择要办理的排产任务", command.code, fields,
                        List.of(new AgentRequiredField("planningTask", "排产任务")), null,
                        Map.of("planningTask", List.of(option("PLAN", "制定生产计划", "从已审核订单比较排程策略并保存草案"),
                                option("DISPATCH", "为生产计划智能派工", "从已提交主管的计划推荐工序、设备及操作员"))), List.of(), null);
            }
        }
        catalog.requireAllowed(command.code, session.getRoleCode());
        Map<String,Object> fields = command.bind(text, inputs);
        if(command==ProductionCommand.SCHEDULE && fields.containsKey("orderIds"))return portfolioPreview(turn,fields,session);
        User assignee = command == ProductionCommand.DISPATCH ? dispatching.bind(text, fields) : null;
        String id = (String) fields.get(command.idField);
        if (id == null) return missingIdentifier(command, fields, switch (command.idField) {
            case "planId" -> "生产计划编号（PP 开头）";
            case "orderId" -> "销售订单编号（CO 开头）";
            default -> "派工编号（DT 开头）";
        });

        Map<String,Object> params = new LinkedHashMap<>(fields);
        params.remove("planningTask");
        params.remove("staffingPolicy");
        List<Map<String,Object>> schemes = new ArrayList<>();
        switch (command) {
            case AUDIT, TO_PLANNER, SCHEDULE -> {
                CustomerOrder order = orders.customerOrderList().stream().filter(row -> id.equals(row.getOrderNo()))
                        .findFirst().orElseThrow(() -> new BusinessException(404, "订单不存在：" + id));
                if (command == ProductionCommand.AUDIT) {
                    requireState("订单须处于待审核状态", order.getAuditStatus(), "PENDING");
                    if (!fields.containsKey("action")) return missing(command, fields, "action", "审核决定（通过或驳回）");
                    if (!Set.of("pass","reject").contains(fields.get("action"))) throw new BusinessException(400, "请填写通过或驳回");
                    if ("reject".equals(fields.get("action")) && !fields.containsKey("reason"))
                        return missing(command, fields, "reason", "驳回原因");
                } else if (command == ProductionCommand.TO_PLANNER)
                    requireState("仅已审核待移交的订单可提交计划员", order.getAuditStatus(), "APPROVED");
                else {
                    requireState("订单须审核通过后才能排产", order.getAuditStatus(), "APPROVED", "PLAN_PENDING");
                    Preview needsDates = schedule(command, fields, params, order, schemes);
                    if (needsDates != null) return needsDates;
                }
            }
            case PUBLISH, TO_MANAGER -> {
                ProductionPlan plan = productionPlans.planList().stream().filter(row -> id.equals(row.getPlanNo()))
                        .findFirst().orElseThrow(() -> new BusinessException(404, "计划不存在：" + id));
                if (command == ProductionCommand.PUBLISH) requireState("仅草稿计划可发布", plan.getPlanStatus(), "DRAFT");
                else requireState("请先发布计划后再提交主管", plan.getPlanStatus(), "PUBLISHED");
            }
            case DISPATCH -> {
                if (fields.containsKey("assignmentOverrides")) {
                    ProductionPlan plan = productionPlans.planList().stream().filter(row -> id.equals(row.getPlanNo()))
                        .findFirst().orElseThrow(() -> new BusinessException(404,"计划不存在"));
                    if (Set.of("DRAFT","PUBLISHED").contains(plan.getPlanStatus())) {
                        catalog.requireAllowed("publishPlan",session.getRoleCode());
                        catalog.requireAllowed("submitPlanToManager",session.getRoleCode());
                        params.put("preparePlan",true);
                        params.put("executionSteps", "DRAFT".equals(plan.getPlanStatus())
                            ? List.of("发布当前计划","提交生产主管","创建并下达工单","按下表派工并投递到各操作员任务列表")
                            : List.of("提交生产主管","创建并下达工单","按下表派工并投递到各操作员任务列表"));
                    }
                    params.remove("assignmentOverrides"); params.remove("excludedOperators");
                }
                params.put("recommendations", dispatching.preview(fields, assignee));
            }
            case ACCEPT, PICK, START, REPORT, REMIND -> {
                DispatchTask task = dispatches.dispatchList().stream().filter(row -> id.equals(row.getDispatchNo()))
                        .findFirst().orElseThrow(() -> new BusinessException(404, "派工不存在：" + id));
                if (command == ProductionCommand.REMIND) {
                    requireState("仅进行中的派工可以提醒", task.getStatus(), "ASSIGNED", "ACCEPTED", "PRODUCING", "RUNNING");
                    User user = task.getOperatorId() == null ? null : users.getUserById(task.getOperatorId());
                    if (user == null || user.getUsername() == null || user.getUsername().isBlank())
                        throw new BusinessException(409, "派工尚未绑定操作员");
                    params.put("targetUsername", user.getUsername());
                } else {
                    if (!Objects.equals(task.getOperatorId(), session.getUserId()))
                        throw new BusinessException(403, "只能办理派给本人的任务");
                    if (command == ProductionCommand.ACCEPT) requireState("派工须处于待接收状态", task.getStatus(), "ASSIGNED");
                    else if (command == ProductionCommand.REPORT) {
                        requireState("仅已接收或生产中的派工可报工", task.getStatus(), "ACCEPTED", "PRODUCING", "RUNNING");
                        Preview missing = report(command, fields, params, task);
                        if (missing != null) return missing;
                    } else if (command == ProductionCommand.PICK)
                        requireState("请先接收该派工", task.getStatus(), "ACCEPTED", "PRODUCING", "RUNNING");
                    else requireState("请先接收该派工", task.getStatus(), "ACCEPTED");
                }
            }
        }
        AgentPlanOutcome outcome = plans.previewProduction(command.code, params, session, turn.sessionId());
        if (command == ProductionCommand.DISPATCH && outcome.confirmation() != null) {
            var card = new LinkedHashMap<>(outcome.confirmation());
            card.put("title", Boolean.TRUE.equals(params.get("preparePlan")) ? "发布计划并下发工序派工" : "工序派工安排");
            card.put("operatorOptions",dispatching.options(fields));
            card.put("adjustmentHint","可继续对话更换某道工序的人员、排除指定人员或要求仅选无任务人员；调整后需重新确认。");
            int count = ((List<?>) params.get("recommendations")).size();
            outcome = new AgentPlanOutcome(outcome.status(),"已准备 **" + count + " 道工序的初步派工安排**。请核对人员与在手任务，可直接对话调整；**确认后才会下发**。",outcome.missingFields(),card);
        }
        return new Preview(outcome.status(), outcome.message(), command.code, fields, outcome.missingFields(), outcome.confirmation(),
                Map.of(), schemes, schemes.isEmpty() ? null : SCHEME_NOTE);
    }

    private Preview portfolioPreview(Turn turn,Map<String,Object> fields,LoginResponse session){
        if(fields.containsKey("plannedQty"))throw new BusinessException(400,"多订单按各单实际数量计划，请勿设置统一生产数量；可选择全量新生产");
        fields.putIfAbsent("planStart",LocalDate.now().plusDays(1).toString());
        fields.putIfAbsent("schedulingMode","DELIVERY");fields.putIfAbsent("fulfillment","STOCK_FIRST");
        var calculation=portfolio.preview(fields);
        if(!Boolean.TRUE.equals(calculation.get("canSubmit")))
            return new Preview("NEEDS_INPUT","**当前多订单方案存在约束冲突，尚未生成可执行方案。** 请查看计划表和缺口，在对话中调整订单范围、交期或履约方式。","saveProductionPlan",fields,List.of(),null,Map.of(),List.of(),null,calculation);
        var rows=(List<Map<String,Object>>)calculation.get("orderPlans");
        var producing=rows.stream().filter(r->((Number)r.get("plannedQty")).intValue()>0).toList();
        if(producing.isEmpty())return new Preview("COMPLETED","**所选订单均可由现有成品库存覆盖，无需新增生产计划。** 库存仅在本次方案中分配，尚未预留或发运。","saveProductionPlan",fields,List.of(),null,Map.of(),List.of(),null,calculation);
        var params=new LinkedHashMap<String,Object>();
        params.put("orderId",producing.get(0).get("orderId"));params.put("schedules",producing.stream().flatMap(r->((List<?>)r.get("schedules")).stream()).toList());
        params.put("portfolioInputs",new LinkedHashMap<>(fields));params.put("planning",calculation);
        var outcome=plans.previewProduction("saveProductionPlan",params,session,turn.sessionId());
        var card=new LinkedHashMap<>(outcome.confirmation());card.put("title","保存多订单生产计划草稿");
        return new Preview(outcome.status(),"**已完成 "+rows.size()+" 张订单的联合规划。** 预计新建 "+producing.size()+" 份计划草稿，确认后保存；人员派工是后续独立步骤。","saveProductionPlan",fields,List.of(),card,Map.of(),List.of(),null,calculation);
    }

    private static final String SCHEME_NOTE = "基于订单、库存、BOM、设备、工艺路线实时计算；完工日期及利用率为策略估算，交期/均衡/成本为方案权重。";

    private Preview schedule(ProductionCommand command, Map<String,Object> fields, Map<String,Object> params,
                             CustomerOrder order, List<Map<String,Object>> available) {
        LocalDate start = date(fields, "planStart", LocalDate.now().plusDays(1));
        LocalDate end = date(fields, "planEnd", order.getRequiredDeliveryDate());
        if (end == null || end.isBefore(start)) {
            if (fields.containsKey("planEnd")) throw new BusinessException(400, "计划结束日期须在开始日期当天或之后");
            fields.put("planStart", start.toString());
            return missing(command, fields, "planEnd", "计划结束日期（YYYY-MM-DD）");
        }
        int qty = 0;
        if (fields.containsKey("plannedQty")) {
            try { qty = Integer.parseInt(fields.get("plannedQty").toString()); }
            catch (NumberFormatException ex) { throw new BusinessException(400, "计划数量须为正整数"); }
            if (qty <= 0) throw new BusinessException(400, "计划数量须为正整数");
        }
        if(qty==0){
            var context=scheduling.previewOrderContext(order.getOrderNo());
            if(context!=null && context.get("inventory") instanceof Map<?,?> stock){
                int demand=((Number)context.getOrDefault("orderQuantity",0)).intValue();
                int gap=((Number)stock.get("needToProduce")).intValue();
                if("NEW_PRODUCTION".equals(fields.get("fulfillment")))qty=demand;
                else if(gap==0){
                    if("STOCK_FIRST".equals(fields.get("fulfillment")))return new Preview("COMPLETED","**库存可覆盖该订单，无需新增生产计划。** 未发货、未扣减库存、未派工。",command.code,fields,List.of(),null);
                    return new Preview("NEEDS_INPUT","**现有成品库存可覆盖该订单。** 库存优先时无需新增生产计划；如仍需新生产，可选择全量新生产。此处未发货、未扣库存，也未派工。",command.code,fields,
                        List.of(new AgentRequiredField("fulfillment","履约方式")),null,
                        Map.of("fulfillment",List.of(option("STOCK_FIRST","优先库存","结束生产计划预览，库存发运需另行办理"),option("NEW_PRODUCTION","全量新生产","按订购数量核算生产计划"))),List.of(),null);
                }else qty=gap;
            }
        }
        if(qty>0)fields.put("plannedQty",String.valueOf(qty));
        String mode = Objects.toString(fields.get("schedulingMode"), "");
        if (!mode.isBlank() && !Set.of("BALANCE", "DELIVERY", "COST").contains(mode)) throw new BusinessException(400, "排产策略无效");
        Map<String,Object> comparison = scheduling.compareSchemes(order.getOrderNo(), start, end, qty);
        fields.put("planStart", start.toString());
        fields.put("planEnd", end.toString());
        if (comparison.get("schemes") instanceof List<?> schemes) {
            for (Object candidate : schemes) {
                if (!(candidate instanceof Map<?,?> scheme)) continue;
                Map<String,Object> copy = new LinkedHashMap<>();
                scheme.forEach((key,value) -> copy.put(String.valueOf(key), value));
                available.add(copy);
            }
            if (mode.isBlank() && !available.isEmpty()) {
                List<Map<String,Object>> choices = available.stream().map(scheme -> option(
                        Objects.toString(scheme.get("key")), Objects.toString(scheme.get("label"), Objects.toString(scheme.get("key"))),
                        Objects.toString(scheme.get("summary"), "请核对工序排程和冲突提示"))).toList();
                return new Preview("NEEDS_INPUT", "已比较排程方案，请选择排产策略后生成确认预览", command.code, fields,
                        List.of(new AgentRequiredField("schedulingMode", "排产策略")), null,
                        Map.of("schedulingMode", choices), available, Objects.toString(comparison.get("dataNote"), SCHEME_NOTE));
            }
            for (Object candidate : schemes) {
                if (!(candidate instanceof Map<?,?> scheme) || !mode.equals(scheme.get("key"))) continue;
                if (!(scheme.get("schedules") instanceof List<?> rows) || rows.isEmpty())
                    throw new BusinessException(409, "订单缺少可排程的工艺工序");
                if (Boolean.FALSE.equals(scheme.get("canSubmit")))
                    throw new BusinessException(409, "所选排程方案存在阻断冲突，请调整日期或选择其他策略");
                LocalDate estimatedFinish;
                try { estimatedFinish = LocalDate.parse(Objects.toString(scheme.get("finishDate"))); }
                catch (RuntimeException ex) { throw new BusinessException(409, "排程方案缺少有效完工日期"); }
                if (estimatedFinish.isAfter(end))
                    return new Preview("NEEDS_INPUT", "**当前方案无法满足指定交期。** 所选策略按完整工序估算为 **" + estimatedFinish
                        + "** 完工，晚于要求的 **" + end + "**。可调整下方截止日期，或在对话中提出新的数量、策略再重新核算。尚未生成可执行方案，也未修改生产数据。",
                        command.code, fields, List.of(new AgentRequiredField("planEnd", "调整计划截止日期")), null,
                        Map.of(), available, SCHEME_NOTE);
                params.put("planStart", start.toString());
                params.put("planEnd", scheme.get("finishDate"));
                params.put("plannedQty", comparison.get("plannedQty"));
                params.put("schedulingMode", mode);
                params.put("schedules", rows);
                params.put("saveAction", "draft");
                if (scheme.get("conflicts") instanceof List<?> conflicts && !conflicts.isEmpty()) params.put("conflicts", conflicts);
                return null;
            }
        }
        throw new BusinessException(409, "订单没有可预览的排程方案");
    }

    private LocalDate date(Map<String,Object> fields, String key, LocalDate fallback) {
        if (!fields.containsKey(key)) return fallback;
        try { return LocalDate.parse(fields.get(key).toString()); }
        catch (DateTimeParseException ex) { throw new BusinessException(400, "日期请填写为 YYYY-MM-DD"); }
    }

    private Preview report(ProductionCommand command, Map<String,Object> fields, Map<String,Object> params, DispatchTask task) {
        if (!fields.containsKey("reportQty")) return missing(command, fields, "reportQty", "本次完成数量");
        String qty = fields.get("reportQty").toString();
        if (!qty.matches("[1-9]\\d{0,8}")) throw new BusinessException(400, "完成数量须为正整数");
        if (task.getAssignedQuantity() == null) throw new BusinessException(409, "派工缺少已分配数量");
        BigDecimal remaining = task.getAssignedQuantity().subtract(Objects.requireNonNullElse(task.getCompletedQuantity(), BigDecimal.ZERO));
        if (remaining.compareTo(new BigDecimal(qty)) < 0) throw new BusinessException(409, "报工数量超过剩余派工数量");
        if (!fields.containsKey("startTime")) return missing(command, fields, "startTime", "实际开始时间（YYYY-MM-DD HH:mm）");
        if (!fields.containsKey("endTime")) return missing(command, fields, "endTime", "实际结束时间（YYYY-MM-DD HH:mm）");
        try {
            LocalDateTime start = LocalDateTime.parse(fields.get("startTime").toString().replace(' ', 'T'));
            LocalDateTime end = LocalDateTime.parse(fields.get("endTime").toString().replace(' ', 'T'));
            if (!end.isAfter(start)) throw new BusinessException(400, "实际结束时间须晚于开始时间");
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            params.put("startTime", start.format(format));
            params.put("endTime", end.format(format));
        } catch (DateTimeParseException ex) {
            throw new BusinessException(400, "请填写实际作业日期和时间（YYYY-MM-DD HH:mm）");
        }
        return null;
    }

    private Preview missing(ProductionCommand command, Map<String,Object> fields, String name, String label) {
        return new Preview("NEEDS_INPUT", "请补充" + label, command.code, fields, List.of(new AgentRequiredField(name,label)), null);
    }

    private Preview missingIdentifier(ProductionCommand command, Map<String,Object> fields, String label) {
        List<Map<String,Object>> choices = new ArrayList<>();
        if ("orderId".equals(command.idField)) {
            Set<String> states = switch (command) {
                case AUDIT -> Set.of("PENDING");
                case TO_PLANNER -> Set.of("APPROVED");
                default -> Set.of("APPROVED", "PLAN_PENDING");
            };
            for (CustomerOrder order : orders.customerOrderList()) {
                if (order.getOrderNo() == null || !states.contains(Objects.toString(order.getAuditStatus(), ""))) continue;
                choices.add(option(order.getOrderNo(), order.getOrderNo() + " · " + Objects.toString(order.getCustomerName(), "客户订单"),
                        "要求交期：" + Objects.toString(order.getRequiredDeliveryDate(), "未填写")));
            }
        } else if ("planId".equals(command.idField)) {
            Set<String> states = switch (command) {
                case PUBLISH -> Set.of("DRAFT");
                case TO_MANAGER -> Set.of("PUBLISHED");
                default -> Set.of("SUBMITTED", "EXECUTING");
            };
            for (ProductionPlan plan : productionPlans.planList()) {
                if (plan.getPlanNo() == null || !states.contains(Objects.toString(plan.getPlanStatus(), ""))) continue;
                if (command == ProductionCommand.DISPATCH && (fields.containsKey("processStep")
                        ? dispatches.hasDispatchForStep(plan.getPlanNo(), fields.get("processStep").toString())
                        : dispatches.hasDispatchForPlan(plan.getPlanNo()))) continue;
                choices.add(option(plan.getPlanNo(), plan.getPlanNo() + " · " + Objects.toString(plan.getPlanName(), "生产计划"),
                        "计划周期：" + Objects.toString(plan.getPlannedStartDate(), "未填写") + " 至 "
                                + Objects.toString(plan.getPlannedEndDate(), "未填写")));
            }
        }
        String message = choices.isEmpty() ? "请补充" + label + "；当前没有符合状态的可选记录" : "请选择" + label;
        if ("dispatchId".equals(command.idField)) return missing(command, fields, command.idField, label);
        return new Preview("NEEDS_INPUT", message, command.code, fields,
                List.of(new AgentRequiredField(command.idField, label)), null,
                Map.of(command.idField, choices), List.of(), null);
    }

    private static Map<String,Object> option(String value, String label, String description) {
        return Map.of("value", value, "label", label, "description", description);
    }

    private void requireState(String message, String actual, String... allowed) {
        if (!Arrays.asList(allowed).contains(actual)) throw new BusinessException(409, message);
    }
}
