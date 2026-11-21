# 本机配置准备情况（2026-11-08）

已完成：Docker Linux 引擎运行，官方 Dify 1.19.0 已部署，安装页与初始化 API 返回 HTTP 200，管理员初始化完成。

实际部署目录：D:\soft\dify-1.19.0（原 E 盘目录不可写，未修改磁盘权限）。本机入口 http://127.0.0.1:18080，Java 对应 http://127.0.0.1:18080/v1。Compose 项目名 mes-dify-local，与 MES 独立。

## 已解决的启动问题

- 首次镜像拉取超时：降低并发后已下载完成，没有修改镜像源。
- Agent 辅助服务密钥格式：改为无填充 Base64URL，保留原随机字节；服务恢复运行。
- 插件命名卷初始归属 root：增加一次性初始化容器，只调整该卷根目录归属为插件运行用户 1001:1001。
- 画布实时连接指向默认 80 端口：固定 NEXT_PUBLIC_SOCKET_URL 为 ws://127.0.0.1:18080，Socket.IO 握手 HTTP 200。
- 模型图标指向未对外开放的 5001 端口：API 的 CONSOLE_API_URL 固定为本机反向代理入口。

管理员登录信息与模型密钥由用户在本地填写，不记录到项目文件。基础服务运行不代表模型、工作流和 Java 联调已经通过。

## 后续启动

先打开 Docker Desktop，等引擎运行，然后可运行：

```powershell
& 'D:\soft\idea\computer\backend\assistant\deploy\Prepare-DifyLocal.ps1' -Start
```

脚本下载固定 release 的官方 docker 目录，生成不显示值的随机内部凭据，使用独立 Compose 项目名 mes-dify-local；不会覆盖已有 .env，不切换旧仓库版本，不删除任何数据，不改 MES 配置。

Compose 覆盖文件只向本机开放 18080，并将数据库和主要运行目录放到 Docker Linux 命名卷。Docker 镜像/卷所在磁盘仍由 Docker Desktop 决定，D:\soft\dify-1.19.0 只保存部署源文件，脚本不会迁移 Docker 磁盘。不要使用 down -v，以免删除 Dify 数据。

已安装 langgenius/deepseek 0.0.26，用户在本地保存密钥后，后台确认 deepseek-flash 处于 active。已导入并发布“MES 接入验证（DeepSeek）”，发布版本标记“接入验证 v1”。[打开本机工作流](http://127.0.0.1:18080/app/2083cf59-f871-4d49-a02b-042d0e7a7818/workflow)。

实际草稿运行测试均返回 succeeded，结束节点 outputs.answer 为非空字符串：

- “你现在能做什么？”：正常解释当前能力与尚未接入业务数据的边界，工作流报告耗时约 1.69 秒。
- “现在系统里的显示器实际库存有多少？请给出数量。”：回答尚未接入实时库存接口，未编造数量，工作流报告耗时约 1.36 秒。

上述仅为两个接入样例，不能当成业务准确率评测或性能基准。发布后 GET 发布配置返回 200，节点为 start → llm → end。

用户已将 Dify 应用密钥填入 Git 忽略的 `backend/assistant/deploy/.env.local`，没有写入跟踪配置、命令参数或聊天。Java 主开关仍为 false，没有启动或重启 MES。

2026-11-08 增加 [独立 Java 联调脚本](Test-DifyLive.ps1) 与 DifyLiveIntegrationTest，显式运行 5 项全部通过（0 失败、0 错误、0 跳过）；未显式开启时 5 项全部跳过。覆盖真实问答、NDJSON、成功重放、用户隔离、本地取消、匿名拒绝和错误密钥处理。登录会话存储使用测试替身，因此不宣称整套 MES/浏览器端到端通过。下一次启用 MES 时仍按 [配置清单](STAGE2-DIFY-SETUP.md) 完成页面验收。

来源：[Dify 1.19.0 发布](https://github.com/langgenius/dify/releases/tag/1.19.0)、[官方 Docker Compose 部署](https://docs.dify.ai/en/self-host/deploy/quick-start/docker-compose)。
