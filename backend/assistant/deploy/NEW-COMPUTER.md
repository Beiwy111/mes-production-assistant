# 在队友的 Windows 电脑上初始化生产协作助手

从仓库最新的 `main` 拉取代码。在**队友自己的电脑**双击根目录的 **Initialize-Assistant.cmd**。脚本在当前电脑创建工作区、账号配置、模型索引和 Docker 卷。首次下载较大，请保持网络畅通。失败时按最后一条 `[FAILED]` 的步骤处理，再运行同一脚本。已生成的密钥和已发布的数据可以复用。

## 开始前

- 使用 Windows 10/11、运行中的 Docker Desktop（Linux 容器、Compose v2，支持 `!override`）、Git、JDK 17、64 位 Python 3.13。Maven 可用系统安装版或仓库 Wrapper。前端另需与项目兼容的 Node/npm。确保能访问 GitHub、Docker 镜像源、PyPI、Hugging Face、Dify Marketplace、DeepSeek、阿里云百炼和 Open-Meteo。
- 准备**自己的 MES MySQL 8、Redis 和可登录的 MES 账号**。队友若已能正常使用业务系统，沿用那套库。若从仓库基础库开始，初始化器会列出缺失的业务表和字段，取得确认后添加，再创建助手草稿表及专用只读账号。已有业务库请先备份。[空库导入和结构补齐步骤](SEED-SCHEMA.md)
- 为向量检索预留 CPU、内存和磁盘。嵌入模型在 CPU 上运行，无需 GPU。RAGFlow 和 CompreFace 不属于本次安装。
- 在新机的固定目录部署。若端口或同名 Docker 项目已由另一份 checkout 占用，先核对归属。原项目的 Docker 卷、`.env.*.local`、人脸样本、客户资料、模型权重及个人会话均无需从别人的电脑复制。

可先执行只读预检。此命令检查开发工具和 Docker 引擎，业务权限、模型和索引在后续步骤验证。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\assistant\deploy\Initialize-Assistant.ps1 -CheckOnly
```

## 队友需要填写什么

| 项目 | 由谁提供 | 用途和保存位置 |
|---|---|---|
| DeepSeek API Key | 队友自己的 DeepSeek 账号 | 业务工作流。首次配置时隐藏输入，经供应商校验后由本地 Dify 管理。工作区已有可用模型时复用；脚本不会把 Key 写入 DSL。 |
| DashScope API Key | 队友自己的阿里云百炼账号，需能调用中国内地 `qwen-plus` | 工作流入口的意图判断、日常问答。隐藏输入，保存在本机忽略的 `backend/assistant/deploy/.env.daily-chat.local`，网关重启后仍可读取。天气预报走 Open-Meteo，无需另填天气 Key。 |
| MySQL 管理账号、密码和已有库名 | 队友本地 MySQL | 用于建助手表、创建和验证权限受限的读取账号。推荐库名 `display_manufacturing`，以实际 MES 配置为准。新机初始化只在当前进程使用管理密码。 |
| 允许维护预览的订单主键 | 队友从自己有权使用的数据中选择 | 按提示输入逗号分隔的正整数，不能填通配符。正常登录后的业务查询仍按 MES 用户权限执行。 |
| 本机 Dify 管理员邮箱及自设密码 | 队友创建 | 只在全新 Dify 首次注册时输入。MES 登录、Dify 管理账号和供应商 API Key 各自独立。 |

**其余密钥自动生成。** 包括总协调和六个专家的角色密钥、运行引导密钥、Dify SUP 应用 Key、Qdrant 读/写 Key、公开知识服务 Key、Neo4j 与私有入库服务凭据。首次配置不需要逐个复制 Agent ID 或手工连接六次工具。终端输入 Key 时没有回显是正常的。

默认工作流使用 `deepseek-flash`。如果你的 DeepSeek 插件列出的可用、支持工具调用的模型 ID 不同，在 PowerShell 执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\assistant\deploy\Initialize-Assistant.ps1 -Model <实际模型ID>`。请勿把 Key 写入命令、提交、工单或聊天记录。

