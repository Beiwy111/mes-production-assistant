-- MySQL 8. Append-only quality evidence. No historical observations are generated.
CREATE TABLE IF NOT EXISTS quality_defect_sample (
  binding_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  nonconforming_id BIGINT UNSIGNED NOT NULL,
  inspection_id BIGINT UNSIGNED NOT NULL,
  inspection_item_id BIGINT DEFAULT NULL COMMENT 'Snapshot pointer; inspection items can be replaced on re-save',
  unit_code VARCHAR(120) NOT NULL,
  sample_label VARCHAR(150) NOT NULL,
  sample_origin VARCHAR(20) NOT NULL,
  source_ref VARCHAR(255) NOT NULL,
  recorded_by BIGINT UNSIGNED NOT NULL,
  recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  request_id CHAR(36) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  UNIQUE KEY uq_defect_sample_request (nonconforming_id, request_id),
  UNIQUE KEY uq_defect_sample_identity (nonconforming_id, sample_origin, unit_code),
  KEY idx_defect_sample_inspection (inspection_id, nonconforming_id),
  CHECK ((sample_origin='MES_ITEM' AND inspection_item_id IS NOT NULL)
      OR (sample_origin='MANUAL_SOURCE' AND inspection_item_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quality_sample_measurement (
  measurement_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  binding_id BIGINT UNSIGNED NOT NULL,
  prior_measurement_id BIGINT UNSIGNED DEFAULT NULL,
  nonconforming_id BIGINT UNSIGNED NOT NULL,
  inspection_id BIGINT UNSIGNED NOT NULL,
  inspection_item_id BIGINT DEFAULT NULL,
  unit_code VARCHAR(120) NOT NULL,
  sample_label VARCHAR(150) NOT NULL,
  work_order_id BIGINT UNSIGNED NOT NULL,
  step_id BIGINT UNSIGNED NOT NULL,
  step_name VARCHAR(150) NOT NULL,
  metric_name VARCHAR(100) NOT NULL,
  before_value DECIMAL(24,8) DEFAULT NULL,
  after_value DECIMAL(24,8) DEFAULT NULL,
  unit VARCHAR(24) NOT NULL,
  before_measured_at DATETIME DEFAULT NULL,
  after_measured_at DATETIME DEFAULT NULL,
  source_ref VARCHAR(255) NOT NULL,
  recorded_by BIGINT UNSIGNED NOT NULL,
  recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  request_id CHAR(36) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  UNIQUE KEY uq_sample_measurement_request (nonconforming_id, request_id),
  UNIQUE KEY uq_sample_measurement_completion (prior_measurement_id),
  KEY idx_sample_measurement_inspection (inspection_id, nonconforming_id),
  FOREIGN KEY (binding_id) REFERENCES quality_defect_sample(binding_id),
  FOREIGN KEY (prior_measurement_id) REFERENCES quality_sample_measurement(measurement_id),
  CHECK (before_value IS NOT NULL OR after_value IS NOT NULL),
  CHECK ((before_value IS NULL) = (before_measured_at IS NULL)),
  CHECK ((after_value IS NULL) = (after_measured_at IS NULL)),
  CHECK (after_measured_at IS NULL OR before_measured_at IS NULL OR after_measured_at >= before_measured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP TRIGGER IF EXISTS quality_defect_sample_no_update;
CREATE TRIGGER quality_defect_sample_no_update BEFORE UPDATE ON quality_defect_sample
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quality evidence is append-only';
DROP TRIGGER IF EXISTS quality_defect_sample_no_delete;
CREATE TRIGGER quality_defect_sample_no_delete BEFORE DELETE ON quality_defect_sample
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quality evidence is append-only';
DROP TRIGGER IF EXISTS quality_sample_measurement_no_update;
CREATE TRIGGER quality_sample_measurement_no_update BEFORE UPDATE ON quality_sample_measurement
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quality evidence is append-only';
DROP TRIGGER IF EXISTS quality_sample_measurement_no_delete;
CREATE TRIGGER quality_sample_measurement_no_delete BEFORE DELETE ON quality_sample_measurement
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quality evidence is append-only';
