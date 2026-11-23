package com.upc.computer.dto;

import java.time.LocalDateTime;

public record SampleMeasurementRequest(String requestId, Long bindingId, Long priorMeasurementId, Long stepId,
        String metricName, String unit, String beforeValue, LocalDateTime beforeMeasuredAt,
        String afterValue, LocalDateTime afterMeasuredAt, String sourceRef) {}
