package com.upc.computer.assistant.v2.mapper.business;

import org.apache.ibatis.annotations.Select;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed, bounded, PII-minimized evidence queries. No original business mapper is registered here. */
public interface AiBusinessReadMapper {
    String WORK_ORDERS = "(SELECT w.work_order_id FROM work_order w JOIN production_plan_item pi ON pi.plan_item_id=w.plan_item_id JOIN production_plan p ON p.plan_id=pi.plan_id JOIN customer_order_item i ON i.order_item_id=pi.order_item_id WHERE p.source_order_id=#{id} AND i.order_id=#{id} AND w.material_id=i.material_id AND pi.material_id=i.material_id AND w.plan_id=pi.plan_id)";
    String REPORTS = "(SELECT r.report_id FROM work_report r WHERE r.work_order_id IN " + WORK_ORDERS + ")";
    String DISPATCHES = "(SELECT d.dispatch_id FROM dispatch_task d WHERE d.work_order_id IN " + WORK_ORDERS + ")";
    String INSPECTIONS = "(SELECT q.inspection_id FROM quality_inspection q WHERE q.work_order_id IN " + WORK_ORDERS + " OR q.work_report_id IN " + REPORTS + ")";
    String EQUIPMENT = "(SELECT d.equipment_id FROM dispatch_task d WHERE d.work_order_id IN " + WORK_ORDERS + " UNION SELECT r.equipment_id FROM work_report r WHERE r.work_order_id IN " + WORK_ORDERS + ")";
    String CASES = "(SELECT c.case_id FROM after_sales_case c WHERE c.order_id=#{id} OR c.delivery_id IN (SELECT d.delivery_id FROM delivery_order d WHERE d.order_id=#{id}) OR c.quality_inspection_id IN " + INSPECTIONS + ")";
    String PRODUCT_INVENTORIES = "(SELECT v.inventory_id FROM inventory v WHERE v.material_id IN (SELECT i.material_id FROM customer_order_item i WHERE i.order_id=#{id}))";

    @Select("SELECT q.inspection_id, q.inspection_no, q.work_order_id, q.work_report_id, q.material_id, q.batch_no, q.inspection_category, q.inspection_type, q.sample_quantity, q.qualified_quantity, q.unqualified_quantity, q.inspection_result, q.inspection_status, q.inspected_at, q.updated_at, LEFT(q.remark,500) AS remark, CASE WHEN (q.work_order_id IS NOT NULL AND q.work_order_id NOT IN " + WORK_ORDERS + ") OR (q.work_report_id IS NOT NULL AND (r.report_id IS NULL OR r.work_order_id NOT IN " + WORK_ORDERS + " OR (q.work_order_id IS NOT NULL AND r.work_order_id<>q.work_order_id))) OR q.material_id IS NULL OR NOT EXISTS (SELECT 1 FROM work_order w WHERE w.work_order_id=COALESCE(q.work_order_id,r.work_order_id) AND w.material_id=q.material_id) THEN 'CONTRADICTORY' ELSE 'EXPLICIT' END AS relation_status FROM quality_inspection q LEFT JOIN work_report r ON r.report_id=q.work_report_id WHERE q.inspection_id IN " + INSPECTIONS + " ORDER BY q.inspection_id LIMIT 1001")
    List<Map<String,Object>> inspections(long id);

    @Select("SELECT v.inspection_item_id, v.inspection_id, v.item_code, v.item_name, v.standard_value, v.measured_value, v.unit, v.result, v.defect_level, v.sort_order, v.updated_at, LEFT(v.remark,500) AS remark FROM quality_inspection_item v WHERE v.inspection_id IN " + INSPECTIONS + " ORDER BY v.inspection_item_id LIMIT 1001")
    List<Map<String,Object>> inspectionItems(long id);

    @Select("SELECT n.nonconforming_id, n.inspection_id, n.work_order_id, n.work_report_id, n.material_id, n.batch_no, n.defect_type, n.quantity, n.severity, n.handle_method, n.handle_status, n.registered_at, n.handled_at, n.updated_at, CASE WHEN n.work_order_id IS NOT NULL AND n.work_order_id NOT IN " + WORK_ORDERS + " THEN 'CONTRADICTORY' ELSE 'EXPLICIT' END AS relation_status FROM nonconforming_product n WHERE n.work_order_id IN " + WORK_ORDERS + " OR n.work_report_id IN " + REPORTS + " OR n.inspection_id IN " + INSPECTIONS + " ORDER BY n.nonconforming_id LIMIT 1001")
    List<Map<String,Object>> nonconformingProducts(long id);

