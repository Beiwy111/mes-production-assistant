package com.upc.computer.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 释放被派工/在制工单占用的操作员与设备，恢复为空闲状态。
 */
@Service
public class MesResourceOccupancyService {

    private static final List<String> ACTIVE_DISPATCH_STATUS = List.of(
            "ASSIGNED", "ACCEPTED", "PRODUCING", "RUNNING", "QC_PENDING");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Transactional
    public Map<String, Object> releaseAllOccupancy() {
        Map<String, Object> before = snapshotMetrics();

        int dispatchUpdated = jdbcTemplate.update("""
                UPDATE dispatch_task
                SET status = 'COMPLETED',
                    accepted_at = COALESCE(accepted_at, NOW()),
                    completed_quantity = GREATEST(COALESCE(completed_quantity, 0), COALESCE(assigned_quantity, 0)),
                    updated_at = NOW()
                WHERE status IN ('ASSIGNED', 'ACCEPTED', 'PRODUCING', 'RUNNING', 'QC_PENDING')
                """);

        int equipmentUpdated = jdbcTemplate.update("""
                UPDATE equipment
                SET status = 'IDLE', updated_at = NOW()
                WHERE status = 'RUNNING'
                """);

        int stationUpdated = jdbcTemplate.update("""
                UPDATE prod_line_station
                SET work_order_id = NULL,
                    work_order_no = NULL,
                    station_status = 'IDLE',
                    alarm_flag = 0,
                    updated_at = NOW()
                WHERE work_order_id IS NOT NULL OR station_status <> 'IDLE'
                """);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("released", true);
        result.put("dispatchCompleted", dispatchUpdated);
        result.put("equipmentSetIdle", equipmentUpdated);
        result.put("stationsReleased", stationUpdated);
        result.put("before", before);
        result.put("after", snapshotMetrics());
        return result;
    }

    private Map<String, Object> snapshotMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("activeDispatches", countDispatchActive());
        metrics.put("runningEquipment", countRunningEquipment());
        metrics.put("boundStations", countBoundStations());
        return metrics;
    }

    private long countDispatchActive() {
        String in = String.join(",", ACTIVE_DISPATCH_STATUS.stream().map(s -> "'" + s + "'").toList());
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dispatch_task WHERE status IN (" + in + ")", Long.class);
        return n != null ? n : 0L;
    }

    private long countRunningEquipment() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM equipment WHERE status = 'RUNNING'", Long.class);
        return n != null ? n : 0L;
    }

    private long countBoundStations() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prod_line_station WHERE work_order_id IS NOT NULL", Long.class);
        return n != null ? n : 0L;
    }
}
