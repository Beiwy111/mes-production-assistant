package com.upc.computer.service;

import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.controller.QualitySampleEvidenceController;
import com.upc.computer.dto.DefectSampleRequest;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.dto.SampleMeasurementRequest;
import com.upc.computer.entity.Role;
import com.upc.computer.entity.User;
import com.upc.computer.mapper.QualitySampleEvidenceMapper;
import com.upc.computer.mapper.QualitySampleEvidenceMapper.*;
import com.upc.computer.mapper.RoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualitySampleEvidenceTest {
    static final String KEY = "5e8baad1-4876-4273-8877-144cff9ec085";
    static final LocalDateTime TIME = LocalDateTime.of(2025, 7, 2, 8, 15);
    final QualitySampleEvidenceMapper mapper = mock(QualitySampleEvidenceMapper.class);
    final QualitySampleEvidenceService service = new QualitySampleEvidenceService(mapper);
    final Scope scope = new Scope(31, "QI-31", 18L, 6L);
    final Step step = new Step(53, "面板贴附");

    @BeforeEach void scope() {
        when(mapper.lockDefect(12)).thenReturn(12L);
        when(mapper.lockScope(12)).thenReturn(scope);
        when(mapper.lockStep(6, 53)).thenReturn(step);
        when(mapper.sameMeasurement(anyLong(),any())).thenReturn(null);
    }

    @Test void manualBindingNeedsOriginalInspectionAndSourceAndStoresNoInventedItem() {
        assertThrows(BusinessException.class, () -> service.bind(12, 7, new DefectSampleRequest(KEY,null,"QI-30","SN9","原始单 33")));
        assertThrows(BusinessException.class, () -> service.bind(12, 7, new DefectSampleRequest(KEY,null,"QI-31","SN9"," ")));
        when(mapper.bindingReceipt(12, KEY)).thenReturn(null, new Receipt(5, ""));
        assertEquals(5, service.bind(12, 7, new DefectSampleRequest(KEY,null,"QI-31","SN9","原始单 33")));
        verify(mapper).insertBinding(eq(12L),eq(31L),isNull(),eq("SN9"),eq("SN9"),eq("MANUAL_SOURCE"),
                eq("原始单 33"),eq(7L),eq(KEY),anyString());
    }

    @Test void itemBindingIsScopedAndSnapshotsTheStoredItem() {
        var r = new DefectSampleRequest(KEY,89L,null,null,"原始单 35");
        assertThrows(BusinessException.class, () -> service.bind(12, 7, r));
        when(mapper.lockItem(31, 89)).thenReturn(new Item(89,"UNIT-002","抽检成品 A02"));
        when(mapper.bindingReceipt(12, KEY)).thenReturn(null, new Receipt(8,""));
        assertEquals(8, service.bind(12, 7, r));
        verify(mapper).insertBinding(eq(12L),eq(31L),eq(89L),eq("UNIT-002"),eq("抽检成品 A02"),eq("MES_ITEM"),
                eq("原始单 35"),eq(7L),eq(KEY),anyString());
    }

    SampleMeasurementRequest reading(String before, LocalDateTime beforeAt, String after, LocalDateTime afterAt, Long prior) {
        return new SampleMeasurementRequest(KEY, 8L, prior, 53L, "电压", "V", before, beforeAt, after, afterAt, "仪表 880 页 2");
    }

    @Test void noImplicitReadingsOrTimeAndChronologyAndPrecisionAreEnforced() {
        for (var r : java.util.List.of(reading(null,null,null,null,null), reading("1",null,null,null,null),
                reading("1",TIME,"2",TIME.minusMinutes(1),null), reading("1.000000009",TIME,null,null,null))) {
            assertThrows(BusinessException.class, () -> service.record(12, 7, r));
        }
        verify(mapper, never()).insertMeasurement(anyLong(),any(),any(),any(),anyLong(),anyString());
    }

    @Test void exactRetryReturnsFirstIdAndChangedReadingConflicts() {
        var r = reading("0.0", TIME, null, null, null);
        when(mapper.measurementReceipt(12, KEY)).thenReturn(null,new Receipt(42,""));
        when(mapper.insertMeasurement(anyLong(),any(),any(),any(),anyLong(),anyString())).thenReturn(1);
        assertEquals(42, service.record(12, 7, r));
        var fingerprint = ArgumentCaptor.forClass(String.class);
        var saved = ArgumentCaptor.forClass(SampleMeasurementRequest.class);
        verify(mapper).insertMeasurement(eq(12L),eq(scope),eq(step),saved.capture(),eq(7L),fingerprint.capture());
        assertEquals("0", saved.getValue().beforeValue());
        assertNull(saved.getValue().afterValue());
        when(mapper.measurementReceipt(12, KEY)).thenReturn(new Receipt(42,fingerprint.getValue()));
        assertEquals(42, service.record(12, 7, r));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.record(12, 7,
                reading("1",TIME,null,null,null))).getCode());
        verify(mapper, times(1)).insertMeasurement(anyLong(),any(),any(),any(),anyLong(),anyString());
    }

    @Test void bothBindingAndStepMustBelongToDefectScope() {
        var r = reading("1",TIME,"2",TIME,null);
        assertThrows(BusinessException.class, () -> service.record(12, 7, r)); // scoped INSERT SELECT matched no binding
        when(mapper.lockStep(6, 53)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.record(12, 7, r));
        verify(mapper,times(1)).insertMeasurement(anyLong(),any(),any(),any(),anyLong(),anyString());
    }

    @Test void reenteringSameSourceReadingWithFreshRequestCannotDuplicateEvidence() {
        when(mapper.sameMeasurement(anyLong(),any())).thenReturn(91L);
        var conflict = assertThrows(BusinessException.class, () -> service.record(12, 8,
                reading("1",TIME,"2",TIME,null)));
        assertEquals(409, conflict.getCode());
        assertTrue(conflict.getMessage().contains("#91"));
        verify(mapper, never()).insertMeasurement(anyLong(),any(),any(),any(),anyLong(),anyString());
    }

    @Test void laterObservationMustCompleteExactSameOpenRecord() {
        var r = reading(null,null,"2",TIME.plusMinutes(1),100L);
        for (var earlier : java.util.List.of(
                new Earlier(9,53,"电压","V",TIME,null,false),new Earlier(8,53,"电压","mV",TIME,null,false),
                new Earlier(8,53,"电压","V",null,TIME,false),new Earlier(8,53,"电压","V",TIME,null,true),
                new Earlier(8,53,"电压","V",TIME.plusMinutes(2),null,false))) {
            when(mapper.earlier(12,31,100)).thenReturn(earlier);
            assertThrows(BusinessException.class, () -> service.record(12, 7, r));
        }
        when(mapper.earlier(12,31,100)).thenReturn(new Earlier(8,53,"电压","V",TIME,null,false));
        when(mapper.insertMeasurement(anyLong(),any(),any(),any(),anyLong(),anyString())).thenReturn(1);
        when(mapper.measurementReceipt(12,KEY)).thenReturn(null,new Receipt(102,""));
        assertEquals(102, service.record(12,7,r));
    }

    @Test void authenticatedActiveQualityRoleRequiredForReadsAndWrites() {
        var auth = mock(AuthService.class);
        var jwt = mock(JwtUtil.class);
        var roles = mock(RoleMapper.class);
        var controller = new QualitySampleEvidenceController(jwt, auth, roles, service);
        assertEquals(401, assertThrows(BusinessException.class, () -> controller.detail(12,null)).getCode());
        var login = new LoginResponse();
        login.setUserId(7L);
        when(jwt.extractTokenFromHeader("Bearer t")).thenReturn("t");
        when(jwt.validateToken("t")).thenReturn(true);
        when(auth.getLoginSession("t")).thenReturn(login);
        var user = new User();
        user.setRoleId(3L);
        user.setStatus(1);
        when(auth.getCurrentUser(7L)).thenReturn(user);
        var role = new Role();
        role.setStatus(1);
        role.setRoleCode("CUSTOMER");
        when(roles.getRoleById(3L)).thenReturn(role);
        assertEquals(403, assertThrows(BusinessException.class, () -> controller.bind(12,"Bearer t", null)).getCode());
        role.setRoleCode("QC");
        when(mapper.scope(12)).thenReturn(scope);
        assertEquals(200,controller.detail(12,"Bearer t").getCode());
    }
}
