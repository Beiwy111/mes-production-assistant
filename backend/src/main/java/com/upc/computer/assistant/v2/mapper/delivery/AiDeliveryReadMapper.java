package com.upc.computer.assistant.v2.mapper.delivery;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** Fixed SELECT projections only. Every query is bounded and order-scoped after server authentication. */
public interface AiDeliveryReadMapper {
    @Select("<script>SELECT order_id, order_no, required_delivery_date, audit_status, updated_at FROM customer_order WHERE 1=1 <if test='id != null'>AND order_id=#{id}</if><if test='number != null'>AND order_no=#{number}</if> ORDER BY order_id LIMIT 2</script>")
    List<Map<String,Object>> resolve(@Param("id") Long id, @Param("number") String number);

    @Select("SELECT order_id, order_no, required_delivery_date, audit_status, updated_at FROM customer_order WHERE order_id=#{id}")
    List<Map<String,Object>> order(long id);

    @Select("SELECT i.order_item_id, i.order_id, i.material_id, i.product_name, i.quantity, i.unit, m.unit AS material_unit, i.delivery_date, i.item_status, i.updated_at FROM customer_order_item i LEFT JOIN material m ON m.material_id=i.material_id WHERE i.order_id=#{id} ORDER BY i.order_item_id LIMIT 1001")
    List<Map<String,Object>> lines(long id);

    @Select("SELECT d.delivery_id, d.order_id, d.material_id, d.work_order_id, d.batch_no, d.delivery_quantity, d.delivery_date, d.delivery_status, d.updated_at, CASE WHEN p.order_item_id=i.order_item_id AND w.material_id=d.material_id AND p.material_id=d.material_id AND pp.source_order_id=d.order_id AND w.plan_id=p.plan_id THEN p.order_item_id END AS mapped_order_item_id, CASE WHEN d.work_order_id IS NOT NULL AND (w.work_order_id IS NULL OR p.plan_item_id IS NULL OR w.plan_id IS NULL OR w.plan_id<>p.plan_id OR i.order_item_id IS NULL OR w.material_id<>d.material_id OR p.material_id<>d.material_id OR pp.source_order_id IS NULL OR pp.source_order_id<>d.order_id) THEN 'INVALID' ELSE 'RECORDED' END AS mapping_status FROM delivery_order d LEFT JOIN work_order w ON w.work_order_id=d.work_order_id LEFT JOIN production_plan_item p ON p.plan_item_id=w.plan_item_id LEFT JOIN production_plan pp ON pp.plan_id=p.plan_id LEFT JOIN customer_order_item i ON i.order_item_id=p.order_item_id AND i.order_id=d.order_id AND i.material_id=d.material_id WHERE d.order_id=#{id} ORDER BY d.delivery_id LIMIT 1001")
    List<Map<String,Object>> deliveries(long id);

    @Select("SELECT p.plan_item_id, p.plan_id, p.order_item_id, p.material_id, p.planned_quantity, p.completed_quantity, p.planned_start_date, p.planned_end_date, p.item_status, p.updated_at, h.source_order_id, h.plan_status FROM production_plan_item p JOIN customer_order_item i ON i.order_item_id=p.order_item_id JOIN production_plan h ON h.plan_id=p.plan_id WHERE i.order_id=#{id} ORDER BY p.plan_item_id LIMIT 1001")
    List<Map<String,Object>> plans(long id);

    @Select("SELECT w.work_order_id, w.work_order_no, w.plan_id, w.plan_item_id, p.order_item_id, w.material_id, w.route_id, w.planned_quantity, w.completed_quantity, w.qualified_quantity, w.unqualified_quantity, w.planned_start_time, w.planned_end_time, w.actual_start_time, w.actual_end_time, w.status, w.updated_at, CASE WHEN i.order_id=h.source_order_id AND p.material_id=i.material_id AND w.material_id=i.material_id AND w.plan_id=p.plan_id THEN 'LINKED' ELSE 'UNVERIFIED' END AS mapping_status FROM work_order w JOIN production_plan h ON h.plan_id=w.plan_id LEFT JOIN production_plan_item p ON p.plan_item_id=w.plan_item_id LEFT JOIN customer_order_item i ON i.order_item_id=p.order_item_id WHERE h.source_order_id=#{id} ORDER BY w.work_order_id LIMIT 1001")
    List<Map<String,Object>> workOrders(long id);

