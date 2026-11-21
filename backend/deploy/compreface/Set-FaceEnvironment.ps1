[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:8000')
$ErrorActionPreference = 'Stop'
function Read-ProcessSecret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
}
$env:COMPREFACE_BASE_URL = $BaseUrl
$env:COMPREFACE_RECOGNITION_API_KEY = Read-ProcessSecret 'CompreFace Recognition service API Key'
$env:COMPREFACE_DETECTION_API_KEY = Read-ProcessSecret 'CompreFace Detection service API Key'
$env:FACE_AUTH_ENABLED = 'true'
$env:FACE_AUTH_ALLOW_DEMO_LOGIN = 'true'
Write-Host 'Configured for this PowerShell process only. Demo login enabled; reliable liveness is NOT available.'
Write-Host 'Start the backend from this same terminal. Keys were not written to disk or printed.'
