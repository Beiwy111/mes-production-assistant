# 第二阶段 Dify 配置与联调

状态：本机 Dify 1.19.0 已部署，DeepSeek 0.0.26 插件已配置；接入验证 Workflow 已导入并发布。2026-11-08 独立 Java → Dify → DeepSeek 联调 5 项通过；整套 MES 浏览器登录与页面验收尚未完成，默认关闭新链路。

## 你需要的两种凭据

| 配置 | 放在哪里 | 用途 |
|---|---|---|
| 模型服务 API Key，或可用的本地模型端点 | Dify 模型供应商设置 | Dify 调用模型 |
| Workflow 应用 API Key | Java 进程环境变量 | Java 调用 Dify 应用 |
| Dify 应用 API 基地址 | Java 进程环境变量 | 一般以 /v1 结尾，以该应用 API 页面为准 |

模型密钥、Dify 应用密钥和 Dify 登录密码不能互换。实际密钥只在本地填写，不发到聊天、不进入前端或源码。

## 1. 准备 Dify

Windows 本机部署按 [官方 Docker Compose 指南](https://docs.dify.ai/en/self-host/deploy/quick-start/docker-compose) 准备 WSL2、Docker Desktop/Compose，选择明确 release。Dify 源码和数据卷独立放置，不复制进 MES、不覆盖现有数据库或 Redis。记录版本，启动前检查端口和资源。

Dify 自身的数据库/内部向量存储与未来 Python RAG 的 Qdrant 是两项配置。第二阶段不用改 Dify 的内部存储，也不必启动 Qdrant/Neo4j。

本机入口：http://127.0.0.1:18080；独立目录 D:\soft\dify-1.19.0；Compose 项目 mes-dify-local。镜像已下载、容器已启动，详见 [本机部署记录](LOCAL-CONFIG-STATUS.md)。

## 2. 配置模型并发布 Workflow

在 Dify 工作区配置一个可用文本模型，先完成供应商连接测试。模型 API Key 放 Dify；本地端点必须从 Dify 所在环境可达。容器里的 localhost 不是 Windows 宿主机，Docker Desktop 可按环境验证 host.docker.internal 等宿主机地址。

本次供应商为 DeepSeek：

1. 在“集成 → 模型供应商”安装作者为 langgenius 的 DeepSeek（深度求索）官方插件。
2. 在供应商授权设置中本地填写 DeepSeek API Key；接口地址使用 https://api.deepseek.com（使用官方默认地址时也可留空）。不要将 Dify 应用密钥填到这里。
3. 初次测试选择 deepseek-flash，关闭 thinking，temperature=0.2，max_tokens=2048。以已安装插件的实际模型列表为准。
4. 本机已完成导入，无需重复创建。[打开已发布应用](http://127.0.0.1:18080/app/2083cf59-f871-4d49-a02b-042d0e7a7818/workflow)。其他环境可在工作室中“导入 DSL 文件”，选择 [接入验证 DSL](../agents/workflows/stage2-deepseek-import-draft.yml)。它包含开始、LLM、结束三个节点；不是七 Agent 的业务流程，也尚未接入实时业务数据。
5. 检查模型绑定，输入 question=“你现在能做什么？”、history=[]（字符串），确认结束节点输出 answer。再问“现在实际库存有多少？”，应明确未接入数据，不应编造数值。
6. 测试通过后发布 Workflow，才生成并使用该应用的 API Key。

模型及插件来源：[DeepSeek API 文档](https://api-docs.deepseek.com/)、[Dify 官方 DeepSeek 插件](https://marketplace.dify.ai/plugin/langgenius/deepseek)。DSL 基于 Dify 1.19.0 / 0.7.0，已在本机完成导入和真实调用；其他环境仍需重新绑定供应商凭据并测试。

按 [最小工作流说明](../agents/workflows/stage2-minimal-workflow.md) 创建 **Workflow**，不要用 Chatflow/Agent 的 API Key 替代。调试成功后发布，然后从应用 API 页面获取基地址和应用密钥。

Java 使用 [Workflow Run API](https://docs.dify.ai/en/api-reference/workflow-runs/run-workflow)，基地址不要包含 /workflows/run，代码会追加路径。

## 3. 配置 Java

在 IDEA 的后端运行配置中，保留原 profiles，追加 assistant-v2。例如原来已有 local、ai，才使用 local,ai,assistant-v2；不要凭示例添加不存在的原 profile。

设置以下环境变量：

```text
ASSISTANT_V2_ENABLED=true
ASSISTANT_V2_DIFY_BASE_URL=http://127.0.0.1:18080/v1
ASSISTANT_V2_DIFY_API_KEY=本地填写应用密钥
ASSISTANT_V2_KNOWLEDGE_ENABLED=false
```

文字占位值必须替换，不能直接运行。Spring Boot 不自动读取 .env；[.env.example](.env.example) 仅列变量，通过 IDE 或进程环境注入。

第二阶段无需知识地址、知识密钥或 assets-root；知识有独立开关，当前保持 false。assets-root 若填写必须是绝对路径。未完成 Dify 配置前保持 ASSISTANT_V2_ENABLED=false。

## 4. 实际验收

已提供隔离检查命令（在项目根目录执行）：

```powershell
# 仅检查本地配置格式，不发起模型请求
& .\backend\assistant\deploy\Test-DifyLive.ps1 -ValidateOnly
# 使用真实 Dify 应用 API，可能消耗模型额度
& .\backend\assistant\deploy\Test-DifyLive.ps1
```

脚本从本机 Git 忽略的 `.env.local` 读取应用密钥，仅给测试子进程注入环境变量，结束后恢复调用进程原值；不会启动 MES，也不会修改配置文件里的开关。普通测试默认不触发这组真实调用。不要把模型供应商密钥填入 Dify 应用密钥字段。

已验证：真实 Workflow 回答、成功重放不重复调用模型、SSE 转 NDJSON、运行查询与跨用户拒绝、实际工作流开始后的本地取消、匿名拒绝、无效应用密钥错误脱敏。控制器、门面、客户端与 JWT 使用实际代码，登录会话存储用测试替身；未覆盖实际 Redis 登录会话、前端 HTTP 链路或模型供应商是否最终停止生成。超时与网络错误的确定性场景仍由已有本地 HTTP 模拟测试覆盖。

下面是整套 MES 启用后的待完成验收，不应与上述独立联调混淆：

1. Dify 中输入 question=“你现在能做什么？”、history="[]"，确认返回非空字符串 answer，发布工作流。
2. 配置并重启 Java，正常登录 MES，从原聊天入口问同一问题。
3. 新链路先返回运行状态，成功后显示最终回答；不是逐 token 展示。
4. 测试停止等待、上游错误和超时；验证不同用户不能查看或取消彼此运行。
5. 将 ASSISTANT_V2_ENABLED=false 并重启，确认原助手分支恢复。新链路失败不会自动回落。

记录 Dify 版本、模型、工作流导出与成功/失败/取消结果，才可标记真实联调通过。

## 边界

- 单实例内存登记：全局最多 4 个进行中请求，每用户 1 个，总记录上限 1000。终态记录按 retention 清理；重启失去状态和幂等缓存。多实例/持久恢复前需共享存储。
- 同一用户相同 requestId 与内容的成功重放返回缓存，进行中/失败/内容不同返回冲突。失败重试应使用新 requestId；客户端不自动重发 Dify POST。
- 历史只是受长度限制的用户输入，不是可信业务事实或服务器记忆。
- 取消先停止本地等待；拿到 task_id 才尽力向 Dify 发送 stop。断网或未知 task_id 时不保证上游停止。Java 自行校验所属用户，不依赖 Dify stop 的 user 参数作为授权。
- 仅返回最终 outputs.answer，不转发内部节点、上游 task_id 或凭据。
- 尚无 RAG、GraphRAG、真实业务工具和七专家协作；本阶段不能查询真实库存或判定交付。
