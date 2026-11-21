package com.upc.computer.assistant.v2.runtime;

import com.upc.computer.assistant.v2.tools.delivery.AiDeliveryQueryService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceToolRegistryTest {
    private static final String REF="12345678-1234-4234-9234-123456789abc";
    @Test void closedCatalogCoversEvidenceAndDocumentsRoleAndDependencies() {
        assertEquals(Set.of("E0","E1","E2","E3","E4","E5","Q1","D1","A1"),EvidenceToolRegistry.catalog().stream().map(EvidenceToolRegistry.Definition::kind).collect(java.util.stream.Collectors.toSet()));
        assertTrue(EvidenceToolRegistry.catalog().stream().allMatch(d->d.readOrWrite().equals("READ")&&!d.parametersSchema().isEmpty()));
        assertTrue(EvidenceToolRegistry.catalog().stream().allMatch(d->!((List<?>)d.resultSchema().get("required")).contains("kind")));
    }
    @Test void unknownToolRoleFieldsAndForgedOwnerNeverReachBusinessService() {
        var service=mock(AiDeliveryQueryService.class);
        var request=Map.<String,Object>of("orderPk","100","scopeRef",REF);
        assertThrows(IllegalArgumentException.class,()->EvidenceToolRegistry.execute(service,1,"SUPPLY","E1",request));
        assertThrows(IllegalArgumentException.class,()->EvidenceToolRegistry.execute(service,1,"ORDER","DELETE",request));
        assertThrows(IllegalArgumentException.class,()->EvidenceToolRegistry.execute(service,0,"ORDER","E1",request));
        var extra=new HashMap<>(request);extra.put("ownerUserId",999);
        assertThrows(IllegalArgumentException.class,()->EvidenceToolRegistry.execute(service,1,"ORDER","E1",extra));
        verifyNoInteractions(service);
    }
    @Test void dependenciesAreRequiredAndExactBindingsAreDelegatedUnchanged() {
        var service=mock(AiDeliveryQueryService.class);
        var request=new HashMap<String,Object>(Map.of("orderPk","100","scopeRef",REF));
        assertThrows(IllegalArgumentException.class,()->EvidenceToolRegistry.execute(service,7,"SUPPLY","E2",request));
        verifyNoInteractions(service);
        request.put("contextRef",REF);request.put("fulfillmentRef",REF);
        when(service.query(7,"100",REF,"E2",REF,REF,null)).thenReturn(Map.of("queryRef",REF));
        assertEquals(REF,EvidenceToolRegistry.execute(service,7,"SUPPLY","E2",request).get("queryRef"));
        verify(service).query(7,"100",REF,"E2",REF,REF,null);
    }
}
