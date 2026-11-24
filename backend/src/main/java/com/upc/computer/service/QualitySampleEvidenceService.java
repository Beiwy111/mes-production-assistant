package com.upc.computer.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.upc.computer.common.BusinessException;
import com.upc.computer.dto.DefectSampleRequest;
import com.upc.computer.dto.SampleMeasurementRequest;
import com.upc.computer.mapper.QualitySampleEvidenceMapper;
import com.upc.computer.mapper.QualitySampleEvidenceMapper.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class QualitySampleEvidenceService {
    private final QualitySampleEvidenceMapper mapper;

    public QualitySampleEvidenceService(QualitySampleEvidenceMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Map<String,Object> detail(long id) {
        Scope scope = requireScope(mapper.scope(id));
        return Map.of("inspectionNo", scope.inspectionNo(), "items", mapper.items(scope.inspectionId()),
                "steps", scope.routeId() == null ? List.of() : mapper.steps(scope.routeId()),
                "bindings", mapper.bindings(id, scope.inspectionId()), "measurements", mapper.measurements(id, scope.inspectionId()));
    }

    @Transactional
    public long bind(long id, long actor, DefectSampleRequest r) {
        Scope scope = lock(id);
        String key = key(r.requestId());
        String source = text(r.sourceRef(), 255, "关联依据");
        boolean manual = r.inspectionItemId() == null;
        String label = manual ? text(r.sampleLabel(), 120, "原始样本标识") : null;
        String inspectionNo = manual ? text(r.inspectionNo(), 50, "原始记录上的质检单号") : null;
        if (!manual && (r.sampleLabel() != null || r.inspectionNo() != null))
            throw invalid("MES 样本标识由所选检测项确定");
        if (manual && !scope.inspectionNo().equals(inspectionNo))
            throw invalid("原始记录的质检单号与当前缺陷不一致");
        String hash = hash(actor, r.inspectionItemId(), inspectionNo, label, source);
        Receipt existing = mapper.bindingReceipt(id, key);
        if (existing != null) return replay(existing, hash);

        String code = label;
        if (!manual) {
            Item item = mapper.lockItem(scope.inspectionId(), r.inspectionItemId());
            if (item == null) throw invalid("所选样本不属于本质检单或不是逐件检测项");
            label = item.label();
            code = item.code();
        }
        try {
            mapper.insertBinding(id, scope.inspectionId(), r.inspectionItemId(), code, label,
                    manual ? "MANUAL_SOURCE" : "MES_ITEM", source, actor, key, hash);
        } catch (DuplicateKeyException duplicate) {
            throw new BusinessException(409, "此缺陷已关联该样本，请刷新后选择已有记录");
        }
        return mapper.bindingReceipt(id, key).id();
    }

    @Transactional
    public long record(long id, long actor, SampleMeasurementRequest input) {
        Scope scope = lock(id);
        SampleMeasurementRequest r = validate(input);
        String hash = hash(actor, r.bindingId(), r.priorMeasurementId(), r.stepId(), r.metricName(), r.unit(),
                r.beforeValue(), r.beforeMeasuredAt(), r.afterValue(), r.afterMeasuredAt(), r.sourceRef());
        Receipt existing = mapper.measurementReceipt(id, r.requestId());
        if (existing != null) return replay(existing, hash);
        Long duplicate = mapper.sameMeasurement(id, r);
        if (duplicate != null) throw new BusinessException(409, "同一来源的这次实测已登记为 #" + duplicate + "，请刷新查看");
        Step step = scope.routeId() == null ? null : mapper.lockStep(scope.routeId(), r.stepId());
        if (step == null) throw invalid("所选工序不属于该质检单关联工单的工艺路线");
        if (r.priorMeasurementId() != null) {
            Earlier earlier = mapper.earlier(id, scope.inspectionId(), r.priorMeasurementId());
            validateContinuation(earlier, r);
        }
        if (mapper.insertMeasurement(id, scope, step, r, actor, hash) != 1)
            throw invalid("请选择当前缺陷与质检单已关联的样本");
        return mapper.measurementReceipt(id, r.requestId()).id();
    }

    private Scope lock(long id) {
        if (mapper.lockDefect(id) == null) throw new BusinessException(404, "缺陷记录不存在");
        return requireScope(mapper.lockScope(id));
    }

    private Scope requireScope(Scope scope) {
        if (scope == null) throw new BusinessException(409, "缺陷缺少一致的质检、物料、批次或工单关联，请核对原始单据");
        return scope;
    }

    private static SampleMeasurementRequest validate(SampleMeasurementRequest r) {
        if (r.bindingId() == null || r.bindingId() < 1 || r.stepId() == null || r.stepId() < 1)
            throw invalid("请选择已关联样本和工序");
        String before = number(r.beforeValue()), after = number(r.afterValue());
        if (before == null && after == null) throw invalid("请至少填写一个实际读数");
        if ((before == null) != (r.beforeMeasuredAt() == null) || (after == null) != (r.afterMeasuredAt() == null))
            throw invalid("每个读数须同时填写对应的实测时间");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        for (LocalDateTime t : Arrays.asList(r.beforeMeasuredAt(), r.afterMeasuredAt())) {
            if (t != null && (t.isAfter(now) || t.getYear() < 1000 || t.getNano() != 0))
                throw invalid("实测时间须按北京时间精确到秒，且不能晚于当前时间");
        }
        chronology(r.beforeMeasuredAt(), r.afterMeasuredAt());
        return new SampleMeasurementRequest(key(r.requestId()), r.bindingId(), r.priorMeasurementId(), r.stepId(),
                text(r.metricName(), 100, "指标"), text(r.unit(), 24, "单位"), before, r.beforeMeasuredAt(),
                after, r.afterMeasuredAt(), text(r.sourceRef(), 255, "测量来源"));
    }

    private static void validateContinuation(Earlier e, SampleMeasurementRequest r) {
        if (e == null || e.completed() || e.bindingId() != r.bindingId() || e.stepId() != r.stepId()
                || !e.metric().equals(r.metricName()) || !e.unit().equals(r.unit()))
            throw invalid("待补测记录须为同一样本、工序、指标和单位下的未配对读数");
        if ((e.beforeTime() == null) != (r.beforeMeasuredAt() != null)
                || (e.afterTime() == null) != (r.afterMeasuredAt() != null))
            throw invalid("仅可补录原记录缺少的一侧读数");
        chronology(e.beforeTime() != null ? e.beforeTime() : r.beforeMeasuredAt(),
                e.afterTime() != null ? e.afterTime() : r.afterMeasuredAt());
    }

    private static void chronology(LocalDateTime before, LocalDateTime after) {
        if (before != null && after != null && after.isBefore(before)) throw invalid("工序后实测时间早于工序前实测时间");
    }

    private static String number(String raw) {
        if (raw == null) return null;
        if (!raw.matches("[+-]?[0-9]{1,16}(\\.[0-9]{1,8})?")) throw invalid("实测值最多支持 16 位整数和 8 位小数");
        return new BigDecimal(raw).stripTrailingZeros().toPlainString();
    }

    private static String key(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw invalid("缺少有效请求标识");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String text(String raw, int limit, String field) {
        String value = Objects.toString(raw, "").strip();
        if (value.isEmpty() || value.length() > limit || value.chars().anyMatch(Character::isISOControl))
            throw invalid(field + "不能为空、包含控制字符或超过 " + limit + " 字");
        return value;
    }

    private static String hash(Object... fields) {
        String value = Arrays.stream(fields).map(v -> v == null ? "-" : "+" + v).collect(Collectors.joining("\0"));
        return DigestUtil.sha256Hex(value);
    }

    private static long replay(Receipt receipt, String hash) {
        if (!hash.equals(receipt.hash())) throw new BusinessException(409, "请求标识已用于其他内容");
        return receipt.id();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message);
    }
}
