# 动态生产任务编排

本轮增量接入现有生产协作助手。原查询、日常问答、图片、Dify、Qdrant 和 Neo4j 链路继续使用已有入口；生产办理交给有界的模型规划循环，不迁移数据库或页面布局。

## 实际执行方式

1. 当前登录身份进入 `/assistant-v2/tasks/stream`，服务端从原角色权限生成本轮工具目录。
2. 使用模型原生 function calling，工具参数以 JSON Schema 提供。互不依赖的读取最多三个并行，需要上一步结果的调用在下一轮决定。每次请求最多四轮，重复查询复用当前结果。
3. 读取工具复用订单、生产计划、排程和派工服务。工具参数白名单校验；模型不能提交 SQL、任意 URL 或直接执行写操作。
4. `preview` 单独执行，复用原 `ProductionOperationService` 和业务计算引擎，返回选项、缺失字段或待确认卡片。
5. 用户修改方案时重新计算并取消旧方案。即使新条件无法排产，也不能误确认旧方案。
6. 只有确认接口能调用原 `AgentActionPlanService`。重新校验身份、方案归属、有效期、状态快照及业务冲突，原审计记录和幂等回执继续生效。
7. 确认后的 CO/PP/WO/DT 标识及结果保存在服务端任务状态中；用户说“继续”时，模型根据真实回执决定后续步骤，不提前编造新编号。

Redis 任务状态按用户 ID、当前角色、会话 ID 隔离，保留 24 小时。它保存目标、查询结果、当前字段、方案及回执，不替代业务数据库。原待确认方案继续使用原有有效期。

## 工具与职责

| 工具 | 实际职责 |
| --- | --- |
| orders | 可制定计划的已审核/待计划订单 |
| orderContext | 指定订单的库存、BOM、工艺路线与产能依据 |
| plans | 权限内的生产计划及状态 |
| planContext | 计划、来源订单、工艺路线与已有工单 |
| resources | 在岗账号与设备状态；不是技能/班次认证 |
| preview | 根据原动作目录准备操作方案，不执行写入 |

总协调规划器调用这些业务适配器；读取结束后向总协调器交回结果。页面中的规划、读取、交接与完成事件来自实际执行，不冒充 Dify 节点执行。原 Dify 七角色及知识分析链路保持独立可用。

## 流式与前端

- 模型工具名称和参数按原生 function calling 增量组装，不显示到聊天中；公开回答按模型返回的正文增量推送。实际工具执行通过独立事件展示，不将模型描述当作工具已完成的证明。
- 方案卡片及短的业务校验结果在服务端生成后发送，不拆字伪装模型生成。
- 主后端通过原 SSE 协议输出任务 ID、调用 ID 和序号；前端沿用现有消息渲染、会话、白色主题和确认卡片。
- 原 SSE 读取流程不变；旧 `/operations` 接口保留兼容。

## 排程计算修正

联调发现旧策略会把同一批产品的数量分摊到不同串行工序，并在日期不足时丢弃后续工序。现在保留全部工序和各工序完整生产量，以既有标准工时估算完整周期；不足时展示实际预计完工日，不通过截断工序或压缩工时凑交期。

助手拒绝把超过用户要求截止日期的方案直接作为待执行方案，返回实际预计完工日和调整日期的表单；这类计算冲突使用业务服务原始结果，不让模型猜工期。用户也可在聊天中调整数量或策略后重新计算。策略仍是原系统的启发式估算，不是经过人员技能、个人班次和所有资源日历验证的最优排产。

## 配置及验证

没有新增 API Key。模型复用独立工作台已有模型配置；主后端需要可访问本机 18083 工作台和现有 Redis。前后端启动方式不变。

后端相关验证：

```powershell
mvn -o -q -f backend/assistant/rag/data/runtime-build/pom.xml '-Dtest=ProductionLifecycleDatabaseTest,ProductionTaskServiceTest,ProductionTaskToolsTest,ProductionSchemeIntegrityTest,ProductionOperationServiceTest,AgentActionPlanServiceTest,OperatorDispatchReceiptTest,TaskEventStreamTest,TaskToolStreamTest' test
```

隔离构建描述文件由 `backend/assistant/deploy/Build-AssistantRuntime.ps1` 生成；新电脑尚未生成时先运行该脚本。生产规划循环测试使用受控模型返回；真实模型和真实订单的预览单独进行浏览器联调，不能把两类测试混称为全真实生产写入测试。

