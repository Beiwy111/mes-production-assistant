# 新助手工作台独立网关

## 从聊天页查看工作流和知识图

「Dify 工作流」读取仓库内九个 DSL 定义，可从 Agent 总览进入具体工作流。节点配置来自 DSL，Agent 执行记录来自当前回答。修改 Dify Studio 后，导出并更新对应的仓库 DSL，重新构建前端即可同步定义。

「联合知识图」可浏览公开资料或当前回答所引用的知识。管理员还能在「资料入库」批量导入资料，每文件上限为 10 MiB。点击文件可查看逐阶段耗时、失败原因及切片预览，成功后点「查看图谱」，或在图谱侧栏的「入库资料」中跨文件检索。图片和扫描 PDF 使用本地文字识别。规则提取的关系与同句共现均保留原文，供复核。

已配置本项目公开知识库和助手启动器的新电脑，在项目根目录执行一次以下命令，安装解析器并初始化私有资料库。

```powershell
& .\backend\assistant\rag\.venv\Scripts\python.exe -m pip install -r .\backend\assistant\rag\requirements.lock.txt
& .\backend\assistant\deploy\Initialize-IngestionLocal.ps1
```

初始化会在部署目录生成 Git 忽略的 `.env.ingestion.local`。私有图使用本机 Bolt 端口 17688 和独立 Docker 卷，Qdrant 使用独立集合，原件与处理记录存放在知识服务的数据目录。初始化后需让已运行的 18081 知识服务重新启动一次，以加载本机凭据。此后沿用前后端的日常启动方式即可。私库的上传、原件下载和检索都通过 MES 管理员会话鉴权。

## 在生产协作助手选择回答方式

在 `/assistant` 输入框上方选择“API 模型”或“本地模型”，再决定是否开启“RAG 知识检索”。两种模型均由现有 MES 后端（8088）使用当前用户的登录会话调用。原有 Dify 多 Agent 的入口为“业务工作流”，仍使用独立网关及工作流内的检索策略。 模型运行在另一台电脑时，参见 [Windows 通过 Tailscale 连接 Ubuntu 模型](TAILSCALE-MODEL.md)。

在**启动 MES 后端的同一个 PowerShell 窗口**配置所需的模型。后端读取环境变量或 Spring 私有配置。示例中的地址和模型名请替换成已部署服务的实际值。

```powershell
# API：默认复用 deepseek.api-url、deepseek.model、deepseek.api-key，也可单独指定。
$env:MES_CHAT_API_BASE_URL = 'https://api.deepseek.com/v1'
$env:MES_CHAT_API_MODEL = '你的 API 模型名'
$env:MES_CHAT_API_KEY = [Net.NetworkCredential]::new('', (Read-Host 'API 模型密钥' -AsSecureString)).Password

# 本地：支持 Ollama、llama.cpp 等提供 OpenAI 兼容 chat/completions 的服务。
# 同机可使用 http://127.0.0.1:11434/v1；远程服务请填写 Windows 能访问的局域网地址或 Tailscale 转发地址。
$env:MES_CHAT_LOCAL_BASE_URL = 'http://192.168.1.100:11434/v1'
$env:MES_CHAT_LOCAL_MODEL = '你的本地模型名'
# 本地推理服务启用认证时，设置 MES_CHAT_LOCAL_API_KEY。

# 仅在启用 RAG 时需要。使用当前 MES 知识服务已配置的查询密钥。
$env:MES_KNOWLEDGE_API_KEY = [Net.NetworkCredential]::new('', (Read-Host '知识服务查询密钥' -AsSecureString)).Password
```

配置后重启 MES 后端，进入助手页面点击“刷新连接状态”。“已配置”表示服务端已载入该模式的参数。RAG 开启时会实际访问 `127.0.0.1:18081/internal/knowledge/query`，将经过校验的文献片段连同问题送给选中的模型，并显示引用或无匹配结果。RAG 关闭时跳过知识服务。模型或知识服务故障时本轮会返回错误，由用户决定是否切换模式。

