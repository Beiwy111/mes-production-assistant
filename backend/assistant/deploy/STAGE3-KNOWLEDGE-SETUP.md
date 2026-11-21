# 第三阶段本机知识服务

2026-11-08：Python RAG、Qdrant、三篇论文索引与独立Dify知识问答已准备。Java有默认关闭的订单只读工具。未切换原助手、未重启MES、未修改业务表。

## 入口和配置

| 组件 | 位置/入口 | 用途 |
|---|---|---|
| Dify 1.19.0 | http://127.0.0.1:18080 | 复用已有DeepSeek模型配置 |
| 知识Workflow | http://127.0.0.1:18080/app/818d9352-e16a-4b2e-be2a-8bd04ca7615b/workflow | 7个节点，不是7个Agent |
| Python | http://127.0.0.1:18081/health | FastAPI公共文献API |
| Qdrant 1.20.0 | 127.0.0.1:16333 | 独立mes-knowledge-local项目/卷 |
| 嵌入模型 | rag/data/model | multilingual-e5-small ONNX、384维、CPU4线程 |

模型固定revision 614241f622f53c4eeff9890bdc4f31cfecc418b3，来源：https://huggingface.co/intfloat/multilingual-e5-small 。本次只部署检索嵌入模型，没有部署/微调生成大模型；回答继续用DeepSeek API，不需要新的付费embedding API或GPU。

Git忽略的私有文件：

- `.env.knowledge.local`：绝对data路径、Qdrant只读key、MES_KNOWLEDGE_API_KEY、线程数。
- `.env.qdrant.local`：管理/只读key；仅入库及容器启动读取管理key。
- `.env.local`：之前Java→Dify应用key，继续enabled=false；没有替换成新知识应用。

用户已将MES_KNOWLEDGE_API_KEY填到Dify秘密变量KNOWLEDGE_API_KEY。不要发到聊天、提交Git或导出含密钥DSL。当前演示无需再创建应用API Key，接Java时再配置。

## 日常启动与检索

Docker Desktop引擎启动后，在项目根目录执行：

```powershell
& .\backend\assistant\deploy\Start-KnowledgeLocal.ps1
```

脚本只启动独立Qdrant和隐藏Python进程，18081已占用时保留现有进程。health只证明进程存活；真实query验证模型与索引。Dify关闭时使用已有Prepare-DifyLocal.ps1 -Start。

本次修正overlay让api/worker的CODE_EXECUTION_API_KEY与sandbox的私有key一致；ssrf_proxy允许host.docker.internal宿主机域名，不放开整个内网、不关闭SSRF。该放行作用于本机Dify全实例，并不限单一端口，不能直接作为工厂多租户部署策略。依据：[Dify官方修复说明](https://github.com/langgenius/dify/issues/38443)。

只读冒烟检查及按需重建：

```powershell
$env:PYTHONPATH = (Resolve-Path backend/assistant/rag/src).Path
$env:PYTHONIOENCODING = 'utf-8'
& backend/assistant/rag/.venv/Scripts/python.exe backend/assistant/rag/scripts/evaluate_public.py --output docs/ai-planning-2026-11-07/verification/stage3-retrieval-evaluation.json
& backend/assistant/rag/.venv/Scripts/python.exe -m mes_knowledge.cli query '分段伽马校正如何检测Mura？'
# 只有资料/处理规则变化、确需重建时执行：
& backend/assistant/rag/.venv/Scripts/python.exe -m mes_knowledge.cli ingest --source-root docs/ai-planning-2026-11-07
```

三篇论文正文索引125块、原PDF53页，来源/许可/哈希见rag/corpus/papers.json；末尾参考文献列表不索引。每次入库新建generation，计数验证后原子替换data/active.json，旧点与原件保留。更换模型/维度须新collection。

撤回命令：同一venv/PYTHONPATH下 `python -m mes_knowledge.cli withdraw P01`。撤回即时影响查询/回查，重建不会自动取消撤回。没有自动重新批准功能。崩溃遗留data/.ingest.lock时，先确认入库进程已结束，再人工清理这一文件，不删除data或卷。

## 复建、备份与停机

本机Python3.13.7，独立venv。用 `python -m venv backend/assistant/rag/.venv` 和venv pip安装requirements.lock.txt复建相同依赖，不对YOLO环境安装。缺模型时先执行 `python -m mes_knowledge.cli prepare-model`，服务启动不自动下载。配置作为纯数据读取，不执行shell内容。

备份包括：私有配置（保密保存）、源PDF/分页文本/来源清单、rag/data下model/sources/releases/active.json/withdrawn.json、Qdrant卷mes-knowledge-local_mes_knowledge_qdrant、Dify数据库/存储卷和无密钥DSL。源码或模型不等于向量数据库备份。迁移须重新生成私有key，更新绝对data路径和Dify秘密变量。

停Python：读取rag/data/api.pid，通过Get-CimInstance Win32_Process核对PID对应本目录venv Python且CommandLine含mes_knowledge.cli serve，才执行Stop-Process -Id <已核验PID>。不要按端口结束未知进程。停Qdrant可在项目根目录：

```powershell
docker compose --project-name mes-knowledge-local --env-file backend/assistant/deploy/.env.qdrant.local -f backend/assistant/deploy/knowledge.compose.yml stop
```

不要使用down -v。恢复运行启动脚本即可。索引回退须核对旧release、对应点、模型signature，再原子恢复active指针并保留当前撤回清单；当前没有自动回退命令。

## 演示边界

开始 → JSON请求 → HTTP检索 → 证据校验 → DeepSeek回答 → 页码校验 → 结束。输入question；history可空，本版不使用历史。输出answer、evidence。页码校验能拒绝不在检索结果中的页码，不能证明每句话由原文支持，仍需人工核查。

可问“Mura缺陷可以如何检测？”，再问“实际可发货库存是多少？”。后者应说明未接业务数据。Java工具字段/未知口径见[交付契约](../contracts/stage3-delivery.md)，未接到公共文献Workflow。

后续：项目知识卡审核 → 第四阶段Neo4j/GraphRAG → 完成业务授权、质量/库存分配证据 → 第五阶段总协调+六业务Agent → MES入口验收。论文实验阈值不能自动变成工厂放行标准。
