package com.upc.computer.assistant.v2.runtime;

import java.util.*;
import java.util.regex.Pattern;

/** Recognizes references, not instructions. A DB lookup must still authorize and disambiguate. */
final class WorkOrderReference {
    static boolean multipleReferences(String query) {
        Set<String> references=new HashSet<>();
        for(String pattern:List.of("(?<![A-Za-z0-9])WO[A-Za-z0-9_-]+","(?:订单|工单)\\s*(?:ID|主键|编号|号)?\\s*[:：#]?\\s*([0-9]{1,19})(?![0-9])","(?<![A-Za-z0-9])([0-9]{1,19})(?:这个|这张|号)?(?:订单|工单)")) {
            var matcher=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(query);
            while(matcher.find())references.add((matcher.groupCount()>0?matcher.group(1):matcher.group()).toUpperCase(Locale.ROOT));
        }
        return references.size()>1;
    }
    static String extract(String query,Map<String,Object> previous) {
        boolean continuation=Pattern.compile("你发给我的就是这个|就是这[个张]|那它|该工单|这个工单|刚才.*工单").matcher(query).find();
        boolean inquiry=Pattern.compile("派工.*(?:了吗|了么|情况|记录|谁|什么)|(?:查|看|是否|有没有|谁).*派工|工单.*(?:进度|情况)|生产进度").matcher(query).find();
        if(Pattern.compile("安排|指派|派给|下发|帮我派工|请派工|执行派工").matcher(query).find())return null;
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
