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
. (Join-Path $PSScriptRoot 'ExperienceCredential.ps1')
$priorExperienceKey = $env:MES_KNOWLEDGE_EXPERIENCE_API_KEY
$ingestionEnvironment = @{}
try {
    $ingestionConfig = Join-Path $PSScriptRoot '.env.ingestion.local'
    if (Test-Path -LiteralPath $ingestionConfig) {
        $settings = Get-Content -LiteralPath $ingestionConfig -Raw | ConvertFrom-StringData
        $writer = Get-Content -LiteralPath $qdrantConfig | Where-Object { $_ -match '^QDRANT__SERVICE__API_KEY=' } | Select-Object -First 1
        if ($writer) { $settings['MES_INGEST_QDRANT_API_KEY'] = $writer.Substring($writer.IndexOf('=')+1).Trim() }
        foreach ($name in @('MES_KNOWLEDGE_INGESTION_API_KEY','MES_INGEST_GRAPH_URI','MES_INGEST_GRAPH_USER','MES_INGEST_GRAPH_PASSWORD','MES_INGEST_QDRANT_API_KEY')) {
            $ingestionEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
            [Environment]::SetEnvironmentVariable($name, $settings[$name])
        }
    }
    $env:MES_KNOWLEDGE_EXPERIENCE_API_KEY = Read-ExperienceCredential
    $env:PYTHONPATH = Join-Path $ragDirectory 'src'
    # Limit inherited handles to the log files and NUL. A redirected PowerShell caller can then exit
    # while the long-lived API stays up, including under an installer capturing console output.
    $launch = @'
import pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1])
with (root / 'logs/api.stdout.log').open('wb') as out, (root / 'logs/api.stderr.log').open('wb') as err:
    server = subprocess.Popen([sys.executable, '-m', 'mes_knowledge.cli', 'serve'], cwd=root,
                              stdin=subprocess.DEVNULL, stdout=out, stderr=err, close_fds=True,
                              creationflags=subprocess.CREATE_NO_WINDOW)
print(server.pid)
'@
    $serverPid = & $pythonPath -c $launch $ragDirectory
    if ($LASTEXITCODE -ne 0 -or $serverPid -notmatch '^\d+$') { throw 'Knowledge API process launch failed.' }
    $serverPid | Set-Content (Join-Path $ragDirectory 'data\api.pid')
    Write-Host ('Knowledge API starting in background; PID ' + $serverPid + '. Verify /health before using.')
} finally {
    foreach ($name in $ingestionEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $ingestionEnvironment[$name]) }
    $env:MES_KNOWLEDGE_EXPERIENCE_API_KEY = $priorExperienceKey
    $env:PYTHONPATH = $oldPythonPath
}
