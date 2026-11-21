# RAGFlow 文档解析草稿接入

本阶段增加独立 Python HTTP 适配，不替换现有 Qdrant、Neo4j、Dify，不修改 MES 业务服务。RAGFlow 负责另行解析已登记的原始论文；本地继续使用固定版本 multilingual-e5-small CPU/384 维模型。**当前只完成接口、草稿、审核校验、隔离暂存和离线基线；未完成真实 RAGFlow 解析或质量提升验证。**

## 固定契约与来源

- RAGFlow `v1.0.0`，Git commit `db1cd5929a7885a3074547791f5ecc4d2f891739`。`git ls-remote` 与外部 shallow checkout 实际核验；不使用 main/nightly，不声称这是最新安全发行版。
- [标签 HTTP API](https://github.com/infiniflow/ragflow/blob/v1.0.0/docs/references/http_api_reference.md)：multipart `POST /api/v1/datasets/{dataset}/documents`；`POST .../chunks`，body `document_ids`；按 ID `GET .../documents`；分页 `GET .../documents/{document}/chunks`。
- [实际 Go 状态映射](https://github.com/infiniflow/ragflow/blob/v1.0.0/internal/handler/document.go)：轮询以 `ingestion_status` 为准，支持 CREATED/SCHEDULED/RUNNING/COMPLETED/FAILED/STOPPING/STOPPED。旧 SDK 的 `run` 字段仅作为兼容回退，不能只照旧例子判断 DONE。
- [页坐标测试](https://github.com/infiniflow/ragflow/blob/v1.0.0/internal/ingestion/component/chunker/pdfcrop_cgo_test.go)：PDF positions 的页号从 1 开始；保存原始 `[page,x0,x1,y0,y1]`，缺失/异常页码不按块序号猜测。
- [官方 Docker](https://github.com/infiniflow/ragflow/blob/v1.0.0/docker/docker-compose.yml)、[配置](https://github.com/infiniflow/ragflow/blob/v1.0.0/docker/.env)、[许可证 Apache-2.0](https://github.com/infiniflow/ragflow/blob/v1.0.0/LICENSE)。上游代码仅放外部缓存目录，没有整体复制到业务仓库。正式生产使用仍需维护人员核验发行版公告、镜像摘要和当前安全更新。

RAGFlow 的 `DOC_ENGINE` 不是现有 Qdrant；此处使用官方 Elasticsearch 路径，仅作为隔离解析服务自己的存储。Go 版还需要 MySQL、对象存储、Kvrocks、NATS。API 解析任务包含服务自己的模型/索引步骤，不能把它当作无依赖 PDF HTTP 函数；须在独立实例中配置可用的 CPU embedding provider 和 DeepDoc PDF 解析，避免依赖未配置的云端模型。

## 资源与部署

在仓库根目录使用 PowerShell 7：

```powershell
./backend/assistant/deploy/Prepare-RagflowLocal.ps1 -Action Probe
./backend/assistant/deploy/Prepare-RagflowLocal.ps1 -Action Prepare -Checkout E:/MESAssistant/ragflow-v1.0.0
# 仅当资源探测允许，且 Docker 的实际磁盘空间也充足时执行：
./backend/assistant/deploy/Prepare-RagflowLocal.ps1 -Action Start -Checkout E:/MESAssistant/ragflow-v1.0.0
```

默认 Probe 不下载、不启动。Prepare 只克隆固定标签、校验 commit、生成随机私有密码和经 Docker Compose 验证的配置，不拉取镜像。Start 在任何准备/启动前检查：至少 4 CPU 线程、16GiB 主机空闲内存、14GiB Docker VM 内存、checkout 磁盘 50GiB 空间。这是与现有服务共存的保守门槛，并非宣称已实测 v1.0.0 的最低消耗。Docker Desktop 数据盘可能不同于 checkout 盘，启动前还需检查 Docker 磁盘镜像的容量。

生成配置的项目名为 `mes-ragflow-isolated`，单独网络/卷；只发布 `127.0.0.1:19380` API 和 `127.0.0.1:19381` UI，数据库/队列/管理端口均不映射宿主机。所有生成密码仅写外部 checkout `docker/.env`；展开后的 compose JSON 同样含密钥，两者设为当前 Windows 用户 ACL，不要复制进 Git、聊天或日志。注册入口关闭，初始管理员密码在私有 env 中读取，登录后建立私有 dataset 与 API key。脚本不连接或迁移任何现存 RAGFlow 数据。

本次实测主机 31.68GiB 总内存，空闲从 5.69GiB 降至约 3.56–4.4GiB，Docker VM 15.46GiB；C/D 盘约 18/40GiB 空闲，E 盘约 116GiB。当前内存不能通过门槛，**没有启动或拉取重型 RAGFlow 服务**。可在有足够空闲资源的隔离主机运行，再将客户端配置到允许的 HTTPS origin。

## Python CLI

已有 rag 环境和依赖即可，无新增 pip 包。以下在 `backend/assistant/rag` 目录执行：

```powershell
$env:PYTHONPATH = 'src'
./.venv/Scripts/python.exe -m mes_knowledge.ragflow --help
./.venv/Scripts/python.exe -m mes_knowledge.ragflow compare --out data/ragflow/baseline-comparison.json
```

默认从 `corpus/papers.json` 找 P01/P02/P03，再读取现有 `data/sources/{sha256}/original.pdf` 和带 text SHA 的归档文本。每次检查 PDF/文本 hash、许可和来源状态；不会访问论文正文或登记项中的任何 URL。离线基线为 P01 21页/45块、P02 18页/46块、P03 14页/34块，共125块。

将部署目录 `.env.ragflow.local.example` 复制为已忽略的 `.env.ragflow.local`，填入真实私有 API key/dataset ID。默认仅允许 loopback origin；远程必须 HTTPS、显式端口与 `MES_RAGFLOW_ALLOWED_HOSTS` JSON allowlist。禁止代理环境继承、重定向、任意路径/用户信息/query URL。token 不会写入草稿或输出；HTTP 错误不复述响应正文。

```powershell
./.venv/Scripts/python.exe -m mes_knowledge.ragflow ingest --out data/ragflow/run-001
# 若上传后解析中断，用 upload-receipt.json 恢复，避免重复上传：
./.venv/Scripts/python.exe -m mes_knowledge.ragflow export --receipt data/ragflow/run-001/upload-receipt.json --out data/ragflow/run-001
# 只有明确需要重新提交解析时才执行；超时不会自动重复 POST：
./.venv/Scripts/python.exe -m mes_knowledge.ragflow parse --document-id ACTUAL_DOCUMENT_ID
./.venv/Scripts/python.exe -m mes_knowledge.ragflow compare --draft data/ragflow/run-001/draft.json --out data/ragflow/run-001/comparison.json
```

部分上传失败会保留已成功文档的 receipt；export 只导出该 receipt 的文档。不要把部分导出当成三篇完整比较，报告中未解析的文档保持 null。完整重试使用新输出目录，并先在隔离实例确认已有文档，避免重复上传。输出目录已有 draft/review 时拒绝覆盖，另开目录保留历史。

默认限制：上传32MiB；每响应4MiB；每文档累计导出64MiB；100条/页、100页、10000块；请求30秒、轮询最长30分钟。拒绝页总数变化、重复块和来源文档 ID 不符。网络/超时失败不自动重试写请求；服务端任务可能仍在运行，后续用 receipt 查询。

## 审阅、引用与隔离暂存

`draft.json` 全部为 DRAFT/NEEDS_REVIEW，包含 PDF SHA、旧文本 SHA、RAGFlow dataset/document/chunk ID、原始 content/positions、纯文本（表格行与单元格用换行/tab），以及页码问题。HTML 不执行，不抓图/链接，不把模型生成解释当作工厂标准。

1. 对照每篇原 PDF 检查阅读顺序、表格、公式、页号及块内容。`comparison.json` 仅比较页覆盖、字数、表格块和原文精确命中，不能证明质量改进。
2. 审阅者填写 `review-template.json`：真实 reviewer、带时区 reviewed_at、所有 approved_chunk_ids、逐页完整 reviewed `pages`，确认后置 `status=APPROVED`。每页首尾不能有多余空白，避免归档读取后偏移变化。草稿 SHA 必须匹配。
3. 页码缺失/跨页块不允许暂存。需要根据原 PDF 更正/拆分时，保留原始导出，另存修订草稿：保持 `ragflow_chunk_id` 为真实原始 ID，给拆分项设唯一 `draft_chunk_id`（如 c1-part1/c1-part2），`approved_chunk_ids` 填这些草稿 ID。重新生成 template，再人工审阅；不要仅改状态字段掩盖错误。
4. 每个块必须在其对应已审页文本中有唯一精确子串位置，使用真实 char_start/char_end。无匹配或重复位置要求进一步拆分/修订。保留原 PDF SHA 的 document_version，归档新的文本 SHA；原文件不覆盖。

```powershell
./.venv/Scripts/python.exe -m mes_knowledge.ragflow review-template --draft data/ragflow/run-001/revised-draft.json --out data/ragflow/run-001/revised-review.json
./.venv/Scripts/python.exe -m mes_knowledge.ragflow validate-review --draft data/ragflow/run-001/draft.json --review data/ragflow/run-001/reviewed.json
# 需要已有本地 E5 模型与私有 Qdrant writer 配置；只写新 collection：
./.venv/Scripts/python.exe -m mes_knowledge.ragflow stage --draft data/ragflow/run-001/draft.json --review data/ragflow/run-001/reviewed.json --confirm-reviewed-stage
```

stage 写新的 `mes_ragflow_stage_<uuid>` collection 与 `data/ragflow/staged/<uuid>/manifest.json`，状态 `STAGED_NOT_PUBLISHED`。不写 active.json、不建别名、不调用 Neo4j，不改变公开搜索/引用。保持现有 evidence_ref/evidence_path 所需的 payload 字段，并沿用现有 E5 的512 token拒绝截断校验；过长表格必须拆分再审核。失败可能留下孤立暂存 collection/目录，保留供排查，由维护者显式清理，绝不因此发布不完整数据。

没有自动审批器，也没有激活命令。真正切换公开版本还需要一次独立的完整检索/引用回归、发布审批与回滚记录；本阶段代码特意不提供绕过这些步骤的快捷路径。未来 RAGFlow 自身检索 backend 尚未接入。

## 验证

```powershell
./.venv/Scripts/python.exe -m pytest -q
```

新增测试通过 httpx.MockTransport 检查实际 HTTP 路径/请求体、分页、Go 状态、失败/超时/响应限额/密钥不回显，使用内存 Qdrant 检查审核后暂存、来源与精确偏移、失败不替换 active。测试不代表真实服务器解析已成功。真实端到端验收需在资源满足且配置真实 dataset/API key 后执行以上三篇 ingest/export/compare，再人工核验表格并做检索回归。
