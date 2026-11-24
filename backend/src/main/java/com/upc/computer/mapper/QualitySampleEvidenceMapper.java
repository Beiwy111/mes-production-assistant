package com.upc.computer.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface QualitySampleEvidenceMapper {
    record Scope(long inspectionId, String inspectionNo, Long workOrderId, Long routeId) {}
    record Item(long itemId, String code, String label) {}
    record Step(long stepId, String name) {}
    record Receipt(long id, String hash) {}
    record Earlier(long bindingId, long stepId, String metric, String unit, LocalDateTime beforeTime,
                   LocalDateTime afterTime, boolean completed) {}

    @Select("SELECT nonconforming_id FROM nonconforming_product WHERE nonconforming_id=#{id} FOR UPDATE")
    Long lockDefect(long id);

    String SCOPE = """
        SELECT q.inspection_id, q.inspection_no, w.work_order_id, w.route_id FROM nonconforming_product n
        JOIN quality_inspection q ON q.inspection_id=n.inspection_id
        LEFT JOIN work_report qr ON qr.report_id=q.work_report_id
        LEFT JOIN work_report nr ON nr.report_id=n.work_report_id
        LEFT JOIN work_order w ON w.work_order_id=COALESCE(q.work_order_id,qr.work_order_id,n.work_order_id,nr.work_order_id)
        WHERE n.nonconforming_id=#{id} AND (n.material_id IS NULL OR n.material_id=q.material_id)
          AND (NULLIF(n.batch_no,'') IS NULL OR n.batch_no=q.batch_no)
          AND (q.work_order_id IS NULL OR q.work_order_id=w.work_order_id)
          AND (n.work_order_id IS NULL OR n.work_order_id=w.work_order_id)
          AND (q.work_report_id IS NULL OR qr.work_order_id=w.work_order_id)
          AND (n.work_report_id IS NULL OR nr.work_order_id=w.work_order_id)
        """;
    @Select(SCOPE)
    Scope scope(long id);
    @Select(SCOPE + " FOR UPDATE")
    Scope lockScope(long id);

    String ITEMS = """
        SELECT inspection_item_id,item_code,item_name FROM quality_inspection_item
        WHERE inspection_id=#{inspectionId} AND item_code REGEXP '^UNIT-[0-9]+$' AND TRIM(item_name)<>''
        """;
    @Select(ITEMS + " ORDER BY sort_order,inspection_item_id")
    List<Item> items(long inspectionId);
    @Select(ITEMS + " AND inspection_item_id=#{itemId} FOR UPDATE")
    Item lockItem(@Param("inspectionId") long inspectionId, @Param("itemId") long itemId);
    @Select("SELECT step_id,step_name FROM process_step WHERE route_id=#{routeId} ORDER BY step_no,step_id")
    List<Step> steps(long routeId);
    @Select("SELECT step_id,step_name FROM process_step WHERE route_id=#{routeId} AND step_id=#{stepId} FOR UPDATE")
    Step lockStep(@Param("routeId") long routeId, @Param("stepId") long stepId);

    @Select("""
        SELECT binding_id bindingId,inspection_item_id itemId,unit_code unitCode,sample_label sampleLabel,
               sample_origin sampleOrigin,source_ref sourceRef,recorded_by recordedBy,CAST(recorded_at AS CHAR) recordedAt
        FROM quality_defect_sample WHERE nonconforming_id=#{id} AND inspection_id=#{inspectionId} ORDER BY binding_id
        """)
    List<Map<String,Object>> bindings(@Param("id") long id, @Param("inspectionId") long inspectionId);

    @Select("""
        SELECT measurement_id measurementId,binding_id bindingId,prior_measurement_id priorMeasurementId,
               unit_code unitCode,sample_label sampleLabel,step_id stepId,step_name stepName,metric_name metricName,unit,
               CAST(before_value AS CHAR) beforeValue,CAST(after_value AS CHAR) afterValue,
               CAST(before_measured_at AS CHAR) beforeMeasuredAt,CAST(after_measured_at AS CHAR) afterMeasuredAt,
               source_ref sourceRef,recorded_by recordedBy,CAST(recorded_at AS CHAR) recordedAt
        FROM quality_sample_measurement WHERE nonconforming_id=#{id} AND inspection_id=#{inspectionId} ORDER BY measurement_id
        """)
    List<Map<String,Object>> measurements(@Param("id") long id,@Param("inspectionId") long inspectionId);

    @Select("SELECT binding_id,request_hash FROM quality_defect_sample WHERE nonconforming_id=#{id} AND request_id=#{key}")
    Receipt bindingReceipt(@Param("id") long id,@Param("key") String key);
    @Select("SELECT measurement_id,request_hash FROM quality_sample_measurement WHERE nonconforming_id=#{id} AND request_id=#{key}")
    Receipt measurementReceipt(@Param("id") long id,@Param("key") String key);

    @Select("""
        SELECT m.measurement_id FROM quality_sample_measurement m
        WHERE m.binding_id=#{r.bindingId} AND m.nonconforming_id=#{id} AND m.step_id=#{r.stepId}
          AND m.prior_measurement_id <=> #{r.priorMeasurementId}
          AND BINARY m.metric_name=BINARY #{r.metricName} AND BINARY m.unit=BINARY #{r.unit}
          AND BINARY m.source_ref=BINARY #{r.sourceRef}
          AND m.before_value <=> CAST(#{r.beforeValue} AS DECIMAL(24,8))
          AND m.after_value <=> CAST(#{r.afterValue} AS DECIMAL(24,8))
          AND m.before_measured_at <=> #{r.beforeMeasuredAt} AND m.after_measured_at <=> #{r.afterMeasuredAt}
        LIMIT 1
        """)
    Long sameMeasurement(@Param("id") long id,@Param("r") com.upc.computer.dto.SampleMeasurementRequest reading);

    @Insert("""
        INSERT INTO quality_defect_sample(nonconforming_id,inspection_id,inspection_item_id,unit_code,sample_label,
                sample_origin,source_ref,recorded_by,request_id,request_hash)
        VALUES(#{id},#{inspectionId},#{itemId},#{code},#{label},#{origin},#{source},#{actor},#{key},#{hash})
        """)
    int insertBinding(@Param("id") long id, @Param("inspectionId") long inspectionId, @Param("itemId") Long itemId,
                      @Param("code") String code, @Param("label") String label, @Param("origin") String origin,
                      @Param("source") String source, @Param("actor") long actor, @Param("key") String key, @Param("hash") String hash);

    @Select("""
        SELECT m.binding_id,m.step_id,m.metric_name,m.unit,m.before_measured_at,m.after_measured_at,
               EXISTS(SELECT 1 FROM quality_sample_measurement child WHERE child.prior_measurement_id=m.measurement_id)
               OR m.prior_measurement_id IS NOT NULL AS completed
        FROM quality_sample_measurement m WHERE m.measurement_id=#{priorId}
          AND m.nonconforming_id=#{id} AND m.inspection_id=#{inspectionId}
        """)
    Earlier earlier(@Param("id") long id,@Param("inspectionId") long inspectionId,@Param("priorId") long priorId);

    @Insert("""
        INSERT INTO quality_sample_measurement(binding_id,prior_measurement_id,nonconforming_id,inspection_id,
               inspection_item_id,unit_code,sample_label,work_order_id,step_id,step_name,metric_name,before_value,
               after_value,unit,before_measured_at,after_measured_at,source_ref,recorded_by,request_id,request_hash)
        SELECT b.binding_id,#{r.priorMeasurementId},b.nonconforming_id,b.inspection_id,b.inspection_item_id,
               b.unit_code,b.sample_label,#{scope.workOrderId},#{step.stepId},#{step.name},#{r.metricName},#{r.beforeValue},
               #{r.afterValue},#{r.unit},#{r.beforeMeasuredAt},#{r.afterMeasuredAt},#{r.sourceRef},#{actor},#{r.requestId},#{hash}
        FROM quality_defect_sample b WHERE b.binding_id=#{r.bindingId} AND b.nonconforming_id=#{id}
          AND b.inspection_id=#{scope.inspectionId}
        """)
    int insertMeasurement(@Param("id") long id,@Param("scope") Scope scope,@Param("step") Step step,
                          @Param("r") com.upc.computer.dto.SampleMeasurementRequest reading,
                          @Param("actor") long actor,@Param("hash") String hash);
}
