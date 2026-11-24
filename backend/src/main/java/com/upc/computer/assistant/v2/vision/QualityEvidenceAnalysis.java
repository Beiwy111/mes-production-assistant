package com.upc.computer.assistant.v2.vision;

import com.upc.computer.assistant.v2.tools.business.BusinessEvidenceCalculator;
import com.upc.computer.assistant.v2.tools.delivery.DeliveryEvidenceRegistry;
import java.util.*;

/** Shared adapter for already-authorized bounded inspection rows, including global overviews. */
public final class QualityEvidenceAnalysis {
    private QualityEvidenceAnalysis() {}
    public static void appendArchive(Map<String,List<Map<String,String>>> tables) {
        Set<Long> ids=new TreeSet<>();
        for(var row:tables.getOrDefault("quality_inspection",List.of())) {
            String id=row.get("inspection_id");
            if(!"CONTRADICTORY".equals(row.get("relation_status")) && id!=null && id.matches("[1-9][0-9]{0,18}"))try{ids.add(Long.parseLong(id));}catch(NumberFormatException ignored){}
        }
        List<Map<String,Object>> records;
        try {records=VisionEvidenceArchive.configured().snapshotRows(ids);}
        catch(Exception unavailable) {
            records=ids.stream().map(id->Map.<String,Object>of("record_type","STATUS","inspection_id",String.valueOf(id),"archive_status","PARTIAL","limitations","ARCHIVE_UNAVAILABLE")).toList();
        }
        List<Map<String,String>> normalized=new ArrayList<>();
        for(var row:records) {Map<String,String> result=new TreeMap<>();row.forEach((k,v)->{if(v!=null)result.put(k,String.valueOf(v));});normalized.add(result);}
        tables.put("vision_evidence_archive",normalized);
    }
    /** Returned fingerprint must participate in the caller's overview revalidation. */
    public static Map<String,Object> overview(List<Map<String,Object>> rows,boolean complete) {
        List<Map<String,String>> inspections=new ArrayList<>();
        for(var row:rows) {
            Map<String,String> normalized=new TreeMap<>();
            row.forEach((k,v)->{if(v!=null)normalized.put(k,String.valueOf(v));});
            if(!normalized.containsKey("inspection_id"))normalized.put("inspection_id",normalized.getOrDefault("record_id",""));
            if(normalized.containsKey("status")) {
                String status=normalized.get("status");
                if(Set.of("QUALIFIED","UNQUALIFIED","CONCESSION","PENDING").contains(status))normalized.putIfAbsent("inspection_result",status);
                else normalized.putIfAbsent("inspection_status",status);
            }
            if(!normalized.containsKey("inspected_at")&&normalized.containsKey("event_at"))normalized.put("inspected_at",normalized.get("event_at"));
            inspections.add(normalized);
        }
        Map<String,List<Map<String,String>>> tables=new LinkedHashMap<>();tables.put("quality_inspection",inspections);appendArchive(tables);
        var q=new BusinessEvidenceCalculator().calculate("Q1",tables,complete);
        @SuppressWarnings("unchecked") var metrics=(Map<String,Object>)q.get("qualityMetrics");
        metrics.put("failedItemRecordCount",null);metrics.put("nonconformingRecordCount",null);metrics.put("defectTypes",List.of());
        metrics.put("detailStatus","ITEM_AND_DEFECT_RECORDS_NOT_READ");
        return Map.of("qualityMetrics",q.get("qualityMetrics"),"visionEvidence",q.get("visionEvidence"),
                "visionEvidenceFingerprint",DeliveryEvidenceRegistry.fingerprint(Map.of("vision_evidence_archive",tables.get("vision_evidence_archive"))),
                "qualityDetailScope","RETURNED_INSPECTION_HEADS_ONLY_ITEMS_AND_DEFECT_RECORDS_NOT_READ");
    }
}
