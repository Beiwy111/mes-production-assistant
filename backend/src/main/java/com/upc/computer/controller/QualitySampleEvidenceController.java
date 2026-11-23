package com.upc.computer.controller;

import com.upc.computer.assistant.v2.security.AssistantSessionResolver;
import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.common.Result;
import com.upc.computer.dto.DefectSampleRequest;
import com.upc.computer.dto.SampleMeasurementRequest;
import com.upc.computer.mapper.RoleMapper;
import com.upc.computer.service.AuthService;
import com.upc.computer.service.QualitySampleEvidenceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/quality/nonconforming/{id}/sample-evidence")
public class QualitySampleEvidenceController {
    private final AssistantSessionResolver sessions;
    private final AuthService auth;
    private final RoleMapper roles;
    private final QualitySampleEvidenceService evidence;

    public QualitySampleEvidenceController(JwtUtil jwt, AuthService auth, RoleMapper roles, QualitySampleEvidenceService evidence) {
        this.sessions = new AssistantSessionResolver(jwt, auth);
        this.auth = auth;
        this.roles = roles;
        this.evidence = evidence;
    }

    @GetMapping
    public Result<Map<String,Object>> detail(@PathVariable long id,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        requireQualityUser(authorization);
        return Result.success(evidence.detail(id));
    }

    @PostMapping("/bindings")
    public Result<Long> bind(@PathVariable long id, @RequestHeader(value="Authorization",required=false) String authorization,
                             @RequestBody DefectSampleRequest input) {
        return Result.success(evidence.bind(id, requireQualityUser(authorization), input));
    }

    @PostMapping("/measurements")
    public Result<Long> record(@PathVariable long id, @RequestHeader(value="Authorization",required=false) String authorization,
                               @RequestBody SampleMeasurementRequest input) {
        return Result.success(evidence.record(id, requireQualityUser(authorization), input));
    }

    private long requireQualityUser(String authorization) {
        long id = sessions.requireUserId(authorization);
        var user = auth.getCurrentUser(id);
        var role = user != null && user.getRoleId() != null ? roles.getRoleById(user.getRoleId()) : null;
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) || role == null
                || !Integer.valueOf(1).equals(role.getStatus()) || !Set.of("ADMIN", "QC").contains(role.getRoleCode()))
            throw new BusinessException(403, "当前账号无权查看或登记样本证据");
        return id;
    }
}
