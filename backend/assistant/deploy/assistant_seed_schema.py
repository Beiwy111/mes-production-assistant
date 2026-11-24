"""Reviewed additive DDL for assistant queries and login on the repository's original MES seed."""
from pathlib import Path
import re


MIGRATIONS = Path(__file__).resolve().parents[3] / 'sql/migrations'
TABLES = {
    'after_sales_task': 'aftersale_workflow_v2.sql',
    'after_sales_closure': 'aftersale_workflow_v2.sql',
    'material_batch': 'warehouse_barcode_schema.sql',
    'inventory_barcode': 'warehouse_barcode_schema.sql',
    'production_plan_schedule': 'upgrade_planner_module.sql',
    'quality_inspection_item': 'quality_module_v2.sql',
}
# Same column types/defaults as customer_portal, aftersale_module_v1, upgrade_planner_module,
# quality_module_v2, seed_eight_finished_products and fixes/bootstrap_modules_workflow.
COLUMNS = {
    'user.customer_name': 'varchar(150) DEFAULT NULL',
    'user.shipping_address': 'varchar(255) DEFAULT NULL',
    'after_sales_case.quality_inspection_id': 'bigint DEFAULT NULL',
    'after_sales_case.resolved_at': 'datetime DEFAULT NULL',
    'production_plan.version_no': "varchar(20) DEFAULT 'V1'",
    'production_plan.parent_plan_no': 'varchar(50) DEFAULT NULL',
    'quality_inspection.inspection_category': "varchar(30) NOT NULL DEFAULT 'SEMI_FINISHED'",
    'quality_inspection.inspection_status': "varchar(30) NOT NULL DEFAULT 'PENDING'",
    'material.image_url': 'varchar(500) DEFAULT NULL',
    'material.product_summary': 'varchar(500) DEFAULT NULL',
    'material.ports': 'varchar(200) DEFAULT NULL',
    'material.sort_order': 'int NOT NULL DEFAULT 0',
    'material.supplier_id': 'bigint DEFAULT NULL',
}


def statements(missing):
    """Return only reviewed single-object DDL. Parse all table definitions before the first change."""
    result = []
    for name, definition in COLUMNS.items():
        if name in missing:
            table, column = name.split('.')
            result.append(f'ALTER TABLE `{table}` ADD COLUMN `{column}` {definition}')
    for name, file in TABLES.items():
        if name in missing:
            content = (MIGRATIONS / file).read_text(encoding='utf-8-sig')
            pattern = rf'^CREATE TABLE IF NOT EXISTS `?{name}`?\s*\([^;]*?\)\s*ENGINE\s*=\s*InnoDB[^;]*;'
            matches = re.findall(pattern, content, re.MULTILINE | re.IGNORECASE)
            if len(matches) != 1:
                raise ValueError(f'Migration has no unique table definition for {name}.')
            result.append(matches[0])
    return result
