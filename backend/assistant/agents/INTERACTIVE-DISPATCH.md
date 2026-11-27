# 交互式工序派工（2026-11-27）

## 使用
同一会话保存计划后说“给这个计划安排派工，空闲人员优先”。也可提供 PP 计划号。
助手读取计划工艺和人员实际负载，准备完整工序分配；可继续说“主板装配改给白操作，其他不变”“不要安排某人”“只能使用没有任务的人”。
严格空闲条件没有可用人员时，应说明工序缺口并询问调整。允许排队也不代表可以立即开工。
核对新方案后点击确认，旧方案在调整后不可再确认。

## 业务约束
- 只读预览调用原 MES 工艺、设备、人员、派工数据；无任务优先，遵守现有车间/工序绑定。
- ASSIGNED 仍算在手任务；ACCEPTED / PRODUCING / RUNNING 不可重复安排进行中任务；关闭/完成工单不占用人员。
- 不将账号在岗误称为有排班或技能认证。现有数据没有完整人员班次和技能证书。
- 工序→账号覆盖、排除名单、严格空闲规则存入用户/角色/会话隔离的 Redis 任务上下文；修改一行保留其他约束。
- 草稿计划可提前生成分配。具有发布与派工权限的管理员可确认一体方案：发布、提交主管、生成下达工单、逐工序派工。生产主管不能隐式发布草稿。
- 确认前仍执行原快照校验、计划行锁、重复派工检查、人员/设备复核、事务和审计。取消或预览不会下发业务任务。
- 操作员收到自己的持久化 dispatch_task；通知缓存丢失时，收件箱从本人的待接收任务恢复提示。已送达工作台不等于本人已读或已接单。
- 沿用白色界面、SSE 和统一 Agent 事件；派工卡直接展示所有工序、人员、负载、设备、数量、安排依据和提醒。

## 关键文件
- operations/ProductionTaskService.java：上下文调整、旧提案失效、重复失败调用限制。
- operations/ProductionTaskTools.java / ProductionCommand.java：人员负载工具和明确的 IDLE_FIRST / IDLE_ONLY 策略。
- operations/ProductionOperationService.java / ProductionDispatchSelection.java：草稿派工预览与覆盖校验。
- runtime/EverydayChat.java：动态查询、工具选择、人员规则及最终回答。
- service/MesDispatchRecommendService.java：真实负载、候选约束和冲突说明。
- service/MesWorkflowService.java / ai/action/AgentActionExecutionService.java：受确认的执行链和个人任务回执。
- frontend/src/components/assistant/ProductionParameters.vue / ProductionOperationCard.vue：全工序分配表与对话调整提示。

## 配置
无需新增 API Key、Dify 配置或数据库迁移。主后端和独立助手网关需加载新增代码。

## 验证说明
测试日志在 backend/assistant/rag/logs/dispatch-*，本机真实预览结果在 backend/assistant/rag/data/interactive-dispatch-check（Git 忽略）。
执行测试在隔离 H2 数据库中写入，模型选择和部分资源是测试夹具；不把它称为真实生产下发。
本机真实数据只验证读取、预览、调整和取消；不替用户确认 PP202611005。


## 本轮实际结果
- 后端 50 项针对性测试全部通过；含隔离 H2 的两名操作员任务落库、收件箱隔离、缓存丢失后恢复、重复确认以及草稿一体确认。
- 前端 121 项针对性测试全部通过，Vite 生产构建通过（已有打包提示保留）。
- 真实 API / LLM / MySQL：PP202611005 初步方案 8 道工序；主板装配由李操作改给白操作后，另外 7 道工序保持一致。方案只取消，未确认。
- 严格只用无任务人员：真实主板装配无人满足，返回实际缺口；同一会话说允许待接收队列后，生成完整 8 道工序方案。
- Playwright 1440×1000：实际聊天两轮都返回 8 行派工；实际 SSE 分块接收，9 个可追溯协作条目，无 pageerror，无整页横向溢出。
- 数据库复核：PP202611005 为 DRAFT，关联工单 0、派工 0。
- 截图：本机 ignored 路径 backend/assistant/rag/data/interactive-dispatch-check/proposal.png 和 adjusted.png。

