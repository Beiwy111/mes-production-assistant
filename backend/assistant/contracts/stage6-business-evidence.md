# 七Agent真实业务证据与场景契约

2026-11-09。扩展第五阶段独立只读服务；原MES入口、登录权限和业务写流程不变。Java查询真实数据库、Python提供公开RAG/GraphRAG、Dify调用专家。本文中的实现完整性与数据库记录完整性分别表示，不能把未知值当0。

## 场景输入

维护启动器 POST `/stage5/runs` 增加可选 `workflowProfile`，其余认证、白名单、十分钟有效期、scope和结果引用规则沿用第五阶段。

| profile | 必需专家 | 用途 |
|---|---|---|
| DELIVERY（默认） | ORDER → SUPPLY → PRODUCTION | 当前交付准备分析，可追加质量等专家 |
| ORDER | ORDER | 订单、计划、履约记录 |
| SUPPLY | ORDER → SUPPLY | 需求范围、采购与库存情景 |
| PRODUCTION | ORDER → SUPPLY → PRODUCTION | 工单、实际路由、派工报工 |
| QUALITY | QUALITY | 检验项、不合格记录及公开缺陷方法 |
| EQUIPMENT | EQUIPMENT | 关联设备、报警、维修 |
| AFTERSALES | AFTERSALES | 案件进展与业务路径追溯 |
| FULL_ASSESSMENT | ORDER → SUPPLY → PRODUCTION → QUALITY → EQUIPMENT → AFTERSALES | 六域综合分析 |

场景由私有启动器固定，模型不能修改订单范围、场景、凭据。专家只持有本角色密钥，仍从 `POST /stage5/runs/{runRef}/tasks/{ROLE}/begin` 取得证据，再经 `/complete` 登记解释和服务端回执。任意SQL、任意表名、客户端数量和身份均不支持。

示例：`New-AgentPreviewRun.ps1 -OrderPk 100 -Profile FULL_ASSESSMENT`。将生成的run_ref交给同一个Dify总协调应用；不需创建另一套Agent。用户问题只用于解释任务，不改变授权。

## 质量、设备、售后实际接口

新Mapper仅在独立运行服务注册，新增14条SELECT，每条最多1001行，1000为完整计算上限。所有数据纳入服务端指纹；Q/D/A变化可使此前E0–E4和领域引用同时失效。

| 角色/证据 | 主要数据来源 | 输出 |
|---|---|---|
| QUALITY / Q1 | quality_inspection、quality_inspection_item、nonconforming_product | inspections、inspectionItems、nonconformingProducts、逐次samplePassRate、记录数、visionEvidence |
| EQUIPMENT / D1 | scoped dispatch_task/work_report设备关联、equipment、andon_alarm、equipment_maintenance_record | equipment、observedUsage、alarms、maintenanceRecords、关联类型与计数 |
| AFTERSALES / A1 | after_sales_case、after_sales_task、after_sales_closure、订单工单及发货/检验/实际领料/入库、material_batch | cases、tasks、closures、tracePaths、caseRecordStatus、tracePathsComplete |

主关联链为订单行→计划行→生产计划→工单，并核对订单、计划、物料一致性。领域记录显式外键互相矛盾时保留CONTRADICTORY标记，不当作确认路径。不输出客户联系方式、供应商联系方式、采购成本或操作人员身份。数据库自由文本仍是待解释的数据，不是Agent指令。

领域data.status：RECORDED=有记录，EMPTY_RECORDED=查询成功但范围内无记录，PARTIAL=范围截断等不完整。外层envelope保持PARTIAL，体现整体业务证明边界；查询错误返回503，不能转成空记录。

质量只计算单条检验的合法样本合格率；重复/冲突记录不重复计数，复检不能直接相加当批产出。抽检不能证明整批放行。当前YOLO检测接口没有按质检单持久化的原始模型版本、检测框和置信度表，visionEvidence明确SOURCE_RECORD_MISSING；不伪造历史结果。

