package com.upc.computer.assistant.v2.mapper.graphexport;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import java.util.List;
import java.util.Map;

/** Fixed, bounded projections; never invokes a business service or schema initialization. */
public interface GraphExportMapper {
    @Select("SELECT order_id AS id, audit_status AS status FROM customer_order WHERE order_id = #{orderId} ORDER BY order_id LIMIT 1000")
    @Options(timeout = 10)
    List<Map<String,Object>> order(@Param("orderId") long orderId);

    @Select("SELECT order_item_id AS id, quantity, item_status AS status FROM customer_order_item WHERE order_id = #{orderId} ORDER BY order_item_id LIMIT 1000")
    @Options(timeout = 10)
    List<Map<String,Object>> items(@Param("orderId") long orderId);

    @Select("""
        SELECT p.plan_id AS id, p.plan_status AS status FROM production_plan p
        WHERE p.source_order_id = #{orderId} OR EXISTS (
          SELECT 1 FROM production_plan_item pi JOIN customer_order_item oi ON pi.order_item_id = oi.order_item_id
          WHERE pi.plan_id = p.plan_id AND oi.order_id = #{orderId})
        ORDER BY p.plan_id LIMIT 1000
        """)
    @Options(timeout = 10)
    List<Map<String,Object>> plans(@Param("orderId") long orderId);

    @Select("""
        SELECT pi.plan_item_id AS id, pi.order_item_id AS item_id, pi.plan_id
        FROM production_plan_item pi JOIN customer_order_item oi ON pi.order_item_id = oi.order_item_id
        JOIN production_plan p ON p.plan_id = pi.plan_id
        WHERE oi.order_id = #{orderId} ORDER BY pi.plan_item_id LIMIT 1000
        """)
    @Options(timeout = 10)
    List<Map<String,Object>> planLinks(@Param("orderId") long orderId);

    @Select("""
        SELECT w.work_order_id AS id, w.plan_id, w.plan_item_id, pi.order_item_id AS item_id,
          w.status, w.planned_quantity, w.completed_quantity
        FROM work_order w JOIN production_plan_item pi ON w.plan_item_id = pi.plan_item_id AND w.plan_id = pi.plan_id
        JOIN customer_order_item oi ON pi.order_item_id = oi.order_item_id
        WHERE oi.order_id = #{orderId} ORDER BY w.work_order_id LIMIT 1000
        """)
    @Options(timeout = 10)
    List<Map<String,Object>> workOrders(@Param("orderId") long orderId);

    @Select("""
        SELECT q.inspection_id AS id, q.work_order_id, q.inspection_result AS status,
          q.sample_quantity, q.qualified_quantity, q.unqualified_quantity
        FROM quality_inspection q JOIN work_order w ON q.work_order_id = w.work_order_id
        JOIN production_plan_item pi ON w.plan_item_id = pi.plan_item_id AND w.plan_id = pi.plan_id
        JOIN customer_order_item oi ON pi.order_item_id = oi.order_item_id
        WHERE oi.order_id = #{orderId} ORDER BY q.inspection_id LIMIT 1000
        """)
    @Options(timeout = 10)
    List<Map<String,Object>> inspections(@Param("orderId") long orderId);

    @Select("""
        SELECT DISTINCT p.plan_id AS id FROM production_plan p
        JOIN work_order w ON w.plan_id = p.plan_id OR EXISTS (
          SELECT 1 FROM production_plan_item work_pi
          WHERE work_pi.plan_item_id = w.plan_item_id AND work_pi.plan_id = p.plan_id)
        LEFT JOIN production_plan_item pi ON w.plan_item_id = pi.plan_item_id
        WHERE (p.source_order_id = #{orderId} OR EXISTS (
          SELECT 1 FROM production_plan_item scope_pi JOIN customer_order_item oi ON scope_pi.order_item_id = oi.order_item_id
          WHERE scope_pi.plan_id = p.plan_id AND oi.order_id = #{orderId}))
          AND (pi.plan_item_id IS NULL OR pi.order_item_id IS NULL OR w.plan_id IS NULL OR pi.plan_id != w.plan_id)
        ORDER BY p.plan_id LIMIT 1000
        """)
    @Options(timeout = 10)
    List<Map<String,Object>> unresolvedPlans(@Param("orderId") long orderId);
}
