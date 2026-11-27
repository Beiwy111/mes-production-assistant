package com.upc.computer.assistant.v2.operations;

import com.upc.computer.common.BusinessException;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import com.upc.computer.service.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Resolve explicit recipients and steps before creating any dispatch proposal. */
@Component
public class ProductionDispatchSelection {
    private static final Pattern WORK_ORDER = Pattern.compile("(?i)(?<![a-z0-9])WO[0-9_-][a-z0-9_-]*");
    private final MesDispatchRecommendService recommendations;
    private final DispatchTaskMapper dispatches;
    private final WorkOrderMapper workOrders;
    private final ProductionPlanMapper plans;

    public ProductionDispatchSelection(MesDispatchRecommendService recommendations, DispatchTaskMapper dispatches,
            WorkOrderMapper workOrders, ProductionPlanMapper plans) {
        this.recommendations = recommendations;
        this.dispatches = dispatches;
        this.workOrders = workOrders;
        this.plans = plans;
    }

    public User bind(String text, Map<String,Object> fields) {
        var numbers = WORK_ORDER.matcher(text).results().map(match -> match.group().toUpperCase(Locale.ROOT)).distinct().toList();
        if (numbers.size() > 1) throw new BusinessException(400, "请每次指定一张工单");
        if (numbers.size() == 1) {
            String number = numbers.get(0);
            if (fields.containsKey("workOrderNo") && !number.equals(fields.get("workOrderNo"))) fields.clear();
            fields.put("workOrderNo", number);
        }
        if (fields.containsKey("workOrderNo")) {
            WorkOrder wo = workOrders.workOrderList().stream().filter(row -> row.getWorkOrderNo().equals(fields.get("workOrderNo")))
                    .findFirst().orElseThrow(() -> new BusinessException(404, "未找到指定工单"));
            ProductionPlan plan = plans.getPlanById(wo.getPlanId());
            if (plan == null) throw new BusinessException(409, "该工单未关联生产计划");
            if (fields.containsKey("planId") && !plan.getPlanNo().equals(fields.get("planId"))) {
                if (numbers.isEmpty() || ProductionCommand.DISPATCH.identifier.matcher(text).find())
                    throw new BusinessException(409, "工单和生产计划不匹配");
                fields.clear();
                fields.put("workOrderNo", wo.getWorkOrderNo());
            }
            fields.put("planId", plan.getPlanNo());
        }
        var stages = ProductionWorkshopCatalog.PRODUCTION_STAGES.stream()
                .filter(stage -> stage.stepKeywords().stream().anyMatch(text::contains)).toList();
        if (stages.size() > 1) throw new BusinessException(400, "请每次指定一道工序，或使用整单智能派工");
        if (stages.size() == 1) fields.put("processStep", stages.get(0).stepName());
        // Once IDs, known step names and generic instructions have been removed, any remaining
        // selector must resolve to a real assignee. Never silently convert one person's job into a batch.
        String remaining = text.replaceAll("(?i)(?:PP|WO)[0-9_-][a-z0-9_-]*", "");
        for (var stage : stages) for (String alias : stage.stepKeywords()) remaining = remaining.replace(alias,"");
        if (!generic(remaining) || fields.containsKey("operator")) {
            String named = Objects.toString(fields.get("operator"), "").strip();
            var matches = recommendations.activeOperators().stream().filter(user ->
                    named.isEmpty() ? containsName(text, user.getUsername()) || containsName(text, user.getRealName())
                            : named.equals(user.getUsername()) || named.equals(user.getRealName())).toList();
            if (matches.size() != 1) throw new BusinessException(400, matches.size() > 1
                    ? "操作员不唯一，请提供一个准确的登录账号" : "未能匹配派工条件，请提供在岗操作员的完整姓名或账号、工序及计划号");
            User assignee = matches.get(0);
            if (!named.isEmpty() && !text.isBlank() && !generic(remaining)
                    && !containsName(text, assignee.getUsername()) && !containsName(text, assignee.getRealName()))
                throw new BusinessException(400, "本轮指定的人员或条件与先前不同，请重新说明");
            remaining = remaining.replace(assignee.getUsername(), "");
            if (assignee.getRealName() != null) remaining = remaining.replace(assignee.getRealName(), "");
            if (!generic(remaining)) throw new BusinessException(400, "派工条件未能全部识别，请明确工序、操作员和计划号");
            fields.put("operator", assignee.getUsername());
            var bound = OperatorWorkshopCatalog.stageForOperator(assignee.getUsername());
            if (bound == null || fields.containsKey("processStep") && !bound.stepName().equals(fields.get("processStep")))
                throw new BusinessException(409, "指定操作员的固定车间与该工序不匹配");
            fields.put("processStep", bound.stepName());
            return assignee;
        }
        return null;
    }

    private boolean generic(String text) {
        text = text.replaceAll("智能|可不可以|能不能|可以|能否|能|吗|么|呢|吧|好不好|接下来|来完成|完成|承担|处理|发给|派发|来做|排产|排程|你|您|去|[?]", "");
        return text.replaceAll("请问|麻烦|请|帮我|帮忙|我要|我想|一下|一键|按照|根据|对应|相关|分别|合适的|这个|这张|这份|这一|这道|所有|全部|整单|智能|推荐|自动|生产|计划|工单|编号|工序|车间|工作|作业|任务|操作员|人员|执行|安排|进行|分配|分派|指派|指定|派给|分给|派工|下达|下发|交给|给|负责|将|把|让|为|该|这|的|按|做|到|工人|[\\s，。！？、：:,.!#-]", "").isBlank();
    }

