package com.upc.computer.assistant.v2;

import com.upc.computer.assistant.v2.tools.business.BusinessEvidenceCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class VisionQualityEvidenceTest {
    @Test void emptyInspectionScopeIsNotEvidenceOfMissingVisionArchives() {
        var facts=com.upc.computer.assistant.v2.vision.QualityEvidenceAnalysis.overview(List.of(),true);
        var vision=(Map<?,?>)facts.get("visionEvidence");
        assertThat(vision.get("status")).isEqualTo("NO_INSPECTIONS_IN_SCOPE");
        assertThat(vision.get("missingInspectionArchiveCount")).isEqualTo(0);
        assertThat((List<?>)vision.get("limitations")).isEmpty();
    }
    @TempDir Path dir;
    private Object archive() throws Exception {
        return Class.forName("com.upc.computer.assistant.v2.vision.VisionEvidenceArchive")
                .getConstructor(Path.class, Clock.class).newInstance(dir, Clock.fixed(Instant.parse("2026-11-09T01:00:00Z"),ZoneOffset.UTC));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> save(Object archive,long id,Map<String,Object> data) throws Exception {
        Map<String,Object> validated=new LinkedHashMap<>(data);validated.put("rawResultValid",true);
        return (Map<String,Object>)archive.getClass().getMethod("save",long.class,byte[].class,Map.class).invoke(archive,id,new byte[]{1,2,3},validated);
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> read(Object archive,Set<Long> ids) throws Exception {
        return (List<Map<String,Object>>)archive.getClass().getMethod("snapshotRows",Set.class).invoke(archive,ids);
    }
    @Test void storesOnlyBoundAllowedRecordsAndExplicitProvenanceWithoutImages() throws Exception {
        Object archive=archive();
        var result=save(archive,7,Map.of("defect",true,"count",1,"maxConfidence",0.8,"model","YOLOv8-Seg","algorithmVersion","SCRATCH_SEG_V1","resultImage","secret-image","detections",List.of(Map.of("classId",0,"className","Scratched","confidence",0.8,"box",List.of(1,2,3,4)))));
        assertThat(result).containsEntry("status","SAVED");
        assertThat(read(archive,Set.of(8L)).toString()).doesNotContain("secret-image","Scratched");
        assertThat(read(archive,Set.of(7L)).toString()).contains("Scratched","IMAGE_NOT_RETAINED","WEIGHTS_VERSION_UNAVAILABLE","039058c6").doesNotContain("secret-image");
    }
    @Test void corruptRecordIsVisibleAsIncompleteEvidence() throws Exception {
        Object archive=archive(); save(archive,7,Map.of("model","Mock","algorithmVersion","MOCK_V1","mock",true));
        try(var files=Files.walk(dir)) { for(Path p:files.filter(Files::isRegularFile).toList()) Files.writeString(p,"{bad"); }
        assertThat(read(archive,Set.of(7L)).toString()).contains("CORRUPT_RECORD");
    }
    @Test void summariesAreNeverPromotedToRawDetectionsAndRatesStayPerInspection() {
        var data=new LinkedHashMap<String,List<Map<String,String>>>();
        data.put("quality_inspection",List.of(Map.of("inspection_id","1","sample_quantity","10","qualified_quantity","9","unqualified_quantity","1"),Map.of("inspection_id","2","sample_quantity","10","qualified_quantity","10","unqualified_quantity","0")));
        data.put("quality_inspection_item",List.of(Map.of("inspection_item_id","4","inspection_id","1","item_code","UNIT-001","remark","AI智能检测：发现划痕")));
        var result=new BusinessEvidenceCalculator().calculate("Q1",data,true);
        assertThat(result).containsKey("qualityMetrics");
        assertThat(((Map<?,?>)result.get("visionEvidence")).get("status")).isEqualTo("RECORDED_SUMMARIES_ONLY");
        assertThat(result.get("visionEvidence").toString()).contains("RECORDED_TEXT_NOT_RAW_VISION");
        assertThat(result.get("qualityMetrics").toString()).contains("PER_INSPECTION_RECORDS_NOT_UNIQUE_PRODUCTS").doesNotContain("totalQualifiedQuantity");
    }
    @Test void mockAndRealImagesRemainSeparateAndFingerprintChangesOnNewRecord() throws Exception {
        Object archive=archive();
        var before=read(archive,Set.of(7L)).toString();
        save(archive,7,Map.of("mock",true,"defect",true,"count",1));
        save(archive,7,Map.of("mock",false,"defect",false,"count",0));
        Map<String,List<Map<String,String>>> tables=new LinkedHashMap<>();
        tables.put("quality_inspection",List.of(Map.of("inspection_id","7")));
        var normalized=new ArrayList<Map<String,String>>();
        for(var record:read(archive,Set.of(7L))) {Map<String,String> row=new TreeMap<>();record.forEach((k,v)->row.put(k,String.valueOf(v)));normalized.add(row);}
        assertThat(normalized.toString()).isNotEqualTo(before);
        tables.put("vision_evidence_archive",normalized);
        var result=new BusinessEvidenceCalculator().calculate("Q1",tables,true);
        var vision=(Map<?,?>)result.get("visionEvidence");
        assertThat(vision.get("status")).isEqualTo("RECORDED");
        assertThat(vision.get("realImageRecordCount")).isEqualTo(1L);
        assertThat(vision.get("realDefectImageRecordCount")).isEqualTo(0L);
        assertThat(vision.get("mockRecordCount")).isEqualTo(1L);
    }
    @Test void limitsCorruptBindingsAndOversizeHaveExplicitReadStatus() throws Exception {
        Object archive=archive();save(archive,7,Map.of());
        Path valid;
        try(var stream=Files.walk(dir)){valid=stream.filter(Files::isRegularFile).findFirst().orElseThrow();}
        String original=Files.readString(valid);
        Files.writeString(valid,original.replace("\"inspectionId\":\"7\"","\"inspectionId\":\"8\""));
        assertThat(read(archive,Set.of(7L)).toString()).contains("CORRUPT_RECORD");
        Files.writeString(valid,"x".repeat(262145));
        assertThat(read(archive,Set.of(7L)).toString()).contains("RECORD_SIZE_LIMIT");
        for(int i=0;i<101;i++) Files.writeString(dir.resolve("7").resolve(UUID.randomUUID()+".json"),"{}");
        assertThat(read(archive,Set.of(7L)).toString()).contains("RECORD_LIMIT_REACHED");
        assertThat(save(archive,7,Map.of())).containsEntry("status","NOT_SAVED").containsEntry("reason","RECORD_LIMIT_REACHED");
    }
    @Test void defaultPathIsTheSameFromRepositoryRootAndBackend() throws Exception {
        Path root=dir.resolve("repo");Files.createDirectories(root.resolve("backend/assistant"));
        var method=archive().getClass().getMethod("rootFor",Path.class);
        assertThat(method.invoke(null,root)).isEqualTo(method.invoke(null,root.resolve("backend")));
    }
    @Test void byteBudgetAndRecordLimitKeepReadsBoundedAndSkippedMetadataChangesFingerprint() throws Exception {
        Object archive=archive();save(archive,7,Map.of());
        Path valid;try(var stream=Files.walk(dir)){valid=stream.filter(Files::isRegularFile).findFirst().orElseThrow();}
        String original=Files.readString(valid);
        Files.writeString(valid,original+" ".repeat(70000));
        String before=read(archive,Set.of(7L)).toString();
        assertThat(before).contains("ARCHIVE_READ_BYTE_LIMIT","PARTIAL").doesNotContain("SERVER_YOLO_RESPONSE");
        Files.writeString(valid,original+" ".repeat(71000));
        assertThat(read(archive,Set.of(7L)).toString()).isNotEqualTo(before);
        Files.delete(valid);
        for(int i=0;i<42;i++)assertThat(save(archive,7,Map.of())).containsEntry("status","SAVED");
        var records=read(archive,Set.of(7L));
        assertThat(records.stream().filter(r->"RECORD".equals(r.get("record_type"))).count()).isEqualTo(40L);
        assertThat(records.toString()).contains("TOTAL_RECORD_LIMIT_REACHED","PARTIAL");
        assertThat(records.stream().map(r->String.valueOf(r.getOrDefault("record_json","")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).reduce(0,Integer::sum)).isLessThanOrEqualTo(65536);
    }
}
