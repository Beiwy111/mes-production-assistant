package com.upc.computer.service;

import com.upc.computer.assistant.v2.runtime.InspectionTraceMapper;
import com.upc.computer.common.JwtUtil;
import com.upc.computer.dto.DefectSampleRequest;
import com.upc.computer.dto.SampleMeasurementRequest;
import com.upc.computer.entity.QualityInspection;
import com.upc.computer.entity.QualityInspectionItem;
import com.upc.computer.mapper.*;
import com.upc.computer.service.impl.QualityServiceImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Opt in with MES_QUALITY_WRITE_LIVE=1. Copies only base table definitions into a unique scratch schema. */
@EnabledIfEnvironmentVariable(named = "MES_QUALITY_WRITE_LIVE", matches = "1")
class QualityInspectionMySqlLiveTest {
    @Test
    void officialWritesAndTraceReadsUseTheSameImmutableEvidence() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/migrations"))) root = root.getParent();
        assertNotNull(root);
        Properties profile = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("backend/assistant/deploy/.env.agents.local"))) {
            profile.load(reader);
        }
        String schema = "mes_quality_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(profile.getProperty("MES_AGENT_JDBC_URL"),
                profile.getProperty("MES_AGENT_JDBC_USER"), profile.getProperty("MES_AGENT_JDBC_PASSWORD"));
             var ddl = connection.createStatement()) {
            String source = connection.getCatalog();
            assertTrue(source != null && source.matches("[a-zA-Z0-9_]+"));
            ddl.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            try {
                connection.setCatalog(schema);
                try (var setup = connection.createStatement()) {
                    try (var selected = setup.executeQuery("SELECT DATABASE()")) {
                        assertTrue(selected.next());
                        assertEquals(schema, selected.getString(1));
                    }
                    for (String table : List.of("quality_inspection","quality_inspection_item","nonconforming_product"))
                        setup.execute("CREATE TABLE " + table + " LIKE " + source + "." + table);
                    setup.execute("CREATE TABLE work_order(work_order_id BIGINT PRIMARY KEY,route_id BIGINT)");
                    setup.execute("CREATE TABLE work_report(report_id BIGINT PRIMARY KEY,work_order_id BIGINT)");
                    setup.execute("CREATE TABLE process_step(step_id BIGINT PRIMARY KEY,route_id BIGINT,step_no INT,step_name VARCHAR(100))");
                    setup.execute("INSERT INTO work_order VALUES (41,21)");
                    setup.execute("INSERT INTO process_step VALUES (13,21,1,'Calibration')");
                }
                for (String file : List.of("quality_inspection_rounds.sql","quality_sample_measurements.sql"))
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve("sql/migrations").resolve(file)));
                verifyWrites(connection);
            } finally {
                connection.setCatalog(source);
                ddl.execute("DROP DATABASE " + schema);
            }
        }
    }

    private void verifyWrites(Connection connection) {
        var dataSource = new SingleConnectionDataSource(connection, true);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var jdbc = new JdbcTemplate(dataSource);
        var config = new Configuration(new Environment("quality-test", new SpringManagedTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        List.of(QualityInspectionMapper.class, QualityInspectionItemMapper.class, NonconformingProductMapper.class,
                QualityInspectionRoundMapper.class, QualitySampleEvidenceMapper.class, InspectionTraceMapper.class)
                .forEach(config::addMapper);
        var session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        var headers = session.getMapper(QualityInspectionMapper.class);
        var items = session.getMapper(QualityInspectionItemMapper.class);
        var defects = session.getMapper(NonconformingProductMapper.class);
        var trace = session.getMapper(InspectionTraceMapper.class);
        var history = new InspectionRoundHistory(session.getMapper(QualityInspectionRoundMapper.class), mock(JwtUtil.class));
        var quality = new QualityServiceImpl();
        ReflectionTestUtils.setField(quality, "inspectionMapper", headers);
        ReflectionTestUtils.setField(quality, "itemMapper", items);
        ReflectionTestUtils.setField(quality, "nonconformingMapper", defects);
        ReflectionTestUtils.setField(quality, "history", history);
        ReflectionTestUtils.setField(quality, "mesWorkflowService", mock(MesWorkflowService.class));
        var samples = new QualitySampleEvidenceService(session.getMapper(QualitySampleEvidenceMapper.class));

        var sheet = inspection("QI-SCRATCH");
        tx.executeWithoutResult(s -> quality.insertInspection(sheet));
        long id = sheet.getInspectionId();
        tx.executeWithoutResult(s -> quality.saveItems(id, List.of(item("3.8"))));
        tx.executeWithoutResult(s -> quality.failInspection(id, "色差", "色差偏高", BigDecimal.ONE, "GENERAL", "初判", "qc1"));
        long defect = defects.listByInspectionId(id).get(0).getNonconformingId();
        long originalItem = items.listByInspectionId(id).get(0).getInspectionItemId();
        long binding = tx.execute(s -> samples.bind(defect, 5, new DefectSampleRequest(key(), originalItem, null, null, "仪表记录 #20")));
        tx.executeWithoutResult(s -> quality.requireRecheck(id, "重新测量", "qc1"));
        tx.executeWithoutResult(s -> quality.saveItems(id, List.of(item("1.9"))));
        tx.executeWithoutResult(s -> quality.recheckPass(id, "复核通过", "qc2"));
        var events = trace.traceEvents(id);
        assertEquals(List.of("OPENED","INITIAL_ITEMS","INITIAL_DECISION","RECHECK_REQUESTED","ITEMS_SAVED","RECHECK_PASSED"),
                events.stream().map(r -> r.get("event_type")).toList());
        assertEquals("QUALIFIED", events.get(5).get("inspection_result"));
        assertEquals(List.of("3.8","3.8","3.8","1.9","1.9"),
                trace.traceEventItems(id).stream().map(r -> r.get("measured_value")).toList());
        assertEquals("qc2", jdbc.queryForObject("SELECT operator_name FROM quality_inspection_round WHERE event_type='RECHECK_PASSED'", String.class));
        assertNotEquals(originalItem, items.listByInspectionId(id).get(0).getInspectionItemId());

        var morning = LocalDateTime.of(2024,1,2,9,0);
        var reading = new SampleMeasurementRequest(key(), binding, null, 13L, "色差", "ΔE",
                "3.8", morning, "1.9", morning.plusMinutes(8), "仪表记录 #20");
        long readingId = tx.execute(s -> samples.record(defect, 5, reading));
        assertEquals(readingId, (long) tx.execute(s -> samples.record(defect, 5, reading)));
        assertEquals("UNIT-01", trace.traceSampleBindings(id).get(0).get("unit_code"));
        var measured = trace.traceSampleMeasurements(id);
        assertEquals(1, measured.size());
        assertEquals("Calibration", measured.get(0).get("step_name"));
        assertEquals(0, new BigDecimal("3.8").compareTo((BigDecimal) measured.get(0).get("before_value")));
        assertEquals(0, new BigDecimal("1.9").compareTo((BigDecimal) measured.get(0).get("after_value")));
        assertEquals(1, ((List<?>) samples.detail(defect).get("measurements")).size());

        var legacy = inspection("QI-LEGACY-SCRATCH");
        var prior = item("3.1");
        tx.executeWithoutResult(s -> {
            headers.insertInspection(legacy);
            prior.setInspectionId(legacy.getInspectionId());
            items.insert(prior);
        });
        long legacyId = legacy.getInspectionId();
        tx.executeWithoutResult(s -> quality.saveItems(legacyId, List.of(item("2.7"))));
        assertEquals(List.of("PRIOR_STATE","ITEMS_SAVED"), trace.traceEvents(legacyId).stream().map(row -> row.get("event_type")).toList());
        assertEquals(List.of("3.1","2.7"), trace.traceEventItems(legacyId).stream().map(row -> row.get("measured_value")).toList());
        assertTrue(trace.traceSampleMeasurements(legacyId).isEmpty());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE quality_inspection_round SET remark='overwrite' WHERE inspection_id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM quality_inspection_round_item"));
        jdbc.execute("CREATE TRIGGER fail_test_snapshot BEFORE INSERT ON quality_inspection_round FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced snapshot failure'");
        assertThrows(DataAccessException.class, () -> tx.executeWithoutResult(s -> quality.saveItems(legacyId, List.of(item("0.1")))));
        assertEquals("2.7", items.listByInspectionId(legacyId).get(0).getMeasuredValue());
        assertEquals(2, trace.traceEvents(legacyId).size());
    }

    private static QualityInspection inspection(String no) {
        var q = new QualityInspection();
        q.setInspectionNo(no);
        q.setWorkOrderId(41L);
        q.setMaterialId(3L);
        q.setBatchNo("SCRATCH");
        q.setInspectionType("FINAL");
        q.setInspectionCategory("FINISHED_PRODUCT");
        q.setInspectionStatus("PENDING");
        q.setInspectionResult("PENDING");
        q.setSampleQuantity(BigDecimal.ONE);
        q.setQualifiedQuantity(BigDecimal.ZERO);
        q.setUnqualifiedQuantity(BigDecimal.ZERO);
        q.setInspectorId(5L);
        q.setInspectedAt(LocalDateTime.now());
        q.setCreatedAt(q.getInspectedAt());
        q.setUpdatedAt(q.getInspectedAt());
        return q;
    }

    private static QualityInspectionItem item(String value) {
        var i = new QualityInspectionItem();
        i.setItemCode("UNIT-01");
        i.setItemName("SCRATCH-UNIT-01");
        i.setStandardValue("≤2");
        i.setMeasuredValue(value);
        i.setUnit("ΔE");
        i.setResult(new BigDecimal(value).compareTo(BigDecimal.valueOf(2)) <= 0 ? "PASSED" : "FAILED");
        i.setSortOrder(1);
        i.setCreatedAt(LocalDateTime.now());
        i.setUpdatedAt(i.getCreatedAt());
        return i;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