    private boolean containsName(String text, String name) {
        if (name == null || name.isBlank()) return false;
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(text).find();
    }

    private Map<String,String> overrides(Object raw) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Objects.toString(raw,"{}"));
            if (!node.isObject() || node.size() > 32) throw new IllegalArgumentException();
            Map<String,String> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isTextual() || e.getValue().asText().isBlank()) throw new IllegalArgumentException();
                result.put(e.getKey(),e.getValue().asText());
            });
            return result;
        } catch(Exception e) { throw new BusinessException(400,"人员调整应为工序名称与操作员账号的对应关系"); }
    }
    private Set<String> excluded(Object raw) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Objects.toString(raw,"[]"));
            if (!node.isArray() || node.size() > 100) throw new IllegalArgumentException();
            Set<String> result = new LinkedHashSet<>();
            node.forEach(n -> { if (!n.isTextual() || n.asText().isBlank()) throw new IllegalArgumentException(); result.add(n.asText()); });
            return result;
        } catch(Exception e) { throw new BusinessException(400,"排除人员应为操作员账号列表"); }
    }

    public List<Map<String,Object>> options(Map<String,Object> fields) {
        Set<String> excluded=excluded(fields.get("excludedOperators"));
        boolean idleOnly="true".equals(fields.get("idleOnly"));
        return recommendations.operatorAvailability().stream().filter(r->!excluded.contains(r.get("username"))).map(r->{
            var row=new LinkedHashMap<String,Object>(r);
            boolean producing=((List<Map<String,Object>>)r.getOrDefault("tasks",List.of())).stream()
                .anyMatch(t->Set.of("ACCEPTED","PRODUCING","RUNNING").contains(t.get("status")));
            boolean disabled=producing || idleOnly&&!Boolean.TRUE.equals(r.get("idle"));
            row.put("disabled",disabled);row.put("reason",producing?"正在执行任务":disabled?"当前要求仅无任务人员":Boolean.TRUE.equals(r.get("idle"))?"当前无在手任务":"将追加待接收队列");
            row.remove("tasks");return (Map<String,Object>)row;
        }).toList();
    }

    public List<Map<String,Object>> preview(Map<String,Object> fields, User assignee) {
        if (fields.containsKey("idleOnly") && !Set.of("true","false").contains(fields.get("idleOnly")))
            throw new BusinessException(400,"仅空闲人员选项必须为 true 或 false");
        String id = fields.get("planId").toString();
        String step = Objects.toString(fields.get("processStep"), "");
        if (step.isEmpty() ? dispatches.hasDispatchForPlan(id) : dispatches.hasDispatchForStep(id, step))
            throw new BusinessException(409, "所选计划或工序已有派工，请查看已有任务");
        var result = fields.containsKey("assignmentOverrides")
            ? recommendations.previewAssignments(id, overrides(fields.get("assignmentOverrides")), excluded(fields.get("excludedOperators")),
                "true".equals(fields.get("idleOnly")))
            : recommendations.generateRecommendations(id);
        if (fields.containsKey("workOrderNo") && !fields.get("workOrderNo").equals(result.get("workOrderId")))
            throw new BusinessException(409, "该计划的推荐工单与指定工单不匹配");
        List<Map<String,Object>> selected = new ArrayList<>();
        if (result.get("recommendations") instanceof List<?> rows) for (Object item : rows) {
            if (!(item instanceof Map<?,?> original) || !step.isBlank() && !step.equals(original.get("processStep"))) continue;
            Map<String,Object> row = new LinkedHashMap<>();
            original.forEach((key,value) -> row.put(String.valueOf(key),value));
            if (assignee != null) {
                String username = assignee.getUsername();
                row.put("recommendedOperator", username);
                row.put("recommendedOperatorName", Objects.requireNonNullElse(assignee.getRealName(), username));
                row.put("workshopName", OperatorWorkshopCatalog.workshopForOperator(username).workshopName());
                row.put("recommendReason", "主管指定操作员");
                for (String key : List.of("department","activeLoad","historyCount","conflicts","conflictStatus"))
                    row.remove(key);
            }
            if (Objects.toString(row.get("recommendedOperator"), "").isBlank() || Objects.toString(row.get("equipmentCode"), "").isBlank())
                throw new BusinessException(409, original.get("processStep") + " 缺少满足本轮限制的操作员或设备；请查询人员负载，调整排除名单或空闲限制，不能直接下发");
            selected.add(row);
        }
        if (selected.isEmpty() || !step.isEmpty() && selected.size() != 1)
            throw new BusinessException(409, "没有唯一匹配的工序安排");
        boolean valid = step.isBlank() ? Boolean.TRUE.equals(result.get("canSubmit"))
                : Boolean.TRUE.equals(recommendations.validateRecommendations(id, selected).get("canSubmit"));
        if (!valid) {
            Object validation = result.get("validation");
            String detail = validation instanceof Map<?,?> map && map.get("conflicts") instanceof List<?> conflicts
                ? conflicts.stream().filter(c -> c instanceof Map<?,?>).map(c -> Objects.toString(((Map<?,?>)c).get("detail"),""))
                    .filter(d -> !d.isBlank()).distinct().limit(8).collect(java.util.stream.Collectors.joining("；"))
                : "";
            throw new BusinessException(409, "派工推荐存在冲突：" + (detail.isBlank() ? "请检查工序安排" : detail));
        }
        return selected;
    }
}