普通模型问答无需启动 Dify、18083 或初始化订单草稿表。实时业务查询继续使用按角色和数据范围授权的只读工具；自然语言下单仍需订单草稿表、原角色权限和最后的显式确认。模型来源切换时不会向另一个模型转发上一种模式的对话历史；有订单草稿时切换来源会开启新会话，原草稿保留在原会话中。

模型状态与普通问答通过 `GET /assistant-v2/chat/status` 和 `POST /assistant-v2/chat/turn` 访问。后者接收 `text`、`modelSource`（`API` 或 `LOCAL`）、布尔值 `ragEnabled` 和可选的同模式 `history`。订单的 `POST /assistant-v2/orders/turn` 支持同样的两个模式字段；省略两者保留原工作流协议。浏览器只使用 MES 令牌，模型地址和服务密钥保存在后端。

自然语言下单已接入：详见 [CONVERSATIONAL-ORDER-SETUP.md](CONVERSATIONAL-ORDER-SETUP.md)。管理员和订单员可代录订单，客户可按自身绑定身份下单；旧有全域查询仍限管理员。新增下单分支与下述只读查询分支分别鉴权。

## 日常启动

在已配置本机服务的 Windows 电脑上，平时只需在 `backend` 运行 `mvn spring-boot:run`，在 `frontend` 运行 `npm run dev`。前端开始监听后，会在后台调用下面的启动器，进度输出到前端终端。

在项目根目录双击 `Start-Assistant.cmd`。它会启动 Docker Desktop，恢复本项目已安装的 Dify、Qdrant、Neo4j 容器，再依次启动 Python 知识服务（18081）、Agent 运行时（18082）、聊天网关（18083）。助手就绪后，已安装的本机 CompreFace（8001）也会从原数据卷恢复。已运行服务跳过，重复点击有启动锁保护。完成后打开 `http://localhost:5173/assistant`，或在现有页面点击“重新连接”。

原后端仍在 `backend` 执行 `mvn spring-boot:run`，原前端仍在 `frontend` 执行 `npm run dev`；脚本不启动、停止或重新编译它们，不覆盖配置或密钥，不拉取镜像、不重建容器、不执行初始化容器、不修改业务数据。此入口用于当前已部署的本机环境，不是首次安装工具。关闭结果窗口不会关闭后台服务。

终端也可在项目根目录运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\backend\assistant\deploy\Start-AssistantLocal.ps1`。每个阶段默认等待180秒，较慢电脑可追加 `-TimeoutSeconds 300`。`[SKIP]` 表示已运行，`[READY]` 表示就绪探测通过，`[FAILED]` 表示需处理原因后再运行。端口被其他进程占用时保留该进程并报错；运行中但不健康的服务不会被强制重启。

探测验证服务响应及带健康检查的容器状态；网关匿名401表示鉴权接口就绪，不代表已登录或模型调用成功。启动过程不调用收费模型API；登录与业务查询在浏览器验证。缺少容器、虚拟环境或编译产物时明确报错，按对应阶段文档恢复即可；独立服务日志位于 `backend/assistant/rag/logs`。Docker Desktop 自身可能按既有重启策略恢复其他容器，本脚本的显式启动仅限三个助手项目。

现有 MES 继续监听 `8088`；维护运行时监听 `18082`；新增网关只绑定 `127.0.0.1:18083`。启动网关不会重启 MES，也不会修改原助手或 Dify 应用。前端通过 Vite 同源 `/api/assistant-workbench` 代理访问网关 `/assistant-workbench`。

1. 保留现有 `.env.agents.local` 中的 BOOTSTRAP 和订单白名单。新机先按第五阶段步骤运行 `Initialize-MesAssistant.py`。网关从 `.env.workbench-read.local` 加载身份核验专用账号，权限限于用户、角色、菜单及角色菜单映射的指定列。[只读账号初始化](STAGE5-AGENTS-SETUP.md)
2. 将 `.env.workbench.local.example` 复制为 `.env.workbench.local`，填入第五阶段 SUP 应用（`82cda4ca-f087-48c6-9f1b-f8004e55fd4d`）的**应用 API 密钥**。此文件已被 `backend/assistant/.gitignore` 忽略，不得提交。默认 Dify API 地址为 `http://127.0.0.1:18080/v1`。不要放入浏览器环境或聊天。
3. 运行 `Build-AssistantRuntime.ps1`，构建输出位于 `assistant/rag/data/runtime-build/classes`，不覆盖正在运行的 MES 的 `target/classes`。仅在确认 PID 属于 `stage5-java.args` 对应的独立 JVM 后重启该 JVM，运行 `Start-AgentRuntimeLocal.ps1`；不要停止原 MES。
4. 运行 `powershell -File backend/assistant/deploy/Start-WorkbenchLocal.ps1 -Build`。脚本隐藏启动窗口，拒绝占用端口，不会停止已有进程。密钥为空时网关可启动，已认证管理员访问 status 返回 `configured:false`。
5. 填好密钥后只重启有 PID 和命令行确认的 `workbench-java.args` 对应网关 JVM。密钥读取发生在启动时；没有热更新。

