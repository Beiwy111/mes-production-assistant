package com.upc.computer.assistant.v2.runtime;

import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

/** Inspection ids originate in the scoped header read; all statements run in the same read-only snapshot. */
public interface InspectionTraceMapper {
    @Select("""
        SELECT q.inspection_id,q.inspection_no,q.inspection_category,q.inspection_type,q.inspection_result,
               q.inspection_status,q.work_order_id,q.work_report_id,q.material_id,m.material_name,q.batch_no,
               q.inspector_id,u.real_name inspector_name,q.sample_quantity,q.qualified_quantity,q.unqualified_quantity,
               CAST(q.inspected_at AS CHAR) inspected_at,CAST(q.created_at AS CHAR) created_at,CAST(q.updated_at AS CHAR) updated_at
        FROM quality_inspection q LEFT JOIN material m ON m.material_id=q.material_id
        LEFT JOIN `user` u ON u.user_id=q.inspector_id
        WHERE q.inspection_no=#{inspectionNo}
        """)
    Map<String,Object> traceInspection(String inspectionNo);

    @Select("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN "
        +"('quality_inspection_round','quality_inspection_round_item','quality_defect_sample','quality_sample_measurement')")
    List<String> revisionTables();

    @Select("""
        SELECT r.round_id,r.event_type,CAST(r.recorded_at AS CHAR) recorded_at,
               CAST(r.source_updated_at AS CHAR) source_updated_at,CAST(r.inspected_at AS CHAR) inspected_at,
               r.operator_source,r.inspection_type,r.inspection_status,r.inspection_result,
               r.sample_quantity,r.qualified_quantity,r.unqualified_quantity
        FROM quality_inspection q JOIN quality_inspection_round r ON r.inspection_id=q.inspection_id AND r.inspection_no=q.inspection_no
        WHERE q.inspection_id=#{id} ORDER BY r.round_id
        """)
    List<Map<String,Object>> traceEvents(long id);

    @Select("""
        SELECT r.round_id,i.item_code,i.item_name,i.standard_value,i.measured_value,i.unit,i.result,CAST(i.source_updated_at AS CHAR) source_updated_at
        FROM quality_inspection q JOIN quality_inspection_round r ON r.inspection_id=q.inspection_id AND r.inspection_no=q.inspection_no
        JOIN quality_inspection_round_item i ON i.round_id=r.round_id
        WHERE q.inspection_id=#{id} ORDER BY r.round_id,i.sort_order,i.round_item_id
        """)
    List<Map<String,Object>> traceEventItems(long id);

    String TRACE_SAMPLE = """
        FROM quality_inspection q JOIN nonconforming_product n ON n.inspection_id=q.inspection_id
          AND (n.material_id IS NULL OR n.material_id=q.material_id) AND (NULLIF(n.batch_no,'') IS NULL OR n.batch_no=q.batch_no)
        LEFT JOIN work_report qr ON qr.report_id=q.work_report_id
        LEFT JOIN work_report nr ON nr.report_id=n.work_report_id
        LEFT JOIN work_order w ON w.work_order_id=COALESCE(q.work_order_id,qr.work_order_id,n.work_order_id,nr.work_order_id)
        JOIN quality_defect_sample b ON b.inspection_id=q.inspection_id AND b.nonconforming_id=n.nonconforming_id
          AND (q.work_order_id IS NULL OR q.work_order_id=w.work_order_id)
          AND (n.work_order_id IS NULL OR n.work_order_id=w.work_order_id)
          AND (q.work_report_id IS NULL OR qr.work_order_id=w.work_order_id)
          AND (n.work_report_id IS NULL OR nr.work_order_id=w.work_order_id)
        """;

    @Select("SELECT b.binding_id,n.nonconforming_no,b.unit_code,b.sample_label,b.sample_origin,b.source_ref,"
        +"CAST(b.recorded_at AS CHAR) recorded_at "+TRACE_SAMPLE+" WHERE q.inspection_id=#{id} ORDER BY b.binding_id")
    List<Map<String,Object>> traceSampleBindings(long id);

