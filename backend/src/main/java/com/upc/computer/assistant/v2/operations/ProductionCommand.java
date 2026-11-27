package com.upc.computer.assistant.v2.operations;

import com.upc.computer.common.BusinessException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bind only identifiers/inputs a person can supply. Schedules and recipients come from MES. */
public enum ProductionCommand {
    AUDIT("auditOrder", "orderId", "CO", "审核.*订单|订单.*审核|通过.*(?:订单|CO\\d)|驳回.*(?:订单|CO\\d)", "action", "reason"),
    TO_PLANNER("submitOrderToPlanner", "orderId", "CO", "(?:转交|交给|提交|转给|送交).{0,8}计划员"),
    SCHEDULE("saveProductionPlan", "orderId", "CO", "排产|排程|(?:安排|制定|创建|生成|新建|编制|做).{0,8}(?:生产计划|计划)|安排生产", "planStart", "planEnd", "plannedQty", "schedulingMode", "planningTask", "orderIds", "fulfillment"),
    PUBLISH("publishPlan", "planId", "PP", "发布.{0,10}(?:计划|PP)|(?:计划|PP).{0,18}发布"),
    TO_MANAGER("submitPlanToManager", "planId", "PP", "(?:转交|交给|提交|转给|送交).{0,8}主管"),
    REMIND("production.dispatch.remind", "dispatchId", "DT", "(?:提醒|催办|催一下|催促|通知).{0,25}(?:操作员|现场|接单|派工|开工|DT)|(?:派工|DT).{0,22}(?:提醒|催办|催促)"),
    REPORT("submitReport", "dispatchId", "DT", "报工|完工登记|登记完工|完成.{0,5}(?:工作|派工|任务)", "reportQty", "startTime", "endTime"),
    PICK("pickAllMaterials", "dispatchId", "DT", "一键领料|领齐物料|领取(?:全部|所有)物料"),
    ACCEPT("acceptDispatch", "dispatchId", "DT", "(?:接收|接受|领取|接下).{0,12}(?:派工|任务|DT)|接单"),
    START("startDispatch", "dispatchId", "DT", "开工|开始生产|开始.{0,10}(?:派工|作业|DT)"),
    DISPATCH("confirmSmartDispatch", "planId", "PP", "派工|派给|指派给|分给|(?:分配|分派|指派).{0,12}(?:操作员|生产任务|工作)|让.{1,16}负责.{0,12}(?:工序|装配|贴附|组装)|(?:下达|下发).{0,6}(?:任务|工单)", "operator", "processStep", "workOrderNo", "assignmentOverrides", "excludedOperators", "idleOnly", "staffingPolicy");

    final String code, idField, prefix;
    final Pattern phrase, identifier;
    final Set<String> inputFields;

    ProductionCommand(String code, String idField, String prefix, String phrase, String... fields) {
        this.code = code;
        this.idField = idField;
        this.prefix = prefix;
        this.phrase = Pattern.compile(phrase, Pattern.CASE_INSENSITIVE);
        this.identifier = Pattern.compile("(?i)(?<![a-z0-9])" + prefix + "(?:[0-9][a-z0-9_-]*|[-_][a-z0-9_-]+)");
        Set<String> all = new HashSet<>(List.of(fields));
        all.add(idField);
        this.inputFields = Set.copyOf(all);
    }

    static ProductionCommand byCode(String code) {
        return Arrays.stream(values()).filter(command -> command.code.equals(code)).findFirst()
                .orElseThrow(() -> new BusinessException(400, "不支持的生产操作"));
    }

    static ProductionCommand detect(String text) {
        if (isInquiry(text)) return null;
        if (TO_PLANNER.phrase.matcher(text).find() && text.matches(".*(?:已审核|审核过|已通过|审核已通过).*")) return TO_PLANNER;
        return Arrays.stream(values()).filter(command -> command.phrase.matcher(text).find()).findFirst().orElse(null);
    }

    /** Shared by the chat router and proposal builder. Routing alone cannot execute a command. */
    public static boolean matchesRequest(String text) {
        return detect(text) != null;
    }

    static boolean isInquiry(String text) {
        if (Pattern.compile("怎么|怎样|如何|为什么|说明|介绍|查询|查一下|查看|有哪些|有多少|了吗|了没|是否|情况|进度|不要|别(?:再|给|做|提醒|派工)|取消|谁(?:来|在|负责|接单)|(?:给|由|是)谁|什么时候|什么是|哪(?:些|个|道)|能否查询").matcher(text).find()) return true;
        boolean politeRequest = Pattern.compile("帮我|替我|帮忙|麻烦|请(?:你|您)?|能否|能不能|可不可以").matcher(text).find();
        return !politeRequest && Pattern.compile("[吗么呢？?][。！!？?\\s]*$").matcher(text).find();
    }

