-- Additive migration only. Execute against the existing MES database; no CREATE DATABASE / DROP / user changes.
CREATE TABLE IF NOT EXISTS mes_face_binding (
  user_id BIGINT NOT NULL PRIMARY KEY,
  subject_id VARCHAR(96) NULL UNIQUE,
  image_id VARCHAR(64) NULL,
  pending_subject VARCHAR(96) NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'NONE',
  revision BIGINT NOT NULL DEFAULT 0,
  consent_version VARCHAR(32) NULL,
  consent_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  INDEX idx_face_state (state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Human consent and CompreFace binding metadata only';

CREATE TABLE IF NOT EXISTS mes_face_cleanup (
  cleanup_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  subject_id VARCHAR(96) NOT NULL UNIQUE,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_face_cleanup_user (user_id),
  INDEX idx_face_cleanup_due (next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable scoped deletion retries; no photos or vectors';

CREATE TABLE IF NOT EXISTS mes_face_audit (
  audit_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_user_id BIGINT NULL,
  target_user_id BIGINT NULL,
  event_type VARCHAR(40) NOT NULL,
  result_code VARCHAR(40) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_face_audit_user (target_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Biometric operation metadata, excluding sensitive payloads';