`ProductionLifecycleDatabaseTest` 使用隔离 H2 数据库和真实 MES Mapper/写入服务，验证计划草案→发布→提交主管→生成工单与派工→指定操作员通知及重复确认幂等。排程/派工建议输入、状态快照及 Redis/审计存储使用测试替身。`OperatorDispatchReceiptTest` 继续验证操作员接单、领料、开工、报工。不会向本机真实订单下发测试生产任务。

本机真实联调结果和截图存放在被 Git 忽略的 `backend/assistant/rag/data/dynamic-task-check/`，不包含登录凭据，也不作为公共业务样本提交。

### 本轮验收（2026-11-26）

- 后端上述 9 个测试类共 40 项通过；前端路由、事件、确认卡片与请求处理等 105 项通过，`npm run build` 通过（仍有原项目动态导入分包提示）。
- 浏览器真实登录、真实模型及 MySQL：列出 4 张可计划订单 → 选 CO20261115002 补产 100 台 → 按交期优先预览 → 改为成本优先。订单、数量、日期保持正确，8 道工序各保留 100 台。未点击实际写入确认。
- 首轮回答收到 45 个模型正文增量；页面没有脚本错误或横向溢出。截图为 `browser-final.png`。
- CO20261115003 的订单条件与人员设备查询真实重叠执行：工具开始时间分别为请求后 6.041 秒、6.044 秒，结束为 6.104 秒、6.076 秒。耗时为该次调用观测，不是性能保证。
- CO20261115002 的 2026-11-30 硬截止测试：工具计算预计 2026-12-24 完工，返回 `NEEDS_INPUT` 且无确认凭据，不虚构加班或压缩工期。
- 原入口“只查询物料库存，不包括成品”返回 8 条记录，全部属于物料范围，无截断。
- 隔离 H2 连续测试经过实际任务服务：查询订单 → 选择 → 调整策略 → 旧方案失效 → 确认保存 → 发布 → 交主管 → 确认派工 → 指定操作员收到通知。模型选择及排程/资源输入由测试夹具控制；Mapper 和业务写入走真实实现，不能视为生产库上完成了全链路写入验收。

前端复测命令（在 `frontend` 目录）：

```powershell
node --test src/utils/workbenchRouting.test.js src/utils/agentWorkbench.test.js src/views/chat/AgentWorkbenchView.test.js src/components/assistant/ProductionOperationCard.test.js src/utils/agentInspector.test.js src/api/agentWorkbench.test.js
npm run build
```

## 当前边界

- 人员技能认证和完整个人班次没有在本轮适配器中补造；派工仍依赖原车间绑定、负载与设备校验。
- 写操作逐项确认，发布、提交主管和正式派工各自有独立回执；“继续”本身不授权自动写入。
- 编排有轮数和超时上限，遇到证据不足或冲突会停在询问/方案阶段。
- 任务锁适用于当前单实例主后端；多实例部署前需要分布式锁及持久任务恢复机制。

## 本轮主要文件

- `backend/src/main/java/com/upc/computer/assistant/v2/operations/ProductionTaskController.java`：SSE 任务与会话确认入口。
- 同目录 `ProductionTaskService.java`、`ProductionTaskModel.java`、`ProductionTaskTools.java`：状态、规划循环、原生模型工具协议和权限工具适配。
- 同目录 `ProductionOperationService.java`：复用预览，增加完整排程截止日期校验及调整表单。
- `backend/src/main/java/com/upc/computer/assistant/v2/runtime/EverydayChat.java`、`TaskToolStream.java`、`WorkbenchGatewayServer.java`、`TaskEventStream.java`：原生工具增量、模型服务桥接及真实事件。
- `backend/src/main/java/com/upc/computer/service/MesPlannerSchedulingService.java`：完整工序数量与完整周期修正。
- `frontend/src/api/agentWorkbench.js`、`frontend/src/views/chat/AgentWorkbenchView.vue`、`frontend/src/utils/agentWorkbench.js`、`frontend/src/utils/workbenchRouting.js`：保留原界面，接入任务流、跨轮上下文及会话绑定确认。
- 对应测试见上方命令；新增完整链路测试为 `backend/src/test/java/com/upc/computer/assistant/v2/operations/ProductionLifecycleDatabaseTest.java`。