## 脚本会完成什么

1. 创建 Python 独立环境、Qdrant、公开知识 Neo4j 和私有上传资料库；准备固定版本的 multilingual-e5-small，发布仓库内的 7 篇论文和 11 份实践资料，通过真实的检索与图关系校验。
2. 对接本机业务库，配置 4 个最小权限读取账号、订单草稿及日常问答。资料中的业务单号应以队友自己的数据库为准。
3. 准备 Dify 1.19.0，首次注册后安装 Agent 策略和 DeepSeek 插件并验证所选模型。若 Marketplace 或供应商网络临时不可用，可在本机 Dify Studio 检查插件和凭据后重跑。配置已存在但模型不可用时，请先修正模型 ID 或凭据。
4. 在本机工作区发布共享知识工具、订单交付、物料供应、生产调度、质量检验、设备维护、售后追溯及总协调。共 **7 个 Agent 加 1 个知识工具**。各应用使用新工作区的 ID，SUP 通过实际的工具关联调用子 Agent。
5. 构建独立 Java 服务、启动助手并验证工作流的应用 Key 确实能访问 SUP 的输入契约。原 MES 后端和前端继续按原方式启动。初始化面向新安装；若之前已启动过使用旧凭据的助手网关，退出旧的助手进程后再启动，使其加载新凭据。

首次在前端目录运行 `npm ci`。之后，后端目录执行 `mvn spring-boot:run`，前端目录执行 `npm run dev`。打开 [生产协作助手](http://localhost:5173/assistant)，选择 **业务工作流**。后续可使用原来的 `Start-Assistant.cmd` 启动已安装的服务；Windows 前端开发服务器也会在检测到本机配置后调用启动器。只启动“API 模型”或“本地模型”模式不会显示七个业务 Agent 的执行面板。

## 验收

从仓库根目录执行。第一条无需登录或产生模型费用。第二条需要启动 MES 并使用有权访问助手的测试账号，会产生少量模型调用，终端不会打印登录令牌或订单明细。

```powershell
& .\backend\assistant\rag\.venv\Scripts\python.exe .\backend\assistant\deploy\Verify-NewComputer.py
& .\backend\assistant\rag\.venv\Scripts\python.exe .\backend\assistant\deploy\Verify-NewComputer.py --live
```

要同时验证多个 Agent，在第二条末尾追加 `--order 100`，把 100 换成**具有交付、订单、库存、生产权限的账号可见且有生产或交付数据**的订单主键。验证器检查真实 SSE 事件、增量正文和至少两个已完成的子 Agent。

| 在聊天界面输入 | 预期行为 |
|---|---|
| 请查询中国上海市明天的天气预报。 | 直接回答当地预报并给出 Open-Meteo 来源。若只剩日常问答或缺预报数据，分别核对 DashScope 和天气服务的出站网络。 |
| 订单 100 能按期交付吗？请分别核对可发货数量、物料齐套情况和生产排程。 | 替换为可见订单。检查右侧工作流中的实际调用，订单、物料、排产按本机记录回答。 |
| 根据知识库里的论文，低对比度屏幕上的 Mura 缺陷可以怎么检测？请给出论文名和页码。 | 检查知识来源及引用；也可在联合知识图中查看文档、切片和证据关系。 |

Dify 在 [本机工作室](http://127.0.0.1:18080/apps)，向量点在 [Qdrant](http://127.0.0.1:16333/dashboard)，知识关系在 [Neo4j Browser](http://127.0.0.1:17474/browser/)。Git 中的历史部署文档记录的是原开发机情况，复建以本页和本机验收结果为准。仓库可重建公开知识，个人后来上传的私有文档需经授权在队友电脑重新导入。若要在图中查看队友自己的订单追溯或历史维修经验，请按 [订单快照导出](../evaluation/work-order-trace.md) 和 [经验索引同步](../evaluation/federation.md) 选择授权范围。