    @Select("SELECT b.binding_id,n.nonconforming_no,b.sample_label,m.measurement_id,m.prior_measurement_id,m.step_id,m.step_name,"
        +"m.metric_name,m.before_value,m.after_value,m.unit,CAST(m.before_measured_at AS CHAR) before_measured_at,"
        +"CAST(m.after_measured_at AS CHAR) after_measured_at,m.source_ref,CAST(m.recorded_at AS CHAR) recorded_at "+TRACE_SAMPLE
        +" JOIN quality_sample_measurement m ON m.binding_id=b.binding_id AND m.nonconforming_id=n.nonconforming_id"
        +" AND m.inspection_id=q.inspection_id AND m.work_order_id=w.work_order_id"
        +" WHERE q.inspection_id=#{id} ORDER BY m.measurement_id")
    List<Map<String,Object>> traceSampleMeasurements(long id);

    @Select("""
        SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name='assistant_training_work_history'
        """)
    int trainingArchiveAvailable();

    @Select("SELECT a.record_kind,a.record_no,a.operator_name,a.step_name,a.source_file " + TRACE_WORK
        + " JOIN assistant_training_work_history a ON a.work_order_no=w.work_order_no"
        + " WHERE q.inspection_id=#{id} AND a.recorded_on<=DATE(q.inspected_at)"
        + " AND ((a.record_kind='REPORT' AND NOT EXISTS (SELECT 1 FROM work_report r WHERE r.report_no=a.record_no))"
        + " OR (#{dispatch} AND a.record_kind='DISPATCH' AND NOT EXISTS (SELECT 1 FROM dispatch_task d WHERE d.dispatch_no=a.record_no)))"
        + " ORDER BY a.record_kind,a.record_no")
    List<Map<String,Object>> traceTrainingHistory(@org.apache.ibatis.annotations.Param("id") long id,
        @org.apache.ibatis.annotations.Param("dispatch") boolean dispatch);

    @Select("""
        SELECT COUNT(*) inspections,SUM(q.sample_quantity) sampled,SUM(q.unqualified_quantity) rejected
        FROM quality_inspection h JOIN quality_inspection q ON q.material_id=h.material_id
          AND q.inspection_type=h.inspection_type AND q.inspection_category=h.inspection_category
          AND q.inspected_at>=DATE_SUB(h.inspected_at,INTERVAL 90 DAY) AND q.inspected_at<h.inspected_at
        WHERE h.inspection_id=#{id} AND q.batch_no<>h.batch_no AND TRIM(q.batch_no)<>''
          AND q.sample_quantity>0 AND q.qualified_quantity>=0 AND q.unqualified_quantity>=0
          AND q.sample_quantity=q.qualified_quantity+q.unqualified_quantity
          AND q.inspection_status IN ('PASSED','FAILED') AND q.inspection_result IN ('QUALIFIED','UNQUALIFIED')
          AND ((q.inspection_status='PASSED' AND q.inspection_result='QUALIFIED')
            OR (q.inspection_status='FAILED' AND q.inspection_result='UNQUALIFIED' AND q.unqualified_quantity>0))
        """ + " AND (" + NaturalBusinessReadMapper.DETAIL_CONFLICT + ")=0")
    Map<String,Object> traceBaseline(long id);

    @Select("""
        SELECT inspection_item_id,inspection_id,item_code,item_name,standard_value,measured_value,unit,result,defect_level,
               CAST(updated_at AS CHAR) updated_at
        FROM quality_inspection_item WHERE inspection_id=#{id} ORDER BY sort_order,inspection_item_id
        """)
    List<Map<String,Object>> traceItems(long id);

    @Select("""
        SELECT nonconforming_id,nonconforming_no,inspection_id,material_id,work_order_id,work_report_id,batch_no,
               defect_type,defect_description,quantity,severity,handle_method,handle_status,remark
        FROM nonconforming_product WHERE inspection_id=#{id} ORDER BY nonconforming_id
        """)
    List<Map<String,Object>> traceDefects(long id);

    @Select("""
        SELECT b.batch_id,b.batch_no,b.material_id,b.batch_status FROM quality_inspection q
        JOIN material_batch b ON b.batch_no=q.batch_no AND b.material_id=q.material_id
        WHERE q.inspection_id=#{id} AND TRIM(q.batch_no)<>'' ORDER BY b.batch_id
        """)
    List<Map<String,Object>> traceBatch(long id);

