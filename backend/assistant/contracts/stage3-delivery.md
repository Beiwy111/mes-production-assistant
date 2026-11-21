# 第三阶段订单交付只读工具：实际实现契约

2026-11-08。实现为保守的首个可运行切片，不等同于规划 07 的全部字段和证据链已经完成。Java 只做认证、固定 SELECT、纯数量计算与证据复核；没有 Java Agent、RAG 或编排。

## 开关和身份

两个开关必须同时显式为 true：`assistant.v2.enabled`、`assistant.v2.tools.delivery.enabled`。缺省关闭。独立 `DeliveryToolConfiguration` 只扫描 `assistant.v2.mapper.delivery`，不改变原 Mapper 扫描或既有 v2 配置类。

每次请求必须携带现有 MES `Authorization: Bearer ...`：复用 `AssistantSessionResolver` 校验 JWT 和 Redis 登录会话，再通过 AuthService/RoleMapper 读取当前用户、角色。初版只允许 status=1 的 ADMIN，停用、角色变更、未登录均拒绝。请求体不能传 userId、role、scope 授权声明、SQL、表名或数量事实。

现有客户门户由 `CustomerPortalServiceImpl.resolveCustomerName` 的 session/user.customerName 与订单 customer_name 关联；该规则没有证明客户可以查看内部工单、公共库存和采购。因此本工具没有直接套用客户门户权限，也没有以创建人猜测订单所有权。其他角色和客户字段投影待独立落实。

scopeRef 是服务端生成、绑定当前 MES 用户和单个订单的内存引用，10 分钟过期，重启失效；它不是 Dify 跨服务运行凭据。不接受 Dify 自报的 agentId/role/run scope。最多 256 个有效 scope、4096 个 queryRef，容量满返回 429。所有调用都重新认证。

## 路径和请求

| 工具 | 路径 | 参数 |
|---|---|---|
| E0 | POST `/ai-tools/orders/resolve` | JSON：orderPk/orderNo 至少一个；二者同时存在须精确匹配；可选 targetDate、targetKind |
| E1 | GET `/ai-tools/orders/{orderPk}/delivery-context` | scopeRef |
| E4 | GET `/ai-tools/orders/{orderPk}/fulfillment-evidence` | scopeRef |
| E2 | GET `/ai-tools/orders/{orderPk}/material-readiness` | scopeRef、orderContextRef=E1.queryRef、fulfillmentQueryRef=E4.queryRef |
| E3 | GET `/ai-tools/orders/{orderPk}/production-execution` | scopeRef、orderContextRef=E1.queryRef、materialQueryRef=E2.queryRef |
| E5 | POST `/ai-tools/orders/{orderPk}/delivery-revalidate` | JSON：scopeRef、queryRefs（1–32个）、expectedScopeVersion=`1`、expectedCalculationVersion=`delivery-readonly-1` |

orderPk/所有 ID 是正十进制字符串；targetDate 是 ISO 日期；targetKind 是 FACTORY_SHIPMENT（默认）或 CUSTOMER_RECEIPT。日期优先级为明确目标、行交期、订单交期，返回 +08:00 次日零点 deadlineExclusive。缺日期返回 NEEDS_INPUT，仍可读取当前事实。

当前 scope 始终覆盖整张订单，取消行单独展示。暂不支持 orderItemPks、targetAt、dateExpression、历史重建、分页、resultRefs、taskStateRef。POST 中传未支持字段返回 400，不静默接受。E3 使用已登记 E2 查询引用 `materialQueryRef`，不是尚未实现的 Dify 专家 `materialResultRef`。这些差异必须在之后的 Dify 绑定中显式适配。

成功直接返回 JSON evidence envelope，不包原 Result。包含 schemaVersion、calculationVersion、status、scopeRef、queryRef、sourceFingerprint、observedAt/readStartedAt/readFinishedAt、calculationDate、data、evidence、completeness、missing、warnings。calculationDate 是本次读取开始时固定的 +08:00 日期，BOM 有效期选择和总指纹共用该日期，读取途中跨午夜也不改变。原数据库记录使用 snake_case，派生字段使用 camelCase；ID 和 decimal 都序列化为字符串。null=未知，不能显示为零。

当前各接口 envelope 均为 PARTIAL，因为完整质量/分配/历史覆盖尚未具备；具体确定的子字段保留 RECORDED/CURRENT_BOM_ESTIMATE 等等级。没有正向交付承诺。

错误使用真实 HTTP 状态：400 输入不支持/非法；401 无会话；403 非授权角色；404 scope/query/order 不可用；409 引用指纹或版本失效；429 容量；503 数据源不可用。错误不暴露 SQL/连接字符串。

## 已实现的业务能力

E0：精确解析订单、绑定 scope、选中有效行、展示取消行、按可信日期生成目标。订单头不返回客户电话、价格、地址。

