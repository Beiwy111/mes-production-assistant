package com.upc.computer.assistant.v2.runtime;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisplayedWorkOrdersTest {
    @Test void bindsOnlyTheCompleteReturnedSet() {
        var rows=List.of(Map.of("record_id","22","work_order_no","WO-A"),Map.of("record_id","23","work_order_no","WO-B"));
        var ids=DisplayedWorkOrders.fromFacts(Map.of("production",Map.of("overview",Map.of("rows",rows))));
        assertEquals(List.of(22L,23L),ids);
        assertEquals(ids,DisplayedWorkOrders.previous(Map.of("scope",Map.of("confirmed",true),"displayedWorkOrders",ids)));
        assertTrue(DisplayedWorkOrders.fromFacts(Map.of("production",Map.of("overview",Map.of("rows",rows,"truncated",true)))).isEmpty());
        assertTrue(DisplayedWorkOrders.previous(Map.of("scope",Map.of("confirmed",false),"displayedWorkOrders",ids)).isEmpty());
    }
    @Test void qualifiersAreNotSilentlyDropped() {
        assertTrue(DisplayedWorkOrders.qualityFollowup("这批工单的质检情况怎么样"));
        assertTrue(DisplayedWorkOrders.qualityFollowup("帮我看看上述工单的检验结果"));
        assertFalse(DisplayedWorkOrders.qualityFollowup("这批工单上个月的检验结果怎么样"));
        assertFalse(DisplayedWorkOrders.qualityFollowup("同批有没有质检异常"));
    }
}
