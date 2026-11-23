package com.upc.computer.dto;

public record DefectSampleRequest(String requestId, Long inspectionItemId, String inspectionNo,
                                 String sampleLabel, String sourceRef) {}
