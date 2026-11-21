package com.upc.computer.assistant.v2.tools.graphexport;
import com.upc.computer.assistant.v2.mapper.graphexport.GraphExportMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GraphExportMapperTest {
    @Test void actualMyBatisStatementsAreBoundSelectsWithHardLimitsAndWhitelistedColumns() {
        var configuration = new org.apache.ibatis.session.Configuration(); configuration.addMapper(GraphExportMapper.class);
        for (var method : GraphExportMapper.class.getDeclaredMethods()) {
            var statement = configuration.getMappedStatement(GraphExportMapper.class.getName() + "." + method.getName());
            assertEquals(org.apache.ibatis.mapping.SqlCommandType.SELECT, statement.getSqlCommandType());
            var bound = statement.getBoundSql(Map.of("orderId", 1L)); var sql = bound.getSql().toLowerCase();
            assertTrue(sql.contains("?")); assertTrue(sql.contains("limit 1000")); assertFalse(sql.contains("${"));
            assertFalse(sql.contains("select *")); assertFalse(sql.matches("(?s).*\\b(customer_name|customer_phone|customer_contact|remark|inspector_id|unit_price|order_amount)\\b.*"));
            assertTrue(bound.getParameterMappings().stream().allMatch(p -> p.getProperty().equals("orderId")));
        }
    }
    @Test void exactWorkOrderAndInspectionScopeIncludesLineOwnerAndPlanConsistency() {
        var configuration = new org.apache.ibatis.session.Configuration(); configuration.addMapper(GraphExportMapper.class);
        for (String method : new String[]{"workOrders", "inspections"}) {
            var sql = configuration.getMappedStatement(GraphExportMapper.class.getName() + "." + method).getBoundSql(Map.of("orderId", 1L)).getSql().toLowerCase();
            assertTrue(sql.contains("w.plan_item_id = pi.plan_item_id")); assertTrue(sql.contains("w.plan_id = pi.plan_id"));
            assertTrue(sql.contains("oi.order_id = ?")); assertFalse(sql.contains("material_id ="));
        }
    }
    @Test void exportMethodDeclaresOneBoundedReadOnlyRepeatableReadTransaction() throws Exception {
        var tx = GraphExportService.class.getMethod("export", long.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(tx); assertTrue(tx.readOnly()); assertEquals(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ, tx.isolation());
        assertTrue(tx.timeout() > 0 && tx.timeout() <= 30);
    }
    @Test void missingPlanHeaderReferenceIsDiagnosedThroughTheExplicitPlanItem() {
        var configuration = new org.apache.ibatis.session.Configuration(); configuration.addMapper(GraphExportMapper.class);
        var sql = configuration.getMappedStatement(GraphExportMapper.class.getName() + ".unresolvedPlans").getBoundSql(Map.of("orderId", 1L)).getSql().toLowerCase();
        assertTrue(sql.contains("work_pi.plan_item_id = w.plan_item_id"), "A work order with a null header plan still has an explicit plan-item diagnostic anchor");
        assertTrue(sql.contains("w.plan_id is null"), "Missing plan header must be reported even if other valid work orders exist on the plan");
    }
}
