[CmdletBinding()]
param([ValidateRange(1024,65535)][int]$Port = 8088, [string]$Profiles = $env:SPRING_PROFILES_ACTIVE, [switch]$RestartCurrentMes)
$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if (-not $env:COMPREFACE_RECOGNITION_API_KEY -or -not $env:COMPREFACE_DETECTION_API_KEY) { throw 'Set the two service API Keys in this PowerShell process first; use Set-FaceEnvironment.ps1.' }
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
if ($listeners.Count) {
    if (-not $RestartCurrentMes -or $listeners.Count -ne 1) { throw "Port $Port is occupied. Stop the existing backend or use -RestartCurrentMes for this checkout's Maven process." }
    $server = Get-CimInstance Win32_Process -Filter "ProcessId=$($listeners[0])"
    $launcher = Get-CimInstance Win32_Process -Filter "ProcessId=$($server.ParentProcessId)"
    $mavenCheckout = '-Dmaven\.multiModuleProjectDirectory="?' + [regex]::Escape($backend) + '"?(?:\s|$)'
    if ($server.Name -ne 'java.exe' -or $server.CommandLine -notmatch '\bcom\.upc\.computer\.ComputerApplication\b' -or
        $launcher.Name -ne 'java.exe' -or $launcher.CommandLine -notmatch $mavenCheckout -or $launcher.CommandLine -notmatch 'spring-boot:run') {
        throw "Port $Port belongs to a different process. Stop the intended MES instance manually."
    }
    $process = Get-Process -Id $server.ProcessId -ErrorAction Stop
    if ([Math]::Abs(($process.StartTime - $server.CreationDate).TotalSeconds) -gt 1) { throw 'MES process changed; retry startup.' }
    $process.Kill()
    if (-not $process.WaitForExit(10000)) { throw 'MES has not exited yet.' }
}
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
