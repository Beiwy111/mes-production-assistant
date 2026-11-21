package com.upc.computer.assistant.v2;

import com.upc.computer.assistant.v2.vision.VisionEvidenceBindingService;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.controller.QualityVisionController;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.entity.*;
import com.upc.computer.mapper.*;
import com.upc.computer.service.AuthService;
import com.upc.computer.service.YoloVisionClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ResourceLock("assistant.vision.evidence-dir")
class VisionImageAccessTest {
    @TempDir Path dir;
    JwtUtil jwt=mock(JwtUtil.class); AuthService auth=mock(AuthService.class);
    RoleMapper roles=mock(RoleMapper.class); QualityInspectionMapper inspections=mock(QualityInspectionMapper.class);
    YoloVisionClient yolo=mock(YoloVisionClient.class);
    VisionEvidenceBindingService binding=new VisionEvidenceBindingService(jwt,auth,roles,inspections);
    ObjectMapper json=new ObjectMapper(); MockMvc mvc; byte[] png; String previous;
    @BeforeEach void setup() throws Exception {
        previous=System.getProperty("assistant.vision.evidence-dir");System.setProperty("assistant.vision.evidence-dir",dir.toString());
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4,3,BufferedImage.TYPE_INT_RGB),"png",out);png=out.toByteArray();
        when(jwt.extractTokenFromHeader("Bearer login")).thenReturn("login");when(jwt.validateToken("login")).thenReturn(true);
        LoginResponse session=new LoginResponse();session.setUserId(2L);when(auth.getLoginSession("login")).thenReturn(session);
        User user=new User();user.setRoleId(3L);user.setStatus(1);when(auth.getCurrentUser(2L)).thenReturn(user);
        Role role=new Role();role.setRoleCode("QC");role.setStatus(1);when(roles.getRoleById(3L)).thenReturn(role);
        QualityInspection i=new QualityInspection();i.setInspectionId(7L);i.setInspectionNo("QI-7");i.setBatchNo("DB-BATCH");i.setWorkOrderId(5L);i.setMaterialId(8L);
        when(inspections.getInspectionById(7L)).thenReturn(i);
        var controller=new QualityVisionController();controller.configureVisionEvidenceBinding(jwt,auth,roles,inspections);
        ReflectionTestUtils.setField(controller,"yoloVisionClient",yolo);
        mvc=MockMvcBuilders.standaloneSetup(controller).build();when(yolo.detect(any())).thenReturn(result());
    }
    @AfterEach void cleanup(){if(previous==null)System.clearProperty("assistant.vision.evidence-dir");else System.setProperty("assistant.vision.evidence-dir",previous);}
    Map<String,Object> result(){return Map.of("rawResultValid",true,"defect",false,"count",0,"maxConfidence",0.0,"detections",List.of(),"mock",false,"model","test","resultImage","data:image/png;base64,"+Base64.getEncoder().encodeToString(png));}
    MockMultipartFile file(String field){return new MockMultipartFile(field,"image.png","image/png",png);}
    String save()throws Exception {
        var r=binding.archive("Bearer login","7",file("file"),file("originalFile"),result());
        assertThat(r).containsEntry("status","SAVED");return r.get("recordId").toString();
    }
    @Test void uploadedOriginalAndAnnotationUseDatabaseBindingAndAuthenticatedBinaryEndpoint()throws Exception {
        var response=mvc.perform(multipart("/quality/vision/detect").file(file("file")).file(file("originalFile"))
                .param("inspectionId","7").param("batchNo","UNTRUSTED-BATCH").header("Authorization","Bearer login"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var body=json.readTree(response.getContentAsByteArray());assertThat(body.at("/data/archiveStatus/status").asText()).isEqualTo("SAVED");
        String id=body.at("/data/archiveStatus/recordId").asText();
        var manifest=json.readTree(Files.readAllBytes(dir.resolve("7").resolve(id+".json")));
        assertThat(manifest.path("batchNo").asText()).isEqualTo("DB-BATCH");assertThat(manifest.path("inspectionNo").asText()).isEqualTo("QI-7");
        for(String variant:List.of("original","input","annotated"))mvc.perform(get("/quality/vision/evidence/7/"+id+"/"+variant).header("Authorization","Bearer login"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(png))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        verify(inspections,never()).updateInspection(any());
    }
    @Test void readAlwaysChecksCurrentSessionRoleAndInspectionNotOnlyPossessionOfRecordId()throws Exception {
        String id=save(),path="/quality/vision/evidence/7/"+id+"/original";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        roles.getRoleById(3L).setRoleCode("SERVICE");mvc.perform(get(path).header("Authorization","Bearer login")).andExpect(status().isForbidden());
        roles.getRoleById(3L).setRoleCode("ADMIN");when(auth.getLoginSession("login")).thenReturn(null);
        mvc.perform(get(path).header("Authorization","Bearer login")).andExpect(status().isUnauthorized());
    }
    @Test void missingBindingAndInvalidSelectorsCannotExposeFiles()throws Exception {
        String id=save();
        mvc.perform(get("/quality/vision/evidence/999/"+id+"/original").header("Authorization","Bearer login")).andExpect(status().isNotFound());
        mvc.perform(get("/quality/vision/evidence/7/"+id+"/secret").header("Authorization","Bearer login")).andExpect(status().isBadRequest());
        mvc.perform(get("/quality/vision/evidence/7/not-a-record/original").header("Authorization","Bearer login")).andExpect(status().isBadRequest());
        when(inspections.getInspectionById(7L)).thenReturn(null);
        mvc.perform(get("/quality/vision/evidence/7/"+id+"/original").header("Authorization","Bearer login")).andExpect(status().isNotFound());
    }
    @Test void optionalArchiveCannotBreakExistingUnboundInference()throws Exception {
        mvc.perform(multipart("/quality/vision/detect").file(file("file"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.archiveStatus.status").value("UNBOUND"));
        assertThat(Files.list(dir).toList()).isEmpty();
        mvc.perform(multipart("/quality/vision/detect").file(file("file")).file(file("originalFile")).param("inspectionId","7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.archiveStatus.status").value("BINDING_REJECTED"));
    }
}
