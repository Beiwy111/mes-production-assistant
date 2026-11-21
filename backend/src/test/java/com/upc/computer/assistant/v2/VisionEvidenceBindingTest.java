package com.upc.computer.assistant.v2;

import com.upc.computer.assistant.v2.vision.VisionEvidenceBindingService;
import com.upc.computer.assistant.v2.vision.VisionEvidenceArchive;
import com.upc.computer.assistant.v2.vision.QualityEvidenceAnalysis;
import com.upc.computer.assistant.v2.mapper.delivery.AiDeliveryReadMapper;
import com.upc.computer.assistant.v2.mapper.business.AiBusinessReadMapper;
import com.upc.computer.assistant.v2.tools.delivery.*;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import com.upc.computer.service.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock("assistant.vision.evidence-dir")
class VisionEvidenceBindingTest {
    @TempDir Path dir;
    JwtUtil jwt=mock(JwtUtil.class); AuthService auth=mock(AuthService.class);
    RoleMapper roles=mock(RoleMapper.class); QualityInspectionMapper inspections=mock(QualityInspectionMapper.class);
    VisionEvidenceBindingService binding=new VisionEvidenceBindingService(jwt,auth,roles,inspections);
    MockMultipartFile image=new MockMultipartFile("file","screen.jpg","image/jpeg",new byte[]{1,2,3});
    String previous;
    @BeforeEach void setup() {
        previous=System.getProperty("assistant.vision.evidence-dir");System.setProperty("assistant.vision.evidence-dir",dir.toString());
        when(jwt.extractTokenFromHeader("Bearer login")).thenReturn("login");when(jwt.validateToken("login")).thenReturn(true);
        LoginResponse session=new LoginResponse();session.setUserId(2L);session.setRoleCode("ADMIN");when(auth.getLoginSession("login")).thenReturn(session);
        User user=new User();user.setRoleId(3L);user.setStatus(1);when(auth.getCurrentUser(2L)).thenReturn(user);
        Role role=new Role();role.setRoleCode("QC");role.setStatus(1);when(roles.getRoleById(3L)).thenReturn(role);
        QualityInspection inspection=new QualityInspection();inspection.setInspectionId(7L);when(inspections.getInspectionById(7L)).thenReturn(inspection);
    }
    @AfterEach void cleanup() {if(previous==null)System.clearProperty("assistant.vision.evidence-dir");else System.setProperty("assistant.vision.evidence-dir",previous);}
    @Test void legacyUnboundDetectionNeedsNoExtraSessionAndWritesNothing() throws Exception {
        assertThat(binding.archive(null,null,image,Map.of("rawResultValid",true))).containsEntry("status","UNBOUND");verifyNoInteractions(auth,inspections);
        try(var files=Files.list(dir)){assertThat(files.count()).isZero();}
    }
    @Test void liveRoleOverridesCachedRoleAndMissingInspectionCannotBind() {
        roles.getRoleById(3L).setRoleCode("SERVICE");
        assertThat(binding.archive("Bearer login","7",image,Map.of("rawResultValid",true))).containsEntry("status","BINDING_REJECTED");verify(inspections,never()).getInspectionById(anyLong());
        roles.getRoleById(3L).setRoleCode("QC");
        assertThat(binding.archive("Bearer login","999",image,Map.of("rawResultValid",true))).containsEntry("status","BINDING_REJECTED");
        assertThat(binding.archive("Bearer login","../7",image,Map.of("rawResultValid",true))).containsEntry("status","BINDING_REJECTED");
    }
    @Test void revokedSessionAndDisabledUserOrRoleCannotArchive() {
        when(auth.getLoginSession("login")).thenReturn(null);
        assertThat(binding.archive("Bearer login","7",image,Map.of("rawResultValid",true))).containsEntry("status","BINDING_REJECTED");
        LoginResponse session=new LoginResponse();session.setUserId(2L);when(auth.getLoginSession("login")).thenReturn(session);
        auth.getCurrentUser(2L).setStatus(0);
        assertThat(binding.archive("Bearer login","7",image,Map.of("rawResultValid",true))).containsEntry("status","BINDING_REJECTED");
        auth.getCurrentUser(2L).setStatus(1);roles.getRoleById(3L).setStatus(0);
        assertThat(binding.archive("Bearer login","7",image,Map.of("rawResultValid",true))).containsEntry("status","BINDING_REJECTED");
    }
    @Test void authorizedSaveRetainsMockFlagAndArchiveFailureIsExplicit() throws Exception {
        var data=Map.<String,Object>of("model","YOLO (Mock)","algorithmVersion","MOCK_V1","mock",true,"rawResultValid",true);
        assertThat(binding.archive("Bearer login","7",image,data)).containsEntry("status","SAVED").containsEntry("mock",true);
        assertThat(VisionEvidenceArchive.configured().snapshotRows(Set.of(7L)).toString()).contains("MOCK_V1");
        Path blocked=dir.resolve("not-a-directory");Files.writeString(blocked,"existing");System.setProperty("assistant.vision.evidence-dir",blocked.toString());
        assertThat(binding.archive("Bearer login","7",image,data)).containsEntry("status","NOT_SAVED");
        verify(inspections,never()).updateInspection(any());
    }
    @Test void newArchiveInvalidatesExistingAuthorizedOrderEvidence() {
        AiDeliveryReadMapper delivery=mock(AiDeliveryReadMapper.class);AiBusinessReadMapper business=mock(AiBusinessReadMapper.class);
        var head=List.<Map<String,Object>>of(Map.of("order_id",10L,"order_no","O-10"));
        when(delivery.resolve(10L,null)).thenReturn(head);when(delivery.order(10L)).thenReturn(head);
        when(business.read(10L)).thenReturn(Map.of("quality_inspection",List.of(Map.of("inspection_id",7L,"relation_status","EXPLICIT"))));
        Clock clock=Clock.systemUTC();var service=new AiDeliveryQueryService(delivery,business,new DeliveryEvidenceRegistry(clock),clock);
        String scope=(String)service.resolve(2L,"10",null,null,null).get("scopeRef");
        var before=service.query(2L,"10",scope,"Q1",null,null,null);
        assertThat(binding.archive("Bearer login","7",image,Map.of("rawResultValid",true))).containsEntry("status","SAVED");
        var checked=service.revalidate(2L,"10",scope,List.of((String)before.get("queryRef")),"1",DeliveryEvidenceRegistry.VERSION);
        assertThat(((Map<?,?>)checked.get("data")).get("comparedFactsStatus")).isEqualTo("CHANGED");
    }
    @Test void overviewOnlyClaimsHeadMetricsAndFingerprintsItsAuthorizedInspectionArchive() {
        var rows=List.<Map<String,Object>>of(Map.of("record_id",7L,"status","UNQUALIFIED","event_at","2026-11-09T09:00:00","sample_quantity",10,"qualified_quantity",9,"unqualified_quantity",1));
        var before=QualityEvidenceAnalysis.overview(rows,true);
        var metrics=(Map<?,?>)before.get("qualityMetrics");
        assertThat(metrics.get("failedInspectionRecordCount")).isEqualTo(1L);
        assertThat(metrics.get("validSampleRecordCount")).isEqualTo(1L);
        assertThat(metrics.get("failedItemRecordCount")).isNull();
        assertThat(metrics.get("nonconformingRecordCount")).isNull();
        binding.archive("Bearer login","7",image,Map.of("rawResultValid",true));
        var after=QualityEvidenceAnalysis.overview(rows,true);
        assertThat(after.get("visionEvidenceFingerprint")).isNotEqualTo(before.get("visionEvidenceFingerprint"));
        assertThat(((Map<?,?>)after.get("visionEvidence")).get("recordCount")).isEqualTo(1);
        var partial=QualityEvidenceAnalysis.overview(rows,false);
        assertThat(((Map<?,?>)partial.get("qualityMetrics")).get("inspectionRecordCount")).isNull();
    }
}
