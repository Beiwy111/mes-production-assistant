# 自然语言下单（2026-11-14）

## 使用方式

按原方式启动 MES 和前端，再双击项目根目录 `Start-Assistant.cmd`。在 `http://localhost:5173/assistant` 输入“我想下单100台显示器”，按追问补充信息或填写聊天中的草稿。

1. 总协调识别需求，订单 Agent 提取本轮新增信息。
2. 从真实成品目录中选择产品；可以继续说“改成200台”“地址改为……”。
3. 填齐客户、型号、数量、交期、收货人、电话和地址，核对草稿。
4. 点击“确认并创建订单”才会创建真实订单；文字“确认”不会自动提交。
5. 成功后显示原业务服务返回的订单号，进入原有待审核流程。点击取消只取消助手草稿。

本版每个会话办理一张单型号订单。已提交、已取消或过期后，如需再下一单请点击“新对话”。草稿每次有效修改后保留24小时；提交结果可从服务端恢复。要求交期属于用户需求，不代表已承诺交付。金额沿用原系统订单服务口径（目前使用产品标准成本），由订单审核员核定，不在助手中冒充正式报价。

## 权限与持久化

- CUSTOMER：使用账号绑定的客户身份，经原 CustomerPortalService 下单，不能修改客户归属。
- ORDER、ADMIN：明确填写客户名称，经原 MesWorkflowService 建单，不冒用客户账号。
- 原库存、生产、质量等全域查询仍限 ADMIN。订单识别暂不可用时，管理员可回到原只读查询路径，写操作不自动重试。
- 所有写入口重新核对真实用户/角色状态。请求中的角色、客户权限、用户ID不能覆盖认证。
- 新增 `assistant_order_draft_v2`，不修改业务表结构。草稿锁、业务订单创建及提交结果在同一 SQL 事务内。确认时锁定所选产品，复核版本、状态和产品信息；重复确认返回原结果。
- 收货信息继续兼容原订单备注字段，合并内容在保存草稿前检查500字限制，不静默截断。完整草稿在助手表中保留。
- 浏览器持久历史只保留草稿引用，收货人、电话、地址及相关聊天正文不写入持久历史。恢复时按当前身份重新读取服务端；停止等待不等于撤销已经开始的确认提交，应刷新草稿查看结果。

## 接入与配置

本机已使用现有 MySQL、Dify SUP 应用密钥与 DeepSeek，无新增 API Key。

首次部署新数据库环境执行 `python backend/assistant/deploy/Prepare-OrderDraftsLocal.py`（需要 pymysql，当前电脑已安装），从既有 `.env.agents.local` 读取凭据，仅执行 `order-drafts.sql` 的助手表创建。不要把凭据放入聊天或提交Git。

Spring 新接口在 `backend/src/main/java/com/upc/computer/assistant/v2/orders`：

| 接口 | 用途 |
|---|---|
| GET `/assistant-v2/orders/status` | 当前身份的下单入口状态 |
| POST `/assistant-v2/orders/turn` | 识别需求、补充草稿或返回查询分流 |
| GET `/assistant-v2/orders/drafts/{id}` | 恢复当前用户的最新草稿/结果 |
| POST `.../{id}/revise` | 按版本修改明确字段 |
| POST `.../{id}/confirm` | 按版本确认，只接受版本和请求编号 |
| POST `.../{id}/cancel` | 取消草稿 |

识别请求由 Spring 转发到18083的 `/assistant-workbench/order-intake`，使用现有登录身份。Dify SUP增加可选 `order_intake` 分支：SUP意图LLM → 校验 → ORDER字段提取LLM → 校验。两个角色节点均实际调用模型，但位于同一SUP工作流内；未调用独立的旧ORDER查询工作流，也不向模型开放写工具。提交阶段由业务服务执行，界面如实标记为“业务服务”，不伪造Agent调用。

原查询分支保留。更新DSL使用既有 `Manage-Stage5Dify.py deploy --roles SUP`；重新生成用 `build_stage5.py --role SUP`。

网关构建独立放在 `assistant/rag/data/workbench-build/classes`，与18082运行时隔离；`Start-WorkbenchLocal.ps1 -Build` 使用独立目录，已存在时一键启动自动采用它。原后端新增接口通过用户正常执行 `mvn spring-boot:run` 加载。

## 本轮验证

- Java相关测试47项通过：订单字段规则、当前角色校验、草稿版本/归属、重复确认、网关及HTTP边界。
- 真实MySQL验证：客户门户和管理员MES建单都在外层回滚事务中验证；管理员路径核对实际订单明细的产品ID；并发确认用助手专属测试行验证只执行一次。无测试业务订单保留。
- Dify实际运行验证数量、收货信息/日期、查询分流、取消、文字确认；执行记录包含真实的两个LLM节点，不调用写工具。
- 浏览器实际走通：100台需求、产品选择、补齐信息、未保存字段与产品一起修改、自然语言切回型号、确认按钮、历史恢复、取消、移动端布局。测试停在提交前并取消草稿。
- 原库存查询仍返回物料供应Agent实际步骤和库存表格，历史与移动端回归通过。
- 前端构建通过，仅原有依赖注释、动态导入及大分块提示。最终构建产物放在忽略的 `backend/assistant/rag/data/order-ui-build`。此前界面验证产物的清理被自动审批拒绝，因此保留在 `frontend/.order-verification-dist` 和 `.order-verification-build.log`，不参与运行。

浏览器联调报告、截图和Dify检查结果位于忽略目录 `backend/assistant/rag/data`。测试不自动确认真实订单，不修改现有业务状态。
