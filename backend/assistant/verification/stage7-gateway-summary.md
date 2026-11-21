# 新助手工作台 Java 网关验证（2026-11-09）

## 本次实现

- 新独立入口 `WorkbenchRuntimeMain`，只监听 `127.0.0.1:18083`；不加载 Spring、不修改原 MES 助手或原 Dify 配置。
- 每个请求先向现有 `8088/auth/userInfo` 验证 MES 会话，再通过独立固定 SELECT 查询用户、角色实时状态和 ADMIN 角色。浏览器不获得 BOOTSTRAP、数据库或 Dify 应用密钥。
- 订单使用原显式白名单，八个 profile 必填。运行按实际 MES 用户 ID 创建证据 scope，GET/cancel 校验归属，越权返回 404。
- 采用 Dify SUP **公开应用 API** `/v1/workflows/run`，输入严格匹配已发布 DSL：`run_ref`、`query`。服务端不根据文本替代 Dify 调度。
- 新 BOOTSTRAP-only `/stage5/runs/{ref}/observe` 返回真实已开始/已完成/已取消的领域任务及最后一次 finalize 结果。SUP 状态来自实际 Dify 生命周期；未执行领域不生成进度。
- 流式输出使用扁平 NDJSON；reply 来自 workflow outputs.answer，运行引用/verdict 必须与运行时复核结果匹配。facts、missing 等只从运行时取值，不采用模型 decision 中可伪造的数量。
- 限制两条并发、64 条运行/请求记录、10 分钟 TTL、单次 5 分钟。32 KiB 输入、2000 字问题、固定 profile、严格 JSON、重复请求保护。失败创建也保留幂等记录，避免超时后重跑。
- 显式取消、断开和截止均尽力取消运行时并停止 Dify task；不自动重试上游。内部异常与凭据不会出现在客户端错误。

## 自动验证

`mvn -f backend/pom.xml -Pquality test -q`：最新复核退出 0；Surefire 合计 **247 项，0 failures、0 errors、0 skipped**。

新增网关服务器测试 12 项、HTTP 适配测试 5 项、只读身份工厂测试 1 项；Stage5 运行时测试共 15 项，含新增 observer 权限/任务状态与实际用户身份 scope 用例。覆盖认证失败、实时角色复核、输入/正文上限、显式范围、请求幂等、失败创建不重试、容量/TTL、用户归属、真实任务/最终事实、SUP 生命周期、超时、取消及真实 TCP 客户端突然断开，以及下述复核修复。

RED/GREEN 记录：observer 新测试先出现 404（预期403）；新网关/HTTP/main 测试先因实现类缺失不能编译；补充 SUP 完成事件与失败创建幂等测试分别先断言失败，再修复通过。完整套件中现有否定配置测试会产生预期 Spring bean 初始化警告，最终没有测试失败。

## 本机启用情况

- 私有 `.env.workbench.local` 已创建并通过 `git check-ignore` 验证；用户填写密钥后启动日志只报告 `Dify configured=true`，未输出密钥。
- 已核验旧 Stage5 PID25080 的 java.exe 命令行、专用 args 文件末尾主类及 18082 监听归属，再仅重启该独立 JVM。新 Stage5 PID26852。
- 新网关 PID64096，监听 `127.0.0.1:18083`。启动脚本默认隐藏窗口、拒绝占用端口、不会停止已有进程。
- 对运行中的 18083 再次执行启动脚本，得到预期的占用端口拒绝；前后监听 PID 都为64096。PowerShell 解析检查也通过。
- 原 MES `8088` PID3708 保持不变。匿名 `/auth/userInfo` HTTP200/code401，匿名网关 `/status` HTTP401。
- 真实浏览器登录与 Dify 业务运行由主任务执行，结果请以主任务联调记录为准。本文件的自动测试不会调用真实模型或写入业务数据。

## 联调发现及复核修复

- 完成竞态：工作流可能在最后一次常规任务观察之后完成，旧实现会缓存仍为 RUNNING 的领域 Agent。新增回归先复现 `COMPLETED` 预期而实际 `RUNNING`，修复为成功取得结果后、发送 SUP 完成与结果前再次读取权威任务状态。
- 数据变化：`DATA_CHANGED` 时清空网关缓存 Agent 的 summary/evidence，并发送显式空值替换事件，避免 owner GET 恢复旧证据。新增测试先复现旧证据仍有1项，再修复通过。
- 安全错误码：`Future.get()` 的 ExecutionException 曾掩盖已清洗的依赖错误码。现在仅解包内部固定 Failure code，不输出原始异常；对应回归已先失败后通过。
- 主任务真实 DELIVERY100 成功；第一次 FULL100 的 Dify 本身成功、网关却中断。经公开应用 API 只读获取该既有执行结果，answer 11260 字符、decision 52498 字符；精确最终事件与实际 observer 离线重放通过，因此排除了最终结果验证错误。
- 主任务只读核查 Dify 节点：FULL `agent_node` outputs253106字符 + inputs12911字符，未加 SSE 包装已超过旧262144字符限制。仅将 SSE 单行/单事件上限调整为1,048,576字符，累计仍为4,194,304字符；其余请求、依赖响应和浏览器事件限制未变。300000字符中间事件通过、超过新界限返回固定 `DIFY_EVENT_LIMIT` 的测试先失败后通过，中间诊断内容不输出给浏览器。
- 247项完整质量测试通过后，核对专用 PID/args/main/监听归属，仅重启网关。**当前网关 PID38600**；Stage5 PID26852、MES PID3708 均保持不变。真实 FULL 重试由主任务执行。

## 接口与限制

配置及完整接口说明见 `deploy/WORKBENCH-SETUP.md`。GET run 返回 `{runId,status,agents,result?}`，与流式 `{type:"result",data:{reply,...}}` 区分。状态接口的 `available` 表示配置齐备，不代表上游健康探测已通过。

取消属于尽力停止；已开始的只读 SQL 可能结束后才收到停止。状态、幂等记录仅在当前 JVM 内存保存；重启后不会恢复。当前允许已有 ADMIN 访问原订单白名单，未扩展普通角色。网关启动时读取应用密钥，修改密钥需要仅重启该独立网关。

未执行 git commit、reset、原 MES 重启或业务写操作。
