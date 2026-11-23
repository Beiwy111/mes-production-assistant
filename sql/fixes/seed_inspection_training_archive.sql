-- Optional local training history. These rows do not create active tasks, change
-- production totals, or identify the individual units sampled by an inspection.
CREATE TABLE IF NOT EXISTS assistant_training_work_history (
  record_no VARCHAR(64) PRIMARY KEY,
  record_kind VARCHAR(16) NOT NULL,
  work_order_no VARCHAR(64) NOT NULL,
  step_name VARCHAR(80) NOT NULL,
  operator_name VARCHAR(80) NOT NULL,
  recorded_on DATE NOT NULL,
  source_file VARCHAR(160) NOT NULL,
  KEY idx_training_work (work_order_no, recorded_on)
);

-- Resolve the installation's business keys, never reuse the old numeric user/step IDs.
INSERT INTO assistant_training_work_history
  (record_no,record_kind,work_order_no,step_name,operator_name,recorded_on,source_file)
SELECT src.record_no,src.kind,w.work_order_no,s.step_name,u.real_name,'2026-07-06',src.source_file
FROM (
  SELECT 'DT202607010' record_no,'DISPATCH' kind,'主板装配' step_name,'吴操作' actor,'sql/migrations/restore_board_and_qc_demo.sql' source_file
  UNION ALL SELECT 'DT202607011','DISPATCH','面板贴附','王操作','sql/migrations/restore_board_and_qc_demo.sql'
  UNION ALL SELECT 'DT202607012','DISPATCH','整机组装','陈操作','sql/migrations/restore_board_and_qc_demo.sql'
  UNION ALL SELECT 'WR202607004','REPORT','面板贴附','王操作','sql/init/seed_data.sql'
) src
JOIN work_order w ON w.work_order_no='WO202607002'
JOIN process_step s ON s.route_id=w.route_id AND s.step_name=src.step_name
JOIN `user` u ON u.real_name=src.actor
JOIN role r ON r.role_id=u.role_id AND r.role_code='OPERATOR'
WHERE EXISTS (SELECT 1 FROM quality_inspection q WHERE q.work_order_id=w.work_order_id
              AND q.inspection_no='QI202607021' AND q.batch_no='BATCH-WO202607002-3201')
  AND NOT EXISTS (SELECT 1 FROM assistant_training_work_history old WHERE old.record_no=src.record_no);
