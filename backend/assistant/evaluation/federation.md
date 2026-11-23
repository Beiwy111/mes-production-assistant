# 生产协作助手联合检索验收与运维

2026-11-21，本机工作区。聊天入口继续使用原工作台、Dify 总协调和六个业务工作流。当前活动的公开发布版本包含 5 篇论文、188 个片段。Qdrant Dashboard 集合总点数包含历史发布版本，在线问答按活动版本和撤回状态过滤。

## 检索和权限

- 库存、工单、派工、检验数等事实通过 Java 注册的只读工具回源。后续提问携带上轮运行 ID，再次核对用户、权限、工单和筛选条件。
- 普通公开知识题走 Qdrant，询问关系时叠加 Neo4j 及原文核验。论文、公开手册和公开标准有不同的来源类型。
- 全厂维修、质量历史案例是独立的私有检索域。当前仅对符合领域和范围限制的管理员开放。指定订单、工单或客户时，优先走可逐行校验的业务工具，无法精确约束的全厂历史检索会跳过。
- 历史检索并发查询向量与固定图路径，通过当前 MySQL 复查原记录及摘要。资料被删除、已改变或图版本不吻合时排除旧证据。批次结论只使用对应工单的物料、批号、检验类别；论文或相似案例用于解释和排查。
- 初始经验来源为 equipment_maintenance_record 与 nonconforming_product。公开向量集合继续只存公开资料；私有集合载荷仅保存不透明标识、摘要和权限分区。案例原文从 MySQL 读取。图路径可以表示共同设备、工序、工单、缺陷及记录处置。明确写在来源中的确认原因可建边，推测、不明原因、售后根因字段均不导入这两个域。
- 经验表每表上限 5,000 行。完整扫描用于发现删除，增量比较摘要决定重新编码和更新哪些关系。同步脚本获取维护锁，确认图写入后切回只读。中断可重跑相同命令。当前采用按需同步，索引延迟期间查询会剔除失效来源。

## 真实验收

30 题走真实接口。26 题使用工作台流式入口和 Dify，4 题经过登录后的原订单草稿入口。订单用例会留下测试账号自己的草稿，没有调用提交、派工或实际订单取消接口。测试组使用同一会话串联需要继承范围的追问。多个登录脚本请顺序运行，以免同一账号重新登录或退出时使另一个测试的凭据失效。

| 题组 | 题数 | 校验重点 |
| --- | ---: | --- |
| 工单与派工 | 7 | 数字短号、WO 编号、代词、授权范围内的工序与同批质检 |
| 订单草稿 | 4 | 100 改为 200、无有效地址、保留草稿等待确认 |
| 生产 | 4 | 查询与否定排产分流 |
| 库存 | 4 | PCB板匹配 PCB主板、其他物料排除前一物料、继承范围查询可用量 |
| 质量 | 4 | 全厂问题与缺少批次的问题分开处理 |
| 竞争与冲突 | 3 | 明细来自物料竞争、产能冲突工具 |
| 权限与范围边界 | 4 | 无锚点澄清，跨客户请求与写操作边界 |

三张授权工单各独立执行三轮，最终检查流式增量、实时行数、工单号、订单关联和图中检验 ID 集合。当前订单 1 有 0 条派工、4 条同批检验；订单 100 有 8 条派工、1 条同批检验；订单 101 有 8 条派工、0 条同批检验。订单 101 的业务图可追到工单。其工单路径与实时 MySQL 连接一致，无质检记录时无法判断是否存在异常。

## 本机运行

在仓库根目录打开 PowerShell。当前机器已配置独立 MySQL 只读账号、知识 API 和工作流。日常启动使用已有 Start-Assistant.cmd。部署到新机器时先按 deploy/STAGE5-AGENTS-SETUP.md 准备本机数据库和 Dify 各应用配置，再创建专用 SELECT 账户。生成的 .env.*.local 仅存于本机。

~~~powershell
$py = '.\backend\assistant\rag\.venv\Scripts\python.exe'
$env:PYTHONPATH = (Resolve-Path 'backend/assistant/rag/src').Path
& $py -m pip install -r backend/assistant/rag/requirements.txt
& $py backend/assistant/deploy/Prepare-AssistantReadersLocal.py
& $py backend/assistant/deploy/Prepare-AssistantReadersLocal.py --verify-only
.\backend\assistant\deploy\Update-GraphLocal.ps1 -Kind Experience
~~~

