package com.upc.computer.service;

import com.upc.computer.common.JwtUtil;
import com.upc.computer.entity.QualityInspection;
import com.upc.computer.entity.QualityInspectionItem;
import com.upc.computer.mapper.NonconformingProductMapper;
import com.upc.computer.mapper.QualityInspectionItemMapper;
import com.upc.computer.mapper.QualityInspectionMapper;
import com.upc.computer.mapper.QualityInspectionRoundMapper;
import com.upc.computer.service.impl.QualityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.upc.computer.service.InspectionRoundHistory.Event.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectionRoundHistoryTest {
    private final QualityInspectionRoundMapper rounds = mock(QualityInspectionRoundMapper.class);
    private final InspectionRoundHistory history = new InspectionRoundHistory(rounds, mock(JwtUtil.class));
    private final QualityServiceImpl service = new QualityServiceImpl();
    private final QualityInspection inspection = new QualityInspection();
    private final QualityInspectionItemMapper items = mock(QualityInspectionItemMapper.class);
    private final List<Map<String,Object>> events = new ArrayList<>();

    @BeforeEach
    void setup() {
        inspection.setInspectionId(11L);
        inspection.setInspectionStatus("PENDING");
        inspection.setInspectionResult("PENDING");
        inspection.setInspectionType("FINAL");
        when(rounds.lockInspection(11L)).thenReturn(inspection);
        when(rounds.insertSnapshot(anyMap())).thenAnswer(call -> {
            Map<String,Object> event = call.getArgument(0);
            event.put("roundId", (long) events.size() + 1);
            events.add(Map.copyOf(event));
            return 1;
        });
        ReflectionTestUtils.setField(service, "history", history);
        ReflectionTestUtils.setField(service, "itemMapper", items);
        ReflectionTestUtils.setField(service, "inspectionMapper", mock(QualityInspectionMapper.class));
        ReflectionTestUtils.setField(service, "nonconformingMapper", mock(NonconformingProductMapper.class));
        ReflectionTestUtils.setField(service, "mesWorkflowService", mock(MesWorkflowService.class));
    }

    @Test
    void capturesLegacyItemsBeforeDestructiveReplacementWithoutClaimingInitialMeasurement() {
        when(rounds.lastStage(11, true)).thenReturn(null, "PRIOR_STATE", "PRIOR_STATE");
        var replacement = measured("2.8");
        service.saveItems(11L, List.of(replacement));
        var order = inOrder(rounds, items);
        order.verify(rounds).lockInspection(11L);
        order.verify(rounds).copyItems(11L, 1L);
        order.verify(items).deleteByInspectionId(11L);
        order.verify(items).insert(replacement);
        order.verify(rounds).copyItems(11L, 2L);
        service.saveItems(11L, List.of(measured("1.9")));
        assertEquals(List.of("PRIOR_STATE", "ITEMS_SAVED", "ITEMS_SAVED"), eventTypes());
        assertNull(events.get(0).get("operator"));
    }

    @Test
    void firstMeasuredSaveAndDecisionHaveIndependentStagesOnTrackedNewSheet() {
        when(rounds.lastStage(11, true)).thenReturn("OPENED");
        when(rounds.lastStage(11, false)).thenReturn("OPENED");
        var template = new QualityInspectionItem();
        template.setResult("PENDING");
        assertEquals(ITEMS_SAVED, history.itemSaveEvent(inspection, List.of(template)));
        service.saveItems(11L, List.of(measured("3.8")));
        when(rounds.lastStage(11, true)).thenReturn("INITIAL_ITEMS");
        service.saveItems(11L, List.of(measured("2.8")));
        assertEquals(List.of("INITIAL_ITEMS","ITEMS_SAVED"), eventTypes());
        assertEquals(INITIAL_DECISION, history.decisionEvent(inspection));
        inspection.setInspectionType("RECHECK");
        assertEquals(DECISION_SAVED, history.decisionEvent(inspection));
    }

    @Test
    void historyFailureBeforeReplacementLeavesExistingMeasurementsAlone() {
        when(rounds.insertSnapshot(anyMap())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.saveItems(11L, List.of(measured("1.9"))));
        verifyNoInteractions(items);
    }

    @Test
    void recordsExplicitRecheckDecisionWithoutInventingANewItemMeasurement() {
        inspection.setInspectionStatus("FAILED");
        inspection.setInspectionResult("UNQUALIFIED");
        when(rounds.lastStage(11L, true)).thenReturn(null, "RECHECK_REQUESTED");
        service.requireRecheck(11L, "复核色准", "qc1");
        service.recheckPass(11L, "复核通过", "qc2");
        assertEquals(List.of("PRIOR_STATE", "RECHECK_REQUESTED", "RECHECK_PASSED"), eventTypes());
        assertEquals("qc2", events.get(2).get("operator"));
        assertEquals("REPORTED", events.get(2).get("operatorSource"));
        assertEquals("QUALIFIED", inspection.getInspectionResult());
        verifyNoInteractions(items);
    }

    @Test
    void attributesSignedRequestToItsActorWhileLeavingHistoricalActorUnknown() {
        var jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "jwtSecret", "inspection-round-unit-test-signing-key");
        ReflectionTestUtils.setField(jwt, "jwtExpireHours", 1L);
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt.generateToken(4L, "qc-signed", 2L, "QC"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var attributed = new InspectionRoundHistory(rounds, jwt);
            attributed.beforeWrite(11L);
            attributed.append(11L, RECHECK_FAILED, "different-body-name");
            assertNull(events.get(0).get("operator"));
            assertEquals("qc-signed", events.get(1).get("operator"));
            assertEquals("SIGNED_TOKEN", events.get(1).get("operatorSource"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private List<String> eventTypes() {
        return events.stream().map(row -> row.get("event").toString()).toList();
    }

    private static QualityInspectionItem measured(String value) {
        var item = new QualityInspectionItem();
        item.setMeasuredValue(value);
        return item;
    }
}
