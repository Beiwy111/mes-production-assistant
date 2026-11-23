# 工序问答与质检追溯验收

生产协作助手沿用 Dify 总协调和六个业务角色。工作流根据问题、上轮已确认的范围和可用工具提出计划。Java 再校验账号的菜单权限、工具白名单、单号、时间范围和附加条件，使用参数化查询读取 MySQL。通用概念可以直接回答。企业现状必须有本次授权查询的依据。

## 本机验收

2026-11-21 经真实工作台流式接口发送八轮提问，再以只读账号独立核对 MySQL、工序目录和返回的工具事件。以下数字只用于复现这次验收。

| 提问 | 独立核对结果 |
| --- | --- |
| 最近质检情况 | 上海时间 11 月 15 日至 21 日共 0 单，无法计算本时段的质量水平。空范围不会报告缺失视觉归档。 |
| 八道工序分别是什么 | 车间标准派工目录为主板装配、电源板装配、接口板装配、显示屏加工、面板贴附、外壳装配、整机组装、支架底座装配。另查到 8 条启用产品路线，其中 5 条含 10 步，3 条含 11 步。 |
| QI202607021 有什么问题 | 主键 63，工单主键 2，编号 WO202607002。抽检 10 件、合格 7 件、不合格 3 件。HDMI 实测无信号。NC202607104 和 NC202607105 分别记录外观划伤和接口异常，两条登记数量合计 2 件。 |
| 那这张单关联的设备能确定吗 | 保留上一轮的 QI 主键和权限范围。工单有路线，但无对应报工、派工或报警记录，不能锁定设备。公开资料命中不能证实企业现场根因。 |
| 为什么近期某个批次的不良率升高 | 先索取批号和比较期间。工具日志中没有执行全厂质检查询。 |
| 补充批号和两个日期 | 同物料、同批号 BATCH-WO202607002-3201，7 月 11 日为终检，3/10；7 月 12 日为复检，1/3。不同检验类型分别列示，不能确认恶化。同口径时按不合格数合计除以抽样数合计，再计算百分点差。 |
| 抽检合格率和整批合格率的区别 | 模型解释概念，未调用业务数据或论文工具。 |
| QI202611001 的判定与数量是否一致 | 通过判定，抽检 20 件、18 件合格、2 件不合格。保留源记录，提示复核接受准则及复判记录。原库另有相同现象的 QI202607503。 |

同期重新运行了 30 道历史题。26 道只读题和 4 道订单草稿题均通过，订单用例只更新测试账号草稿。首次回归中发现“分析质量问题”被误当成指定批次的归因请求，更新总协调后已重测。真实 Chromium 分别校验工序查询、检验单和设备追问的文字增量、业务表以及右侧工具事件。浏览器复测入口为 `frontend/tests/workbench-inspection-routing.browser.py`。

## 复测

从仓库根目录运行。先启动原 MES 服务与独立助手，在本机环境中设置已获授权的测试账号变量 `MES_TRACE_USER`、`MES_TRACE_PASSWORD`。核验脚本的预期数据针对本机管理员的可见范围，若业务记录或数据权限变化，应先核对测试预期。

~~~powershell
$py = '.\backend\assistant\rag\.venv\Scripts\python.exe'
$outDir = 'backend/assistant/rag/data/inspection-check'
& $py backend/assistant/evaluation/inspection_live.py --out $outDir
& $py backend/assistant/evaluation/verify_inspection_live.py --out $outDir
~~~

第一条命令保存工作台真实返回的正文、事实、流式增量数量和工具事件到本机忽略目录。第二条命令用限定表权限的账号复查源记录、日期范围、缺失关联和判定差异。需要单独复验时可传 `--cases`。设备追问随 `inspection` 一起发起；单独跑 `named_batch` 也会先提问获取批次澄清。

## 新机器的服务与资料

- 按项目既有部署说明启动 Dify、Qdrant、Neo4j 和知识服务。使用 `backend/assistant/deploy/Prepare-AssistantReadersLocal.py` 为独立助手补齐受限读权限，包括启用路线和检验追溯的指定字段，再用 `--verify-only` 核验。
- 独立服务编译命令为 `backend/assistant/deploy/Build-AssistantRuntime.ps1 -OutputName runtime-build` 和 `-OutputName workbench-build`。启动器对正在运行的服务会跳过。更新时需要让独立运行时与网关重新加载已编译版本。
- 本机 Dify 的七份 DSL 在 `backend/assistant/agents/workflows/stage5`。已配置本地绑定的环境可执行 `python backend/assistant/deploy/Manage-Stage5Dify.py inspect --roles SUP QUALITY` 核对发布图。新机器应使用自身的 Dify 绑定和凭据。
- 企业 SOP 从本地审批目录检索，命中须满足角色、有效期、版本和允许模型处理的审批范围。发布与撤回入口为 `mes_knowledge.approved_sop publish 文件 审批JSON` 和 `withdraw 文档ID 版本`，使用知识服务的 Python 环境。审批字段见发布器与对应测试。公开论文继续通过 Qdrant 检索，业务图只展示与当前 MySQL 关联相符的已授权快照。
### 本地实训历史与检验人员核对

`QI202607021` 的质检员来自检验单的 `inspector_id`。生产报工、派工和人员沿工单外键回查。终检、复检和缺陷登记分别核算，以免将同一件产品重复计入。

需要复现实训历史时，在本地 MES 数据库执行 [定向补录脚本](../../../sql/fixes/seed_inspection_training_archive.sql)，然后运行 `python backend/assistant/deploy/Prepare-AssistantReadersLocal.py` 和 `--verify-only`。脚本仅向助手的实训历史表补录指定工单的三张历史派工单和一张历史报工单，保存原始单号、日期及种子文件。工单、人员角色和工序必须匹配。未安装该表时，助手照常查询当前业务单据。

在评测账号已配置的环境中，用 `inspection_live.py --out <本地结果目录> --cases personnel` 从聊天网关回放原问题，再用 `verify_inspection_live.py --out <同一目录> --cases personnel` 对照 MySQL 核验姓名、岗位、单号、样本不合格率和缺陷线索。
