# RAG 与 GraphRAG

第三阶段已实现 FastAPI → multilingual-e5-small ONNX CPU → Qdrant 公开论文检索。五篇论文已入库，188 个正文切片。第四阶段已加入独立Neo4j及图增强检索：11个概念/方法实体、8条有原文支持的研究声明，图检索结果必须回查向量库原文。

`src/mes_knowledge/` 中：corpus 负责来源校验、页内切片；embedding 负责固定版本 CPU 推理；indexing 负责归档、版本发布与撤回；retrieval 负责当前公开版本过滤和引用回查；graph 负责版本化图发布、固定模板检索及维护业务图；api/config/cli 负责接口、配置与命令。hybrid 模式用 BM25 和向量排名融合，目前无重排器。

原query默认vector保持兼容，可选graph_assisted；图不可用明确降级。公开图与业务维护图分域，业务图目前只导入模拟样例，不通过公共HTTP或Dify暴露。运行见[第四阶段指南](../deploy/STAGE4-GRAPHRAG-SETUP.md)，契约见[GraphRAG接口](../contracts/stage4-graph-knowledge.md)。

Python 环境在独立 `.venv`，与现有 YOLO 环境隔离。直接依赖在 requirements.txt，当前 Windows/Python 3.13 全部安装版本在 requirements.lock.txt。模型、发布清单、原件归档位于 Git 忽略的 data。运行见 [第三阶段指南](../deploy/STAGE3-KNOWLEDGE-SETUP.md)。

每个检索结果带出处、PDF页码、版本和可回查标识。当前仅开放带服务鉴权的公共论文域，不接收角色/租户范围；18张项目卡仍为DRAFT。实时库存和数量由Java业务接口负责；文献不是工厂放行标准。接口见 [知识契约](../contracts/stage3-knowledge.md)。

来源管理见 [corpus](corpus/README.md)。

新增 `mes_knowledge.ragflow` 独立解析适配：上传、解析轮询、草稿导出、三篇论文基线对照、审核校验与新集合暂存。当前本机资源不足，RAGFlow 完整服务未启动，未替换上述公开版本，不能声称解析或检索质量已提升。操作见 [RAGFlow 指南](../deploy/STAGE7-RAGFLOW-SETUP.md)。
# 企业批准 SOP 的本地登记

维护者可以在项目根目录用知识服务的 Python 环境登记可在对话模型中引用的企业 SOP。原文件支持 PDF（文本型）、DOCX、TXT、MD，最大 4 MiB。只有适用于全厂、经过人工批准的版本才适合此入口。工单、设备型号专用规程需有可核验的适用范围，留待对应权限与范围规则接入。普通聊天账户没有发布入口。

审查记录使用 UTF-8 JSON。必填字段为 `document_id`（如 `SOP_QA_001`）、`title`、`version`、`approved_by`、`approved_on`、`effective_from`、`valid_to`（无明确终止日填 null）、`sha256`（经审核的原文件小写 SHA-256）、`keywords`（工序名、缺陷名等明确检索词的数组）、`domain`（例如 `QUALITY`）、`allowed_roles`（本系统的业务角色代码数组）、`status: "APPROVED"`、`scope: "FACTORY_WIDE"`、`allow_model_processing: true`。日期格式为 YYYY-MM-DD。维护者应核实原文件能在本机配置的模型服务中处理，再确认此授权。

```powershell
$py = '.\backend\assistant\rag\.venv\Scripts\python.exe'
$env:PYTHONPATH = (Resolve-Path 'backend/assistant/rag/src').Path
& $py -m mes_knowledge.approved_sop publish .\approved-file.docx .\review.json
& $py -m mes_knowledge.approved_sop withdraw SOP_QA_001 1.0
```

发布与撤回均为原子切换。正文、审批记录和归档仅在本机被 Git 忽略的 `data/approved-sop/` 目录保存。检索通过 Java 只读工具按 MES 的角色与业务域过滤，再按有效期、文档号、标题与已审核关键词选出可引用段落；管理员可阅读各业务域，客户账号无法读取。结果携带版本、原文位置和来源标识。刚安装时目录为空，聊天返回没有可核验的企业 SOP；不会自动把公开论文转成企业规范。
