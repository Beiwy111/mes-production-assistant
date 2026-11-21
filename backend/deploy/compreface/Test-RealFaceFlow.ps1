[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8088',
    [string]$AccountA = 'face_test_a',
    [string]$AccountB = 'face_test_b',
    [Parameter(Mandatory)][string]$FaceAPath,
    [Parameter(Mandatory)][string]$FaceBPath,
    [string]$UpdatedFaceAPath,
    [switch]$AuthorizedTestData
)
$ErrorActionPreference = 'Stop'
if (-not $AuthorizedTestData) { throw 'Explicit -AuthorizedTestData is required. Obtain participant consent first.' }
if ($AccountA -notmatch '^face_test_[a-zA-Z0-9_]+$' -or $AccountB -notmatch '^face_test_[a-zA-Z0-9_]+$' -or $AccountA -eq $AccountB) { throw 'Use two distinct fictional face_test_ accounts, already enabled with ordinary roles.' }
function Read-Secret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
}
function Image-Base64([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -gt 3MB) { throw 'A supplied test image exceeds 3MB.' }
    return [Convert]::ToBase64String([IO.File]::ReadAllBytes($file.FullName))
}
function Api([string]$Method, [string]$Path, [string]$Token, $Body) {
    $arguments = @{ Method=$Method; Uri=($BaseUrl.TrimEnd('/') + $Path); TimeoutSec=90 }
    if ($Token) { $arguments.Headers = @{ Authorization="Bearer $Token" } }
    if ($null -ne $Body) { $arguments.ContentType='application/json; charset=utf-8'; $arguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress -Depth 5)) }
    try { return Invoke-RestMethod @arguments }
    catch { throw "HTTP request failed for $Method $Path. Sensitive response/body omitted." }
}
function Require-Code($Result, [int]$Expected, [string]$Case) {
    if ($Result.code -ne $Expected) { throw "$Case failed: expected code $Expected; got $($Result.code). No success claimed." }
    Write-Host "PASS: $Case"
}
function Face-Login([string]$Account, [string]$Image, [string]$Version) {
    $nonce = Api 'POST' '/auth/face/challenge' '' @{username=$Account}
    if ($nonce.code -ne 200) { throw 'Challenge failed. Check demo opt-in and rate limits.' }
    return Api 'POST' '/auth/face/login' '' @{username=$Account;challengeId=$nonce.data.challengeId;imageBase64=$Image;consent=$true;consentVersion=$Version}
}
$passwordA = Read-Secret "Password for $AccountA"
$passwordB = Read-Secret "Password for $AccountB"
$cleanup = @()
try {
    $caps = Api 'GET' '/auth/face/capabilities' '' $null
    if (-not $caps.data.loginAllowed) { throw 'Real service is not configured or demo login is not explicitly enabled.' }
    $version = $caps.data.consentVersion
    $imageA = Image-Base64 $FaceAPath; $imageB = Image-Base64 $FaceBPath
    $updatedA = if ($UpdatedFaceAPath) { Image-Base64 $UpdatedFaceAPath } else { $imageA }
    $a = Api 'POST' '/auth/login' '' @{username=$AccountA;password=$passwordA}; Require-Code $a 200 'A original password login'
    $b = Api 'POST' '/auth/login' '' @{username=$AccountB;password=$passwordB}; Require-Code $b 200 'B original password login'
    $infoA = Api 'GET' '/auth/userInfo' $a.data.token $null
    $infoB = Api 'GET' '/auth/userInfo' $b.data.token $null
    if ($infoA.data.roleCode -eq 'ADMIN' -or $infoB.data.roleCode -eq 'ADMIN') { throw 'Use ordinary test roles to verify isolation.' }
    foreach ($item in @(@{token=$a.data.token}, @{token=$b.data.token})) {
        $before = Api 'GET' '/auth/face/me' $item.token $null; Require-Code $before 200 'Read initial face status'
        if ($before.data.enrolled -or $before.data.pendingCleanup -or $before.data.state -ne 'NONE') { throw 'Refusing to replace an existing face binding or pending operation. Use fresh test accounts.' }
    }
    $cleanup += @{account=$AccountA;password=$passwordA}
    Require-Code (Api 'PUT' '/auth/face/me' $a.data.token @{imageBase64=$imageA;password=$passwordA;consent=$true;consentVersion=$version}) 200 'A enrollment'
    $cleanup += @{account=$AccountB;password=$passwordB}
    Require-Code (Api 'PUT' '/auth/face/me' $b.data.token @{imageBase64=$imageB;password=$passwordB;consent=$true;consentVersion=$version}) 200 'B enrollment'
    $faceA = Face-Login $AccountA $imageA $version; Require-Code $faceA 200 'A matching face login'
    Require-Code (Face-Login $AccountB $imageB $version) 200 'B matching face login'
    Require-Code (Face-Login $AccountA $imageB $version) 401 'B face rejected for A account'
    Require-Code (Api 'GET' "/auth/face/users/$($infoA.data.userId)" $b.data.token $null) 403 'Ordinary user cannot read another face binding'
    Require-Code (Api 'PUT' '/auth/face/me' $a.data.token @{imageBase64=$updatedA;password=$passwordA;consent=$true;consentVersion=$version}) 200 'A template update'
    Require-Code (Face-Login $AccountA $updatedA $version) 200 'A updated template login'
    $deleted = Api 'DELETE' '/auth/face/me' $a.data.token @{password=$passwordA}; Require-Code $deleted 200 'A local binding revoked'
    if ($deleted.data.pendingCleanup) { Write-Host 'PENDING: CompreFace remote cleanup; do not count remote deletion as passed.' }
    else { Write-Host 'PASS: CompreFace scoped deletion acknowledged (backup erasure is outside this test).' }
    Require-Code (Face-Login $AccountA $imageA $version) 401 'Deleted binding cannot authenticate'
    Require-Code (Api 'POST' '/auth/logout' $faceA.data.token $null) 200 'Existing logout endpoint'
    Require-Code (Api 'GET' '/auth/userInfo' $faceA.data.token $null) 401 'Logged-out session invalid'
    Require-Code (Api 'POST' '/auth/login' '' @{username=$AccountA;password=$passwordA}) 200 'Password login still works after face deletion'
    Write-Host 'Real test sequence finished. This does not establish liveness resistance or general accuracy.'
} finally {
    foreach ($item in $cleanup) {
        try {
            $login = Api 'POST' '/auth/login' '' @{username=$item.account;password=$item.password}
            if ($login.code -eq 200) {
                $result = Api 'DELETE' '/auth/face/me' $login.data.token @{password=$item.password}
                if ($result.code -ne 200 -or $result.data.pendingCleanup) { Write-Host "PENDING CLEANUP: $($item.account). Review in MES face management." }
                $null = Api 'POST' '/auth/logout' $login.data.token $null
            } else { Write-Host "MANUAL CLEANUP REQUIRED: $($item.account)" }
        } catch { Write-Host "MANUAL CLEANUP REQUIRED: $($item.account)" }
    }
    $imageA=$null; $imageB=$null; $updatedA=$null; $passwordA=$null; $passwordB=$null; $cleanup=@()
}
