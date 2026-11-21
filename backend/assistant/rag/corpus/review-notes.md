# 项目知识卡审核说明

整理日期：2026-11-08。范围：当前工作区源码现状，含工作区已有未提交修改；不是某个已发布 Git 提交或真实工厂资料的认证。

`project-cards.json` 是 UTF-8 JSON 数组，共18张卡。所有卡均为 `authority_type=PROJECT_IMPLEMENTATION`、`review_status=DRAFT`。本次仅整理可定位证据，不标记人工已审核，不自动发布、生成向量或写入 Qdrant/Neo4j，不修改公开论文的 `sources.json`。后续导入必须拒绝把 DRAFT 当作已发布检索证据；不能因文件存在而自动批准。

规划依据：`docs/ai-planning-2026-11-07/planning/02-knowledge-design.md`、`09-qdrant-neo4j-model-roadmap.md`。规划确定卡片范围，事实陈述仍逐项核对当前源码。规划中质检规则曾使用 `PROJECT_DEMO_RULE` 描述性质；本次按第三阶段数据约定统一使用 `PROJECT_IMPLEMENTATION`，并在正文及限制中明确默认阈值只是项目演示实现，不提升为厂家或行业标准。

## 卡片清单

| ID | 标题 | 知识域 | 人工确认重点 |
|---|---|---|---|
| MES-PROJECT-001 | 项目全流程与八道生产工序 | mes_process | 实际路线版本、缺工序及名称匹配歧义 |
| MES-PROJECT-002 | 项目角色工作台与职责边界 | mes_process | 岗位职责、服务端授权及数据范围 |
| MES-PROJECT-003 | 第1工序：主板装配 | mes_process | 作业说明、人数与焊接条件 |
| MES-PROJECT-004 | 第2工序：电源板装配 | mes_process | 共用作业文案、电源调试依据 |
| MES-PROJECT-005 | 第3工序：接口板装配 | mes_process | 板级检查与成品接口检查的区分 |
| MES-PROJECT-006 | 第4工序：显示屏加工 | mes_process | 初检内容、面板型号、测量条件 |
| MES-PROJECT-007 | 第5工序：面板贴附 | mes_process | 压力、温度、精度及实测依据 |
| MES-PROJECT-008 | 第6工序：外壳装配 | mes_process | 与贴附共用文案是否准确 |
| MES-PROJECT-009 | 第7工序：整机组装 | mes_process | 每机2人配置与实际派工关系 |
| MES-PROJECT-010 | 第8工序：支架底座装配及终检入口 | mes_process | 共用整机文案、末道定义、返修例外 |
| MES-PROJECT-011 | 采购缺料、可用库存与在途口径 | mes_process | 库存空值回落、在途状态、预留归属 |
| MES-PROJECT-012 | 订单、计划与主管派工的交接状态 | mes_process | 提交主管即写approvedAt的真实含义 |
| MES-PROJECT-013 | 质检通过、批次放行与成品入库交接 | mes_process | 整批放行算法、数量兜底、运行态缺失 |
| MES-PROJECT-014 | 五步检验界面、正式检验模板与默认阈值出处 | mes_quality_rules | 规则快照、刷新率、编号冲突、测量方法 |
| MES-PROJECT-015 | YOLO视觉检测与报告的证据边界 | mes_quality_rules | 实际权重、Mock识别、持久化与对象绑定 |
| MES-PROJECT-016 | 复检、不合格品与判定建议流转 | mes_quality_rules | 警告放行、复检数量与实物处置证据 |
| MES-PROJECT-017 | 售后追溯中的可靠键与候选关联 | mes_process | 多批次、模糊供应商、报警窗口、LIMIT结果 |
| MES-PROJECT-018 | 工序产量、末道送检与检验数量的不同口径 | mes_process | 瓶颈量/末道量/抽检量/放行量/入库量分离 |

## 必须保留的审核缺口

