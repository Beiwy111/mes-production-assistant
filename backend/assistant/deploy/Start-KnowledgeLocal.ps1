[CmdletBinding()]
param([switch]$SkipQdrantStart)
$ErrorActionPreference = 'Stop'
$ragDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\rag'))
$pythonPath = Join-Path $ragDirectory '.venv\Scripts\python.exe'
$configPath = Join-Path $PSScriptRoot '.env.knowledge.local'
$qdrantConfig = Join-Path $PSScriptRoot '.env.qdrant.local'
foreach ($required in @($pythonPath, $configPath, $qdrantConfig, (Join-Path $ragDirectory 'data\active.json'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw 'Knowledge environment is not prepared. Follow STAGE3-KNOWLEDGE-SETUP.md first.'
    }
}
if (-not $SkipQdrantStart) {
    & docker compose --project-name mes-knowledge-local --env-file $qdrantConfig -f (Join-Path $PSScriptRoot 'knowledge.compose.yml') up -d
    if ($LASTEXITCODE -ne 0) { throw 'Qdrant startup failed. MES was not changed.' }
}
if (Get-NetTCPConnection -State Listen -LocalPort 18081 -ErrorAction SilentlyContinue) {
    Write-Host 'Port 18081 is already in use; no existing process was changed. Check http://127.0.0.1:18081/health.'
    return
}
$logDirectory = Join-Path $ragDirectory 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$oldPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = Join-Path $ragDirectory 'src'
    $process = Start-Process -FilePath $pythonPath -ArgumentList @('-m', 'mes_knowledge.cli', 'serve') -WorkingDirectory $ragDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDirectory 'api.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'api.stderr.log') -PassThru
    $process.Id | Set-Content (Join-Path $ragDirectory 'data\api.pid')
    Write-Host ('Knowledge API starting in background; PID ' + $process.Id + '. Verify /health before using.')
} finally {
    $env:PYTHONPATH = $oldPythonPath
}
