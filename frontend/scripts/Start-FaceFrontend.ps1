[CmdletBinding()]
param([ValidateRange(1024,65535)][int]$Port = 5173, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$frontend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
function Find-AvailablePort([int]$Preferred) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Preferred)
    try { $probe.Start(); return ([Net.IPEndPoint]$probe.LocalEndpoint).Port }
    catch {
        $probe.Stop()
        $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $probe.Start()
        return ([Net.IPEndPoint]$probe.LocalEndpoint).Port
    } finally { $probe.Stop() }
}
$selected = Find-AvailablePort $Port
if ($selected -ne $Port) { Write-Host "Port $Port is occupied/reserved by Windows. Using free local port $selected without changing vite.config.js." }
Write-Host "Frontend: http://127.0.0.1:$selected ; existing /api proxy still targets backend 8088."
if ($CheckOnly) { return }
Push-Location $frontend
try {
    & npm.cmd 'run' 'dev' '--' '--host' '127.0.0.1' '--port' ([string]$selected) '--strictPort'
    if ($LASTEXITCODE -ne 0) { throw 'Frontend startup failed.' }
} finally { Pop-Location }
