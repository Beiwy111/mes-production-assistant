package com.upc.computer.assistant.v2.tools.delivery;

import com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper;
import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessQueryEvidenceTest {
    @Test void qualityReadsRealSourceAndItsChangesInvalidatePreviouslyRegisteredDeliveryEvidence() {
        var base=mock(AiDeliveryReadMapper.class);var domain=mock(AiBusinessReadMapper.class);
        var head=List.<Map<String,Object>>of(Map.of("order_id",10L));
        when(base.resolve(10L,null)).thenReturn(head);when(base.order(10L)).thenReturn(head);
        when(domain.read(10L)).thenReturn(Map.of("quality_inspection",List.of(Map.of("inspection_id",1L,"inspection_status","PENDING"))));
        Clock clock=Clock.fixed(Instant.parse("2026-11-09T04:00:00Z"),ZoneOffset.UTC);
        var service=new AiDeliveryQueryService(base,domain,new DeliveryEvidenceRegistry(clock),clock);
        var scope=(String)service.resolve(1,"10",null,null,null).get("scopeRef");
        var q=service.query(1,"10",scope,"Q1",null,null,null);
        assertEquals("PARTIAL",q.get("status"));assertNotNull(q.get("queryRef"));
        assertTrue(((Map<?,?>)q.get("data")).containsKey("inspections"));
        when(domain.read(10L)).thenReturn(Map.of("quality_inspection",List.of(Map.of("inspection_id",1L,"inspection_status","PASSED"))));
        var check=service.revalidate(1,"10",scope,List.of((String)q.get("queryRef")),"1",DeliveryEvidenceRegistry.VERSION);
        assertEquals("CHANGED",((Map<?,?>)check.get("data")).get("comparedFactsStatus"));
    }
    @Test void newDomainQueryCannotBypassScopeOwnership() {
        var base=mock(AiDeliveryReadMapper.class);var domain=mock(AiBusinessReadMapper.class);
        var clock=Clock.systemUTC();var service=new AiDeliveryQueryService(base,domain,new DeliveryEvidenceRegistry(clock),clock);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.query(1,"10","forged","A1",null,null,null));
        verifyNoInteractions(base,domain);
    }
}