    String MATERIALS = "(SELECT material_id FROM customer_order_item WHERE order_id=#{id} UNION SELECT b.child_material_id FROM bom b JOIN customer_order_item i ON i.material_id=b.parent_material_id WHERE i.order_id=#{id})";
    @Select("SELECT b.bom_id, b.parent_material_id, b.child_material_id, m.unit AS child_unit, b.quantity, b.loss_rate, b.version_no, b.effective_date, b.expire_date, b.status, b.updated_at FROM bom b LEFT JOIN material m ON m.material_id=b.child_material_id WHERE b.parent_material_id IN (SELECT material_id FROM customer_order_item WHERE order_id=#{id}) ORDER BY b.bom_id LIMIT 1001")
    List<Map<String,Object>> bom(long id);

    @Select("SELECT inventory_id, material_id, warehouse_code, location_code, batch_no, quantity_on_hand, quantity_reserved, quantity_available, inventory_status, updated_at FROM inventory WHERE material_id IN " + MATERIALS + " ORDER BY inventory_id LIMIT 1001")
    List<Map<String,Object>> inventory(long id);

    @Select("SELECT transaction_id, inventory_id, material_id, transaction_type, quantity, batch_no, related_work_order_id, related_purchase_order_id, handled_at FROM inventory_transaction WHERE material_id IN " + MATERIALS + " ORDER BY transaction_id LIMIT 1001")
    List<Map<String,Object>> transactions(long id);

    @Select("SELECT p.purchase_order_id, p.status, p.expected_arrival_date, i.purchase_order_item_id, i.material_id, i.quantity, i.received_quantity, i.unit, i.item_status, i.updated_at FROM purchase_order_item i JOIN purchase_order p ON p.purchase_order_id=i.purchase_order_id WHERE i.material_id IN " + MATERIALS + " ORDER BY i.purchase_order_item_id LIMIT 1001")
    List<Map<String,Object>> purchases(long id);

    String WORK_ORDERS = "(SELECT w.work_order_id FROM work_order w JOIN production_plan p ON p.plan_id=w.plan_id WHERE p.source_order_id=#{id})";
    @Select("SELECT r.report_id, r.work_order_id, r.dispatch_id, r.step_id, r.report_date, r.start_time, r.end_time, r.completed_quantity, r.qualified_quantity, r.unqualified_quantity, r.report_status, r.confirmed_at, r.updated_at FROM work_report r WHERE r.work_order_id IN " + WORK_ORDERS + " ORDER BY r.report_id LIMIT 1001")
    List<Map<String,Object>> reports(long id);

    @Select("SELECT d.dispatch_id, d.work_order_id, d.step_id, d.assigned_quantity, d.accepted_quantity, d.completed_quantity, d.assigned_at, d.accepted_at, d.status, d.updated_at FROM dispatch_task d WHERE d.work_order_id IN " + WORK_ORDERS + " ORDER BY d.dispatch_id LIMIT 1001")
    List<Map<String,Object>> dispatch(long id);

    @Select("SELECT s.step_id, s.route_id, s.step_no, s.step_name, s.standard_work_hours, s.status, s.updated_at FROM process_step s WHERE s.route_id IN (SELECT w.route_id FROM work_order w JOIN production_plan p ON p.plan_id=w.plan_id WHERE p.source_order_id=#{id}) ORDER BY s.step_id LIMIT 1001")
    List<Map<String,Object>> steps(long id);

    @Select("SELECT s.schedule_id, s.plan_id, s.step_id, s.step_no, s.step_name, s.planned_quantity, s.planned_start, s.planned_end, s.standard_hours, s.sort_no, s.updated_at FROM production_plan_schedule s JOIN production_plan p ON p.plan_id=s.plan_id WHERE p.source_order_id=#{id} ORDER BY s.schedule_id LIMIT 1001")
    List<Map<String,Object>> schedules(long id);
}
