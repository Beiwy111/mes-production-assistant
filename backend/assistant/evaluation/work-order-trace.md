# 工单连续追问验收（2026-11-21）

样本库 trace-baseline.json 共30题（含历史原话和边界变体），本次不报告30题的整体通过率。相同管理员、订单100、工单 WO202607003 下，改造前后三轮均经真实工作台网关和 Dify；前后运行从不同的新会话开始。

|问题|原版|新版|
|---|---|---|
|WO202607003这个订单现在派工了吗|工单20，8条派工|相同；6条已派未完成、2条完成|
|哪道工序没做完|范围丢失，返回生产总览|保持工单20；显示6道仍有未完派工的工序。另标出当前路线与实际派工不一致，不把无派工模板步骤计作已开工|
|同批有没有质检异常|范围丢失，引用不相关检验|保持工单20；按源检验的批号+物料+检验类别关联1条记录，已判合格但记录2件不合格。客观异常线索不等于整批失败|

业务图只导入白名单中订单100的只读投影；图路径只能作为补充，查询时检查角色、当前订单/工单等真实关联，并与活动快照相交。缺快照不影响 MySQL 主查询。公共资料没有注入业务数据。质检轮另外获得公开RAG的4条片段、2条研究图路径，不作为判定本批次缺陷的证据。图仅涵盖订单/明细/计划/工单/检验；派工、工序、同批判定仍回源查询。

浏览器测试：设本机 MES_TRACE_USER 与 MES_TRACE_PASSWORD，以有权限账号运行 python frontend/tests/workbench-trace-chain.browser.py。依赖上述种子及独立服务；不提交订单、不执行派工。三轮均收到 agent_started 及 message_delta，页面显示真实工序与检验号。数据改变时应复核预期，不固化生产答案。

其他机器没有业务快照时，在本机初始化独立助手、配置明确订单白名单后，按下列命令仅导出所选订单；私有内容不应提交 Git。维护窗口内更新 Neo4j，脚本会恢复只读，避免与其他维护操作并行。Java运行时从已有私有Neo4j配置加载凭据。
~~~powershell
$d='backend/assistant/rag/data'
$cp=(Resolve-Path "$d/runtime-build/classes").Path+';'+(Get-Content "$d/stage5-classpath.txt" -Raw).Trim()
java -cp $cp backend/assistant/deploy/TracePilotExport.java 100
if ($LASTEXITCODE -eq 0) { & ./backend/assistant/deploy/Update-GraphLocal.ps1 -Kind Business -ExpectedOrder 100 -ExportFile "$d/chain-order-100.json" }
~~~
