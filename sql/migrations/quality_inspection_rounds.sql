-- Prospective snapshots. Existing inspections acquire a PRIOR_STATE only on their next write.
CREATE TABLE IF NOT EXISTS quality_inspection_round (
    round_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    inspection_id BIGINT UNSIGNED NOT NULL,
    inspection_no VARCHAR(50) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operator_name TEXT NULL,
    operator_source VARCHAR(20) NULL,
    inspector_id BIGINT UNSIGNED NULL,
    inspected_at DATETIME NULL,
    source_updated_at DATETIME NULL,
    inspection_type VARCHAR(30) NULL,
    inspection_status VARCHAR(30) NULL,
    inspection_result VARCHAR(20) NULL,
    sample_quantity DECIMAL(18,4) NULL,
    qualified_quantity DECIMAL(18,4) NULL,
    unqualified_quantity DECIMAL(18,4) NULL,
    remark TEXT NULL,
    KEY idx_inspection_round (inspection_id, round_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quality_inspection_round_item (
    round_item_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    round_id BIGINT UNSIGNED NOT NULL,
    source_item_id BIGINT NOT NULL,
    source_updated_at DATETIME NULL,
    item_code VARCHAR(30) NULL,
    item_name VARCHAR(100) NULL,
    standard_value TEXT NULL,
    measured_value TEXT NULL,
    unit VARCHAR(20) NULL,
    result VARCHAR(20) NULL,
    defect_level VARCHAR(20) NULL,
    sort_order INT NULL,
    remark TEXT NULL,
    KEY idx_round_item (round_id, sort_order),
    CONSTRAINT fk_round_item_history FOREIGN KEY (round_id) REFERENCES quality_inspection_round (round_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TRIGGER IF EXISTS quality_inspection_round_no_update;
CREATE TRIGGER quality_inspection_round_no_update BEFORE UPDATE ON quality_inspection_round
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Inspection history is append-only';
DROP TRIGGER IF EXISTS quality_inspection_round_no_delete;
CREATE TRIGGER quality_inspection_round_no_delete BEFORE DELETE ON quality_inspection_round
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Inspection history is append-only';
DROP TRIGGER IF EXISTS quality_inspection_round_item_no_update;
CREATE TRIGGER quality_inspection_round_item_no_update BEFORE UPDATE ON quality_inspection_round_item
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Inspection history is append-only';
DROP TRIGGER IF EXISTS quality_inspection_round_item_no_delete;
CREATE TRIGGER quality_inspection_round_item_no_delete BEFORE DELETE ON quality_inspection_round_item
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Inspection history is append-only';
