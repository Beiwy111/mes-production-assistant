# Windows MES 通过 Tailscale 调用 Ubuntu 上的 llama-server

在生产协作助手选择“本地模型”时，Windows 上的 MES 后端可以通过 Tailscale 调用 Ubuntu 上的 GPU。两台设备需接入同一 tailnet，并允许 Windows 访问 Ubuntu 的模型转发端口。下文以 `21434` 为转发端口。

## 1. 在 Ubuntu 确认或建立转发

在运行 `llama-server` 的电脑执行以下命令。用 `ss` 确认推理服务的实际监听端口，记下 `tailscale ip -4` 返回的 Ubuntu 地址。

```bash
ss -ltnp
tailscale ip -4
tailscale serve status
```

如果已有可用的转发，沿用它的端口，直接执行第 2 步。首次配置时，让 `llama-server` 监听本机回环地址，选择一个空闲的 tailnet 端口。先在本机检查模型健康状态，模型就绪后再建立转发。

```bash
read -r -p 'llama-server 在 127.0.0.1 上监听的端口：' model_port
tailnet_port=21434
curl --noproxy '*' --fail --silent --show-error "http://127.0.0.1:$model_port/health"

# 在上一步返回 {"status":"ok"} 后执行
sudo tailscale serve --bg --tcp="$tailnet_port" "tcp://127.0.0.1:$model_port"
tailscale serve status
```

`serve status` 应显示所选 TCP 端口指向模型的回环端口。此转发仅在 tailnet 内提供服务。需要停用这条转发时，在同一终端执行 `sudo tailscale serve --tcp="$tailnet_port" off`。[Tailscale Serve 命令](https://tailscale.com/docs/reference/tailscale-cli/serve#use-a-tcp-forwarder)

## 2. 在 Windows 验证实际推理

在**将要启动 MES 后端的 PowerShell 窗口**执行以下命令。若 Ubuntu 使用了其他转发端口，同步修改 `$tailnetPort`。在模型启用了认证时，取消密钥输入行的注释，输入模型服务配置的密钥。

```powershell
$ubuntuIp = (Read-Host 'Ubuntu 的 Tailscale IPv4 地址').Trim()
$tailnetPort = 21434
if (-not (Test-NetConnection -ComputerName $ubuntuIp -Port $tailnetPort -InformationLevel Quiet)) {
    throw '转发端口不可达，请核对 Ubuntu 地址、Tailscale 访问策略和转发状态。'
}
$baseUrl = 'http://{0}:{1}/v1' -f $ubuntuIp, $tailnetPort
# $env:MES_CHAT_LOCAL_API_KEY = [Net.NetworkCredential]::new('', (Read-Host '本地模型密钥' -AsSecureString)).Password
$headers = @{}
if ($env:MES_CHAT_LOCAL_API_KEY) {
    $headers['Authorization'] = 'Bearer ' + $env:MES_CHAT_LOCAL_API_KEY
}
$catalog = Invoke-RestMethod -Uri "$baseUrl/models" -Headers $headers -TimeoutSec 15 -MaximumRedirection 0 -ErrorAction Stop
$ids = @($catalog.data | ForEach-Object { $_.id } | Where-Object { $_ })
if ($ids.Count -eq 0) { throw '服务没有列出已加载的模型。' }
$modelId = if ($ids.Count -eq 1) {
    $ids[0]
} else {
    Write-Host ($ids -join [Environment]::NewLine)
    Read-Host '从以上列表输入模型 ID'
}
if ($ids -cnotcontains $modelId) { throw '所选模型 ID 与服务返回的列表不一致。' }

$body = @{
    model = $modelId
    messages = @(@{ role = 'user'; content = '请用一句中文说明什么是生产工单。' })
    stream = $false
} | ConvertTo-Json -Depth 5
$reply = Invoke-RestMethod -Method Post -Uri "$baseUrl/chat/completions" -Headers $headers `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
    -TimeoutSec 90 -MaximumRedirection 0 -ErrorAction Stop
$answer = [string]$reply.choices[0].message.content
if ([string]::IsNullOrWhiteSpace($answer)) { throw '推理接口返回了空回答。' }
$answer

$env:MES_CHAT_LOCAL_BASE_URL = $baseUrl
$env:MES_CHAT_LOCAL_MODEL = $modelId
```

能看到模型生成的中文回答，就验证了 Windows 到 Ubuntu 的模型调用。HTTP 401 通常表示需要核对模型服务的密钥。模型列表有结果但问答失败时，先查看 Ubuntu 的 `llama-server` 日志。[llama-server 接口文档](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)

## 3. 在 MES 里演示

1. 在同一个 PowerShell 窗口保留项目现有的数据库和登录环境变量，按项目原方式启动或重启 MES 后端。前端沿用原来的启动方式。
2. 登录 MES，打开“生产协作助手”，在“回答方式”中选择“本地模型”，关闭“RAG 知识检索”。发送“用一句话说明生产工单的用途”，可在 Ubuntu 的模型日志中核对这次请求。
3. 演示“本地模型 + RAG”时，先按项目说明启动 Windows 上的知识服务并为后端配置 `MES_KNOWLEDGE_API_KEY`。开启 RAG，提出与已入库资料有关的问题，查看回答下的来源或无匹配提示。API 模型使用自己的后端配置，“业务工作流”继续使用原有 Dify 网关。[助手工作台配置说明](WORKBENCH-SETUP.md)
