[CmdletBinding()]
param([ValidateRange(1024,65535)][int]$Port = 8088, [string]$Profiles = $env:SPRING_PROFILES_ACTIVE)
$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if (-not $env:COMPREFACE_RECOGNITION_API_KEY -or -not $env:COMPREFACE_DETECTION_API_KEY) { throw 'Set the two service API Keys in this PowerShell process first; use Set-FaceEnvironment.ps1.' }
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is occupied. Stop the existing backend yourself or choose another -Port; no process was stopped." }
$env:FACE_AUTH_ENABLED = 'true'
Write-Host 'Apply the additive face-auth-v1.sql migration first. Existing MySQL/Redis configuration is reused.'
Write-Host "Backend port: $Port. Face login remains demo-only; FACE_AUTH_ALLOW_DEMO_LOGIN must be true to allow it."
Push-Location $backend
try {
    $activeProfiles = ((@($Profiles -split ',' | Where-Object { $_.Trim() }) + @('face')) | Select-Object -Unique) -join ','
    $maven = & (Join-Path $PSScriptRoot 'Resolve-Maven.ps1') -BackendDirectory $backend
    & $maven '-DskipTests' 'spring-boot:run' '-Dspring-boot.run.main-class=com.upc.computer.ComputerApplication' "-Dspring-boot.run.profiles=$activeProfiles" "-Dspring-boot.run.arguments=--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw 'Backend exited unsuccessfully.' }
} finally { Pop-Location }
