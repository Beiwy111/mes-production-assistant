-- Additive assistant state only. No changes to existing business tables.
CREATE TABLE IF NOT EXISTS assistant_order_draft_v2 (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  session_id VARCHAR(80) NOT NULL,
  owner_role VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  fields_json LONGTEXT NOT NULL,
  result_json LONGTEXT NOT NULL,
  product_fingerprint VARCHAR(64) NOT NULL DEFAULT '',
  expires_at DATETIME NOT NULL,
  last_request VARCHAR(80) NOT NULL DEFAULT '',
  last_hash VARCHAR(64) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_assistant_order_session (owner_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
