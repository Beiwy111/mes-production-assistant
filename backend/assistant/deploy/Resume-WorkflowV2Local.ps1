[CmdletBinding()]
param([Parameter(Mandatory)][int]$GatewayProcessId)
$ErrorActionPreference = 'Stop'
$gateway = Get-Process -Id $GatewayProcessId -ErrorAction SilentlyContinue
if (!$gateway -or $gateway.ProcessName -ne 'java') { return }
$settings = Join-Path $PSScriptRoot '.env.workbench-v2.local'
$python = Join-Path $PSScriptRoot '..\rag\.venv\Scripts\python.exe'
$publisher = Join-Path $PSScriptRoot 'Provision-Stage5Dify.py'
$failures = 0
try {
    # Keep the original process handle so a reused Windows PID cannot extend this setup.
    $null = $gateway.Handle
    do {
        if (![IO.File]::Exists($settings)) { return }
        if (Select-String -LiteralPath $settings -Pattern '^MES_WORKBENCH_V2_DIFY_API_KEY=app-[a-zA-Z0-9]+$' -Quiet) { return }
        & $python $publisher --local-v2 --if-online | Where-Object { $_ -notlike 'Ubuntu is unavailable*' }
        if (Select-String -LiteralPath $settings -Pattern '^MES_WORKBENCH_V2_DIFY_API_KEY=app-[a-zA-Z0-9]+$' -Quiet) { return }
        if ($LASTEXITCODE -eq 0) { $delay = 60000; $failures = 0 }
        else {
            $failures++
            if ($failures -ge 3) { throw 'Local workflow publication failed three times. Review the v2 setup log and rerun the provisioner.' }
            $delay = 300000
        }
    } until ($gateway.WaitForExit($delay))
} finally { $gateway.Dispose() }
