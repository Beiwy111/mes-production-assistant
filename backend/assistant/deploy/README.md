# 配置与部署

第五阶段已发布总协调与六专家的独立Dify工作流，配有单独Java只读预览入口。2026-11-09已完成订单101的真实交付链及六专家扩展知识调用，最终仍明确证据不足，尚未切换MES助手。详见[第五阶段指南](STAGE5-AGENTS-SETUP.md)。不要为预览打开原MES新助手开关。

第四阶段已部署独立Neo4j并连接Python/Qdrant，新增独立Dify图增强问答；原MES入口不切换。启动、可视化、维护与业务同步边界见[第四阶段指南](STAGE4-GRAPHRAG-SETUP.md)。本机公共演示已复用现有API配置，不需要新增密钥。

第三阶段已部署独立Python/Qdrant公开论文RAG及Dify知识演示，详见[第三阶段启动/备份指南](STAGE3-KNOWLEDGE-SETUP.md)。订单只读工具默认关闭，未切MES入口。以下第二阶段说明继续适用于原接入验证应用。

第二阶段已实现 Java→Dify 接入，MES 开关默认关闭。本机 Dify 1.19.0 与 DeepSeek 已配置，独立 Java 真实调用检查已通过，整套 MES 浏览器登录与页面验收仍待完成。

请按 [第二阶段配置清单](STAGE2-DIFY-SETUP.md) 准备 Dify、模型和已发布的 Workflow，再配置 Java。

本机已准备 Git 忽略的 `.env.local`，应用密钥只由用户在本地填写。[Test-DifyLive.ps1](Test-DifyLive.ps1) 可读取它进行配置校验和真实联调，不启动 MES、不修改系统环境变量、不切换原助手。使用 `-ValidateOnly` 只校验格式；不带此参数会调用真实模型，可能消耗额度。

- 原系统无需新增配置即可使用，原 application.yaml 和已有 profile 未改。
- [application-assistant-v2.yml](../../src/main/resources/application-assistant-v2.yml) 是可选 profile；[.env.example](.env.example) 只是变量清单，Spring 不自动加载。
- enabled 默认 false；开启后原聊天入口使用新接入，失败不自动回落。关闭并重启后走原助手。
- Java知识独立开关仍false；第三、四阶段独立Dify演示直接调用Python。Qdrant与Neo4j均已独立部署。
- Dify、Python、数据库卷和模型独立部署；Java JAR 不包含 backend/assistant 的运行资产。
- 第一阶段“仅校验配置”已被实际接入替代；订单只读工具首版已实现但保持关闭，完整业务证据尚未齐备。
