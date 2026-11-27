package com.upc.computer.assistant.v2.runtime;

import java.util.*;
import java.util.regex.Pattern;

/** Material master types used by the warehouse catalog. An inventory record's bin is independent of its type. */
enum InventoryCategory {
    ALL("全部", "RAW,SEMI,FINISHED,PACK,SPARE"),
    MATERIALS("物料", "RAW,SEMI,PACK,SPARE"),
    RAW("原材料", "RAW"),
    FINISHED("成品", "FINISHED"),
    SEMI("半成品", "SEMI"),
    PACK("包装材料", "PACK"),
    SPARE("备件", "SPARE");

    final String label;
    final String types;
    InventoryCategory(String label, String types) { this.label=label; this.types=types; }

    static final String KEY="material_types";
    private static final String TERMS="半成品|非成品|成品|原材料|原料|包装材料|包装物料|备品备件|备件|物料(?!编号|编码|名称|类型|主键|ID)|材料|产品(?!编号|编码|名称)";
    private static final Pattern TERM=Pattern.compile(TERMS);
    private static final Pattern NEGATED=Pattern.compile("(?:排除|除了|剔除|不看|不查|不含|不包括|不包含|不是|并非|(?:不应(?:该)?|无需|不用|不想|不要|不需要|别)(?:查(?:询)?|看|显示|列出|返回|包含|包括|统计|展示|算上|算入|把)?)\\s*$");
    private static final Pattern EXCLUDED_AFTER=Pattern.compile("^\\s*(?:的?库存)?(?:以外|之外|除外|(?:就|则|也)?(?:别|不用|不要|不应(?:该)?|不需要)(?:查|看|显示|列|返回|包括|统计|展示|算)?)");
    static boolean hasCategoryTerms(String query) { return TERM.matcher(query).find(); }
    static boolean switchesCategory(String query) {
        return query.strip().matches("(?:那(?:么)?|改(?:查|看)|换(?:成|查|看))(?:再)?(?:查|看)?(?:一下)?(?:只看)?(?:"+TERMS+")(?:的?库存)?(?:有多少|呢)?[？?。]?");
    }

    static boolean genericKeyword(String keyword) {
        return keyword!=null && keyword.matches("(?:"+TERMS+")(?:的?(?:可用)?库存)?");
    }
    static InventoryCategory fromTypes(String types) {
        return Arrays.stream(values()).filter(c->c.types.equals(types)).findFirst().orElseThrow();
    }
    static InventoryCategory explicit(String query) {
        var matcher=TERM.matcher(query);
        Set<String> included=new HashSet<>(),excluded=new HashSet<>();
        while(matcher.find()){
            String term=matcher.group();
            var category=switch(term){
                case "成品","产品" -> FINISHED;
                case "原材料","原料" -> RAW;
                case "半成品" -> SEMI;
                case "包装材料","包装物料" -> PACK;
                case "备品备件","备件" -> SPARE;
                default -> MATERIALS;
            };
            var prefix=query.substring(0,matcher.start());
            var suffix=query.substring(matcher.end());
            // A type followed by an explicit item name/code is also commonly a classifier ("物料 PCB").
            if((term.equals("物料")||term.equals("材料")||term.equals("产品"))
                    && (suffix.matches("(?s)^\\s*[:：A-Za-z0-9“「‘].*") || prefix.matches("(?s).*(?:按|每个|每种|各种|各个)$")))continue;
            if(prefix.matches("(?s).*(?:不仅|不只|不光|不限于)(?:查|看)?$"))
                throw new IllegalArgumentException("Specify the inventory categories to include");
            boolean negative=NEGATED.matcher(prefix).find()||EXCLUDED_AFTER.matcher(suffix).find();
            (negative?excluded:included).addAll(List.of(category.types.split(",")));
        }
        if(included.isEmpty()&&excluded.isEmpty())return null;
        if(included.isEmpty())included.addAll(List.of(ALL.types.split(",")));
        included.removeAll(excluded);
        return Arrays.stream(values()).filter(c->included.equals(Set.of(c.types.split(",")))).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Specify the inventory categories to include"));
    }
    static Map<String,String> forDomain(String domain,Map<String,String> selectors){
        if(domain.equals("INVENTORY")||!selectors.containsKey(KEY))return selectors;
        var scoped=new LinkedHashMap<>(selectors);scoped.remove(KEY);return scoped;
    }
}
