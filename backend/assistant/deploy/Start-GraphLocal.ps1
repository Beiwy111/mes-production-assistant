[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'GraphLocal.Common.ps1')
$ragDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\rag'))
$config = Join-Path $PSScriptRoot '.env.neo4j.local'
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) {
    throw 'Private Neo4j configuration missing; follow STAGE4-GRAPHRAG-SETUP.md.'
}
$lock = Enter-GraphMaintenanceLock $ragDirectory
$oldPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = Join-Path $ragDirectory 'src'
    & docker compose --project-name mes-graph-local --env-file $config -f (Join-Path $PSScriptRoot 'neo4j.compose.yml') up -d
    if ($LASTEXITCODE -ne 0) { throw 'Neo4j startup failed; no MES process was changed.' }
    if (-not (Wait-GraphMode (Join-Path $ragDirectory '.venv\Scripts\python.exe') $true)) {
        throw 'Neo4j read-only readiness check failed.'
    }
    Write-Host 'Neo4j read-only mode verified. Browser: http://127.0.0.1:17474'
} finally { $env:PYTHONPATH = $oldPythonPath; $lock.Dispose() }
