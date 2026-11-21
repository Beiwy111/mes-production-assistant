# Explicit maintenance window. Vector retrieval stays available while graph reads degrade.
[CmdletBinding()]
param(
    [ValidateSet('Public', 'Business')][string]$Kind = 'Public',
    [string]$ExportFile,
    [string]$ExpectedOrder,
    [switch]$Synthetic
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'GraphLocal.Common.ps1')
$ragDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\rag'))
$pythonPath = Join-Path $ragDirectory '.venv\Scripts\python.exe'
$config = Join-Path $PSScriptRoot '.env.neo4j.local'
$compose = Join-Path $PSScriptRoot 'neo4j.compose.yml'
$overlay = Join-Path $PSScriptRoot 'neo4j-maintenance.compose.yml'
foreach ($required in @($pythonPath, $config, $compose, $overlay)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw 'Missing graph maintenance prerequisite.' }
}
$cliArguments = @('-m', 'mes_knowledge.cli', 'graph-build')
if ($Kind -eq 'Business') {
    if (-not $ExportFile -or -not (Test-Path -LiteralPath $ExportFile -PathType Leaf) -or $ExpectedOrder -notmatch '^[1-9][0-9]{0,18}$') {
        throw 'Business import requires an existing export file and explicit expected order primary key.'
    }
    $resolvedExport = (Resolve-Path -LiteralPath $ExportFile).Path
    $cliArguments = @('-m', 'mes_knowledge.cli', 'business-sync', '--export-file', $resolvedExport, '--expected-order', $ExpectedOrder)
    if ($Synthetic) { $cliArguments += '--synthetic' } else { $cliArguments += '--maintenance-export' }
}
$base = @('compose', '--project-name', 'mes-graph-local', '--env-file', $config, '-f', $compose)
$oldPythonPath = $env:PYTHONPATH
$maintenanceLock = Enter-GraphMaintenanceLock $ragDirectory
try {
    $env:PYTHONPATH = Join-Path $ragDirectory 'src'
    & docker @base -f $overlay up -d
    if ($LASTEXITCODE -ne 0) { throw 'Could not enter graph maintenance mode.' }
    $ready = Wait-GraphMode $pythonPath $false
    if (-not $ready) { throw 'Neo4j did not become ready for maintenance.' }
    & $pythonPath @cliArguments
    if ($LASTEXITCODE -ne 0) { throw 'Graph import failed. See safe error output; prior public release was preserved.' }
} finally {
    try {
        & docker @base up -d
        if ($LASTEXITCODE -ne 0 -or -not (Wait-GraphMode $pythonPath $true)) {
            throw 'Neo4j read-only restore failed. Run Start-GraphLocal.ps1 before graph queries.'
        }
        Write-Host 'Neo4j read-only mode restored and verified.'
    } finally {
        $env:PYTHONPATH = $oldPythonPath
        $maintenanceLock.Dispose()
    }
}