    @Select("SELECT e.equipment_id, e.equipment_code, e.equipment_name, e.equipment_type, e.workshop, e.workstation, e.status, e.last_maintenance_at, e.updated_at FROM equipment e WHERE e.equipment_id IN " + EQUIPMENT + " ORDER BY e.equipment_id LIMIT 1001")
    List<Map<String,Object>> equipment(long id);

    @Select("SELECT 'dispatch_task' AS source_table, d.dispatch_id AS source_id, d.work_order_id, d.equipment_id, d.step_id, d.assigned_at AS observed_at, d.status AS source_status FROM dispatch_task d WHERE d.work_order_id IN " + WORK_ORDERS + " AND d.equipment_id IS NOT NULL UNION ALL SELECT 'work_report' AS source_table, r.report_id AS source_id, r.work_order_id, r.equipment_id, r.step_id, r.start_time AS observed_at, r.report_status AS source_status FROM work_report r WHERE r.work_order_id IN " + WORK_ORDERS + " AND r.equipment_id IS NOT NULL ORDER BY source_table, source_id LIMIT 1001")
    List<Map<String,Object>> equipmentUsage(long id);

    @Select("SELECT a.alarm_id, a.alarm_no, a.work_order_id, a.dispatch_id, a.equipment_id, a.alarm_type, a.alarm_level, a.alarm_status, a.reported_at, a.received_at, a.closed_at, a.updated_at, CASE WHEN (a.work_order_id IS NOT NULL AND a.work_order_id NOT IN " + WORK_ORDERS + ") OR (a.dispatch_id IS NOT NULL AND (d.dispatch_id IS NULL OR d.work_order_id NOT IN " + WORK_ORDERS + " OR (a.work_order_id IS NOT NULL AND a.work_order_id<>d.work_order_id) OR (a.equipment_id IS NOT NULL AND d.equipment_id IS NOT NULL AND a.equipment_id<>d.equipment_id))) THEN 'CONTRADICTORY' WHEN a.work_order_id IN " + WORK_ORDERS + " OR a.dispatch_id IN " + DISPATCHES + " THEN 'DIRECT_WORK_ORDER' ELSE 'CANDIDATE_EQUIPMENT' END AS relation_status FROM andon_alarm a LEFT JOIN dispatch_task d ON d.dispatch_id=a.dispatch_id WHERE a.work_order_id IN " + WORK_ORDERS + " OR a.dispatch_id IN " + DISPATCHES + " OR (a.work_order_id IS NULL AND a.dispatch_id IS NULL AND a.equipment_id IN " + EQUIPMENT + ") ORDER BY a.alarm_id LIMIT 1001")
    List<Map<String,Object>> alarms(long id);

    @Select("SELECT m.maintenance_id, m.equipment_id, m.alarm_id, m.maintenance_type, m.start_time, m.end_time, m.downtime_minutes, m.maintenance_result, m.updated_at FROM equipment_maintenance_record m WHERE m.equipment_id IN " + EQUIPMENT + " ORDER BY m.maintenance_id LIMIT 1001")
    List<Map<String,Object>> maintenance(long id);

    @Select("SELECT c.case_id, c.case_no, c.order_id, c.delivery_id, c.material_id, c.batch_no, c.quality_inspection_id, c.problem_type, c.case_level, c.case_status, c.opened_at, c.resolved_at, c.closed_at, c.updated_at, CASE WHEN (c.order_id IS NOT NULL AND c.order_id<>#{id}) OR (c.delivery_id IS NOT NULL AND (d.delivery_id IS NULL OR d.order_id<>#{id} OR (c.material_id IS NOT NULL AND c.material_id<>d.material_id) OR (c.batch_no IS NOT NULL AND d.batch_no IS NOT NULL AND c.batch_no<>d.batch_no))) OR (c.quality_inspection_id IS NOT NULL AND (q.inspection_id IS NULL OR q.inspection_id NOT IN " + INSPECTIONS + " OR (c.material_id IS NOT NULL AND c.material_id<>q.material_id) OR (c.batch_no IS NOT NULL AND q.batch_no IS NOT NULL AND c.batch_no<>q.batch_no))) THEN 'CONTRADICTORY' WHEN c.order_id=#{id} THEN 'DIRECT_ORDER' WHEN d.order_id=#{id} THEN 'DIRECT_DELIVERY' ELSE 'DIRECT_INSPECTION' END AS relation_status FROM after_sales_case c LEFT JOIN delivery_order d ON d.delivery_id=c.delivery_id LEFT JOIN quality_inspection q ON q.inspection_id=c.quality_inspection_id WHERE c.case_id IN " + CASES + " ORDER BY c.case_id LIMIT 1001")
    List<Map<String,Object>> cases(long id);

