package com.upc.computer.mapper;

import com.upc.computer.entity.QualityInspection;
import org.apache.ibatis.annotations.*;

import java.util.Map;

@Mapper
public interface QualityInspectionRoundMapper {
    @Select("SELECT * FROM quality_inspection WHERE inspection_id=#{inspectionId} FOR UPDATE")
    QualityInspection lockInspection(long inspectionId);

    // Current reads also see a previous writer when this transaction acquired its read view earlier.
    @Select("""
            SELECT event_type FROM quality_inspection_round
            WHERE inspection_id=#{id} AND event_type NOT IN ('ITEMS_RESET','ITEMS_SAVED','SAMPLING_UPDATED')
              AND (#{includeInitialItems} OR event_type <> 'INITIAL_ITEMS')
            ORDER BY round_id DESC LIMIT 1 FOR UPDATE
            """)
    String lastStage(@Param("id") long id, @Param("includeInitialItems") boolean includeInitialItems);

    @Insert("""
            INSERT INTO quality_inspection_round (inspection_id, inspection_no, event_type, operator_name,
                operator_source, inspector_id, inspected_at, source_updated_at, inspection_type, inspection_status,
                inspection_result, sample_quantity, qualified_quantity, unqualified_quantity, remark)
            SELECT inspection_id, inspection_no, #{event}, #{operator}, #{operatorSource}, inspector_id,
                inspected_at, updated_at, inspection_type, inspection_status, inspection_result, sample_quantity,
                qualified_quantity, unqualified_quantity, remark FROM quality_inspection WHERE inspection_id=#{id}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "roundId")
    int insertSnapshot(Map<String, Object> event);

    @Insert("""
            INSERT INTO quality_inspection_round_item (round_id, source_item_id, source_updated_at,
                item_code, item_name, standard_value, measured_value, unit, result, defect_level, sort_order, remark)
            SELECT #{roundId}, inspection_item_id, updated_at, item_code, item_name, standard_value, measured_value,
                unit, result, defect_level, sort_order, remark FROM quality_inspection_item
            WHERE inspection_id=#{id} ORDER BY sort_order, inspection_item_id
            """)
    void copyItems(@Param("id") long id, @Param("roundId") long roundId);
}
