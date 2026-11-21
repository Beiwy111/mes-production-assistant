-- 补充生产主管大屏 + 质检员首页演示数据（可重复执行）
-- 依赖：已有 WO202607001/002、equipment、process_step、绑定操作员
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ── 1. 设备：关键车间置为运行中，其余空闲 ─────────────────────────────
UPDATE equipment SET status = 'RUNNING', updated_at = NOW()
WHERE equipment_code IN (
  'EQ-MB-01', 'EQ-MB-02', 'EQ-PB-01', 'EQ-IF-01',
  'EQ-DISP-01', 'EQ-001', 'EQ-SH-01', 'EQ-003', 'EQ-BR-01'
);

UPDATE equipment SET status = 'IDLE', updated_at = NOW()
WHERE equipment_code IN (
  'EQ-MB-03', 'EQ-PB-02', 'EQ-IF-02', 'EQ-DISP-02', 'EQ-DISP-03',
  'EQ-002', 'EQ-010', 'EQ-SH-02', 'EQ-007', 'EQ-012', 'EQ-BR-02',
  'EQ-004', 'EQ-005', 'EQ-006', 'EQ-008', 'EQ-009', 'EQ-011'
);

-- ── 2. 派工任务：必须与 OperatorWorkshopCatalog 绑定一致 ───────────────
-- WO1 路线1 step: 12主板/51电源板/52接口板/11显示屏/1贴附/53外壳/2组装/54支架
-- 吴操作=主板二车间, 白操作=主板三车间（不可派到电源板/接口板）

DELETE FROM dispatch_task WHERE dispatch_no LIKE 'DT202607%';

INSERT INTO dispatch_task (
  dispatch_no, work_order_id, step_id, operator_id, equipment_id,
  assigned_quantity, accepted_quantity, completed_quantity,
  assigned_by, assigned_at, accepted_at, status, remark, created_at, updated_at
) VALUES
('DT202607001', 1, 12, 64, 18, 200, 200, 180, 4, '2026-07-05 08:00:00', '2026-07-05 08:15:00', 'RUNNING', '主板装配一车间', NOW(), NOW()),
('DT202607002', 1, 12, 67, 14, 200, 200,  90, 4, '2026-07-05 08:20:00', '2026-07-05 08:25:00', 'RUNNING', '主板装配二车间', NOW(), NOW()),
('DT202607003', 1, 12, 75, 15, 200, 200,  70, 4, '2026-07-05 08:25:00', '2026-07-05 08:30:00', 'RUNNING', '主板装配三车间', NOW(), NOW()),
('DT202607004', 1, 51, 76, 24, 200, 200, 165, 4, '2026-07-05 08:30:00', '2026-07-05 08:45:00', 'RUNNING', '电源板装配一车间', NOW(), NOW()),
('DT202607004B',1, 11, 63, 17, 200, 200, 140, 4, '2026-07-05 09:30:00', '2026-07-05 09:40:00', 'RUNNING', '显示屏加工一车间', NOW(), NOW()),
('DT202607005', 1,  1, 34,  1, 200, 200, 120, 4, '2026-07-05 10:00:00', '2026-07-05 10:10:00', 'RUNNING', '贴附一车间', NOW(), NOW()),
('DT202607006', 1, 52, 78, 26, 200, 200, 150, 4, '2026-07-05 09:00:00', '2026-07-05 09:10:00', 'RUNNING', '接口板装配一车间', NOW(), NOW()),
('DT202607006B',1, 53, 81, 28, 200, 180, 100, 4, '2026-07-05 14:00:00', '2026-07-05 14:10:00', 'RUNNING', '外壳装配一车间', NOW(), NOW()),
('DT202607007', 1,  2, 65,  3, 200, 150,  85, 4, '2026-07-06 08:00:00', '2026-07-06 08:20:00', 'RUNNING', '组装一车间', NOW(), NOW()),
('DT202607008', 1, 54, 83, 30, 200, 120,  65, 4, '2026-07-06 09:00:00', '2026-07-06 09:15:00', 'RUNNING', '支架底座装配一车间', NOW(), NOW()),
('DT202607010', 2, 14, 67, 14, 150, 150,  80, 4, '2026-07-06 09:00:00', '2026-07-06 09:10:00', 'RUNNING', '电竞-主板装配二车间', NOW(), NOW()),
('DT202607011', 2,  5, 34,  1, 150, 150,  55, 4, '2026-07-06 10:00:00', '2026-07-06 10:10:00', 'RUNNING', '电竞-贴附', NOW(), NOW()),
('DT202607012', 2,  6, 69,  7, 150, 120,  42, 4, '2026-07-06 14:00:00', '2026-07-06 14:05:00', 'RUNNING', '电竞-组装二车间', NOW(), NOW()),
('DT202607013', 1,  3, 80,  4,  65,  65,  45, 4, '2026-07-06 10:00:00', '2026-07-06 10:05:00', 'ACCEPTED', '老化测试', NOW(), NOW());