查询公开图使用 Neo4j Browser 的 Graph 视图，订单快照和经验关系分别选择相应标签。公开图可以从 Document 沿 CONTAINS 展开。经验图在 Browser 显示实体种类和不透明标识；业务名称、记录内容和经核验的路径可在已登录的聊天 Agent 面板展开。

~~~cypher
MATCH p=(:ExperienceCase)-[]->(:ExperienceEntity) RETURN p LIMIT 80
~~~

本机额外授权导出订单由 deploy/.env.graph-orders.local 的 MES_GRAPH_EXPORT_ORDER_IDS 管理。本轮配置为 100,101,1，独立于互动助手的订单授权。按 evaluation/work-order-trace.md 逐单导出和更新。变更、删除业务记录后重新导出投影；回答具体工单时会与实时 MySQL 路径取交集。

## 添加资料

允许公开分发的文本 PDF 或 DOCX 可由维护者审查后本机导入，目前单文件最大 4 MiB。扫描 PDF 需先 OCR 并复核。只有公开授权的手册和标准可以走此入口。DOCX 按原文引用；文本提取没有 Word 的排版页码。程序会校验原文件哈希，保留活动论文、归档材料和文字、创建新版本、验证点数，再切换线上发布版本。现有 ID 内容变更时应走版本审核或使用明确的新版本 ID，并按需撤回旧版本。导入失败时当前活动发布仍可查询。

将以下字段存入 UTF-8 review.json，并用 Get-FileHash 的 SHA256 小写值替换占位文本。source_url 指向资料公开来源，license 填实际分发许可。

~~~json
{
  "id": "MANUAL_001",
  "title": "实际手册名称",
  "source_type": "PUBLIC_MANUAL",
  "source_url": "https://example.org/public-manual",
  "license": "实际许可",
  "visibility": "PUBLIC",
  "review_status": "REFERENCE_ALLOWED",
  "sha256": "原文件的64位小写SHA256"
}
~~~

公开标准使用 PUBLIC_STANDARD。以下命令接续上面的环境变量；更新公开图时只发布已核验的关系声明。

~~~powershell
& $py -m mes_knowledge.reviewed_documents .\manual.docx .\review.json
.\backend\assistant\deploy\Update-GraphLocal.ps1 -Kind Public
~~~

## 复测命令和主要文件

先在本机为测试进程设置 MES_TRACE_USER、MES_TRACE_PASSWORD，使用已获授权的测试账号，并选用 Git 忽略的本地回执目录。题库中的工单、账户授权和预期均针对本机数据；数据库或权限变化时先复核事实。orders_live.py 会从只读 MySQL 独立读取当时的派工、检验数量和关联 ID。

~~~powershell
& $py backend/assistant/evaluation/orders_live.py
python backend/assistant/evaluation/federation_live.py --out backend/assistant/rag/data/federation-check.json
python backend/assistant/evaluation/replay.py --out backend/assistant/rag/data/eval30-check
& $py -m pytest -q backend/assistant/rag/tests
~~~

| 范围 | 主要文件 |
| --- | --- |
| 真实历史抽取、清洗、增量发布、两跳检索 | backend/assistant/rag/src/mes_knowledge/experience/{source,index,graph,__init__,__main__}.py |
| 私有 API 与公开资料追加 | backend/assistant/rag/src/mes_knowledge/{api,cli,reviewed_documents,indexing,corpus,retrieval}.py |
| 账户与密钥配置、图同步 | backend/assistant/deploy/{Prepare-AssistantReadersLocal.py,AgentRuntime.Common.ps1,ExperienceCredential.ps1,TracePilotExport.java,Update-GraphLocal.ps1} 及独立服务启动脚本 |
| 路由、数据范围、来源复核 | backend/src/main/java/com/upc/computer/assistant/v2/runtime 下的 NaturalLanguageRuntime、BusinessSelectors、BusinessToolRegistry、NaturalBusinessReadMapper、WorkOrderReference、WorkTraceGraph、KnowledgeEvidenceClient、WorkbenchHttpServices 等 |
| Dify | backend/assistant/agents/scripts/stage5_nodes.py、agents/workflows/stage5 下的七份 DSL |
| 聊天与证据 | frontend/src/components/assistant 下的 AgentInspector、ExperienceEvidence、KnowledgeSources、KnowledgeEvidence 及对应的工具标签、引用处理 |
| 可重复验收 | backend/assistant/evaluation 下的 replay.py、orders_live.py、federation_live.py；rag/tests 下的 test_experience、test_experience_citations、test_mysql_readers、test_reviewed_documents 等 |