    @Select("SELECT t.task_id, t.task_no, t.case_no, t.plan_id, t.task_type, t.status, t.due_at, t.started_at, t.completed_at, t.updated_at FROM after_sales_task t WHERE t.case_no IN (SELECT c.case_no FROM after_sales_case c WHERE c.case_id IN " + CASES + ") ORDER BY t.task_id LIMIT 1001")
    List<Map<String,Object>> tasks(long id);

    @Select("SELECT c.closure_id, c.case_no, c.recheck_passed, c.customer_confirmed, c.created_at, c.updated_at FROM after_sales_closure c WHERE c.case_no IN (SELECT a.case_no FROM after_sales_case a WHERE a.case_id IN " + CASES + ") ORDER BY c.closure_id LIMIT 1001")
    List<Map<String,Object>> closures(long id);

    @Select("SELECT b.barcode_id, b.material_id, b.batch_no, b.inventory_id, b.quantity, b.remaining_quantity, b.barcode_status, b.source_type, b.related_work_order_id, b.updated_at FROM inventory_barcode b WHERE b.inventory_id IN " + PRODUCT_INVENTORIES + " ORDER BY b.barcode_id LIMIT 1001")
    List<Map<String,Object>> barcodes(long id);

    @Select("SELECT b.batch_id, b.batch_no, b.material_id, b.source_type, b.quantity, b.batch_status, b.produced_at, b.received_at, b.updated_at FROM material_batch b WHERE b.material_id IN (SELECT i.material_id FROM customer_order_item i WHERE i.order_id=#{id} UNION SELECT t.material_id FROM inventory_transaction t WHERE t.related_work_order_id IN " + WORK_ORDERS + ") ORDER BY b.batch_id LIMIT 1001")
    List<Map<String,Object>> batches(long id);

    @Select("SELECT t.transaction_id, t.inventory_id, t.material_id, t.transaction_type, t.quantity, t.batch_no, t.related_work_order_id, t.handled_at FROM inventory_transaction t WHERE t.inventory_id IN " + PRODUCT_INVENTORIES + " ORDER BY t.transaction_id LIMIT 1001")
    List<Map<String,Object>> lineage(long id);

    @Select("SELECT t.transaction_id, t.inventory_id, t.material_id, t.transaction_type, t.quantity, t.batch_no, t.related_work_order_id, t.handled_at FROM inventory_transaction t WHERE t.related_work_order_id IN " + WORK_ORDERS + " ORDER BY t.transaction_id LIMIT 1001")
    List<Map<String,Object>> consumption(long id);

    default Map<String,List<Map<String,Object>>> read(long orderId) {
        Map<String,List<Map<String,Object>>> data=new LinkedHashMap<>();
        data.put("quality_inspection",inspections(orderId));
        data.put("quality_inspection_item",inspectionItems(orderId));
        data.put("nonconforming_product",nonconformingProducts(orderId));
        data.put("equipment",equipment(orderId));
        data.put("equipment_usage",equipmentUsage(orderId));
        data.put("andon_alarm",alarms(orderId));
        data.put("equipment_maintenance_record",maintenance(orderId));
        data.put("after_sales_case",cases(orderId));
        data.put("after_sales_task",tasks(orderId));
        data.put("after_sales_closure",closures(orderId));
        data.put("inventory_barcode",barcodes(orderId));
        data.put("material_batch",batches(orderId));
        data.put("inventory_lineage",lineage(orderId));
        data.put("inventory_transaction_consumption",consumption(orderId));
        return data;
    }
}