-- ── 3. 安灯：再补 2 条未关闭，大屏异常面板有内容 ─────────────────────
INSERT INTO andon_alarm (
  alarm_no, work_order_id, dispatch_id, equipment_id,
  alarm_type, alarm_level, alarm_description, alarm_status,
  reported_by, reported_at, created_at, updated_at
)
SELECT 'AL202607901', 1, d.dispatch_id, 3,
  'EQUIPMENT', 'IMPORTANT', '组装一车间节拍下降，疑似供料不稳', 'OPEN',
  69, NOW(), NOW(), NOW()
FROM dispatch_task d WHERE d.dispatch_no = 'DT202607007'
  AND NOT EXISTS (SELECT 1 FROM andon_alarm WHERE alarm_no = 'AL202607901');

INSERT INTO andon_alarm (
  alarm_no, work_order_id, dispatch_id, equipment_id,
  alarm_type, alarm_level, alarm_description, alarm_status,
  reported_by, reported_at, created_at, updated_at
)
SELECT 'AL202607902', 2, d.dispatch_id, 1,
  'QUALITY', 'GENERAL', '贴附工序外观不良率升高，待质检复核', 'RECEIVED',
  34, DATE_SUB(NOW(), INTERVAL 1 HOUR), NOW(), NOW()
FROM dispatch_task d WHERE d.dispatch_no = 'DT202607011'
  AND NOT EXISTS (SELECT 1 FROM andon_alarm WHERE alarm_no = 'AL202607902');

SET FOREIGN_KEY_CHECKS = 1;

-- ── 4. 额外待检：成品/过程待办（不依赖已删除的 WO003+） ──────────────
INSERT INTO quality_inspection
  (inspection_no, work_order_id, material_id, batch_no, inspection_type, inspection_category,
   sample_quantity, qualified_quantity, unqualified_quantity, inspection_result, inspection_status,
   inspector_id, inspected_at, remark, created_at, updated_at)
SELECT 'QI202607501', wo.work_order_id, wo.material_id, 'BATCH-WO202607001-FINAL-P1', 'FINAL', 'FINISHED_PRODUCT',
  12, 0, 0, 'PENDING', 'PENDING', 5, NOW(), '华创批次老化完成，待成品终检', NOW(), NOW()
FROM work_order wo WHERE wo.work_order_no = 'WO202607001'
  AND NOT EXISTS (SELECT 1 FROM quality_inspection WHERE inspection_no = 'QI202607501')
  AND NOT EXISTS (
    SELECT 1 FROM quality_inspection qi
    WHERE qi.work_order_id = wo.work_order_id
      AND qi.inspection_category = 'FINISHED_PRODUCT'
      AND qi.inspection_status = 'PASSED'
  );

INSERT INTO quality_inspection
  (inspection_no, work_order_id, material_id, batch_no, inspection_type, inspection_category,
   sample_quantity, qualified_quantity, unqualified_quantity, inspection_result, inspection_status,
   inspector_id, inspected_at, remark, created_at, updated_at)
SELECT 'QI202607502', wo.work_order_id, wo.material_id, 'BATCH-WO202607002-PROC', 'PROCESS', 'SEMI_FINISHED',
  20, 0, 0, 'PENDING', 'PENDING', 5, NOW(), '电竞批次整机组装后过程检待办', NOW(), NOW()
FROM work_order wo WHERE wo.work_order_no = 'WO202607002'
  AND NOT EXISTS (SELECT 1 FROM quality_inspection WHERE inspection_no = 'QI202607502');

SELECT 'equipment_running' t, COUNT(*) c FROM equipment WHERE status = 'RUNNING'
UNION ALL SELECT 'dispatch_active', COUNT(*) FROM dispatch_task WHERE status IN ('ASSIGNED','ACCEPTED','RUNNING')
UNION ALL SELECT 'andon_open', COUNT(*) FROM andon_alarm WHERE alarm_status <> 'CLOSED';
