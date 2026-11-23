package com.upc.computer.assistant.v2.runtime;

import java.util.*;
import java.util.regex.Pattern;

/** Resolve explicit evidence requests without replacing identifiers, dates or permissions from the plan. */
final class BusinessReadIntent {
    private static final Pattern OTHER_CUSTOMER=Pattern.compile("(?:另(?:外)?(?:一[个位家]?)?|其他|其它|别的)客户");
    private static final Pattern DISCLOSE=Pattern.compile("给|发给|发送|转发|分享|共享|展示|出示|查看|看这");
    private static final Pattern PUBLIC=Pattern.compile("公开(?:资料|文献|研究|方法|标准|规范)|行业(?:标准|规范)|通用(?:方法|规范|原理)");
    private static final Pattern LOCAL=Pattern.compile("本厂|我厂|我们(?:工厂|厂里)|厂内|本车间|生产现场|(?:本次|当前|历史|实际|现有)(?:的)?(?:设备|检验|质检|质量|报警|维修|维护)(?:记录|数据|台账|工单|情况|经验|案例|问题)|维修记录|维修工单|质检记录|检验记录|报警记录");
    private static final Pattern TECHNICAL_SUBJECT=Pattern.compile("显示器|液晶屏|显示屏|面板|量具|测量仪|控制图|抽检|抽样|不良品占比|不合格率|不良率|(?i:\\bLCD\\b|\\bGR&R\\b)");
    private static final Pattern PRACTICAL_TASK=Pattern.compile("排(?:查|障)|自检|复测|验证|复核|诊断|辨[别识]|区分|(?:怎样|怎么|如何|何时|先|从哪).{0,12}(?:判[断定]|检[测查]|测[试量]|安排|监[测控]|跟踪|查起|定位|设计|取样|验收)|故障定位");
    private static final Pattern TEXT_TASK=Pattern.compile("翻译|译成|翻成|改写|润色|缩写|写(?:一[个篇首段封])?(?:故事|小说|诗|邮件|通知)|造句|笑话|假设|虚构|不(?:用|要|必)(?:查|检索)|只(?:改|翻|译)|什么是|是什么意思|解释.{0,12}概念");

    /** A model may treat a practical technical question as everyday chat. Only narrow GENERAL to public guidance. */
    static boolean needsTechnicalGuidance(String query) {
        return TECHNICAL_SUBJECT.matcher(query).find() && PRACTICAL_TASK.matcher(query).find()
            && !TEXT_TASK.matcher(query).find() && !LOCAL.matcher(query).find() && !crossCustomerDisclosure(query);
    }

    static boolean crossCustomerDisclosure(String query) {
        return query.contains("订单")&&OTHER_CUSTOMER.matcher(query).find()&&DISCLOSE.matcher(query).find();
    }

    static boolean unresolvedEquipment(String query,String keyword) {
        return Pattern.compile("这台设备|该设备|这台机器").matcher(query).find()
            &&!BusinessSelectors.EQUIPMENT_CODE.matcher(query).find()
            &&(keyword==null||!query.contains(keyword)||Set.of("设备","机器","这台设备","该设备","这台机器","维修","故障","报警").contains(keyword));
    }

    static List<String> specialists(String query,List<String> proposed) {
        boolean local=LOCAL.matcher(query).find(),published=PUBLIC.matcher(query).find();
        if(published&&!local&&!Pattern.compile("订单|工单|库存|派工|批次|这批|售后案件|设备编号").matcher(query).find())return List.of("KNOWLEDGE");
        if(!local)return proposed;
        var domains=new LinkedHashSet<>(proposed);
        if(!published)domains.remove("KNOWLEDGE");
        if(Pattern.compile("维修|维保|维护记录").matcher(query).find())domains.add("MAINTENANCE");
        if(Pattern.compile("质检|检验|质量|色斑|缺陷").matcher(query).find())domains.add("QUALITY");
        if(Pattern.compile("报警|告警").matcher(query).find())domains.add("ALARMS");
        if(Pattern.compile("设备(?:台账|状态|故障)|故障(?:诊断|排查)").matcher(query).find()
            &&!domains.contains("MAINTENANCE")&&!domains.contains("ALARMS"))domains.add("EQUIPMENT");
        if(published)domains.add("KNOWLEDGE");
        return domains.isEmpty()?proposed:List.copyOf(domains);
    }
}
