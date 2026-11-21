[CmdletBinding()]
param([switch]$Build)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AgentRuntime.Common.ps1')
$agentConfig = Read-AgentRuntimeConfig
$config = @{}
foreach ($name in @('MES_AGENT_BOOTSTRAP_KEY','MES_AGENT_ALLOWED_ORDER_IDS','MES_AGENT_JDBC_URL','MES_AGENT_JDBC_USER','MES_AGENT_JDBC_PASSWORD')) {
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
$old = @{}
try {
    foreach ($name in $config.Keys) { $old[$name] = [Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$config[$name],'Process') }
    $process = Start-Process -FilePath 'java' -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'workbench-java.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'workbench-java.stderr.log')
    $process.Id | Set-Content (Join-Path $dataDirectory 'workbench-java.pid')
    Write-Host ('Independent workbench starting on 127.0.0.1:18083; PID ' + $process.Id + '. MES was not restarted.')
} finally {
    foreach ($name in $old.Keys) { [Environment]::SetEnvironmentVariable($name,$old[$name],'Process') }
}
