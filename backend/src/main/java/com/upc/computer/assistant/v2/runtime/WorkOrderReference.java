package com.upc.computer.assistant.v2.runtime;

import java.util.*;
import java.util.regex.Pattern;

/** Recognizes references, not instructions. A DB lookup must still authorize and disambiguate. */
final class WorkOrderReference {
    static final String BATCH_QUESTION="请提供对应的生产工单号，再核对该工单关联批次的检验记录。要判断缺陷原因，还需对照检验项目、缺陷明细或原始图片。";
    static boolean multipleReferences(String query) {
        Set<String> references=new HashSet<>();
        for(String pattern:List.of("(?<![A-Za-z0-9])WO[A-Za-z0-9_-]+","(?:订单|工单)\\s*(?:ID|主键|编号|号)?\\s*[:：#]?\\s*([0-9]{1,19})(?![0-9])","(?<![A-Za-z0-9])([0-9]{1,19})(?:这个|这张|号)?(?:订单|工单)")) {
            var matcher=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(query);
            while(matcher.find())references.add((matcher.groupCount()>0?matcher.group(1):matcher.group()).toUpperCase(Locale.ROOT));
        }
        return references.size()>1;
    }
    // Only standalone read questions can inherit a work order. Never discard extra selectors.
    static String focus(String query) {
        String q=query.strip().replaceAll("[？?。！!]+$","").replaceFirst("(?i)^(?:WO[A-Z0-9_-]+|[0-9]{1,18})(?:这个订单|这张工单|这个工单)?","").strip();
        if(q.matches("(?:那|那么)?(?:这张工单|这个工单|工单|该工单)?(?:现在)?(?:派给谁了|派给谁|派工了吗|谁负责|派工给谁了)"))return "ASSIGNEE";
        if(q.matches("(?:那|那么)?(?:这张工单|这个工单|该工单)?(?:还有)?(?:哪些|哪道|哪个|哪几道)工序(?:还)?(?:没做完|没完成|未完成)"))return "STEPS";
        if(q.matches("(?:那|那么)?(?:同批|同一批|同批次|这个批次)(?:的)?(?:有没有|有无|是否有)(?:质检|检验|质量)异常"))return "BATCH";
        return null;
    }
    static boolean needsAnchor(String q){return focus(q)!=null||q.matches(".*(?:同批|哪道工序|哪些工序).*(?:异常|没做完|未完成).*");}
    static boolean needsBatchIdentity(String query) {
        return Pattern.compile("(?:这(?:一)?批|该批|本批|同(?:一)?批).*(?:质量|质检|检验|复检|不良|不合格|缺陷|异常|色斑|反复|根因)").matcher(query).find();
    }
    static String batchClarificationAnswer(String query,Map<String,Object> previous) {
        if(previous==null||!(previous.get("plan") instanceof Map<?,?> plan)||!"CLARIFY".equals(plan.get("action"))
            ||!BATCH_QUESTION.equals(plan.get("question")))return null;
        var code=Pattern.compile("(?i)^(?:工单(?:号|编号)?[：:#]?\\s*)?(WO[A-Z0-9_-]+)[。]?$").matcher(query.strip());
        return code.matches()?code.group(1):null;
    }
    static String extract(String query,Map<String,Object> previous) {
        boolean continuation=Pattern.compile("你发给我的就是这个|就是这[个张]|那它|该工单|这个工单|刚才.*工单").matcher(query).find();
        boolean inquiry=Pattern.compile("派工.*(?:了吗|了么|情况|记录|谁|什么)|(?:查|看|是否|有没有|谁).*派工|工单.*(?:进度|情况)|生产进度").matcher(query).find();
        String focus=focus(query);
        if(focus!=null){continuation=true;inquiry=true;}
        if(focus==null&&Pattern.compile("安排|指派|派给|下发|帮我派工|请派工|执行派工").matcher(query).find())return null;
        boolean bare=query.trim().matches("(?i)WO[A-Z0-9_-]+")&&previous!=null&&String.valueOf(previous.get("userQuery")).contains("派工");
        if(!inquiry&&!continuation&&!bare)return null;
        var full=Pattern.compile("(?<![A-Za-z0-9])WO[A-Za-z0-9_-]+",Pattern.CASE_INSENSITIVE).matcher(query);
        if(full.find()){String value=full.group();return full.find()?null:value;}
        for(String expression:List.of("(?<![A-Za-z0-9])([0-9]{1,19})(?:这个|这张|号)?(?:订单|工单)","(?:订单|工单)\\s*(?:ID|主键|编号|号)?\\s*[:：#]?\\s*([0-9]{1,19})(?![0-9])")) {
            var number=Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(query);
            if(number.find()){String value=number.group(1);return number.find()?null:value;}
        }
        if(continuation&&previous!=null&&previous.get("scope") instanceof Map<?,?> scope&&Boolean.TRUE.equals(scope.get("confirmed"))&&scope.get("workOrderNo") instanceof String value)return value;
        return null;
    }
}
