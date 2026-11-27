package com.upc.computer.assistant.v2.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class InspectionTraceTest {
    @Test void imageMustBelongToThisInspectionAndRespectRecordedLotAndWorkOrder(){
        var header=Map.<String,Object>of("inspection_id",62,"batch_no","A","work_order_id",1);
        var image=new HashMap<String,Object>(Map.of("inspectionId","62","batchNo","A","workOrderId","1","mock",false));
        assertTrue(InspectionTrace.matchesInspectionImage(header,image));
        for(var change:Map.of("inspectionId","61","batchNo","B","workOrderId","2","mock",true).entrySet()){
            var changed=new HashMap<>(image);changed.put(change.getKey(),change.getValue());
            assertFalse(InspectionTrace.matchesInspectionImage(header,changed));
        }
    }
    @Test void pendingInspectionMayHaveUndisposedSamplesButNeverMoreDispositionsThanSamples(){
        assertTrue(InspectionTrace.quantityConflicts(Map.of("inspection_status","PENDING","sample_quantity",10,
            "qualified_quantity",0,"unqualified_quantity",0)).isEmpty());
        assertFalse(InspectionTrace.quantityConflicts(Map.of("inspection_status","PENDING","sample_quantity",10,
            "qualified_quantity",10,"unqualified_quantity",1)).isEmpty());
    }
    @Test void aPassedSampleCanContainRejectsWithoutProvingWholeBatchFailed(){
        var faults=InspectionTrace.quantityConflicts(Map.of("inspection_status","PASSED","inspection_result","QUALIFIED",
            "sample_quantity",150,"qualified_quantity",148,"unqualified_quantity",2));
        assertTrue(faults.isEmpty());
        assertEquals(2,InspectionTrace.quantityConflicts(Map.of("inspection_status","FAILED","inspection_result","UNQUALIFIED",
            "sample_quantity",10,"qualified_quantity",8,"unqualified_quantity",0)).size());
    }

    @Test void requiresQualityAndSkipsToolsOutsideGrantedDomains(){
        var mapper=mock(NaturalBusinessReadMapper.class);
        var user=AssistantReadPolicy.of(42,"CUSTOMER","Test",List.of("customer:orders"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->InspectionTrace.read(
            new NaturalLanguageRuntime.Data(mapper,null,user),"QI101",(tool,read)->read.get()));
        verifyNoInteractions(mapper);
        var quality=AssistantReadPolicy.of(43,"INSPECTOR",null,List.of("quality:records"));
        when(mapper.traceInspection("QI101")).thenReturn(Map.of("inspection_id",71,"inspection_no","QI101","batch_no","B1","material_id",8));
        var calls=new ArrayList<String>();
        var result=InspectionTrace.read(new NaturalLanguageRuntime.Data(mapper,null,quality),"QI101",(tool,read)->{calls.add(tool);return read.get();});
        assertEquals(List.of("INSPECTION_HEADER","INSPECTION_DETAILS","INSPECTION_BATCH"),calls);
        assertFalse(result.containsKey("workOrder"));assertFalse(result.containsKey("graphPaths"));
        assertFalse(result.containsKey("boundImages"));verify(mapper,never()).traceLotReports(anyLong());
        verify(mapper,never()).traceBatch(anyLong());verify(mapper,never()).traceReports(anyLong());
        verify(mapper,never()).trainingArchiveAvailable();
    }

    @Test void sampleAndProductionAttributionRemainSeparateFromConflictingDefectRecords(){
        var mapper=mock(NaturalBusinessReadMapper.class);
        when(mapper.traceInspection("QI123")).thenReturn(Map.of("inspection_id",71,"inspection_no","QI123",
            "batch_no","B1","material_id",8,"inspector_name","检验甲","sample_quantity",8,"qualified_quantity",7,"unqualified_quantity",1));
        when(mapper.traceBaseline(71)).thenReturn(Map.of("inspections",3,"sampled",80,"rejected",4));
        when(mapper.traceDefects(71)).thenReturn(List.of(Map.of("nonconforming_no","NC1","batch_no","B2","defect_description","HDMI无信号")));
        when(mapper.traceReports(71)).thenReturn(List.of(Map.of("report_no","WR1","operator_name","操作乙","step_name","装配","link_basis","同工单背景")));
        var packet=InspectionTrace.read(new NaturalLanguageRuntime.Data(mapper,null),"QI123",(t,f)->f.get());
        String summary=packet.get("summary").toString();
        for(String expected:List.of("检验甲","操作乙","WR1","12.5%","4/80","5%","不一致"))assertTrue(summary.contains(expected),expected);
        assertFalse(packet.get("possibleCauses").toString().contains("HDMI"));
        assertFalse(packet.containsKey("trainingHistory"));
    }

    @Test void noHeaderDoesNotSearchBySubstringOrQueryOtherTools(){
        var mapper=mock(NaturalBusinessReadMapper.class);
        var result=InspectionTrace.read(new NaturalLanguageRuntime.Data(mapper,null),"QI123",(tool,read)->read.get());
        assertEquals("NOT_FOUND",result.get("status"));verify(mapper).traceInspection("QI123");verifyNoMoreInteractions(mapper);
        assertThrows(IllegalArgumentException.class,()->InspectionTrace.read(new NaturalLanguageRuntime.Data(mapper,null),"QI123,456",(t,f)->f.get()));
    }

    @Test void passedLotWithRejectsSeparatesAcceptanceFromCountingAndPrioritizesTheMissingCriterion(){
        var header=Map.<String,Object>of("inspection_id",78,"inspection_no","QI-X78","batch_no","LOT-X",
            "work_order_id",20,"inspection_status","PASSED","inspection_result","QUALIFIED",
            "sample_quantity",40,"qualified_quantity",39,"unqualified_quantity",1);
        var facts=new LinkedHashMap<String,Object>();
        InspectionTrace.conclude(facts,header,new ArrayList<>());
        assertEquals("2.5%",facts.get("confirmedSampleRejectRate"));
        assertEquals(List.of(),facts.get("conflicts"));
        assertTrue(facts.get("diagnosticConclusion").toString().contains("39 + 1 = 40"));
        assertTrue(((List<?>)facts.get("verificationActions")).get(0).toString().contains("接受准则"));
        assertTrue(facts.get("summary").toString().contains("## 结论"));
    }

    @Test void distinguishConfirmedWorkOrderCoverageFromInspectionLinksAndDeduplicateLotReferences(){
        var header=Map.<String,Object>of("inspection_id",78,"inspection_no","QI-X78","batch_no","LOT-X","work_order_id",20);
        var facts=new LinkedHashMap<String,Object>();
        facts.put("workOrder",List.of(Map.of("work_order_id",20,"work_order_no","WO-X")));
        facts.put("routeSteps",List.of(Map.of("step_id",1,"step_name","装配"),Map.of("step_id",2,"step_name","装配"),
            Map.of("step_id",3,"step_name","测试")));
        var report=Map.<String,Object>of("step_id",1,"report_id",8,"report_no","WR-X","work_order_id",20,
            "report_status","CONFIRMED","link_basis","检验单直接报工","step_name","装配");
        var foreign=new HashMap<>(report);foreign.putAll(Map.of("work_order_id",22,"report_no","WR-OTHER","step_id",3,
            "link_basis","检验工单与报工工单矛盾"));
        facts.put("reportRows",List.of(report,report,foreign));
        facts.put("dispatchRows",List.of(Map.of("work_order_id",20,"dispatch_no","DT-Y","step_id",2)));
        facts.put("lotReports",List.of(Map.of("inspection_no","QI-X78","report_no","WR-X","batch_no","LOT-X"),
            Map.of("inspection_no","QI-X79","report_no","WR-X","batch_no","LOT-X")));
        facts.put("graphStatus","NO_SNAPSHOT");
        InspectionTrace.conclude(facts,header,new ArrayList<>());
        var coverage=(Map<?,?>)facts.get("processCoverage");
        assertEquals(3,coverage.get("routeStepCount"));assertEquals(1,coverage.get("confirmedStepCount"));
        assertEquals(1,coverage.get("reportedStepCount"));assertEquals(1,coverage.get("dispatchedStepCount"));
        var steps=(List<Map<String,Object>>)coverage.get("steps");
        assertEquals(List.of("WR-X"),steps.get(0).get("confirmedReports"));
        assertEquals(List.of("DT-Y"),steps.get(1).get("dispatches"));
        assertEquals(List.of(),steps.get(2).get("reports"));
        String summary=facts.get("summary").toString();
        for(String term:List.of("1/3","仅有派工","未查到报工","1 张不同报工单","QI-X79","Neo4j","未取得业务图快照"))
            assertTrue(summary.contains(term),term);
    }

    @Test void conflictBetweenStagesIsReportedWithoutDerivingARateOrInventingARecheck(){
        var facts=new LinkedHashMap<String,Object>();
        var h=Map.<String,Object>of("inspection_id",7,"inspection_no","QI-A","sample_quantity",10,
            "qualified_quantity",10,"unqualified_quantity",0,"inspection_status","RECHECK_REQUIRED","inspection_result","UNQUALIFIED",
            "updated_at","2026-07-12 12:00:00");
        facts.put("items",List.of(Map.of("item_code","F-1","item_name","色差","result","PASSED","measured_value","合格","updated_at","2026-07-12 11:59:00")));
        facts.put("defects",List.of(Map.of("nonconforming_no","NC-A","quantity",1,"defect_description","色差超限"),
            Map.of("nonconforming_no","NC-B","quantity",1,"defect_description","边缘漏光","handle_method","CONCESSION")));
        facts.put("legacyBaseline",Map.of("kind","DEMO_SEED_REFERENCE","source","test seed","sample_quantity",10,
            "qualified_quantity",8,"unqualified_quantity",2,"items",List.of(Map.of("item_code","F-1","measured_value","3.2","result","FAILED"))));
        facts.put("inspectionEvents",List.of(Map.of("event_type","ITEMS_SAVED","recorded_at","2026-07-12 11:59:00")));
        InspectionTrace.conclude(facts,h,new ArrayList<>());
        assertNull(facts.get("confirmedSampleRejectRate"));assertEquals("WITHHELD_CONFLICT",facts.get("sampleRateStatus"));
        String summary=(String)facts.get("summary");
        for(String term:List.of("流程状态：需复检","检验判定：不合格","初始化脚本","合格 8 件、不合格 2 件","3.2",
            "当前项目表保存值 合格","保存检验项目","未取得正式复检","让步接收","前后实测","不合格率待核实"))
            assertTrue(summary.contains(term),term);
        assertFalse(summary.contains("0%"));assertFalse(summary.contains("20%"));
    }

    @Test void reportsBoundMeasurementsAndFormalDecisionOnlyWhenActuallyRecorded(){
        var h=Map.<String,Object>of("inspection_id",8,"inspection_no","QI-B","sample_quantity",4,
                "qualified_quantity",3,"unqualified_quantity",1,"inspection_status","FAILED","inspection_result","UNQUALIFIED");
        var facts=new LinkedHashMap<String,Object>();
        facts.put("inspectionEvents",List.of(Map.of("event_type","INITIAL_DECISION"),Map.of("event_type","RECHECK_FAILED")));
        facts.put("defects",List.of(Map.of("nonconforming_no","NC-B","quantity",1)));
        facts.put("sampleBindings",List.of(Map.of("binding_id",1,"sample_label","U-8","nonconforming_no","NC-B","source_ref","WR-A")));
        facts.put("sampleMeasurements",List.of(Map.of("binding_id",1,"sample_label","U-8","step_name","组装","metric_name","色差",
                "before_value","1.8","source_ref","WR-A")));
        InspectionTrace.conclude(facts,h,new ArrayList<>());
        assertEquals("25%",facts.get("confirmedSampleRejectRate"));
        String summary=(String)facts.get("summary");
        for(String term:List.of("正式复检未通过","初次判定","U-8","WR-A","工序前 1.8","工序后 缺失","缺少后测"))
            assertTrue(summary.contains(term),term);
        assertFalse(summary.contains("未取得正式复检"));
    }

    @Test void onlyExplicitContinuationPairsTwoSeparatelyRecordedProcessReadings(){
        var common=Map.<String,Object>of("binding_id",19,"sample_label","测试机1","step_name","点胶","metric_name","厚度","unit","mm");
        var before=new HashMap<>(common);before.putAll(Map.of("measurement_id",3,"before_value","0.8","source_ref","工艺卡-A"));
        var after=new HashMap<>(common);after.putAll(Map.of("measurement_id",4,"after_value","0.9","source_ref","工艺卡-B"));
        var facts=new LinkedHashMap<String,Object>();facts.put("sampleMeasurements",List.of(before,after));
        var header=Map.<String,Object>of("inspection_id",9,"inspection_no","QI-S9");
        InspectionTrace.conclude(facts,header,new ArrayList<>());
        assertTrue(facts.get("missingData").toString().contains("缺少后测"));assertTrue(facts.get("missingData").toString().contains("缺少前测"));
        after.put("prior_measurement_id",3);
        InspectionTrace.conclude(facts,header,new ArrayList<>());
        String summary=facts.get("summary").toString();
        assertTrue(summary.contains("工序前 0.8 mm"));assertTrue(summary.contains("工序后 0.9 mm"));
        assertTrue(summary.contains("工艺卡-A"));assertTrue(summary.contains("工艺卡-B"));
        assertFalse(summary.contains("缺少后测"));assertFalse(summary.contains("缺少前测"));
    }

    @Test @EnabledIfEnvironmentVariable(named="MES_TRACE_LIVE",matches="1")
    void liveSeedResolvesInspectionIdsFromRowsNotNumericSuffixes() throws Exception {
        Path cwd=Path.of("").toAbsolutePath();
        while(cwd!=null && !Files.exists(cwd.resolve("backend/assistant/deploy/.env.agent-read.local")))cwd=cwd.getParent();
        assertNotNull(cwd);
        Map<String,String> env=new HashMap<>();
        for(String l:Files.readAllLines(cwd.resolve("backend/assistant/deploy/.env.agent-read.local"))){
            var pair=l.split("=",2);if(pair.length==2)env.put(pair[0],pair[1]);
        }
        try(SqlSession session=Stage5RuntimeMain.createFactory(env).openSession(false)){
            var connection=session.getConnection();
            connection.setReadOnly(true);connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);assertTrue(connection.isReadOnly());
            var mapper=session.getMapper(NaturalBusinessReadMapper.class);
            var packet=InspectionTrace.read(new NaturalLanguageRuntime.Data(mapper,null),"QI202607021",(t,f)->f.get());
            assertEquals("FOUND",packet.get("status"));var header=((List<Map<String,Object>>)packet.get("inspection")).get(0);
            assertEquals("63",header.get("inspection_id").toString());assertEquals("2",header.get("work_order_id").toString());
            assertEquals("BATCH-WO202607002-3201",header.get("batch_no"));
            assertEquals("8",header.get("material_id").toString());assertNull(header.get("work_report_id"));
            assertTrue(packet.get("summary").toString().contains("无信号"));assertTrue(packet.get("possibleCauses").toString().contains("接口板装配"));
            assertEquals(2,((List<?>)packet.get("sameBatchInspections")).size());assertEquals(2,((List<?>)packet.get("defects")).size());
            assertEquals(List.of(),packet.get("equipmentRows"));assertEquals(List.of(),packet.get("reportRows"));assertEquals(List.of(),packet.get("batchRecords"));
            var optical=mapper.traceInspection("QI202607013");
            assertEquals(List.of(),mapper.traceAlarms(((Number)optical.get("inspection_id")).longValue()),
                "Alarms first reported after July 11 cannot explain this inspection");
            var opticalHistory=mapper.traceSimilarDefects(((Number)optical.get("inspection_id")).longValue());
            assertTrue(opticalHistory.stream().anyMatch(r->"NC202607107".equals(r.get("nonconforming_no"))));
            assertTrue(opticalHistory.stream().noneMatch(r->Objects.equals(optical.get("batch_no"),r.get("batch_no"))));
            var reported=mapper.traceInspection("QI202611001");
            var lot=mapper.traceLotReports(((Number)reported.get("inspection_id")).longValue());
            assertTrue(lot.stream().anyMatch(r->"WR202611008".equals(r.get("report_no"))));
            var reportedTrace=InspectionTrace.read(new NaturalLanguageRuntime.Data(mapper,null),
                reported.get("inspection_no").toString(),(tool,read)->read.get());
            assertEquals("10%",reportedTrace.get("confirmedSampleRejectRate"));
            var coverage=(Map<?,?>)reportedTrace.get("processCoverage");
            assertEquals(10,coverage.get("routeStepCount"));assertEquals(8,coverage.get("reportedStepCount"));
            assertEquals(0,coverage.get("confirmedStepCount"));
            assertTrue(reportedTrace.get("summary").toString().contains("18 + 2 = 20"));
            assertTrue(reportedTrace.get("summary").toString().contains("完成 20 件、合格 20 件、不合格 0 件"));
            assertEquals(List.of(),mapper.traceLotReports(((Number)optical.get("inspection_id")).longValue()));
            assertTrue(InspectionTrace.quantityConflicts(mapper.traceInspection("QI202607503")).isEmpty());
            session.rollback(true);
        }
    }
}
