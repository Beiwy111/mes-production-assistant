# 队友交接：MES Web 与生产协作助手

唯一入口为 /assistant，旧 /chat 自动跳转。工具栏“业务指令与语音”使用原角色授权和确认接口。

1. 在独立 MySQL、Redis 开发环境初始化 MES（见根 README）。从 backend/src/main/resources/application-local.yaml.example 复制为 application-local.yaml，填写本机数据库密码、独立随机 MES_JWT_SECRET（至少 32 字节）及用到的供应商密钥。不要使用他人登录令牌。
2. 分别在 backend、frontend 运行 mvn spring-boot:run 和 npm ci、npm run dev。
3. 新环境先看本目录 deploy/STAGE2-DIFY-SETUP.md、STAGE3-KNOWLEDGE-SETUP.md、STAGE4-GRAPHRAG-SETUP.md、STAGE5-AGENTS-SETUP.md、WORKBENCH-SETUP.md、CONVERSATIONAL-ORDER-SETUP.md。导入知识工具、六专家、总协调，重新选择本工作区工具和模型、配置内部服务凭据、发布并配置新 SUP 应用密钥。本地 bindings 中的应用 ID 仅供参考。按指南准备并构建 18081/18082/18083。Start-Assistant.cmd 是已安装服务的启动器，不是首次安装程序。
4. 公开资料在 docs/ai-planning-2026-11-07 的 papers 和 paper-text 中。作者、DOI、CC BY 4.0 和校验值见 rag/corpus/papers.json。页文本是从原件提取，PDF 未改。添加资料时要在完整清单中追加获许可的来源、文件校验值、页码与检索词，不要用新资料覆盖原清单。按第三阶段指南执行 ingest，再从根目录运行 backend/assistant/deploy/Update-GraphLocal.ps1。论文入库不会自动生成经核验的研究声明。
5. 浏览 Dify：http://127.0.0.1:18080/apps；Qdrant：http://127.0.0.1:16333/dashboard （使用知识服务配置中的只读 key）；Neo4j：http://127.0.0.1:17474/browser/ （bolt://127.0.0.1:17687）。知识库当前发布见 rag/data/active.json；图对应版本见 rag/data/graph-active.json。Browser 中按 rag/graph-visualization.cypher 分段查询，先将 graphRelease 换成本机 graph_release_id。
6. 初次发布用新的私有仓库、新的初始提交；不要从旧库推送历史。禁止提交 .env.local、application-local.yaml、数据库卷、训练权重、截图日志和客户数据。开发样例账号和种子 SQL 仅用于隔离测试库。不要对已有业务库批量执行 sql/fixes。

交接范围：MES Web 前后端、助手、SQL、公开知识来源及可选 YOLO 服务源码。YOLO 权重/训练集需独立配置，本发布不包含独立微信小程序、课程报告和运行数据。
