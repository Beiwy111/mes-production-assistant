package com.upc.computer.assistant.v2.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EverydayChatTest {
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
