# Read-only browser acceptance helper: restart only the two independent assistant JVMs.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$mesBefore = (Get-NetTCPConnection -State Listen -LocalPort 8088).OwningProcess
$targets = @(
    @{Port=18082; PidFile='stage5-java.pid'; Arg='stage5-java.args'},
    @{Port=18083; PidFile='workbench-java.pid'; Arg='workbench-java.args'}
)
# Verify both identities before stopping either process. Never touch MES or frontend.
foreach ($target in $targets) {
    $target.RuntimePid = [int](Get-Content -LiteralPath (Join-Path $projectRoot ('backend\assistant\rag\data\' + $target.PidFile)))
    $listener = Get-NetTCPConnection -State Listen -LocalPort $target.Port
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $target.RuntimePid)
    if ($listener.OwningProcess -ne $target.RuntimePid -or $process.CommandLine -notmatch [regex]::Escape($target.Arg)) {
        throw 'Independent service identity mismatch; nothing stopped.'
    }
}
foreach ($target in $targets) { Stop-Process -Id $target.RuntimePid }
& (Join-Path $projectRoot 'backend\assistant\deploy\Start-AgentRuntimeLocal.ps1')
& (Join-Path $projectRoot 'backend\assistant\deploy\Start-WorkbenchLocal.ps1')
$until = (Get-Date).AddSeconds(30)
do {
    $ready = @(Get-NetTCPConnection -State Listen -LocalPort 18082,18083 -ErrorAction SilentlyContinue)
    if ($ready.Count -ge 2) { break }
    Start-Sleep -Milliseconds 300
} while ((Get-Date) -lt $until)
if ($ready.Count -lt 2) { throw 'Independent service startup timed out.' }
$mesAfter = (Get-NetTCPConnection -State Listen -LocalPort 8088).OwningProcess
if ($mesBefore -ne $mesAfter) { throw 'MES process changed unexpectedly.' }
Write-Output ('CONTEXT_RESTART_RESULT:' + (@{originalMesUnchanged=$true; ports=@($ready | ForEach-Object {$_.LocalPort})} | ConvertTo-Json -Compress))