## 2026-11-27：选择新生产后计划不存在与初排中断修复

### 实际根因
- 本机 MySQL 中不存在截图里的 PP20261127001；CO20261115002 也没有已保存计划。失败实际为“计划不存在”，通用错误文案却显示“查询失败或权限不足”。
- 会话曾出现没有执行回执的保存/发货文字，不能把它当作下一步操作的真实编号来源。
- 100 台、2026-11-27 开始、DELIVERY 策略的计算完工日为 2026-12-24，不能满足用户指定的 2026-12-07。原交期校验有效，不能为了生成派工绕过。
- 只有 PP 计划可预览人员的旧路径，导致尚未保存计划时看不到人员设备初排；短答“新生产”还会被模型反复引回库存建议。

### 增量实现
- ProductionTaskTools：orderContext 返回同一订单真实 existingPlans / hasSavedPlan；新增只读 orderStaffing，受原生产计划权限、参数白名单和事务超时约束。
- MesDispatchRecommendService：抽取共用人员/设备推荐与校验；按订单真实产品和路线初排，缺路线或多产品不猜测。不会生成虚拟 PP、工单、派工或锁定资源。
- ProductionTaskService：失败事件保留业务错误原因；历史助手文字不再作为规划输入中的执行依据；保存计划预览时附带只读人员初排；同一订单调整日期时保留数量、起始日和策略。
- “新生产”等简短明确选项视作当前任务的补充回答，从最新核验订单重新读取数量并预览；否定表达不自动转生产。最新订单选择优先于旧订单草案，编号仍由数据库核对。
- EverydayChat：区分真实已有计划、预览与执行回执；无已保存计划时按 CO 核对并准备计划，禁止拼造未来编号。
- ProductionOperationCard / workbenchRouting：展示完整初排表，标明未下发；短选项直接续接当前生产任务，保持白色 UI 和现有事件/逐字输出。

### 实际验证
- 后端 40 项针对性测试通过：TaskService 17、Tools 2、Operation 13、Availability 5、LifecycleDatabase 2、OperatorDispatchReceipt 1。生命周期写入只在隔离 H2 中验证。
- 前端 66 项测试与 Vite 构建通过。
- 真实 MySQL / LLM / SSE：核对 CO20261115002 → 新生产 100 台、截止 12 月 7 日 → 返回交期冲突及 8 行人员设备初排 → 对话改为 12 月 31 日 → 保留订单/数量/策略，返回 PENDING。测试预览已取消。
- 真实短回复：核对该订单 → “新生产” → NEEDS_INPUT 策略选择及 8 行人员设备初排，不再重复追问生产还是库存。
- 原有真实计划 PP202611005：整单 8 道工序 → 主板装配改给白操作 → 其他 7 道保持一致。只预览、取消，没有下发。
- Playwright 初排场景：1440×1000，8 行完整表格、22 个实际网络分块、11 个实际协作条目，无 pageerror 或整页横向溢出。
- 数据库复核：CO20261115002 保存计划数仍为 0；PP202611005 仍为 DRAFT，工单数和派工数均为 0。
- 本机日志/截图：backend/assistant/rag/data/dispatch-new-production-check/（忽略提交）。

初排仅反映当前岗位、在手任务和设备数据，不承诺未来班次已锁定。日期可行后先确认保存计划，再对真实计划号重新核对人员并确认下发。没有新增 Key 或数据库结构变更。

最终浏览器连续对话复测：同一页面先核对订单再只发送“新生产”，COMPLETED → NEEDS_INPUT，8 行初排，实际 SSE 接收分别 61 / 9 个分块；无页面异常或横向溢出。截图 short-choice-staffing.png，报告 browser-short-verification.json。
