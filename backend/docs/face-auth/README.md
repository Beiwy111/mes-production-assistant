# MES 人脸登录

在原 MES 登录页输入账号并选择“人脸登录”。浏览器获取授权后自动检测画面，后端用 CompreFace Recognition 将采集帧与这个账号绑定的样本作 1:1 比对。成功后沿用 MES 的 JWT、Redis 会话、菜单和登出流程。默认相似度阈值为 0.85。目前缺少可靠活体检测，照片或视频可能通过验证。

## 本机重启后

在 `backend` 运行 `mvn spring-boot:run`，在 `frontend` 运行 `npm run dev`。已配置的 Windows 开发机在前端开始监听时会自动启动本机服务。前端终端出现 `[OK] CompreFace recognition API` 后，在登录页输入原 MES 账号，选择 **人脸登录**。若启动中途遇到问题，也可双击 `Start-Assistant.cmd` 单独重试。

本机 Spring 配置导入 `backend/deploy/compreface/mes-face.local.yaml` 和 `services.local.env`，普通启动即可读取已有 Key。人脸绑定同时依赖 MES MySQL 中的绑定记录和 Docker 卷 `mes-compreface-face-isolated_postgres-data` 中的识别样本。启动器复用这两处数据。

## 首次启用

已有服务使用 8000 时，可从项目根目录用独立端口启动 CompreFace。首次部署需按 [CompreFace 部署说明](../../../compreface/README.md) 准备本机配置，首次接入业务库需执行下方的人脸表迁移。

```powershell
.\compreface\Start-CompreFace.ps1 -Isolated -LoopbackOnly -Port 8001
```

打开 [本机人脸控制台](http://127.0.0.1:8001)，在应用内分别创建 Recognition 和 Detection 服务。在同一个 PowerShell 窗口执行下面两行，按提示输入各自的 Key。第二行会重新启动当前项目在 8088 上通过 Maven 运行的 MES 后端，使其加载人脸配置；原前端可继续使用。

```powershell
.\backend\deploy\compreface\Set-FaceEnvironment.ps1 -BaseUrl 'http://127.0.0.1:8001'
.\backend\deploy\compreface\Start-MesFaceBackend.ps1 -RestartCurrentMes
```

用原账号密码登录，在右上角账号菜单打开 **我的人脸信息**，完成本人授权、采集和绑定。退出后，在登录页输入同一账号，选择 **人脸登录**。上述 Key 仅在当前 PowerShell 进程中有效。

## 在已有部署上启用

从仓库根目录打开 PowerShell。沿用原 MES MySQL、Redis、CompreFace 地址和原服务的两把 Key，即可沿用已有绑定。人脸特征保存在 CompreFace 的 PostgreSQL 卷中。MES 用户 ID、样本 ID 和绑定版本保存在原 MySQL 中。

1. 确认本机 CompreFace 已启动。已有部署使用 `http://127.0.0.1:8000`，可直接连接。在它的控制台进入原应用下的 Recognition 和 Detection 两项服务，分别取得 API Key。管理账号只用于这个控制台。
2. 让 MES 后端使用现有的数据库、Redis 和 JWT 配置。仓库支持通过进程环境变量 `MYSQL_PASSWORD` 和 `MES_JWT_SECRET` 或本机 Spring 私有配置提供原有凭据。若该库已经应用人脸迁移，跳过下面的建表步骤。新库需要已有 MES 用户表，然后按下节创建三张人脸表。
3. 在同一个 PowerShell 窗口运行下面两行。第一行会隐蔽提示输入两把 Key，第二行将 `face` 加到现有 Spring profiles 中，并在 8088 启动 MES 后端。

```powershell
.\backend\deploy\compreface\Set-FaceEnvironment.ps1
.\backend\deploy\compreface\Start-MesFaceBackend.ps1
```

4. 在仓库根目录打开另一个 PowerShell 窗口运行以下命令。浏览器打开脚本输出的本机地址，在 Windows 及浏览器设置中允许摄像头访问。

```powershell
npm.cmd --prefix .\frontend ci --replace-registry-host=always
.\frontend\scripts\Start-FaceFrontend.ps1
```

5. 首次录入时，用密码登录，进入业务界面右上角账号菜单的“我的人脸信息”。授权、输入本人当前密码、自动采集，再核对定格画面及目标账号并确认。已有绑定可直接登出，从登录页验证。管理员可在“系统管理 → 用户管理 → 人脸”查看和撤销选定用户的绑定。

## 新数据库的人脸表

在仓库根目录运行。将连接信息指向该 MES 实例实际使用的 MySQL。迁移仅执行三条 `CREATE TABLE IF NOT EXISTS`，创建 `mes_face_binding`、`mes_face_cleanup` 和 `mes_face_audit`。首次运行时需要本机已有 MySQL Connector/J，可用 `-MysqlDriverPath` 指定其 JAR 路径。

```powershell
$env:FACE_DB_URL = 'jdbc:mysql://127.0.0.1:3306/display_manufacturing?serverTimezone=Asia/Shanghai'
$env:FACE_DB_USER = Read-Host 'MES MySQL user'
$env:FACE_DB_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'MES MySQL password' -AsSecureString)).Password
.\backend\deploy\compreface\Apply-FaceMigration.ps1 -Check
.\backend\deploy\compreface\Apply-FaceMigration.ps1 -Apply
Remove-Item Env:FACE_DB_PASSWORD
```

## 验证

- 后端契约测试使用虚构账号、H2 和模拟识别服务。运行 `.\backend\deploy\compreface\Test-FaceAdapter.ps1`。
- 浏览器用例使用模拟摄像头和接口。安装前端依赖后运行 `npm.cmd --prefix .\frontend run test:face`，需要 Microsoft Edge。若默认测试端口被 Windows 保留，可通过 `FACE_TEST_PORT` 指定可用端口。
- 真人验收时，先确认本人可以登录，再由另一位授权参与者验证同一账号会被拒绝。已登录状态下可在“我的人脸信息 → 匹配测试”查看当前绑定的分数、阈值与版本，保留已有样本。

部署、接口和隐私细节见 [CompreFace 部署说明](../../../compreface/README.md)、[人脸 API](API.md) 和[数据处理说明](SECURITY.md)。