## 使用新界面

原 MES 后端、Dify 和独立运行时保持运行。在 `frontend` 执行 `npm run dev`，打开 `http://127.0.0.1:5173/assistant`，使用现有管理员账号登录；也可以从系统侧栏进入“生产协作助手”。直接输入“查库存”“看生产进度”“分析质量问题”或“订单 ID 123 能交付吗”，无需选择订单和场景。总协调规划问题并派发必要的专家；缺少具体订单时先追问。卡片显示真正启动的 Agent，展开查看摘要；“业务证据”展示查询范围、记录总数及全部已取回明细（最多100条），默认折叠。

原“智能对话”位于 `/chat`，继续使用原助手。自然语言查询仍限当前已登录且实时验证的 ADMIN，可查现有订单，不再受100/101维护预览白名单限制；旧显式预览请求仍受白名单限制。新入口仅查询和分析。生产静态部署时需由反向代理配置同源 `/api/assistant-workbench` 转发至18083；Vite配置仅用于本地开发。

## 浏览器协议

所有接口都要求当前 MES 的 `Authorization: Bearer ...`。每次请求向 `/auth/userInfo` 验证会话，并通过新只读查询验证当前 ADMIN 权限。不使用缓存角色作为授权依据。模型只选择固定查询计划，不能生成SQL、业务URL或授权。

- `GET /assistant-workbench/status` → `{configured,available,naturalLanguage,message,allowedOrderIds,profiles}`。available 表示配置已备齐，不表示已完成 Dify 端到端探测。allowedOrderIds/profiles仅用于兼容旧显式请求。
- `POST /assistant-workbench/runs/stream`，JSON `{text,sessionId,requestId,previousRunId?}`。text 1–2000 字符；sessionId/requestId 为 1–80 位字母、数字、下划线、连字符。previousRunId 必须属于同一用户、同一会话的已完成自然运行，服务端上下文有效期10分钟。旧历史不自动作为自然运行上下文。重复请求10分钟内返回409，不自动重跑。旧 `{text,orderPk,profile,sessionId,requestId}` 请求保持兼容。
- `GET /assistant-workbench/runs/{runId}` → `{runId,status,agents,result?}`；`POST .../{runId}/cancel`，JSON `{}`。均检查当前用户归属，其他用户返回 404。
- NDJSON 扁平事件：`{type:"run",runId}`、`{type:"agent",agentId,status,summary?,evidence?,startedAt?,completedAt?}`、`{type:"result",data:{reply,facts,missing,verdict,...}}`、`{type:"error",message}`、`{type:"done"}`。忽略空白行。HTTP 非 2xx 错误为 `{message:固定错误码}`。

