# 从仓库基础库准备助手所需的 MySQL 结构

队友已有可用 MES 数据库时，直接运行仓库根目录的 `Initialize-Assistant.cmd`。它先列出所选本机数据库缺少的表和列。结构完整时自动跳过补表。

## 全新的演示库

1. 在项目根目录打开终端，连接自己的 MySQL 8 实例。输入该实例的管理员密码。

   ```powershell
   mysql --default-character-set=utf8mb4 --host=127.0.0.1 --port=3306 --user=root -p
   ```

2. 在 MySQL 提示符运行 `SHOW DATABASES LIKE 'display_manufacturing';`。仅在该库尚不存在或已经确认是**空库**时导入基础库。仓库的全量脚本会先删除同名旧表，再恢复演示结构及原有基础数据。已有业务数据请使用经过备份的本机 MES 库，直接进入第 3 步。

   ```sql
   SOURCE sql/init/init.sql
   EXIT
   ```

3. 双击 `Initialize-Assistant.cmd`，选择正确的库名和端口。初始化器会显示缺项，询问是否在这个库新增所列结构。核对后输入 `y`，按提示选择维护预览允许访问的订单主键并提供本机密钥。使用已有角色及业务数据需有相应权限的 MES 登录账号。

4. 单独重跑数据库配置时，可在根目录运行下方命令。这里的显式参数授权脚本补齐所列业务结构，适合已准备好助手 Python 环境的电脑。只核验四个查询账号可运行第二条。

   ```powershell
   & .\backend\assistant\rag\.venv\Scripts\python.exe .\backend\assistant\deploy\Initialize-MesAssistant.py --prepare-seed-schema
   & .\backend\assistant\rag\.venv\Scripts\python.exe .\backend\assistant\deploy\Prepare-AssistantReadersLocal.py --verify-only
   ```

## 实际执行的变更

| 所属模块 | 从基础库开始时需要新增的结构 |
|---|---|
| MES 登录与授权 | `user.customer_name`、`user.shipping_address` |
| 产品选择与订单草稿 | `material.image_url`、`product_summary`、`ports`、`sort_order`、`supplier_id` |
| 生产计划 | `production_plan.version_no`、`parent_plan_no`，以及 `production_plan_schedule` 表 |
| 质检 | `quality_inspection.inspection_category`、`inspection_status`，以及 `quality_inspection_item` 表 |
| 售后 | `after_sales_case.quality_inspection_id`、`resolved_at`，以及 `after_sales_task`、`after_sales_closure` 表 |
| 库存 | `material_batch`、`inventory_barcode` 表 |

六张表直接采用仓库相应模块迁移中的建表语句。程序逐项检查后添加缺失结构，列的类型和默认值沿用项目定义。已有业务记录保留，新表初始为空。原库里没有记录的质检、条码、排产和售后事实，须由对应 MES 业务流程产生。需要记录检验版本和样机实测值时，另外按根目录 README 运行质量历史模块的两份迁移。

随后，初始化器创建助手草稿表 `assistant_order_draft_v2`，生成八把独立的引导及角色密钥，创建并核验四个限本机的只读 MySQL 账号。重跑时复用本机凭据。若报错列出其他基础表或字段，请先核对所选库和 MES 版本。
