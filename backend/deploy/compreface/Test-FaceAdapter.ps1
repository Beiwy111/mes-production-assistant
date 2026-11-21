[CmdletBinding()]
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $backend
try {
    Write-Host 'Contract tests only: fictional accounts, generated NON-FACE images, H2 and HTTP/Redis test doubles.'
    $maven = & (Join-Path $PSScriptRoot 'Resolve-Maven.ps1') -BackendDirectory $backend
    $testArgs = @('-q', '-Dtest=FaceAuthIntegrationTest,FaceSchemaToolTest', 'test')
    if ($Offline) { $testArgs = @('-o') + $testArgs }
    & $maven @testArgs
    if ($LASTEXITCODE -ne 0) { throw 'Face adapter tests failed.' }
    Write-Host 'Adapter tests passed. This does NOT establish real recognition accuracy or liveness protection.'
} finally { Pop-Location }