SUP 状态来自 Dify workflow_started/finished 事件；六领域任务来自运行时实际 begin/complete/cancel 状态，未执行角色不生成事件。摘要为模型解释，不能视为业务事实。reply 来自 SUP 工作流最终 answer；facts、missing、verdict 等来自运行时最后一次真实 finalize，决策运行引用及 verdict 必须匹配。Dify 输入遵循已发布 DSL：`inputs.run_ref` 与 `inputs.query`。

最多同时运行 2 次，最多保留 64 条运行/幂等记录，记录 TTL 10 分钟，单次截止 5 分钟。请求正文上限 32 KiB；事件/依赖响应均有限制。断开、取消、失败或超时均尽力取消运行时并停止 Dify task；原始内部异常、调用参数与密钥不输出。断流后不自动重复执行；上游停止只能尽力完成，已开始的只读查询可能结束后才响应取消。

Dify SSE 单事件最多1,048,576字符、累计最多4,194,304字符，以容纳全域六专家产生的实际中间节点体积；这些诊断事件只用于读取生命周期，不转发内部内容。完成前会再次读取权威任务快照。若最终 verdict 为 DATA_CHANGED，缓存和后续恢复响应中的旧 Agent 摘要/证据会清空。

生产部署需提供同源 TLS 反向代理、禁用流缓冲，并设置适当连接超时。当前内存状态不跨 JVM 重启保留，网关适用于单机受控工作台。

## 自动规划与数据口径

SUP新增读取上下文、规划LLM、校验JSON和提交计划节点，再通过原六个工作流工具调用业务Agent。必须同步发布 `agents/workflows/stage5` 的七个应用，沿用已有应用密钥；不需要另买模型API。公开RAG/GraphRAG工具继续提供有来源的方法知识，实时库存和订单事实以业务接口为准。

概览支持当前记录、今天、最近7天、本月及一个名称/编号关键词。日期过滤以Asia/Shanghai和各表业务时间字段为准，不能作为历史时点库存。暂不支持的复杂条件先澄清；精确订单评估使用当前整单证据。库存按物料原单位逐行显示，不跨物料相加；账面可用量不等于确认可发货量。质量概览记录数不等于产品或缺陷数量。运行结束复核数据，变化时要求重新查询。

## 质量分析与售后追溯（2026-11-09）

直接提问“分析质量问题”“分析订单100的质量问题，给出核查依据”或“追溯订单100的售后质量问题”。精确订单售后流程先完成 QUALITY，再将同一运行的质量业务摘录与查询凭据交给 AFTERSALES；无需手动选择专家。没有案件时明确已查无登记案件，仍可解释已读取的工单、检验、领料和发货链。关系保留显式、候选及冲突状态，不自动确认责任或根因。

QUALITY/AFTERSALES 的固定 research 节点调用 Java 运行时，运行时以白名单领域词访问现有 Python 知识服务，不把订单、客户或内部标识放入公开知识查询。`Start-AgentRuntimeLocal.ps1` 自动读取已有 `.env.knowledge.local` 的知识服务密钥，无需新购 API。Qdrant 提供原文片段，Neo4j 提供有页码、原句和文档版本支持的研究关系。无命中、图谱降级和服务不可用均明确显示；研究关系不能代替真实业务追溯。

回答区的“Agent 协作过程”显示真实的业务读取、视觉证据核对、知识检索、分析完成事件。“业务与研究证据”默认折叠，包含样本口径、视觉来源、公开论文页码、图谱路径以及带引用的观察/待验证假设/建议。引用核验只确认来源属于本次任务，不保证模型解释正确。完成前重新读取业务指纹，数据变化时清除旧分析和引用。

### 新检测的可选证据归档

原 `POST /quality/vision/detect` 兼容无检验单调用。质检工作台现在附带当前实际 `inspectionId`；后端验证当前启用的 QC/ADMIN 身份及真实检验单，取得实际 YOLO 响应后，另存到 `backend/assistant/data/vision-evidence`，不改变业务表、检测结论、放行或入库逻辑。归档失败返回提示，不使原检测失败。

