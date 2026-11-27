package com.upc.computer.assistant.v2.runtime;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessSelectorsTest {
    @Test void inventoryTypesUseCatalogSemanticsIndependentlyOfSpecificSkuOrStockLocation() {
        for(var e:Map.of("查物料库存","RAW,SEMI,PACK,SPARE","不要成品库存","RAW,SEMI,PACK,SPARE","查原材料库存","RAW",
                "查成品库存","FINISHED","查半成品库存","SEMI","查包装材料库存","PACK","查备件库存","SPARE").entrySet())
            assertEquals(e.getValue(),BusinessSelectors.parse(e.getKey(),List.of("INVENTORY"),null).values().get("material_types"));
        for(String query:List.of("查全部库存","查物料编码PRD-001的库存","按物料列出库存","查PCB板库存"))
            assertEquals(Map.of(),BusinessSelectors.parse(query,List.of("INVENTORY"),null).values(),query);
        for(String query:List.of("我只查物料库存，那成品就不应该返回回来","查物料库存，不要返回成品","库存里排除成品","列出全部物料库存，不要包含成品"))
            assertEquals("RAW,SEMI,PACK,SPARE",BusinessSelectors.parse(query,List.of("INVENTORY"),null).values().get("material_types"),query);
        var mixed=BusinessSelectors.parse("列出物料库存和所有工单",List.of("INVENTORY","PRODUCTION"),null);
        assertEquals("RAW,SEMI,PACK,SPARE",mixed.values().get("material_types"));
        assertTrue(InventoryCategory.forDomain("PRODUCTION",mixed.values()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->BusinessSelectors.validate("INVENTORY",Map.of("material_types","UNKNOWN")));
    }
    @Test void customerNegationCannotTurnIntoPositiveSelection() {
        for(String q:List.of("查询客户‘甲’和‘乙’以外的订单","查询不属于客户‘甲’的订单","客户‘甲’的订单除外","查询除了客户‘甲’之外的订单","查询客户‘甲’和乙公司的订单","查询客户‘甲’和‘乙’、丙公司的订单","查询客户‘甲’与乙","查询客户甲与客户乙的订单"))
            assertThrows(IllegalArgumentException.class,()->BusinessSelectors.parse(q,List.of("ORDER"),null),q);
    }
    @Test void multipleQuotedCustomersPreservePunctuationAndReplaceConfirmedSelection() {
        var parsed=BusinessSelectors.parse("查询客户‘甲,乙公司’和客户‘丙公司’的订单",List.of("ORDER"),null);
        assertEquals("[\"丙公司\",\"甲,乙公司\"]",parsed.values().get("customer_names"));
        assertEquals(parsed.values(),BusinessSelectors.parse("查询客户‘甲,乙公司’与‘丙公司’的订单",List.of("ORDER"),null).values());
        var previous=Map.<String,Object>of("scope",Map.of("confirmed",true,"selectors",parsed.values()));
        assertEquals(parsed.values(),BusinessSelectors.parse("其中待审核的订单",List.of("ORDER"),previous).values());
        assertEquals(Map.of("customer_name","丁公司"),BusinessSelectors.parse("其中改查客户‘丁公司’的订单",List.of("ORDER"),previous).values());
        assertThrows(IllegalArgumentException.class,()->BusinessSelectors.parse("查询客户‘甲’和客户‘乙’的质量",List.of("QUALITY"),null));
        assertThrows(IllegalArgumentException.class,()->BusinessSelectors.parse("不要客户‘甲’的订单",List.of("ORDER"),null));
    }
    @Test void multipleCodesAndExclusionAreGroundedAndCanonical() {
        var parsed=BusinessSelectors.parse("查仓库WH-02、WH-01的PCB库存，排除库位A-02、A-03",List.of("INVENTORY"),null);
        assertEquals(Map.of("warehouse_codes","WH-01,WH-02","excluded_location_codes","A-02,A-03"),parsed.values());
        assertTrue(parsed.remainingText().contains("PCB"));assertFalse(parsed.remainingText().contains("WH-"));
        assertEquals("WH-01,WH-02",BusinessSelectors.parse("WH-01和WH-02仓库库存",List.of("INVENTORY"),null).values().get("warehouse_codes"));
        assertEquals("WH-01",BusinessSelectors.parse("查询不含仓库WH-01的库存",List.of("INVENTORY"),null).values().get("excluded_warehouse_codes"));
        assertEquals("WH-01,WH-02",BusinessSelectors.parse("排除仓库WH-01和仓库WH-02的库存",List.of("INVENTORY"),null).values().get("excluded_warehouse_codes"));
        assertEquals("WH-01",BusinessSelectors.parse("查询不包括仓库WH-01的库存",List.of("INVENTORY"),null).values().get("excluded_warehouse_codes"));
    }
    @Test void followupInheritsAndExplicitDimensionReplacesPreviousSelection() {
        var previous=Map.<String,Object>of("scope",Map.of("confirmed",true,"selectors",Map.of("warehouse_codes","WH-01,WH-02","location_code","A-01")));
        assertEquals("WH-01,WH-02",BusinessSelectors.parse("其中可用库存",List.of("INVENTORY"),previous).values().get("warehouse_codes"));
        var replaced=BusinessSelectors.parse("其中改查仓库WH-03",List.of("INVENTORY"),previous).values();
        assertEquals("WH-03",replaced.get("warehouse_code"));assertFalse(replaced.containsKey("warehouse_codes"));
        assertEquals(Map.of(),BusinessSelectors.parse("查全部订单",List.of("ORDER"),previous).values());
    }
    @Test void malformedOrUnsupportedScopeNeverWidensToGlobal() {
        for(String query:List.of("查仓库WH-01和一号仓库库存","查WH-01仓库除外的库存","查仓库WH-01和库位未知的库存","查询不只仓库WH-01的库存","查询非仓库WH-01的库存","排除仓库WH-01和库位A-01的库存"))
            assertThrows(IllegalArgumentException.class,()->BusinessSelectors.parse(query,List.of("INVENTORY"),null),query);
        assertThrows(IllegalArgumentException.class,()->BusinessSelectors.parse("仓库WH-01、WH-02的质量",List.of("QUALITY"),null));
        assertThrows(IllegalArgumentException.class,()->BusinessSelectors.validate("INVENTORY",Map.of("warehouse_codes","WH-01,' OR 1=1")));
        assertThrows(IllegalArgumentException.class,()->BusinessSelectors.validate("INVENTORY",Map.of("warehouse_codes","WH-01,WH-01")));
    }
    @Test void customerLiteralIsNotReparsedAsWarehouseOrExclusion() {
        assertEquals("不要仓库WH-01公司",BusinessSelectors.parse("查询客户名称为‘不要仓库WH-01公司’的订单",List.of("ORDER"),null).values().get("customer_name"));
    }
}
