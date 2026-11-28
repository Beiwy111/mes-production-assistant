[CmdletBinding()]
param([switch]$Build, [switch]$PassThru)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AgentRuntime.Common.ps1')
$agentConfig = Read-AgentRuntimeConfig
$config = Read-AssistantReaderConfig -Reader workbench
$dailyConfig = Join-Path $PSScriptRoot '.env.daily-chat.local'
if (Test-Path -LiteralPath $dailyConfig -PathType Leaf) {
    $lines = [IO.File]::ReadAllLines($dailyConfig)
    if ($lines.Length -ne 1 -or $lines[0] -cnotmatch '^DASHSCOPE_API_KEY=(\S+)$') {
        throw 'Invalid daily chat configuration in .env.daily-chat.local.'
    }
    $config['DASHSCOPE_API_KEY'] = $matches[1]
}
foreach ($name in @('MES_AGENT_BOOTSTRAP_KEY','MES_AGENT_ALLOWED_ORDER_IDS')) {
    $config[$name] = $agentConfig[$name]
}
$privatePath = Join-Path $PSScriptRoot '.env.workbench.local'
if (Test-Path -LiteralPath $privatePath -PathType Leaf) {
    foreach ($line in [IO.File]::ReadAllLines($privatePath)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        $parts = ($line -split '=', 2)
        if ($parts.Count -ne 2 -or $parts[0] -notin @('MES_WORKBENCH_DIFY_API_KEY','MES_WORKBENCH_DIFY_BASE_URL') -or $config.ContainsKey($parts[0])) {
            throw 'Unknown or duplicate workbench configuration; values are not displayed.'
        }
        $config[$parts[0]] = $parts[1]
    }
}
# An absent app key starts the gateway in an explicitly unconfigured state.
if (-not $config.ContainsKey('MES_WORKBENCH_DIFY_API_KEY')) { $config['MES_WORKBENCH_DIFY_API_KEY'] = '' }
if (-not $config.ContainsKey('MES_WORKBENCH_DIFY_BASE_URL')) { $config['MES_WORKBENCH_DIFY_BASE_URL'] = 'http://127.0.0.1:18080/v1' }
$v2Config = Join-Path $PSScriptRoot '.env.workbench-v2.local'
$config['MES_WORKBENCH_V2_BINDING_FILE'] = $v2Config
if ([IO.File]::Exists($v2Config)) {
    foreach ($line in [IO.File]::ReadAllLines($v2Config)) {
        if (!$line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
        $pair = $line -split '=',2
        if ($pair.Count -ne 2 -or $pair[0] -notin @('MES_WORKBENCH_V2_DIFY_API_KEY','MES_WORKFLOW_V2_BASE_URL','MES_WORKFLOW_V2_API_KEY') -or $config.ContainsKey($pair[0])) {
            throw 'Invalid workflow v2 settings; values omitted.'
        }
        $config[$pair[0]] = $pair[1]
    }
}
# The managed desktop launcher imports these settings into Spring after spawning
# its sidecars. Reuse that same private file for the workflow entry and planner.
$cloudSettings = $env:MES_WORKFLOW_CLOUD_SETTINGS
$managed = Join-Path $env:USERPROFILE '.mes-production-assistant'
if (!$cloudSettings -and $PSScriptRoot -eq (Join-Path $managed 'runtime\backend\assistant\deploy')) {
    $cloudSettings = Join-Path $managed 'private\assistant-api.properties'
}
if ($cloudSettings -and [IO.File]::Exists($cloudSettings)) {
    foreach ($line in [IO.File]::ReadAllLines($cloudSettings)) {
        if ($line -match '^(MES_CHAT_API_KEY|DEEPSEEK_API_KEY|MES_CHAT_API_BASE_URL|MES_CHAT_API_MODEL)=(.+)$') {
            $config[$matches[1]] = $matches[2]
        }
    }
}
if (Get-NetTCPConnection -State Listen -LocalPort 18083 -ErrorAction SilentlyContinue) {
    throw 'Port 18083 is already in use. No process was stopped or changed.'
}
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$dataDirectory = Join-Path $projectRoot 'backend\assistant\rag\data'
$logDirectory = Join-Path $projectRoot 'backend\assistant\rag\logs'
New-Item -ItemType Directory -Path $dataDirectory,$logDirectory -Force | Out-Null
$classpathFile = Join-Path $dataDirectory 'stage5-classpath.txt'
$classDirectory = Join-Path $dataDirectory 'runtime-build\classes'
if (Test-Path -LiteralPath (Join-Path $dataDirectory 'workbench-build\classes') -PathType Container) {
    $classDirectory = Join-Path $dataDirectory 'workbench-build\classes'
}
if ($Build) {
    & (Join-Path $PSScriptRoot 'Build-AssistantRuntime.ps1') -OutputName workbench-build
    $classDirectory = Join-Path $dataDirectory 'workbench-build\classes'
}
if (-not (Test-Path -LiteralPath $classpathFile) -or -not (Test-Path -LiteralPath (Join-Path $classDirectory 'com\upc\computer\assistant\v2\runtime\WorkbenchRuntimeMain.class'))) {
    throw 'Workbench classes/classpath missing. Run this script with -Build first.'
}
$classpath = $classDirectory + ';' + [IO.File]::ReadAllText($classpathFile).Trim()
$argFile = Join-Path $dataDirectory 'workbench-java.args'
$argumentText = '-Xms64m' + "`n" + '-Xmx384m' + "`n" + '-Dsun.net.httpserver.maxReqTime=15' + "`n" + '-Dsun.net.httpserver.maxRspTime=360' + "`n" + '-cp' + "`n" + '"' + $classpath.Replace('\','/') + '"' + "`n" + 'com.upc.computer.assistant.v2.runtime.WorkbenchRuntimeMain' + "`n"
[IO.File]::WriteAllText($argFile,$argumentText,(New-Object Text.UTF8Encoding($false)))
$javaExe = Resolve-AssistantJava
$old = @{}
try {
    foreach ($name in $config.Keys) { $old[$name] = [Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$config[$name],'Process') }
    $process = Start-Process -FilePath $javaExe -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'workbench-java.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'workbench-java.stderr.log')
    $process.Id | Set-Content (Join-Path $dataDirectory 'workbench-java.pid')
    if ($PassThru) { $process }
    Write-Host ('Independent workbench starting on 127.0.0.1:18083; PID ' + $process.Id + '. MES was not restarted.')
    if ($config['MES_WORKFLOW_V2_BASE_URL'] -and !$config['MES_WORKBENCH_V2_DIFY_API_KEY']) {
        $python = Join-Path $projectRoot 'backend\assistant\rag\.venv\Scripts\python.exe'
        if ([IO.File]::Exists($python)) {
            $resume = Join-Path $PSScriptRoot 'Resume-WorkflowV2Local.ps1'
            $shell = (Get-Process -Id $PID).Path
            Start-Process -FilePath $shell -ArgumentList ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $resume + '" -GatewayProcessId ' + $process.Id) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $logDirectory 'workflow-v2-setup.log') -RedirectStandardError (Join-Path $logDirectory 'workflow-v2-setup.err') | Out-Null
        }
    }
} finally {
    foreach ($name in $old.Keys) { [Environment]::SetEnvironmentVariable($name,$old[$name],'Process') }
}