    @Select("""
        SELECT q.inspection_id,q.inspection_no,q.inspection_status,q.inspection_result,q.inspection_category,q.inspection_type,q.work_order_id,q.batch_no,
               q.sample_quantity,q.qualified_quantity,q.unqualified_quantity,CAST(q.inspected_at AS CHAR) inspected_at,
        """ + NaturalBusinessReadMapper.DETAIL_CONFLICT + """
         AS detail_count_conflict FROM quality_inspection h JOIN quality_inspection q ON q.batch_no=h.batch_no AND q.material_id=h.material_id
          AND q.inspection_category=h.inspection_category
        WHERE h.inspection_id=#{id} AND TRIM(h.batch_no)<>'' ORDER BY q.inspected_at,q.inspection_id
        """)
    List<Map<String,Object>> traceBatchInspections(long id);

    // Anchor the comparison to a recorded symptom, and exclude the current lot and future cases.
    @Select("""
        SELECT b.inspection_no,b.batch_no,b.inspection_category,b.material_id,
               CAST(b.inspected_at AS CHAR) inspected_at,n.nonconforming_no,n.defect_type,
               n.defect_description,n.handle_method,n.handle_status
        FROM quality_inspection q JOIN quality_inspection b ON b.inspected_at<q.inspected_at
          AND b.batch_no<>q.batch_no AND TRIM(b.batch_no)<>'' AND TRIM(q.batch_no)<>''
        JOIN nonconforming_product n ON n.inspection_id=b.inspection_id
        WHERE q.inspection_id=#{id} AND b.inspection_status<>'PENDING'
          AND (n.material_id IS NULL OR n.material_id=b.material_id)
          AND (n.batch_no IS NULL OR n.batch_no=b.batch_no)
          AND (n.work_order_id IS NULL OR n.work_order_id=b.work_order_id)
          AND EXISTS (SELECT 1 FROM nonconforming_product t WHERE t.inspection_id=q.inspection_id
              AND t.defect_type=n.defect_type AND TRIM(t.defect_type)<>''
              AND (t.batch_no IS NULL OR t.batch_no=q.batch_no)
              AND (t.material_id IS NULL OR t.material_id=q.material_id)
              AND (t.work_order_id IS NULL OR t.work_order_id=q.work_order_id))
        ORDER BY (b.material_id=q.material_id) DESC,(b.inspection_category=q.inspection_category) DESC,
                 b.inspected_at DESC,n.nonconforming_id DESC LIMIT 6
        """)
    List<Map<String,Object>> traceSimilarDefects(long id);

    @Select("""
        SELECT b.inspection_no,b.batch_no,r.report_no,s.step_name,r.unqualified_quantity,r.report_status
        FROM quality_inspection q JOIN quality_inspection b ON b.batch_no=q.batch_no AND b.material_id=q.material_id
          AND b.inspection_category=q.inspection_category
        JOIN work_report r ON r.report_id=b.work_report_id AND (b.work_order_id IS NULL OR b.work_order_id=r.work_order_id)
        JOIN work_order w ON w.work_order_id=r.work_order_id AND w.material_id=b.material_id
        LEFT JOIN process_step s ON s.step_id=r.step_id
        WHERE q.inspection_id=#{id} AND TRIM(q.batch_no)<>'' ORDER BY b.inspection_id,r.report_id
        """)
    List<Map<String,Object>> traceLotReports(long id);

    // If the inspection carries only a report FK, the report's work order is still verifiable.
    String TRACE_WORK = """
        FROM quality_inspection q LEFT JOIN work_report direct ON direct.report_id=q.work_report_id
        JOIN work_order w ON w.work_order_id=COALESCE(q.work_order_id,direct.work_order_id)
        """;

    @Select("SELECT w.work_order_id,w.work_order_no,w.material_id,w.route_id,w.status " + TRACE_WORK + " WHERE q.inspection_id=#{id}")
    List<Map<String,Object>> traceWorkOrder(long id);

    @Select("SELECT s.step_id,s.step_no,s.step_name " + TRACE_WORK
        + " JOIN process_step s ON s.route_id=w.route_id WHERE q.inspection_id=#{id} ORDER BY s.step_no,s.step_id")
    List<Map<String,Object>> traceRoute(long id);

    String INSPECTION_WORK_ID = "COALESCE(q.work_order_id,(SELECT qr.work_order_id FROM work_report qr WHERE qr.report_id=q.work_report_id))";
    String DEFECT_REPORT_LINK = """
        EXISTS (SELECT 1 FROM nonconforming_product n WHERE n.inspection_id=q.inspection_id AND n.work_report_id=r.report_id
            AND (n.material_id IS NULL OR n.material_id=q.material_id)
            AND (NULLIF(n.batch_no,'') IS NULL OR n.batch_no=q.batch_no)
            AND (n.work_order_id IS NULL OR n.work_order_id=r.work_order_id)
            AND r.work_order_id=COALESCE(
        """ + INSPECTION_WORK_ID + ",r.work_order_id))";

