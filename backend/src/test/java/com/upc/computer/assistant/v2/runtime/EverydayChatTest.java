package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EverydayChatTest {
    @Test void productionRequestsAndReadQuestionsRemainSeparate() {
        for (String text : java.util.List.of("请为订单 CO123 制定生产计划","把已审核的订单提交给计划员","请把主板装配派给张三",
                "可以帮我为计划 PP123 派工吗？","你能帮我去排产吗","帮我去制定生产计划呢","智能排产","下发订单","报工 DT123，完成了10台"))
            assertEquals("PRODUCTION_ACTION",EverydayChat.localRoute(text,"NEW"),text);
        for (String text : java.util.List.of("帮我看看派工情况","这张工单派给谁了","怎么排产？","帮我查一下物料库存","我要下单100台显示器"))
            assertEquals("FALLBACK",EverydayChat.localRoute(text,"PRODUCTION_ACTION"),text);
        assertEquals("PRODUCTION_ACTION",EverydayChat.localRoute("CO123","PRODUCTION_ACTION"));
        assertEquals("PRODUCTION_ACTION",EverydayChat.localRoute("交期优先","PRODUCTION_ACTION"));
        assertEquals("FALLBACK",EverydayChat.localRoute("交期优先","NEW"));
        assertEquals("FALLBACK",EverydayChat.localRoute("CO123","NEW"));
    }
    @Test void homonymousVillagesDoNotHideTheCapitalAndExplicitRegionWins() throws Exception {
        var candidates=new ObjectMapper().readTree("""
            [{"name":"北京","admin1":"北京市","feature_code":"PPLC"},
             {"name":"北京","admin1":"重庆市","feature_code":"PPL"}]
            """);
        assertEquals(candidates.get(0),EverydayChat.choosePlace(candidates,""));
        assertEquals(candidates.get(1),EverydayChat.choosePlace(candidates,"重庆"));
        assertNull(EverydayChat.choosePlace(candidates,"四川"));
        var ambiguous=new ObjectMapper().readTree("""
            [{"name":"朝阳","admin1":"吉林","feature_code":"PPLA3"},
             {"name":"朝阳","admin1":"辽宁","feature_code":"PPLA3"}]
            """);
        assertNull(EverydayChat.choosePlace(ambiguous,""));
    }
}
