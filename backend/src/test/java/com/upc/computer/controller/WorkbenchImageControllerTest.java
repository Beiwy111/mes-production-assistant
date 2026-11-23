package com.upc.computer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.config.*;
import com.upc.computer.dto.LoginResponse;
import com.upc.computer.service.AuthService;
import com.upc.computer.service.YoloVisionClient;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbenchImageControllerTest {
    final JwtUtil jwt=mock(JwtUtil.class);
    final AuthService auth=mock(AuthService.class);
    final YoloVisionClient detector=mock(YoloVisionClient.class);
    final AiProperties settings=new AiProperties();
    final AiClient vision=mock(AiClient.class);
    WorkbenchImageController controller(AiClient client,YoloVisionClient yolo){
        when(jwt.extractTokenFromHeader("Bearer local")).thenReturn("local");
        when(jwt.validateToken("local")).thenReturn(true);
        when(auth.getLoginSession("local")).thenReturn(new LoginResponse());
        return new WorkbenchImageController(auth,jwt,client,settings,yolo,new ObjectMapper());
    }
    MockMultipartFile picture() throws Exception {
        var image=new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,200,200);g.setColor(Color.RED);g.fillOval(30,30,140,140);g.dispose();
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        return new MockMultipartFile("file","shape.png","image/png",out.toByteArray());
    }
    @Test void requiresActiveSessionAndValidImage() throws Exception {
        var api=controller(vision,detector);
        assertThrows(BusinessException.class,()->api.ask(null,picture(),"图中是什么","auto"));
        var invalid=new MockMultipartFile("file","fake.png","image/png",new byte[]{1,2,3});
        assertThrows(BusinessException.class,()->api.ask("Bearer local",invalid,"","auto"));
        verifyNoInteractions(vision,detector);
    }
    @Test void ignoresSimulatedInference() throws Exception {
        var api=controller(vision,detector);
        when(detector.detect(any())).thenReturn(Map.of("rawResultValid",true,"mock",true));
        assertThrows(BusinessException.class,()->api.ask("Bearer local",picture(),"","screen"));
        verifyNoInteractions(vision);
    }
    @Test void referencesRequireSameAccountAndSessionAndUseAnnotatedFrame() throws Exception {
        var memory=new ChatImageMemory();
        var marked=new BufferedImage(100,50,BufferedImage.TYPE_INT_RGB);
        var out=new ByteArrayOutputStream(); ImageIO.write(marked,"jpg",out);
        var result=Map.<String,Object>of("count",1,"resultImage","data:image/jpeg;base64,"+java.util.Base64.getEncoder().encodeToString(out.toByteArray()),
            "detections",java.util.List.of(Map.of("box",java.util.List.of(60,0,90,10))));
        var ref=memory.save(8,"session-a",picture().getBytes(),"png","prior-run",result);
        var photo=memory.read(8,"session-a",ref);
        assertEquals("prior-run",photo.workRun());
        assertTrue(photo.locations().get(0).contains("右上"));
        assertTrue(photo.locations().get(0).contains("60.0%–90.0%"));
        assertThrows(BusinessException.class,()->memory.read(8,"session-b",ref));
        assertThrows(BusinessException.class,()->memory.read(9,"session-a",ref));
        var empty=memory.save(8,"session-a",picture().getBytes(),"png",null,Map.of("count",0));
        assertEquals(0,memory.read(8,"session-a",empty).count());
        var unavailable=memory.save(8,"session-a",picture().getBytes(),"png",null,null);
        assertNull(memory.read(8,"session-a",unavailable).count());
    }
    @Test void ordinaryPhotosSkipYoloAndScreensUseBothTools() throws Exception {
        var api=controller(vision,detector);
        when(vision.callWorkbenchVision(any(),anyString(),anyString())).thenReturn("ordinary","screen");
        when(vision.extractJson("ordinary")).thenReturn("{\"screen\":false,\"answer\":\"红色圆形\"}");
        when(vision.extractJson("screen")).thenReturn("{\"screen\":true,\"answer\":\"屏幕照片\"}");
        assertEquals("红色圆形",api.ask("Bearer local",picture(),"什么图形","auto").getData().get("answer"));
        verifyNoInteractions(detector);
        when(detector.detect(any())).thenReturn(Map.of("rawResultValid",true,"mock",false,"count",1));
        assertTrue(api.ask("Bearer local",picture(),"检查屏幕","auto").getData().get("answer").contains("1 处划痕候选"));
        verify(detector).detect(any());
    }
    @Test void zeroScratchBoxesStillInterpretsVisibleMarksAndSuggestsADiscriminatingCheck() throws Exception {
        var api=controller(vision,detector);
        when(detector.detect(any())).thenReturn(Map.of("rawResultValid",true,"mock",false,"count",0));
        when(vision.callWorkbenchVision(any(),anyString(),contains("YOLO 未检出划痕候选框"))).thenReturn("reading");
        when(vision.extractJson("reading")).thenReturn("""
            {"screen":true,"answer":"屏幕右侧有浅色斑点","hypothesis":"优先排查表面附着物","verify":"清洁并在相同位置复拍"}
            """);
        var result=api.ask("Bearer local",picture(),"看下这个屏幕，缺陷情况怎么样","auto").getData();
        assertEquals("0",result.get("detectionCount"));
        for (String expected: new String[]{"未检出划痕","浅色斑点","附着物","复拍"})
            assertTrue(result.get("answer").contains(expected));
        assertFalse(result.containsKey("annotatedImage"));
    }
    @Test @EnabledIfEnvironmentVariable(named="WORKBENCH_LIVE_VISION",matches="1")
    void liveVisionDescribesAnUnlabelledImage() throws Exception {
        settings.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        settings.setModel("qwen-vl-plus");
        var answer=controller(new AiClient(settings),detector).ask("Bearer local",picture(),"描述图形及颜色","auto").getData().get("answer");
        assertTrue(answer.contains("红")&&answer.contains("圆"),answer);
        verifyNoInteractions(detector);
    }
}
