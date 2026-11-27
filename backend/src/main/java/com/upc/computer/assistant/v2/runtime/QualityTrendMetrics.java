package com.upc.computer.assistant.v2.runtime;

import java.util.List;
import java.util.Map;

/** Daily groups use the same inspection records and quality checks as the overview. */
public final class QualityTrendMetrics {
    private QualityTrendMetrics() {}
    public static List<Map<String,Object>> summarize(List<Map<String,Object>> rows,boolean complete) {
        return QualityOverviewStatistics.trend(rows,complete);
    }
}
