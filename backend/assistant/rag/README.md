# RAG 与 GraphRAG

第三阶段已实现 FastAPI → multilingual-e5-small ONNX CPU → Qdrant 公开论文检索。五篇论文已入库，188 个正文切片。第四阶段已加入独立Neo4j及图增强检索：11个概念/方法实体、8条有原文支持的研究声明，图检索结果必须回查向量库原文。

`src/mes_knowledge/` 中：corpus 负责来源校验、页内切片；embedding 负责固定版本 CPU 推理；indexing 负责归档、版本发布与撤回；retrieval 负责当前公开版本过滤和引用回查；graph 负责版本化图发布、固定模板检索及维护业务图；api/config/cli 负责接口、配置与命令。hybrid 模式用 BM25 和向量排名融合，目前无重排器。

原query默认vector保持兼容，可选graph_assisted；图不可用明确降级。公开图与业务维护图分域，业务图目前只导入模拟样例，不通过公共HTTP或Dify暴露。运行见[第四阶段指南](../deploy/STAGE4-GRAPHRAG-SETUP.md)，契约见[GraphRAG接口](../contracts/stage4-graph-knowledge.md)。

Python 环境在独立 `.venv`，与现有 YOLO 环境隔离。直接依赖在 requirements.txt，当前 Windows/Python 3.13 全部安装版本在 requirements.lock.txt。模型、发布清单、原件归档位于 Git 忽略的 data。运行见 [第三阶段指南](../deploy/STAGE3-KNOWLEDGE-SETUP.md)。

每个检索结果带出处、PDF页码、版本和可回查标识。当前仅开放带服务鉴权的公共论文域，不接收角色/租户范围；18张项目卡仍为DRAFT。实时库存和数量由Java业务接口负责；文献不是工厂放行标准。接口见 [知识契约](../contracts/stage3-knowledge.md)。

来源管理见 [corpus](corpus/README.md)。

新增 `mes_knowledge.ragflow` 独立解析适配：上传、解析轮询、草稿导出、三篇论文基线对照、审核校验与新集合暂存。当前本机资源不足，RAGFlow 完整服务未启动，未替换上述公开版本，不能声称解析或检索质量已提升。操作见 [RAGFlow 指南](../deploy/STAGE7-RAGFLOW-SETUP.md)。
