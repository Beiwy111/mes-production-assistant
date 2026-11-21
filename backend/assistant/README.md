# 新助手后端资产

当前状态：**第五阶段七 Agent 与八种订单场景已通过真实只读联调；新增独立 Vue 聊天工作台和 Java 18083 网关，原助手的开关及业务处理保持不变。** 工作台复用真实 MES 登录，仅向当前启用的管理员开放原订单白名单，回答中显示真实 Agent 状态。使用见[工作台指南](deploy/WORKBENCH-SETUP.md)。

RAGFlow 新增 Python 文档解析适配、草稿校验与隔离暂存；由于本机可用内存不足，完整服务未启动，尚未实测解析质量，也未替换当前知识版本。见[RAGFlow 接入指南](deploy/STAGE7-RAGFLOW-SETUP.md)。公开 GraphRAG 运行仍见[第四阶段指南](deploy/STAGE4-GRAPHRAG-SETUP.md)。

| 位置 | 职责 |
|---|---|
| [Java v2 模块](../src/main/java/com/upc/computer/assistant/v2/) | 鉴权、Dify 协议、运行状态/取消、保守的订单只读工具 |
| [rag](rag/README.md) | 文档入库、Qdrant检索、Neo4j图增强与出处回查 |
| [agents](agents/README.md) | 七角色登记、提示词、Dify 工作流和绑定 |
| [contracts](contracts/README.md) | 接入、知识查询和订单只读实际契约 |
| [evaluation](evaluation/README.md) | 评测问题、证据标准和评测结果规范 |
| [models](models/README.md) | 模型端点、CPU 部署和可选微调配置 |
| [deploy](deploy/README.md) | 独立部署与环境变量说明 |

## 当前可以做什么

最新补齐：七Agent已接真实Q1质量、D1设备、A1售后证据，并支持八种订单范围场景。独立Dify链路已逐项实测；库存与材料的记录事实、分配情景和缺失来源分开。见[业务证据契约](contracts/stage6-business-evidence.md)及[运行指南](deploy/STAGE5-AGENTS-SETUP.md)。

- 在不配置新外部服务的情况下正常加载 Java 配置，默认 `assistant.v2.enabled=false`。
- 单独验证新配置的绑定与启用前检查；检查本身不连接任何服务。
- 从目录和七角色登记确定后续代码的落点。

第二阶段已通过真实Dify传输联调，登录会话存储为测试替身；不代替MES浏览器验收。第三阶段已单独验证Python/API/Qdrant、Dify知识回答及14条MySQL只读投影。第五阶段已通过独立只读运行入口完成Dify真实订单101的交付链和六专家扩展知识调用；质量、入库及库存分配证据未完整覆盖，返回PARTIAL/UNKNOWN，不能直接承诺交期。false保留原助手，true时失败不自动回落。第二阶段接入步骤见[配置清单](deploy/STAGE2-DIFY-SETUP.md)，当前预览见[第五阶段指南](deploy/STAGE5-AGENTS-SETUP.md)。

## 开发边界

1. Java 新文件集中 `com.upc.computer.assistant.v2`；旧包不移动。
2. 新只读Mapper使用专用的条件MapperScan，原扫描不改；第三阶段工具受双开关控制，第四阶段维护导出有独立默认关闭开关和凭据。
3. 第一版业务工具只读；实时数量由 Java 查询和计算，文档/图谱提供证据。
4. 七角色是逻辑角色，可共用一个模型端点；不创建七个 Java 推理服务。
5. Python、Dify、Qdrant、Neo4j 和模型是独立服务，不自动打进 JAR。
6. catalog 记录已部署的Dify预览应用和实际工具清单；MES enabled仍为false，不是终端用户授权来源。
7. 权重、密钥、数据库卷、原始业务导出和训练 checkpoint 保存在部署数据目录，不能提交源码仓库。

## 验证

在项目根目录运行（无需数据库、Redis 或模型服务）：

```powershell
mvn -f backend/pom.xml -Pquality test
node --test frontend/src/utils/assistantStream.test.js
```

此命令覆盖配置基础与部分旧助手回归，不等于全业务或真实服务联调验收。

后续顺序：知识来源/契约 → 模型端点与新接入 → RAG/只读工具 → 最小图谱 → Dify 七工作流 → 前端/评测 → 可选微调 → 本地部署。
