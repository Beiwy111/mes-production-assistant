[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:8000')
$ErrorActionPreference = 'Stop'
Get-Command java,node,docker -ErrorAction SilentlyContinue | Select-Object Name,Source
& docker version --format 'Docker client {{.Client.Version}}; server {{.Server.Version}}'
& docker ps --format '{{.Names}} | {{.Image}} | {{.Ports}} | {{.Status}}'
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(8000,8001,8088,3306,6379,5173) } | Select-Object LocalAddress,LocalPort
$response = Invoke-WebRequest -Uri $BaseUrl -TimeoutSec 15 -UseBasicParsing
Write-Host "CompreFace web HTTP status: $([int]$response.StatusCode) (not a face-accuracy test)"
Write-Host "Recognition key present: $([bool]$env:COMPREFACE_RECOGNITION_API_KEY)"
Write-Host "Detection key present: $([bool]$env:COMPREFACE_DETECTION_API_KEY)"
if ($env:COMPREFACE_RECOGNITION_API_KEY) {
    try {
        $null = Invoke-RestMethod -Uri "$($BaseUrl.TrimEnd('/'))/api/v1/recognition/subjects" -Headers @{ 'x-api-key' = $env:COMPREFACE_RECOGNITION_API_KEY } -TimeoutSec 15
        Write-Host 'Recognition service authentication: OK. Subject data was not printed.'
    } catch { Write-Host 'Recognition authentication check failed; keys and response body are intentionally omitted.' }
}
