-- 补全售后工单 AS202607901~905 的一键追溯留痕（仓储发货 / 成品入库 / 成品质检 / 生产 / 领料 / 物料质检 / 供应商采购）
USE display_manufacturing;
SET NAMES utf8mb4;

-- ── 1) 关联案例与成品质检单、发货单 ─────────────────────────────
UPDATE after_sales_case SET quality_inspection_id = 43, delivery_id = 10
WHERE case_no = 'AS202607902' AND (quality_inspection_id IS NULL OR delivery_id IS NULL);

UPDATE after_sales_case SET quality_inspection_id = 24, delivery_id = 8, batch_no = 'BATCH-WO202607002-4057'
WHERE case_no = 'AS202607903';

UPDATE after_sales_case SET delivery_id = 9
WHERE case_no = 'AS202607901' AND delivery_id IS NULL;

UPDATE after_sales_case SET quality_inspection_id = 45, delivery_id = 12
WHERE case_no = 'AS202607905' AND (quality_inspection_id IS NULL OR delivery_id IS NULL);

-- 904：订单 45 补发货记录
INSERT INTO delivery_order
(delivery_no, order_id, work_order_id, customer_name, material_id, batch_no,
 delivery_quantity, delivery_date, logistics_company, logistics_no,
 receiver_name, receiver_phone, receiver_address, delivery_status, shipped_by, remark, created_at, updated_at)
SELECT 'DO202607121212004-A1', 45, 32, co.customer_name, 9, 'BATCH-WO202607002-8840',
       150.0000, '2026-07-11', '顺丰速运', 'SF2026071104',
       IFNULL(NULLIF(co.customer_contact, ''), '陈经理'), IFNULL(NULLIF(co.customer_phone, ''), '13900002222'),
       '上海市浦东新区联创电子产业园', 'SHIPPED', 7, '上海优视订单追溯补录发货', NOW(), NOW()
FROM customer_order co
WHERE co.order_id = 45
  AND NOT EXISTS (SELECT 1 FROM delivery_order WHERE delivery_no = 'DO202607121212004-A1');

UPDATE after_sales_case a
JOIN delivery_order d ON d.delivery_no = 'DO202607121212004-A1'
SET a.delivery_id = d.delivery_id
WHERE a.case_no = 'AS202607904';

-- ── 2) 采购入库（与领料批次一致，支撑供应商采购环）────────────────
INSERT INTO inventory_transaction
(transaction_no, inventory_id, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_purchase_order_id, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-LCD-202607', 1, 1, 'PURCHASE_IN', 500.0000, 'WH-01', 'A-01-01',
       'BATCH-LCD-202607', 1, NULL, 7, '2026-06-25 10:00:00', '追溯补录LCD面板采购入库', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-LCD-202607');

INSERT INTO inventory_transaction
(transaction_no, inventory_id, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_purchase_order_id, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-BL-202607', 2, 2, 'PURCHASE_IN', 500.0000, 'WH-01', 'A-02-01',
       'BATCH-BL-202607', 2, NULL, 7, '2026-06-27 14:00:00', '追溯补录背光模组采购入库', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-BL-202607');

INSERT INTO inventory_transaction
(transaction_no, inventory_id, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_purchase_order_id, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-IC-202605', 3, 3, 'PURCHASE_IN', 800.0000, 'WH-01', 'A-03-01',
       'BATCH-IC-202605', 3, NULL, 7, '2026-06-20 09:00:00', '追溯补录驱动IC采购入库', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-IC-202605');

-- ── 3) 成品入库 ───────────────────────────────────────────────────
INSERT INTO inventory_transaction
(transaction_no, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-IN-WO33', 8, 'PRODUCT_IN', 78.0000, 'WH-02', 'B-02-02',
       'BATCH-WO202607003-3201', 33, 7, '2026-07-09 16:30:00', '工单WO202607003成品入库', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-IN-WO33');

INSERT INTO inventory_transaction
(transaction_no, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-IN-WO35', 7, 'PRODUCT_IN', 95.0000, 'WH-02', 'B-01-03',
       'BATCH-WO202607005-1506', 35, 7, '2026-07-08 15:00:00', '工单WO202607005成品入库', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-IN-WO35');

-- ── 4) 生产领料（WO31 / WO33 / WO35）──────────────────────────────
INSERT INTO inventory_transaction
(transaction_no, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-OUT-WO31-LCD', 1, 'MATERIAL_OUT', 180.0000, 'WH-01', 'A-01-01',
       'BATCH-LCD-202607', 31, 7, '2026-07-04 08:00:00', '工单WO202607001领LCD面板', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-OUT-WO31-LCD');

INSERT INTO inventory_transaction
(transaction_no, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-OUT-WO31-BL', 2, 'MATERIAL_OUT', 90.0000, 'WH-01', 'A-02-01',
       'BATCH-BL-202607', 31, 7, '2026-07-04 08:30:00', '工单WO202607001领背光模组', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-OUT-WO31-BL');

INSERT INTO inventory_transaction
(transaction_no, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-OUT-WO33-LCD', 1, 'MATERIAL_OUT', 120.0000, 'WH-01', 'A-01-01',
       'BATCH-LCD-202607', 33, 7, '2026-07-07 08:00:00', '工单WO202607003领LCD面板', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-OUT-WO33-LCD');

INSERT INTO inventory_transaction
(transaction_no, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-OUT-WO33-BL', 2, 'MATERIAL_OUT', 60.0000, 'WH-01', 'A-02-01',
       'BATCH-BL-202607', 33, 7, '2026-07-07 08:30:00', '工单WO202607003领背光模组', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-OUT-WO33-BL');

INSERT INTO inventory_transaction
(transaction_no, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-OUT-WO35-LCD', 1, 'MATERIAL_OUT', 150.0000, 'WH-01', 'A-01-01',
       'BATCH-LCD-202607', 35, 7, '2026-07-06 08:00:00', '工单WO202607005领LCD面板', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-OUT-WO35-LCD');

INSERT INTO inventory_transaction
(transaction_no, material_id, transaction_type, quantity, warehouse_code, location_code,
 batch_no, related_work_order_id, handled_by, handled_at, remark, created_at)
SELECT 'IT-TRACE-OUT-WO35-BL', 2, 'MATERIAL_OUT', 75.0000, 'WH-01', 'A-02-01',
       'BATCH-BL-202607', 35, 7, '2026-07-06 08:30:00', '工单WO202607005领背光模组', NOW()
WHERE NOT EXISTS (SELECT 1 FROM inventory_transaction WHERE transaction_no = 'IT-TRACE-OUT-WO35-BL');

-- ── 5) 物料来料质检（驱动IC，支撑 WO32 领料批次）──────────────────
INSERT INTO quality_inspection
(inspection_no, work_order_id, material_id, batch_no, inspection_type, inspection_category,
 sample_quantity, qualified_quantity, unqualified_quantity, inspection_result, inspector_id, inspected_at, remark, created_at, updated_at)
SELECT 'QI-TRACE-IC-202605', NULL, 3, 'BATCH-IC-202605', 'INCOMING', 'RAW_MATERIAL',
       100.0000, 98.0000, 2.0000, 'QUALIFIED', 5, '2026-06-19 11:00:00', '驱动IC来料检验', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM quality_inspection WHERE inspection_no = 'QI-TRACE-IC-202605');
