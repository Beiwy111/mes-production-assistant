# CompreFace 人脸服务

此目录管理晶程 MES 使用的官方 CompreFace 1.2.0 CPU 部署。前端、后端和人脸服务在仓库中并列，浏览器经 MES 后端访问识别服务。默认地址为 `http://127.0.0.1:8000`。

## Windows 11 启动

打开 Docker Desktop 的 Linux 引擎，在仓库根目录运行以下 PowerShell 命令。

```powershell
.\compreface\Start-CompreFace.ps1
```

若本机已有其他检出目录管理的 CompreFace，启动器会检查项目、数据卷、服务、端口和识别引擎健康状态，然后复用运行中的服务。已停止的服务请从原部署目录启动。复用时无需将原部署的 `.env` 复制到这里。

从任一目录运行 `docker ps --filter label=com.docker.compose.project=compreface_120` 可查看已有服务。MES 的摄像头授权、密钥配置和前后端启动步骤见[后端人脸登录说明](../backend/docs/face-auth/README.md)。

## 登录数据在哪里

| 数据 | 保存位置 |
| --- | --- |
| CompreFace 管理账号、Recognition/Detection 服务、服务 Key 和已录入特征 | 原 Docker 外部卷 `compreface_120_postgres-data`，挂载到 PostgreSQL 的 `/var/lib/postgresql/data` |
| MES 业务账号、绑定到的 Subject/样本 ID 和绑定版本 | 原 MySQL 库 `display_manufacturing` 的用户表与 `mes_face_binding` 等表 |
| 服务端凭据 | 本机 `.env` 保存该部署的数据库连接设置，MES 后端继续使用原来的两把服务 Key |

主项目名固定为 `compreface_120`。主数据卷缺失或现有数据库挂载不同，启动会终止。此时先检查 Docker Desktop 的上下文和原卷。已录入的绑定依赖原 Recognition Key 和 MES 使用的原地址，修改服务地址、创建新应用或轮换 Key 会影响现有绑定。

私有 `.env` 由部署维护者保存在本机，Git 只包含 `.env.example` 模板。沿用旧账号绑定需要对应的 MES 数据库、原 Recognition 服务及其特征数据。另一台机器上的独立业务数据库应配套使用独立的人脸服务并重新录入。

## 发行配置与独立测试

`upstream/docker-compose.yml` 来自[官方 1.2.0 发行版](https://github.com/exadel-inc/CompreFace/releases/tag/v1.2.0)，许可证见 [LICENSE](LICENSE)。实际服务和模型由五个版本固定的官方镜像提供。顶层 `docker-compose.yml` 复用其服务定义，指定原项目、外部数据卷、8000 端口和 `SAVE_IMAGES_TO_DB=false`。识别引擎保留原健康检查，启动宽限时间设为 90 秒，以容纳 Windows 笔记本上 CPU 模型的冷启动。

在新机器上部署独立演示环境时，先复制 `compreface\.env.example` 为 `compreface\.env` 并填写自选的 `postgres_password`，再从仓库根目录运行 `.\compreface\Start-CompreFace.ps1 -Isolated -LoopbackOnly -Port 8001`。测试项目 `mes-compreface-face-isolated` 使用独立网络和数据卷。随后在 8001 的控制台创建 Recognition 与 Detection 服务，并在 MES 密钥配置命令中指定 `-BaseUrl 'http://127.0.0.1:8001'`。
