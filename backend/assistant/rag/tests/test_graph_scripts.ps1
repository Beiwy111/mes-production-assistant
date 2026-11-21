# No Docker invocation is allowed in this lock regression check.
$ErrorActionPreference='Stop'
$ragRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dataRoot=Join-Path $ragRoot 'data'
$lockFile=Join-Path $dataRoot '.graph-maintenance.lock'
$held=[IO.File]::Open($lockFile,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$script:DockerCalls=0
function docker { $script:DockerCalls++; throw 'Unexpected Docker invocation' }
try {
    foreach($file in @('Start-GraphLocal.ps1','Update-GraphLocal.ps1')) {
        $refused=$false
        try { & (Join-Path $ragRoot "..\deploy\$file") } catch {
            $refused=$_.Exception.Message -like '*maintenance lock*'
        }
        if(-not $refused -or $script:DockerCalls -ne 0) { throw "$file did not refuse the held maintenance lock before Docker." }
    }
    Write-Host 'Both graph scripts refuse a concurrent maintenance lock before changing services.'
} finally { $held.Dispose() }