E1：订单行、计划行与关联工单事实。缺历史计划版本/产出分配，计划覆盖净量和 unplannedQty 保持 null，避免计划行与工单重复计数。

E4：按订单行求记录已发货和剩余交付量。只纳入 SHIPPED/RECEIVED，按 delivery_id 去重；优先核对工单→计划行→订单行及物料/订单关系，无直接键时只有同物料在包括取消行的完整订单范围唯一才回落。同物料多行、破损关联、缺数量/单位、截断均保留未知。超发单列，不把 PREPARED 算已发。库存只展示原始事实，不把汇总 reserved 变为本订单预留。orderReadyQty/shippableQty/candidateReadyQty 均为 null；verifiedReadyLowerBoundQty=0 仅表示尚无通过核验的部分。

E2：明确关联工单的 MATERIAL_OUT 记录量；当前有效且唯一 BOM 版本下，用整工单目标×单位耗用×(1+loss_rate) 返回 demandBuckets.grossRequirementByMaterial，标 CURRENT_BOM_ESTIMATE。已完成/取消工单不产生新增毛需求。该值没有声称为冻结工单 BOM，也没有扣除历史全部领料后再减完工量。没有退料语义或非重叠新任务覆盖时净领料、整单需求、精确缺口保持 null。全部订单行当前剩余为零、相关工单全部关闭、范围完整时可给 NOT_NEEDED。共享库存 AllocationLedger 的纯计算保证同一库存不能同时完整分给成品和原料；HTTP 尚无合格公共池和订单归属证明，因此不会生成虚假的分配情景。

E3：读取真实路由工序、派工、报工与保存排程，区分工单 COMPLETED、CANCELLED、NOT_COMPLETED、IN_PROGRESS、UNKNOWN。完整范围下单工序只有一个有效派工和一条关联 CONFIRMED 报工时，采用该报工量；不存在报工时记录口径为零。多个派工/报工可能包含返工，缺物理去重键时不硬相加。按实际路由求记录口径瓶颈和末序完成量，不把各工序相加；不强行套八工序。工单完成/报工不证明质量放行或入库。排程为计划级已保存记录，不是实际完成时点或发货保证。

E5：每次重新读取范围内记录集合和关键投影字段，以排序、长度分隔的 SHA-256 比较服务端保存的查询指纹，检测插入、删除及无 updated_at 流水数量变化。总指纹也包含 calculationDate；跨 +08:00 日期时保守失效全部旧查询引用，即使数据库记录未变，避免旧 BOM 生效期计算继续被复用。单表 evidence 指纹仍只覆盖该表记录。E2/E3也校验上游指纹，旧引用和新库存不能混算。变化返回 CHANGED、invalidatedQueryRefs 和 replacementEvidence；当前已读字段与计算日期均未变时返回 revalidationStatus=PARTIAL、comparedFactsStatus=UNCHANGED，避免宣称未读取的质量/Redis也已验证。暂未输出逐字段 diff、Dify resultRefs 派生失效图。

## 数据范围和剩余限制

独立 Mapper 仅固定 SELECT，当前读取 customer_order、customer_order_item、delivery_order、production_plan_item（含计划头状态）、work_order、bom、inventory、inventory_transaction、purchase_order_item（含采购头）、process_step、dispatch_task、work_report、production_plan_schedule。物料范围限订单成品及直接 BOM 子料；多级 BOM、make/buy、环与版本快照未完成。库存/流水/采购没有放开全厂自由查询。

每表 LIMIT 1001，1000 为完整计算上限；超限标 SOURCE_RECORD_LIMIT_1000_EXCEEDED 并禁用完整数量计算，不把页面小计称整单。一次查询使用只读 REPEATABLE_READ 事务和15秒事务超时。跨 API 不宣称同一数据库事务；用引用指纹检查依赖变化。

仍需完成：订单/工单预留明细或可证明分配；质量放行与 PRODUCT_IN/当前库存核对；退料业务语义；多级 BOM/make-buy；多派工/返工去重；资源日历；历史/签收证明；客户/业务角色投影；Dify运行授权、resultRef登记；持久化跨实例证据；精确变更diff。现阶段仅可作为管理员只读事实分析接口，不可用于库存预留或自动业务动作。

未启动 MES/数据库；后续在明确只读核验授权下，利用已运行的本机 MySQL，在 READ ONLY 事务中对一个已存在订单执行了全部14个Mapper SELECT投影（resolve使用主键分支），全部成功并回滚。只输出方法计数/列数，不保存业务记录或凭据。该核验支持当前运行库字段和基本关联SQL兼容性；不代表全部数据场景、执行计划、Java事务代理、Redis会话与Dify跨服务链路已验证。记录见 `docs/ai-planning-2026-11-07/verification/stage3-delivery-readonly-db.json`。