1. 八工序以 `ProductionWorkshopCatalog.PRODUCTION_STAGES` 为依据，并对照操作员前端目录；五步质检工位是另一套仿真界面。整机组装为第7工序，支架底座装配为第8工序。外壳装配与面板贴附、支架底座装配与整机组装分别共用任务描述，不能擅自改写成新SOP。
2. `DisplaySpec.refreshStd()` 调用的 `is165hz()` 固定返回false；165Hz、240Hz识别没有完整传入精确刷新率规则。应审核或修复业务代码后另建知识版本，本次不修改业务实现。
3. 成品外观编号使用 `FP-` 加项目数量；仅HDR而非4K/高刷时，HDR和外观可能同为FP-14。跨型号规则应同时绑定名称、适用条件、检验单及规则版本，不只依赖itemCode。
4. 五步界面的漏光与屏闪包含名称匹配；UNIT矩阵可恢复通过/失败位串，样本编号取批次末8字符加序号。这些不证明独立仪器实测或全链路唯一SN。
5. `generateDefaultItems` 会先删除旧项目；审核后发布需保留不可变规则版本和检验单历史快照，不能让当前代码默认值覆盖历史解释。
6. 现有阈值、来料默认抽样、整批放行及低数量兜底均待业务核验。公开显示缺陷论文不能提供本工厂焊接温度、贴附压力、螺钉扭矩、抽样方案、均匀性测量公式或良率目标。
7. `finishedGoodsQty` 是八工序瓶颈，当前送检使用末道完成量。累计送检、抽样量和放行量存在不同入口及回落规则；必须核对真实记录和调用入口，不按名称推定等价。质检通过创建待入库任务，实际入库需库存事务证据。
8. `calculateRequirements` 会写采购需求工作台，不能把它包装为只读工具。实时数量从受控只读API查询，本文件不保存业务当前余额。
9. 售后映射存在供应商LIKE、设备时间窗口、最近发货回落、首条成品入库LIMIT及来料检仅按物料ID筛选。保留候选性质与链路缺失，不将关联直接表述为根因。
10. 未填写审核人、生效日期、工厂标准编号或人工批准标记，因为尚无真实审核证据。正式发布前须补齐审核人、产品适用范围、规则版本、生效时间、发布清单和权限元数据；不能仅将DRAFT字符串改名后发布。

## 来源核验方法

每个 `source_files` 条目使用仓库相对路径、**从1开始且包含两端的行号**以及**源文件完整原始字节的SHA-256**。哈希不是节选文本哈希；即使只改变换行符也会失配。生成时读取当前源码全文计算哈希，检查每个行号范围合法，再重新读取JSON校验18个唯一ID、限定知识域、统一DRAFT状态和全部来源哈希。

在仓库根目录用PowerShell复核（只读，不安装依赖）：

```powershell
$cards = Get-Content -LiteralPath 'backend/assistant/rag/corpus/project-cards.json' -Raw -Encoding UTF8 | ConvertFrom-Json
if ($cards.Count -ne 18 -or @($cards.id | Sort-Object -Unique).Count -ne 18) { throw 'Card count or IDs invalid' }
foreach ($card in $cards) {
    if ($card.review_status -ne 'DRAFT' -or $card.authority_type -ne 'PROJECT_IMPLEMENTATION') { throw "Unexpected state: $($card.id)" }
    if ($card.knowledge_domain -notin @('mes_process', 'mes_quality_rules')) { throw "Unexpected domain: $($card.id)" }
    foreach ($source in $card.source_files) {
        $actual = (Get-FileHash -LiteralPath $source.path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $source.sha256) { throw "Source changed: $($source.path)" }
        $lines = @(Get-Content -LiteralPath $source.path -Encoding UTF8)
        if ($source.line_start -lt 1 -or $source.line_end -lt $source.line_start -or $source.line_end -gt $lines.Count) { throw "Invalid range: $($card.id)" }
    }
}
'18 DRAFT cards; source hashes and line ranges verified'
```

哈希核验只能证明文件与整理时版本一致，不能代替对正文事实、业务合理性、实际运行结果及工厂适用性的人工审核。源码变化后先重新核对内容，再创建新知识版本；不要为通过校验直接刷新哈希。
