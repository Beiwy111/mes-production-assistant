# 七角色与 Dify 资产

[catalog.yaml](catalog.yaml) 已记录第五阶段的独立Dify预览应用及工具清单，`enabled=false`继续表示MES入口未切换。它不是用户授权注册表；实际运行权限由独立Java服务的凭据、订单白名单、任务依赖与证据引用验证。2026-11-09已完成订单101的真实三专家交付链和六专家扩展链；公开知识调用通过，交付证据仍为PARTIAL。

| 角色 | 负责 | 不负责 |
|---|---|---|
| SUP 总协调 | 分派、补查、汇总与解释 | 直接算库存或执行生产动作 |
| SUPPLY 采购库存 | 采购到货、领料、物料缺口、仓储和可发货资源 | 批准质量放行 |
| ORDER 订单计划 | 交付需求、订单与计划覆盖 | 修改排产、重复定义数量算法 |
| PRODUCTION 生产调度 | 工单、八道工序、派工与进度约束 | 改写库存或代替质检 |
| QUALITY 质量检测 | 检验、标准、可信 YOLO 结果、缺陷线索 | 根据语言推断直接放行 |
| EQUIPMENT 设备保障 | 设备、报警、维护及影响候选 | 自行修改排程 |
| AFTERSALES 售后追溯 | 案件到交付/批次/生产/检验的关系证据 | 把相关关系宣称为确定根因 |

只有 SUP 调用子工作流；子角色通过 collaborationRequests 请求补查，不直接相互调用。订单交付先使用 ORDER/SUPPLY/PRODUCTION 三专家；其他场景按需调用，辅助校验节点不计为新业务 Agent。

- [prompts](prompts/README.md)：第五阶段七份按角色划分的提示词。
- [workflows](workflows/README.md)：生成并部署的Dify工作流；发布图与本地DSL核对。
- [本机绑定](bindings.stage5.local.json)：实际应用/工具ID，不含密钥。
- [bindings.example.json](bindings.example.json)：绑定文件形状示例，没有环境 ID 或密钥。

订单交付固定ORDER→SUPPLY→PRODUCTION依赖；SUP实际选择专家，后端验证依赖。QUALITY/EQUIPMENT/AFTERSALES已接Q1/D1/A1真实只读证据，支持独立领域与六域综合场景；无记录、来源缺失、错误各自区分。运行方式见[第五阶段指南](../deploy/STAGE5-AGENTS-SETUP.md)，计算和场景见[业务证据契约](../contracts/stage6-business-evidence.md)。
