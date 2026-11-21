package com.upc.computer.assistant.v2.vision;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Pure presentation calculation; notes and model observations never establish release or cause. */
public final class QualityEvidenceDetails {
    private static final ObjectMapper JSON=new ObjectMapper();
    private QualityEvidenceDetails() {}
    public static Map<String,Object> metrics(List<Map<String,Object>> inspections,List<Map<String,Object>> items,List<Map<String,Object>> defects,boolean complete) {
        Map<String,Long> types=new TreeMap<>();
        for(var defect:defects) if(trustworthy(defect)) {
            String type=text(defect.get("defect_type"),100); if(!type.isBlank())types.merge(type,1L,Long::sum);
        }
        List<Map<String,Object>> rates=inspections.stream().map(i->object("inspectionId",i.get("inspection_id"),"samplePassRate",i.get("samplePassRate"),"status",i.get("sampleRateStatus"))).toList();
        return object("basis","PER_INSPECTION_RECORDS_NOT_UNIQUE_PRODUCTS","inspectionRecordCount",complete?inspections.size():null,
                "validSampleRecordCount",complete?inspections.stream().filter(i->i.get("samplePassRate")!=null).count():null,
                "failedInspectionRecordCount",complete?inspections.stream().filter(QualityEvidenceDetails::trustworthy).filter(i->"FAILED".equals(i.get("inspection_status"))||"UNQUALIFIED".equals(i.get("inspection_result"))).count():null,
                "failedItemRecordCount",complete?items.stream().filter(QualityEvidenceDetails::trustworthy).filter(i->"FAILED".equals(i.get("result"))).count():null,
                "nonconformingRecordCount",complete?defects.size():null,
                "defectTypes",types.entrySet().stream().map(e->object("defectType",e.getKey(),"recordCount",complete?e.getValue():null)).toList(),
                "sampleRates",rates,"batchPassRate",null,"complete",complete);
    }
    public static Map<String,Object> vision(Map<String,List<Map<String,String>>> tables,List<Map<String,Object>> inspections,List<Map<String,Object>> items) {
        Set<String> allowed=new HashSet<>(); inspections.stream().filter(QualityEvidenceDetails::trustworthy).forEach(i->allowed.add(String.valueOf(i.get("inspection_id"))));
        List<Map<String,Object>> summaries=new ArrayList<>();
        for(var row:inspections) addSummary(summaries,row,"remark","inspection_id","INSPECTION_REMARK");
        for(var row:items) if(allowed.contains(String.valueOf(row.get("inspection_id")))) addSummary(summaries,row,"remark","inspection_id",String.valueOf(row.getOrDefault("item_code","ITEM_REMARK")));
        List<Map<String,Object>> records=new ArrayList<>(); Set<String> limitations=new TreeSet<>(); Set<String> seen=new HashSet<>();
        boolean partial=false; int missing=0;
        for(var row:tables.getOrDefault("vision_evidence_archive",List.of())) {
            if(!allowed.contains(row.get("inspection_id"))) continue;
            if("STATUS".equals(row.get("record_type"))) {
                if("PARTIAL".equals(row.get("archive_status"))) partial=true;
                if("SOURCE_RECORD_MISSING".equals(row.get("archive_status"))) missing++;
                if(!row.getOrDefault("limitations","").isBlank())limitations.addAll(Arrays.asList(row.get("limitations").split(",")));
                continue;
            }
            if(!"RECORD".equals(row.get("record_type")))continue;
            try {
                String raw=row.getOrDefault("record_json",""); if(raw.length()>262144)throw new IllegalArgumentException();
                Map<String,Object> record=JSON.readValue(raw,new TypeReference<>(){});
                if(!row.get("inspection_id").equals(String.valueOf(record.get("inspectionId")))||!"SERVER_YOLO_RESPONSE".equals(record.get("source")))throw new IllegalArgumentException();
                if(seen.add(String.valueOf(record.get("recordId")))) records.add(record);
            } catch(Exception ex) {partial=true;limitations.add("CORRUPT_RECORD");}
        }
        long mockCount=records.stream().filter(r->Boolean.TRUE.equals(r.get("mock"))).count();
        if(mockCount>0)limitations.add("MOCK_RECORDS_EXCLUDED_FROM_REAL_IMAGE_COUNTS");
        if(missing>0 || records.isEmpty())limitations.add("HISTORICAL_RAW_VISION_RESULTS_NOT_AVAILABLE_FOR_ALL_INSPECTIONS");
        if(records.stream().anyMatch(r->"IMAGE_NOT_RETAINED".equals(r.get("imageRetention"))))limitations.add("IMAGE_NOT_RETAINED");
        if(records.stream().anyMatch(r->"PARTIAL_IMAGES".equals(r.get("imageRetention"))))limitations.add("PARTIAL_IMAGES");
        limitations.add("MODEL_LABEL_AND_ALGORITHM_VERSION_DO_NOT_PROVE_WEIGHT_VERSION");
        limitations.add("IMAGE_COUNT_IS_NOT_PRODUCT_SAMPLE_COUNT");
        records.sort(Comparator.comparing(r->String.valueOf(r.get("recordId"))));
        return object("status",partial?"PARTIAL":!records.isEmpty()?"RECORDED":!summaries.isEmpty()?"RECORDED_SUMMARIES_ONLY":"SOURCE_RECORD_MISSING",
                "records",records,"summaries",summaries,"limitations",List.copyOf(limitations),"imageCountBasis","IMAGES_NOT_PRODUCT_UNITS",
                "recordCount",records.size(),"realImageRecordCount",records.size()-mockCount,"mockRecordCount",mockCount,
                "realDefectImageRecordCount",records.stream().filter(r->!Boolean.TRUE.equals(r.get("mock"))&&Boolean.TRUE.equals(r.get("defect"))).count(),
                "missingInspectionArchiveCount",missing,"complete",!partial);
    }
    private static void addSummary(List<Map<String,Object>> out,Map<String,Object> row,String field,String idField,String itemCode) {
        String value=text(row.get(field),500); if(value.isBlank()||!trustworthy(row))return;
        out.add(object("inspectionId",row.get(idField),"itemCode",itemCode,"text",value,"source","RECORDED_TEXT_NOT_RAW_VISION"));
    }
    private static boolean trustworthy(Map<String,Object> row) {return !Boolean.TRUE.equals(row.get("conflictingDuplicate"))&&!"CONTRADICTORY".equals(row.get("relation_status"));}
    private static String text(Object value,int max) {String v=value==null?"":String.valueOf(value);return v.substring(0,Math.min(v.length(),max));}
    private static Map<String,Object> object(Object... pairs) {Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
}