    Map<String,Object> bind(String text, Map<String,Object> prior) {
        Map<String,Object> fields = new LinkedHashMap<>();
        if (prior != null) {
            for (String key : prior.keySet()) {
                if (!inputFields.contains(key)) throw new BusinessException(400, "不支持的操作字段：" + key);
                Object value = prior.get(key);
                if (value == null) continue;
                if (!(value instanceof String || value instanceof Number) || value.toString().length() > (Set.of("assignmentOverrides","excludedOperators","orderIds").contains(key) ? 4000 : 500))
                    throw new BusinessException(400, "操作字段格式错误：" + key);
                if (!value.toString().isBlank()) fields.put(key, value.toString().strip());
            }
        }
        if (this == DISPATCH && fields.containsKey("staffingPolicy")) {
            String policy = fields.get("staffingPolicy").toString();
            if (!Set.of("IDLE_FIRST","IDLE_ONLY").contains(policy)) throw new BusinessException(400,"请选择无任务优先或仅无任务人员");
            fields.put("idleOnly",String.valueOf("IDLE_ONLY".equals(policy)));
        }
        Set<String> ids = new LinkedHashSet<>();
        identifier.matcher(text).results().forEach(match -> ids.add(match.group().toUpperCase(Locale.ROOT)));
        if (ids.size() > 1 && this == SCHEDULE) { fields.remove("orderId");fields.put("orderIds",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(ids).toString());ids.clear(); }
        if (ids.size() > 1) throw new BusinessException(400, "请每次指定一个业务编号，以便分别确认");
        if (!ids.isEmpty()) {
            String id = ids.iterator().next();
            if (fields.containsKey(idField) && !id.equalsIgnoreCase(fields.get(idField).toString())) fields.clear();
            fields.put(idField, id);
        }
        if (fields.containsKey(idField)) {
            String id = fields.get(idField).toString().toUpperCase(Locale.ROOT);
            if (!identifier.matcher(id).matches() || id.length() > 80)
                throw new BusinessException(400, "请提供以 " + prefix + " 开头的完整业务编号");
            fields.put(idField, id);
        }
        if (this == AUDIT) {
            if (text.contains("驳回") || text.contains("不通过") || text.contains("拒绝") || text.equals("reject")) fields.put("action", "reject");
            else if (text.contains("通过") || text.equals("pass")) fields.put("action", "pass");
            String action = Objects.toString(fields.get("action"), "");
            if (action.equals("通过")) fields.put("action", "pass");
            if (action.equals("驳回")) fields.put("action", "reject");
            Matcher reason = Pattern.compile("(?:理由|原因)[：:为是\\s]*(.+)").matcher(text);
            if (reason.find()) fields.put("reason", reason.group(1).strip());
            else if ("reject".equals(fields.get("action")) && fields.containsKey("orderId") && !text.isBlank()
                    && !phrase.matcher(text).find() && !identifier.matcher(text).find() && !text.matches("reject|驳回|不通过|拒绝"))
                fields.putIfAbsent("reason", text);
        }
        if (this == SCHEDULE) {
            if(fields.containsKey("orderIds")) {
                com.upc.computer.service.ProductionPortfolioPlanner.orderNumbers(fields.get("orderIds"));
                if(fields.containsKey("orderId"))throw new BusinessException(400,"单订单与多订单范围不能同时指定");
            }
            if(fields.containsKey("fulfillment") && !Set.of("STOCK_FIRST","NEW_PRODUCTION").contains(fields.get("fulfillment")))
                throw new BusinessException(400,"请选择库存优先或全量新生产");
            Matcher dates = Pattern.compile("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)").matcher(text);
            if (dates.find()) {
                String first = dates.group();
                if (dates.find()) {
                    fields.put("planStart", first);
                    fields.put("planEnd", dates.group());
                } else if (text.matches(".*(?:结束|截止|截至|完工|交期).*") || fields.containsKey("planStart"))
                    fields.put("planEnd", first);
                else fields.put("planStart", first);
            }
            Matcher qty = Pattern.compile("(?<![\\d.])([1-9]\\d*)\\s*(?:台|件|套)").matcher(text);
            if (qty.find()) fields.put("plannedQty", qty.group(1));
            if (text.contains("交期优先")) fields.put("schedulingMode", "DELIVERY");
            if (text.contains("成本优先")) fields.put("schedulingMode", "COST");
            if (text.contains("均衡")) fields.put("schedulingMode", "BALANCE");
            if (text.strip().matches("(?i)BALANCE|DELIVERY|COST")) fields.put("schedulingMode", text.strip().toUpperCase(Locale.ROOT));
        }
        if (this == REPORT) {
            Matcher qty = Pattern.compile("(?<![\\d.])([1-9]\\d*)\\s*(?:台|件|套)").matcher(text);
            if (qty.find()) fields.put("reportQty", qty.group(1));
            else if (text.matches("[1-9]\\d{0,8}")) fields.put("reportQty", text);
            Matcher times = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2})?").matcher(text);
            if (times.find()) {
                String first = times.group();
                if (times.find()) {
                    fields.put("startTime", first);
                    fields.put("endTime", times.group());
                } else fields.put(fields.containsKey("startTime") || text.contains("结束") ? "endTime" : "startTime", first);
            }
        }
        return fields;
    }
}