这段兼容接入位于原 MES 后端，需要按原方式停止当前后端，再在 `backend` 执行 `mvn spring-boot:run` 才会加载。不要在运行中覆盖 `target/classes`。YOLO 服务沿用项目已有服务与权重，可使用 CPU；本轮未租 GPU。不能用联通测试图片绑定真实检验单。

保存字段包括检验单、服务器时间、上传图像哈希、模型声明、算法声明、模拟标志、检测框与置信度。新schema2还保留用户上传原图、实际推理输入图和模型返回的标注图，并固定检测时的检验单号、批次、工单和物料。旧schema1仅有结果与哈希，历史图片无法补回；权重版本不可得时仍明确缺失。模拟记录不计入真实检测，图片数不等于产品数。

归档目录已被 Git 忽略。若跨机器部署，需共享或同步该目录；也可在 MES 与独立运行时同时配置相同的绝对路径 `ASSISTANT_VISION_EVIDENCE_DIR`。每单上限100份，单次业务查询读取最多40份结果且总结果JSON内容64KiB；图片不进入LLM上下文或聊天历史。损坏、超限及缺失均显式标记不完整，不能作为“零缺陷”。

### 图片保存与聊天查看

在具体检验单的“AI智能外观检测”上传JPEG/PNG并检测。前端保留原有压缩推理行为，额外以可选`originalFile`传原始文件；无检验单的演示不归档。后台按实际字节验证格式、尺寸与哈希，批次取数据库记录，不采信客户端批次参数。单图上限10MiB、4000万像素，默认归档目录总预算2GiB；原图过大/格式不支持、标注未返回或空间不足时提示未保存/部分保存，不改变原质检结果。

在新助手询问具体订单的质量或售后问题后，展开“查看业务与研究证据 → 视觉证据 → 归档记录”，点击“原图 / 标注图对照”或“查看推理输入图”，支持放大。标注坐标对应实际推理输入，原图分辨率可不同。历史回答需要重新提问才会取得新归档；未保存图片不会显示成可查看。

读图接口`GET /quality/vision/evidence/{inspectionId}/{recordId}/{variant}`仅接受original/input/annotated。每次读取重新验证MES登录、当前启用的QC/ADMIN角色和检验单，返回真实JPEG/PNG并设no-store，未提供公开静态目录。浏览器以Bearer读取blob，关闭/退出登录/数据变化时释放，不把令牌写入链接。Agent收到元数据和检测数据，并未自动阅读图像像素；当前无需新增模型API或GPU。

文件名由服务端生成，图片先保存、结果manifest最后提交；满额时不会自动清理旧证据。可通过保存图片的MES JVM系统属性`assistant.vision.max-storage-bytes`调整预算，默认无需配置。该目录需自行定期备份，不能作为生产级对象存储或长期备份系统。

## 动态 Agent 协作面板（2026-11-09）

新自然语言运行通过既有流式连接新增 `workflow` 快照，无需安装 AgentScope 或配置新 API。沿用 Dify 总协调与六个业务 Agent，Vue 聊天区展示确认计划、实际开始的角色、业务读取/知识检索步骤和前序依据交接。计划中的角色未执行时标为等待，不表示所有专家都已调用。独立角色不绘制虚构的顺序依赖。

`GET /assistant-workbench/runs/{runId}` 增加可选 `workflow`；NDJSON 新增 `{type:"workflow",runId,plan,events,invalidated,truncated?}`。plan 只包含 action、requiredAgents、dependencies、confirmedAt。事件仅取运行时真实生命周期，包含 seq、kind、agentId、可选 taskId/fromAgent/toAgent、status、label、detail、at；不转发内部提示词、模型思维过程或原始 Dify 调试内容。单运行最多128条，超出时明确标记截断。短步骤可能同一轮到达，但起止记录保留。

