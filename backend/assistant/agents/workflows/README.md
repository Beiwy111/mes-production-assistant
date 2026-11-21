# Dify 工作流目录

第五阶段[七角色DSL](stage5/)各包含一个真实FunctionCalling Agent节点：SUP挂六个专家Workflow工具，专家只挂共享公开RAG/GraphRAG工具。固定HTTP节点取得角色范围内的业务事实并登记结果；最终判断使用后端E5复核。当前用于本机只读预览，MES入口未切换，真实订单联调需要数据库连接配置。[运行与覆盖说明](../../deploy/STAGE5-AGENTS-SETUP.md)。

第四阶段新增[GraphRAG DSL](stage4-graph-rag.yml)：独立应用复用已有DeepSeek和知识服务密钥，以graph_assisted检索、验证图路径与原文引用、输出answer/evidence/graph。包含7个处理节点，仍不是7个Agent。已通过真实问答和Neo4j停止后的明确降级测试；第三阶段应用保持原状。入口和配置见[第四阶段指南](../../deploy/STAGE4-GRAPHRAG-SETUP.md)。

第三阶段新增 [公共论文RAG DSL](stage3-public-rag.yml)：已在本机导入、通过3次真实知识/边界问题运行并发布。7个节点为开始、JSON请求、HTTP检索、证据校验、DeepSeek回答、页码校验、结束；**不是7个Agent**。只访问公开论文Python API，无MES订单/库存工具。输入question，history目前不使用；输出answer/evidence。导入到新环境需绑定DeepSeek并设置秘密变量KNOWLEDGE_API_KEY，仓库DSL不含密钥。运行见[第三阶段指南](../../deploy/STAGE3-KNOWLEDGE-SETUP.md)。

当前有 [DeepSeek 接入验证 DSL](stage2-deepseek-import-draft.yml)，基于 Dify 1.19.0 / DSL 0.7.0。2026-11-08 已在本机导入、通过两次真实问答并发布，文件名保留 draft 以避免路径变动；它是人工整理并实测的导入文件，不是由 Dify 导出的文件。它不是总协调与六子 Agent 的业务工作流。后续在实际 Dify 验证七个 Agent 后再导出，保留节点映射和依赖说明。

第二阶段节点见 [最小工作流节点说明](stage2-minimal-workflow.md)，实测结果见 [部署记录](../../deploy/LOCAL-CONFIG-STATUS.md)。Java 端到端验收仍待完成。

导入后必须重新绑定模型、工具与环境引用；密钥通过部署环境提供。导出、提示词、catalog 和 contracts 的版本/哈希须对应，不能分别维护互相冲突的定义。
