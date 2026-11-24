package com.upc.computer.service;

import com.upc.computer.common.BusinessException;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.entity.QualityInspection;
import com.upc.computer.entity.QualityInspectionItem;
import com.upc.computer.mapper.QualityInspectionRoundMapper;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InspectionRoundHistory {
    public enum Event {
        OPENED, PRIOR_STATE, INITIAL_ITEMS, ITEMS_SAVED, ITEMS_RESET, INITIAL_DECISION, DECISION_SAVED,
        RECHECK_REQUESTED, RECHECK_PASSED, RECHECK_FAILED, SAMPLING_UPDATED, HEADER_UPDATED,
        CLOSED, AUTO_CLOSED, DELETED
    }

    private final QualityInspectionRoundMapper rounds;
    private final JwtUtil jwt;

    public InspectionRoundHistory(QualityInspectionRoundMapper rounds, JwtUtil jwt) {
        this.rounds = rounds;
        this.jwt = jwt;
    }

    public QualityInspection beforeWrite(Long inspectionId) {
        if (inspectionId == null) throw new BusinessException("inspectionId 不能为空");
        QualityInspection inspection = rounds.lockInspection(inspectionId);
        if (inspection == null) throw new BusinessException("质检单不存在: " + inspectionId);
        if (rounds.lastStage(inspectionId, true) == null) {
            append(inspectionId, Event.PRIOR_STATE, null);
        }
        return inspection;
    }

    public Event decisionEvent(QualityInspection previous) {
        return eligibleForInitial(previous, false) ? Event.INITIAL_DECISION : Event.DECISION_SAVED;
    }

    public Event itemSaveEvent(QualityInspection inspection, List<QualityInspectionItem> items) {
        boolean containsMeasurements = items.stream().anyMatch(item ->
                (item.getMeasuredValue() != null && !item.getMeasuredValue().isBlank())
                || (item.getResult() != null && !item.getResult().isBlank() && !"PENDING".equals(item.getResult())));
        return containsMeasurements && eligibleForInitial(inspection, true) ? Event.INITIAL_ITEMS : Event.ITEMS_SAVED;
    }

    private boolean eligibleForInitial(QualityInspection previous, boolean items) {
        return "PENDING".equals(previous.getInspectionStatus()) && "PENDING".equals(previous.getInspectionResult())
                && !"RECHECK".equals(previous.getInspectionType())
                && "OPENED".equals(rounds.lastStage(previous.getInspectionId(), items));
    }

    public void append(long id, Event event, String reportedOperator) {
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("event", event.name());
        if (event != Event.PRIOR_STATE && event != Event.AUTO_CLOSED) {
            String signed = signedUsername();
            if (signed != null && !signed.isBlank()) {
                row.put("operator", signed);
                row.put("operatorSource", "SIGNED_TOKEN");
            } else if (reportedOperator != null && !reportedOperator.isBlank()) {
                row.put("operator", reportedOperator);
                row.put("operatorSource", "REPORTED");
            }
        }
        if (rounds.insertSnapshot(row) != 1 || !(row.get("roundId") instanceof Number key)) {
            throw new IllegalStateException("无法保存质检历史快照");
        }
        rounds.copyItems(id, key.longValue());
    }

    private String signedUsername() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        String token = jwt.extractTokenFromHeader(attributes.getRequest().getHeader("Authorization"));
        if (token == null || token.isBlank()) return null;
        try {
            return jwt.parseToken(token).getSubject();
        } catch (JwtException | IllegalArgumentException ex) {
            return null;
        }
    }
}
