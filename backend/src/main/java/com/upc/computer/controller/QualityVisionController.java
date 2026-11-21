package com.upc.computer.controller;

import com.upc.computer.common.BusinessException;
import com.upc.computer.common.Result;
import com.upc.computer.service.VisionReportAiService;
import com.upc.computer.service.YoloVisionClient;
import com.upc.computer.assistant.v2.vision.VisionEvidenceBindingService;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.service.AuthService;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.mapper.QualityInspectionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/quality/vision")
public class QualityVisionController {

    @Autowired
    private YoloVisionClient yoloVisionClient;

    @Autowired
    private VisionReportAiService visionReportAiService;

    private VisionEvidenceBindingService visionEvidenceBindingService;

    /** Bind the optional archive only inside the existing quality controller, not module scanning. */
    @Autowired
    public void configureVisionEvidenceBinding(JwtUtil jwt, AuthService auth,
            RoleMapper roles, QualityInspectionMapper inspections) {
        this.visionEvidenceBindingService = new VisionEvidenceBindingService(jwt, auth, roles, inspections);
    }

    /** YOLO 外观缺陷检测：上传图片，返回缺陷类型、置信度与标注图 */
    @PostMapping("/detect")
    public Result<Map<String, Object>> detect(@RequestParam("file") MultipartFile file,
            @RequestParam(value="inspectionId", required=false) String inspectionId,
            @RequestParam(value="originalFile", required=false) MultipartFile originalFile,
            @RequestHeader(value="Authorization", required=false) String authorization) {
        Map<String,Object> result=new java.util.LinkedHashMap<>(yoloVisionClient.detect(file));
        result.put("archiveStatus",visionEvidenceBindingService.archive(authorization,inspectionId,file,originalFile,result));
        return Result.success(result);
    }

    /** Images are served only after fresh MES session/role and inspection checks. */
    @GetMapping("/evidence/{inspectionId}/{recordId}/{variant}")
    public ResponseEntity<byte[]> evidenceImage(@PathVariable String inspectionId,@PathVariable String recordId,
            @PathVariable String variant,@RequestHeader(value="Authorization",required=false) String authorization) {
        try {
            var image=visionEvidenceBindingService.readImage(authorization,inspectionId,recordId,variant);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mimeType()))
                    .header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                    .header("Cross-Origin-Resource-Policy","same-origin").body(image.bytes());
        }catch(BusinessException denied) {
            int status=java.util.Set.of(400,401,403,404).contains(denied.getCode())?denied.getCode():503;
            return ResponseEntity.status(status).header("Cache-Control","no-store").build();
        }catch(java.io.IOException absent) {
            return ResponseEntity.status(404).header("Cache-Control","no-store").build();
        }catch(Exception unavailable) {
            return ResponseEntity.status(503).header("Cache-Control","no-store").build();
        }
    }

    /**
     * 基于已完成的 YOLO 检测结果，一键生成外观检测 AI 报告（千问；失败时模板兜底）。
     * body: { context: {...}, images: [{ name, defect, count, maxConfidence, summary }] }
     */
    @PostMapping("/report")
    public Result<Map<String, Object>> report(@RequestBody Map<String, Object> body) {
        if (body == null) throw new BusinessException("请求体不能为空");
        Object images = body.get("images");
        if (!(images instanceof List<?> list) || list.isEmpty()) {
            throw new BusinessException("请先完成至少一张图片的 YOLO 检测");
        }
        return Result.success(visionReportAiService.generate(body));
    }
}
