package com.upc.computer.assistant.v2.vision;

import com.upc.computer.common.JwtUtil;
import com.upc.computer.common.BusinessException;
import com.upc.computer.entity.QualityInspection;
import com.upc.computer.service.AuthService;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.mapper.QualityInspectionMapper;
import com.upc.computer.assistant.v2.security.AssistantSessionResolver;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.io.IOException;

/** The optional archive never changes or blocks the existing detection/business result. */
public class VisionEvidenceBindingService {
    private final AssistantSessionResolver sessions;
    private final AuthService auth;
    private final RoleMapper roles;
    private final QualityInspectionMapper inspections;
    public VisionEvidenceBindingService(JwtUtil jwt,AuthService auth,RoleMapper roles,QualityInspectionMapper inspections) {
        this.sessions=new AssistantSessionResolver(jwt,auth);this.auth=auth;this.roles=roles;this.inspections=inspections;
    }
    public Map<String,Object> archive(String authorization,String inspectionId,MultipartFile image,Map<String,Object> serverResult) {
        if(inspectionId==null||inspectionId.isBlank()) return Map.of("status","UNBOUND","reason","INSPECTION_ID_NOT_PROVIDED");
        long id;
        try {
            if(!inspectionId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            id=Long.parseLong(inspectionId);
            long userId=sessions.requireUserId(authorization);
            var user=auth.getCurrentUser(userId);
            var role=user!=null&&user.getRoleId()!=null?roles.getRoleById(user.getRoleId()):null;
            if(user==null||!Integer.valueOf(1).equals(user.getStatus())||role==null||!Integer.valueOf(1).equals(role.getStatus())||!Set.of("QC","ADMIN").contains(role.getRoleCode())) throw new IllegalArgumentException();
            if(inspections.getInspectionById(id)==null) throw new IllegalArgumentException();
        } catch(Exception failure) {return Map.of("status","BINDING_REJECTED","reason","ACTIVE_QUALITY_ROLE_AND_EXISTING_INSPECTION_REQUIRED");}
        try {return VisionEvidenceArchive.configured().save(id,image.getBytes(),serverResult);}
        catch(Exception failure) {return Map.of("status","NOT_SAVED","reason","ARCHIVE_UNAVAILABLE");}
    }

    /** New HTTP detections retain images without changing the existing inference result. */
    public Map<String,Object> archive(String authorization,String inspectionId,MultipartFile input,
            MultipartFile original,Map<String,Object> serverResult) {
        if(inspectionId==null||inspectionId.isBlank())return Map.of("status","UNBOUND","reason","INSPECTION_ID_NOT_PROVIDED");
        QualityInspection inspection;long id;
        try {id=inspectionId(inspectionId);inspection=requireInspection(authorization,id);}
        catch(Exception denied){return Map.of("status","BINDING_REJECTED","reason","ACTIVE_QUALITY_ROLE_AND_EXISTING_INSPECTION_REQUIRED");}
        try {
            Map<String,Object> snapshot=new LinkedHashMap<>();
            snapshot.put("inspectionNo",inspection.getInspectionNo());snapshot.put("batchNo",inspection.getBatchNo());
            snapshot.put("workOrderId",inspection.getWorkOrderId());snapshot.put("materialId",inspection.getMaterialId());
            return VisionEvidenceArchive.configured().save(id,input.getBytes(),original==null?null:original.getBytes(),serverResult,snapshot);
        }catch(Exception unavailable){return Map.of("status","NOT_SAVED","reason","ARCHIVE_UNAVAILABLE");}
    }

    public VisionEvidenceArchive.ImageAsset readImage(String authorization,String inspectionId,String recordId,String variant)throws IOException {
        long id=inspectionId(inspectionId);
        if(recordId==null||!recordId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                ||!Set.of("original","input","annotated").contains(variant))throw new BusinessException(400,"图片标识无效");
        requireInspection(authorization,id);
        return VisionEvidenceArchive.configured().readImage(id,recordId,variant);
    }

    private long inspectionId(String value) {
        try {if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}
        catch(IllegalArgumentException invalid){throw new BusinessException(400,"检验单标识无效");}
    }
    private QualityInspection requireInspection(String authorization,long id) {
        long userId=sessions.requireUserId(authorization);
        var user=auth.getCurrentUser(userId);
        var role=user!=null&&user.getRoleId()!=null?roles.getRoleById(user.getRoleId()):null;
        if(user==null||!Integer.valueOf(1).equals(user.getStatus())||role==null||!Integer.valueOf(1).equals(role.getStatus())
                ||!Set.of("QC","ADMIN").contains(role.getRoleCode()))throw new BusinessException(403,"当前账号无权查看质检图片");
        QualityInspection inspection=inspections.getInspectionById(id);
        if(inspection==null)throw new BusinessException(404,"图片依据不存在或不可访问");
        return inspection;
    }
}