运行时自动展开协作卡片，完成后收起，点击可重新查看。动态效果只表示正在运行的已知步骤。停止、失败、断流或重新打开历史后停止动画；旧历史没有计划/时间线时只展示原有观测记录。数据复核发现变化时清空计划、交接与旧证据，恢复接口同样清空，不以旧图片或论文引用继续下结论。

这是现有 Dify 调用过程的实时可视化，并未替换执行引擎，也未新增任意重规划、循环重试或写业务权限。更新只需加载独立18082/18083运行时和刷新前端。部署时先隔离编译，确认并停止这两个独立 JVM 后再替换其类文件；原8088、Dify、数据库和知识库无需重启。

## 库存结果与新界面（2026-11-09）

查询当前库存时，物料供应Agent返回数据后，协作过程显示物料名称、可用量、单位、仓库和批次，标注等待最终复核。数据按实际返回批次显示；动画只标示当前运行，不模拟逐物料查询。最终回答直接展示库存一览表，包含现存、预留、可用量及仓库/批次。支持在已返回记录中筛选与每页10条翻页；筛选框不会发起数据库查询。

完整的单库存请求显示短总结，完整文字保留在折叠区。混合查询、截断或证据不足时保留原有解释。失效、取消或缺失证据不显示过期的临时结果。库存原值只做小数/千分位格式化，不重新计算或跨单位相加。

本轮参考Element Plus X的执行链与内容区交互，在现有Vue组件内自行实现，无新UI依赖。刷新前端即可，无需新增API或重启Dify、MES与独立助手。实施及验证详见docs/ai-planning-2026-11-07/planning/26-conversational-results-ui.md。
### 日常问答与业务分流

新机初始化会将千问密钥保存到本机私有的 `.env.daily-chat.local`。工作台启动脚本每次启动都从该文件读取 `DASHSCOPE_API_KEY` 并传给网关。已有部署也可以继续通过进程环境提供此变量。已配置时，聊天页先使用千问 API 识别请求；日常对话由模型流式回答，业务查询进入原有 Dify 和授权工具，下单进入订单草稿流程。仅上一轮日常问答的文本会随日常追问传入，业务表格和订单草稿不会作为日常历史传入。缺少此变量时沿用原有业务入口。

天气查询使用 Open-Meteo 的地名和预报接口，先确认城市，按当地日期返回未来七天以内的单日预报。网关需要能够访问 DashScope、`geocoding-api.open-meteo.com` 与 `api.open-meteo.com`，可读取 HTTP 类型的 `HTTPS_PROXY`。天气数据未返回时会说明原因。修改环境变量后重新启动独立工作台网关。

### 工作流的模型选择

网页助手默认使用 API 模型。选择本地模型时，MES 网关使用独立的 Dify 应用密钥运行同一份工作流，模型固定为 `mes-qwen14b-v2`。两组工作流共用 MES 授权检查、业务工具和知识库。

在完成原有 Dify 初始化的 Windows 部署目录执行下面的命令。输入 Windows 和 Dify 插件容器都能访问的 Ubuntu 模型服务地址。

```powershell
$v2BaseUrl = Read-Host '请输入 Ubuntu 模型服务的 Base URL（以 /v1 结尾）'
python .\Provision-Stage5Dify.py --local-v2 --base-url $v2BaseUrl
```

脚本使用 Dify 签名供应商注册 v2，生成单独的总协调、专家和知识工作流，并把本机绑定保存到私有文件 `.env.workbench-v2.local`。重复运行可核对和补全同一组应用。首次配置时若 Ubuntu 离线，脚本保存目标地址。只要工作流网关正在运行，助手就会每分钟检查一次，发现 v2 上线后自动完成首次发布。发布完成后，在助手中点击「重新连接」即可。手动重试可在同一部署目录运行 `python .\Provision-Stage5Dify.py --local-v2`。已发布的绑定会直接复用。