设备当前台账、派工计划、实际报工分别展示。只有工单/派工显式关系是直接关联，单凭相关设备历史报警是候选关系。维修或报警记录不能推出因果、故障概率或过去每个时刻的状态。

售后路径每条带sourceRecordRefs（表名:主键）与EXPLICIT/CANDIDATE。无售后案件时仍能返回已有订单生产路径；caseRecordStatus独立为EMPTY_RECORDED。路径上限100，达到上限明确tracePathsComplete=false；显示端另有限量，不把显示的小计当总计。实际领料不依赖当前BOM，避免漏掉历史投入的物料；批次文字匹配仅作为候选。此处路径由实时SQL关系核验，公开论文图谱继续来自Neo4j，两种来源不混同。

## 剩余交付、成品准备和材料估算

剩余交付沿用既有E4：`max(记录订购量 − 合法SHIPPED/RECEIVED数量, 0)`，按delivery_id去重，关联和单位不明确则未知；超发单列。

新增candidateReadyQty是**已核验库存条件下的分配情景**：

1. NORMAL库存的可用量必须等于在手量减已预留量，数量均非负。
2. 物料/批次/库存键一致；全部流水可解释为PRODUCT_IN和SALE_OUT且净额等于当前在手。调整、混合来源、未知工单或关系冲突时不冒充核验成功。
3. 入库对应当前订单的LINKED工单；每工单/物料/批次只有一份FINAL/PASSED/QUALIFIED检验，样本数=合格数=该批所有库位入库总量、不合格数0，且没有对应不合格品记录。抽检或复检不满足这一严格情景。
4. 同物料多订单行按主键顺序共享同一个池，候选量=`min(行剩余交付量, 尚未分配合格池)`，不重复占用。

没有订单预留明细，因此candidateReadyQty不能升级为shippableQty。只在已无剩余需求或完整记录证明无在手库存时确认shippableQty=0；其他情况保持未知。场景不执行库存占用，也不授权发货。

材料新增estimatedGrossRequirementQty、estimatedRequiredUnissuedQty、scenarioPublicAvailablePoolQty、scenarioAllocatedQty和scenarioGapQty：

- 毛需求情景：每个未完成且未取消的LINKED工单目标量 × 当前唯一有效BOM单位耗用 × (1+损耗率)，按物料合并。
- 未领料情景：`max(上述毛需求 − 对应未关闭工单记录领料, 0)`；已关闭工单的领料不能冲抵新工单需求。
- 可用池情景：NORMAL库存available合计，先扣除同一成品分配情景已使用的数量。
- 分配量=`min(未领料情景, 剩余公共池)`；情景缺口=`max(未领料情景−分配量,0)`。

这些数值均带CURRENT_BOM_SCENARIO及假设：当前BOM非冻结工单版本、无未记录退料、开放工单确需执行、NORMAL库存尚非质量放行证明、公共池尚未预留。多版本、未知单位、破损关联或截断时情景值也未知。confirmedShortageNow继续保持未知，直到真实业务源能证明冻结需求、退料和订单预留。采购记录单独展示，不把采购在途当在手库存。

## 最终输出

交付/综合场景仍以INSUFFICIENT_EVIDENCE表述当前未能证明交期；独立领域场景必需专家完成且证据复核未变化时返回EVIDENCE_READY，含义是查询已完成，绝非业务成功。缺专家INCOMPLETE，数据变化DATA_CHANGED。

facts新增quality/equipment/aftersales，expertExplanations保留六专家解释但不参与数量计算。DATA_CHANGED隐藏旧领域事实和旧解释。Dify按角色字段白名单取上下文，结构化输出展示情景假设、查询时间和引用；公开研究仍走现有Python RAG/GraphRAG知识工具。

## 不改变原有业务

未新增业务写接口、未改业务表、未切原助手入口。当前为可信本机维护预览，正式MES接入仍需绑定真实登录用户和角色字段权限；不能把这些本机维护凭据发给普通用户。API配置沿用本地私有文件，不需要新增模型供应商密钥。
