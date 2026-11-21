[CmdletBinding()]
param([switch]$Build)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AgentRuntime.Common.ps1')
$config = Read-AgentRuntimeConfig
# Reuse the existing private Python knowledge-service credential; never expose it to the browser/model.
$knowledgeConfig = Join-Path $PSScriptRoot '.env.knowledge.local'
if (Test-Path -LiteralPath $knowledgeConfig -PathType Leaf) {
    foreach ($line in [IO.File]::ReadAllLines($knowledgeConfig)) {
        if ($line.StartsWith('MES_KNOWLEDGE_API_KEY=')) { $config['MES_KNOWLEDGE_API_KEY'] = $line.Substring('MES_KNOWLEDGE_API_KEY='.Length) }
    }
}
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$ragDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\rag'))
$classpathFile = Join-Path $ragDirectory 'data\stage5-classpath.txt'
$classDirectory = Join-Path $ragDirectory 'data\runtime-build\classes'
$port = 18082
if ($config.ContainsKey('MES_AGENT_PORT')) { $port = [int]$config['MES_AGENT_PORT'] }
if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
    Write-Host 'The preview port is already in use; no existing process was changed.'
    return
}
if ($Build) {
    & (Join-Path $PSScriptRoot 'Build-AssistantRuntime.ps1')
}
if (-not (Test-Path -LiteralPath $classpathFile) -or -not (Test-Path -LiteralPath (Join-Path $classDirectory 'com\upc\computer\assistant\v2\runtime\Stage5RuntimeMain.class'))) {
    throw 'Preview classes/classpath missing. Run this script with -Build first.'
}
$classpath = $classDirectory + ';' + [IO.File]::ReadAllText($classpathFile).Trim()
$argFile = Join-Path $ragDirectory 'data\stage5-java.args'
$argumentText = '-Xms64m' + "`n" + '-Xmx384m' + "`n" + '-cp' + "`n" + '"' + $classpath.Replace('\','/') + '"' + "`n" + 'com.upc.computer.assistant.v2.runtime.Stage5RuntimeMain' + "`n"
[IO.File]::WriteAllText($argFile,$argumentText,(New-Object Text.UTF8Encoding($false)))
$logDirectory = Join-Path $ragDirectory 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$old = @{}
try {
    foreach ($name in $config.Keys) { $old[$name] = [Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$config[$name],'Process') }
    $process = Start-Process -FilePath 'java' -ArgumentList ('@"' + $argFile + '"') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory 'stage5-java.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'stage5-java.stderr.log')
    $process.Id | Set-Content (Join-Path $ragDirectory 'data\stage5-java.pid')
    Write-Host ('Independent read-only preview starting on port ' + $port + '; PID ' + $process.Id + '. MES was not restarted.')
} finally {
    foreach ($name in $old.Keys) { [Environment]::SetEnvironmentVariable($name,$old[$name],'Process') }
}