    @Select("""
        SELECT r.report_id,r.report_no,r.work_order_id,r.step_id,s.step_name,r.equipment_id,r.dispatch_id,r.report_status,
               r.operator_id,u.real_name operator_name,r.completed_quantity,r.qualified_quantity,r.unqualified_quantity,CAST(r.report_date AS CHAR) report_date,
               CASE WHEN r.report_id=q.work_report_id AND (q.work_order_id IS NULL OR r.work_order_id=q.work_order_id)
                         THEN '检验单直接报工'
                    WHEN r.report_id=q.work_report_id THEN '检验工单与报工工单矛盾'
                    WHEN
        """ + DEFECT_REPORT_LINK + """
                    THEN '不合格品关联报工'
                    ELSE '同工单背景' END AS link_basis
        FROM quality_inspection q JOIN work_report r ON (r.report_id=q.work_report_id OR r.work_order_id=
        """ + INSPECTION_WORK_ID + " OR "+DEFECT_REPORT_LINK+") " + """
        LEFT JOIN process_step s ON s.step_id=r.step_id LEFT JOIN `user` u ON u.user_id=r.operator_id
        WHERE q.inspection_id=#{id} ORDER BY r.report_id
        """)
    List<Map<String,Object>> traceReports(long id);

    @Select("SELECT d.dispatch_id,d.dispatch_no,d.work_order_id,d.step_id,s.step_name,d.operator_id,u.real_name operator_name,d.equipment_id,d.status " + TRACE_WORK
        + " JOIN dispatch_task d ON d.work_order_id=w.work_order_id LEFT JOIN process_step s ON s.step_id=d.step_id"
        + " LEFT JOIN `user` u ON u.user_id=d.operator_id"
        + " WHERE q.inspection_id=#{id} ORDER BY d.dispatch_id")
    List<Map<String,Object>> traceDispatches(long id);

    @Select("""
        SELECT e.equipment_id,e.equipment_code,e.equipment_name,e.status,
               CASE WHEN EXISTS (SELECT 1 FROM quality_inspection q JOIN work_report r ON r.report_id=q.work_report_id
                        WHERE q.inspection_id=#{id} AND r.equipment_id=e.equipment_id
                        AND (q.work_order_id IS NULL OR q.work_order_id=r.work_order_id)) THEN '检验单直接报工设备'
                    WHEN EXISTS (SELECT 1 FROM quality_inspection q JOIN work_report r ON r.equipment_id=e.equipment_id
                        WHERE q.inspection_id=#{id} AND
        """ + DEFECT_REPORT_LINK + ") THEN '不合格品关联报工设备' " + """
                    ELSE '同工单背景设备' END AS link_basis
        FROM equipment e
        WHERE e.equipment_id IN (
            SELECT r.equipment_id FROM work_report r JOIN quality_inspection q ON q.inspection_id=#{id}
            WHERE (r.report_id=q.work_report_id AND (q.work_order_id IS NULL OR q.work_order_id=r.work_order_id))
                  OR r.work_order_id=
        """ + INSPECTION_WORK_ID + " OR "+DEFECT_REPORT_LINK+" " + """
            UNION
            SELECT d.equipment_id
        """ + TRACE_WORK + """
            JOIN dispatch_task d ON d.work_order_id=w.work_order_id WHERE q.inspection_id=#{id})
        ORDER BY e.equipment_id
        """)
    List<Map<String,Object>> traceEquipment(long id);

    @Select("SELECT a.alarm_id,a.alarm_no,a.alarm_type,a.alarm_level,a.alarm_status,a.equipment_id,"
        + " CAST(a.reported_at AS CHAR) reported_at,a.work_order_id,a.dispatch_id " + TRACE_WORK
        + " JOIN andon_alarm a ON (a.work_order_id=w.work_order_id OR (a.work_order_id IS NULL AND EXISTS "
        + " (SELECT 1 FROM dispatch_task d WHERE d.dispatch_id=a.dispatch_id AND d.work_order_id=w.work_order_id)))"
        + " WHERE q.inspection_id=#{id} AND a.reported_at<=q.inspected_at ORDER BY a.reported_at DESC,a.alarm_id")
    List<Map<String,Object>> traceAlarms(long id);
}
