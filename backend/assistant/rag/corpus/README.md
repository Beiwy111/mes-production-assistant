# 知识来源与知识卡

`papers.json` 登记了七篇 CC BY 4.0 公开论文。P01–P05 涵盖 LCD 缺陷检测、Mura、串扰、TFT 阵列测试和彩色 Mura。P06 研究黑白条纹饱和成像如何显现偏光片内的微弱透明缺陷。P07 研究偏光片涂层表面检测，其划痕、裂纹、白点、漏光、破损、污渍和破膜样本类别见 PDF 第 12 页。这七篇论文经当前分词器生成 279 个正文切片，线上可查范围以活动发布清单为准。

原PDF仍在 docs/ai-planning-2026-11-07/papers，分页文本在同级paper-text。入库复制到rag/data/sources；没有移动或覆盖原件。清单记录标题、来源URL、许可、PDF/文本SHA-256和页码。

project-cards.json 含18张项目实现卡、38处源码引用，全部DRAFT，不进入公共索引；review-notes.md记录待确认项。尚未完成PDF表格/公式人工校对，也未取得论文作者的原始训练数据。论文实验结论不能自动成为本项目检验放行标准。

## 生产问答参考

`practice/display.json` 保存五则显示器复检笔记，每则附 Dell 原文和适用条件。`practice/quality.json` 保存五则依据 NIST/SEMATECH 手册整理的质量统计笔记。引文标题中的“中文参考笔记”表示本项目整理。外部原文保留作者署名和原有权利。公开晶圆数据 `practice/MONITOR-6_3_3_2.DAT` 用于计算控制图示例，答案应注明该样本来自 NIST 手册。

从项目根目录使用以下命令更新已经初始化的本机公开知识服务。发布器核对源文件哈希，保留其他已审核文档并按版本发布。重复运行且目录内容相同时会跳过向量构建。仅在向量版本或图谱清单更新后执行图重建。

```powershell
$env:PYTHONPATH = 'backend/assistant/rag/src'
$ragPython = 'backend/assistant/rag/.venv/Scripts/python.exe'
& $ragPython -m mes_knowledge.practice_release
& ./backend/assistant/deploy/Update-GraphLocal.ps1 -Kind Public
& $ragPython backend/assistant/rag/scripts/evaluate_public.py --practice --output backend/assistant/rag/data/practice-retrieval-check.json
```

在生产协作助手打开「联合知识图 → 公开知识库总览」，可按文档查看本次发布的所有切片。点击切片可读到对应的索引原文。「公开资料检索」和「当前回答证据」分别展示搜索结果和某一轮回答引用的范围。管理员上传的资料在「入库资料」中按文件查看，保留原有访问范围。

可在上述 PowerShell 环境运行 `& $ragPython backend/assistant/rag/scripts/verify_visual_catalog.py` 做只读核对。命令逐页比对目录、Qdrant 的切片编号与正文、Neo4j 的文档归属和声明证据边，输出当前发布版本与实际数量。
