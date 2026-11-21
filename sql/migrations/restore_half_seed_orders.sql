-- Restore ~half of seed customer orders / plans / work orders.
-- Keeps existing order CO20260719001 (order_id=82). Does not re-seed routes/equipment.
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 3 of 5 seed orders (APPROVED x2 + PENDING x1)
INSERT INTO customer_order (
  order_id, order_no, customer_name, customer_contact, customer_phone,
  order_date, required_delivery_date, order_amount, audit_status,
  audit_user_id, audit_at, audit_opinion, remark, created_by, created_at, updated_at
) VALUES
(1, 'CO202607001', '深圳华创科技有限公司', '林经理', '13900001001',
 '2026-07-01', '2026-07-20', 136000.00, 'APPROVED',
 1, '2026-07-01 14:00:00', '订单审核通过', '商用显示器批量订单', 2, NOW(), NOW()),
(2, 'CO202607002', '北京星辰电竞俱乐部', '王总', '13900001002',
 '2026-07-02', '2026-07-25', 192000.00, 'APPROVED',
 1, '2026-07-02 10:30:00', '同意排产', '电竞显示器订单', 2, NOW(), NOW()),
(4, 'CO202607004', '广州教育设备有限公司', '黄主任', '13900001004',
 '2026-07-04', '2026-08-10', 68000.00, 'PENDING',
 NULL, NULL, NULL, '教育行业采购', 2, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  customer_name = VALUES(customer_name),
  order_amount = VALUES(order_amount),
  audit_status = VALUES(audit_status),
  remark = VALUES(remark),
  updated_at = NOW();

INSERT INTO customer_order_item (
  order_item_id, order_id, material_id, product_name, specification,
  quantity, unit, unit_price, line_amount, delivery_date, item_status, created_at, updated_at
) VALUES
(1, 1, 7, '15.6寸商用显示器', '1920x1080 商用款', 200.0000, '台', 680.0000, 136000.00, '2026-07-20', 'PRODUCING', NOW(), NOW()),
(2, 2, 8, '23.8寸电竞显示器', '2560x1440 144Hz', 150.0000, '台', 1280.0000, 192000.00, '2026-07-25', 'PRODUCING', NOW(), NOW()),
(4, 4, 7, '15.6寸商用显示器', '1920x1080 教育版', 100.0000, '台', 680.0000, 68000.00, '2026-08-10', 'PENDING', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  quantity = VALUES(quantity),
  item_status = VALUES(item_status),
  updated_at = NOW();

INSERT INTO production_plan (
  plan_id, plan_no, plan_name, source_order_id, planned_start_date, planned_end_date,
  priority, plan_status, planner_id, approved_by, approved_at, remark, created_at, updated_at
) VALUES
(1, 'PP202607001', '华创科技200台商用显示器生产计划', 1, '2026-07-05', '2026-07-18',
 'HIGH', 'RUNNING', 2, 1, '2026-07-02 15:00:00', '关联订单CO202607001', NOW(), NOW()),
(2, 'PP202607002', '星辰电竞150台电竞显示器生产计划', 2, '2026-07-06', '2026-07-22',
 'HIGH', 'RUNNING', 2, 1, '2026-07-03 10:00:00', '关联订单CO202607002', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  plan_name = VALUES(plan_name),
  plan_status = VALUES(plan_status),
  updated_at = NOW();

INSERT INTO production_plan_item (
  plan_item_id, plan_id, order_item_id, material_id, planned_quantity, completed_quantity,
  planned_start_date, planned_end_date, item_status, created_at, updated_at
) VALUES
(1, 1, 1, 7, 200.0000, 85.0000, '2026-07-05', '2026-07-18', 'RUNNING', NOW(), NOW()),
(2, 2, 2, 8, 150.0000, 42.0000, '2026-07-06', '2026-07-22', 'RUNNING', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  planned_quantity = VALUES(planned_quantity),
  completed_quantity = VALUES(completed_quantity),
  item_status = VALUES(item_status),
  updated_at = NOW();

INSERT INTO work_order (
  work_order_id, work_order_no, plan_id, plan_item_id, material_id, route_id,
  planned_quantity, completed_quantity, qualified_quantity, unqualified_quantity,
  planned_start_time, planned_end_time, actual_start_time, actual_end_time,
  status, created_by, released_by, released_at, remark, created_at, updated_at
) VALUES
(1, 'WO202607001', 1, 1, 7, 1,
 200.0000, 65.0000, 62.0000, 3.0000,
 '2026-07-05 08:00:00', '2026-07-18 18:00:00', '2026-07-05 08:30:00', NULL,
 'RUNNING', 2, 1, '2026-07-04 17:00:00', '华创订单工单', NOW(), NOW()),
(2, 'WO202607002', 2, 2, 8, 2,
 150.0000, 42.0000, 40.0000, 2.0000,
 '2026-07-06 08:00:00', '2026-07-22 18:00:00', '2026-07-06 09:00:00', NULL,
 'RUNNING', 2, 1, '2026-07-05 16:00:00', '电竞显示器工单', NOW(), NOW())
ON DUPLICATE KEY UPDATE
  status = VALUES(status),
  completed_quantity = VALUES(completed_quantity),
  updated_at = NOW();

SET FOREIGN_KEY_CHECKS = 1;

-- Keep auto_increment above existing + restored ids
ALTER TABLE customer_order AUTO_INCREMENT = 100;
ALTER TABLE customer_order_item AUTO_INCREMENT = 100;
ALTER TABLE production_plan AUTO_INCREMENT = 20;
ALTER TABLE production_plan_item AUTO_INCREMENT = 20;
ALTER TABLE work_order AUTO_INCREMENT = 20;

SELECT 'orders' AS t, COUNT(*) AS c FROM customer_order
UNION ALL SELECT 'items', COUNT(*) FROM customer_order_item
UNION ALL SELECT 'plans', COUNT(*) FROM production_plan
UNION ALL SELECT 'plan_items', COUNT(*) FROM production_plan_item
UNION ALL SELECT 'work_orders', COUNT(*) FROM work_order;

SELECT order_id, order_no, customer_name, audit_status, order_amount
FROM customer_order
ORDER BY order_id;
