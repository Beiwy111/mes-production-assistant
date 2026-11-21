param(
    [ValidateSet('Probe', 'Prepare', 'Start')][string]$Action = 'Probe',
    [string]$Checkout = (Join-Path $env:LOCALAPPDATA 'MESAssistant/ragflow-v1.0.0')
)
$ErrorActionPreference = 'Stop'
$pinnedCommit = 'db1cd5929a7885a3074547791f5ecc4d2f891739'
$checkoutPath = [IO.Path]::GetFullPath($Checkout)
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if ($checkoutPath.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep the upstream checkout outside the business repository.'
}
$osInfo = Get-CimInstance Win32_OperatingSystem
$drive = Get-PSDrive -Name ([IO.Path]::GetPathRoot($checkoutPath).Substring(0, 1))
$dockerMemory = 0L
try {
    $dockerValue = & docker info --format '{{.MemTotal}}' 2>$null
    if ($LASTEXITCODE -eq 0) { $dockerMemory = [long]$dockerValue }
} catch { }
$report = [ordered]@{
    parserVersion = 'v1.0.0'; parserCommit = $pinnedCommit
    cpuThreads = [Environment]::ProcessorCount
    totalMemoryGiB = [math]::Round($osInfo.TotalVisibleMemorySize / 1MB, 2)
    freeMemoryGiB = [math]::Round($osInfo.FreePhysicalMemory / 1MB, 2)
    dockerMemoryGiB = [math]::Round($dockerMemory / 1GB, 2)
    checkoutDiskFreeGiB = [math]::Round($drive.Free / 1GB, 2)
    # Conservative local coexistence gate, not a measured v1.0.0 benchmark.
    canStart = ([Environment]::ProcessorCount -ge 4 -and $osInfo.FreePhysicalMemory / 1MB -ge 16 -and
                $dockerMemory / 1GB -ge 14 -and $drive.Free / 1GB -ge 50)
}
$report | ConvertTo-Json
if ($Action -eq 'Probe') { return }
if ($Action -eq 'Start' -and -not $report.canStart) {
    throw 'Insufficient resources for an additional full stack: require 16 GiB free host memory, 14 GiB Docker memory, 4 threads and 50 GiB disk. Existing services were not changed.'
}
if (-not (Test-Path -LiteralPath $checkoutPath)) {
    & git clone --depth 1 --branch v1.0.0 --filter=blob:none --sparse https://github.com/infiniflow/ragflow.git $checkoutPath
    if ($LASTEXITCODE -ne 0) { throw 'Pinned upstream clone failed.' }
}
$actualCommit = & git -C $checkoutPath rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actualCommit.Trim() -ne $pinnedCommit) {
    throw 'Existing checkout does not match the pinned commit; use a new checkout path.'
}
& git -C $checkoutPath sparse-checkout set docker
if ($LASTEXITCODE -ne 0) { throw 'Unable to prepare upstream docker files.' }
$dockerPath = Join-Path $checkoutPath 'docker'
$privateEnv = Join-Path $dockerPath '.env'
$generatedCompose = Join-Path $dockerPath '.mes-ragflow-private.compose.json'
$privateMarker = Join-Path $dockerPath '.mes-private-env-ready'
function New-PrivatePassword {
    $buffer = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToHexString($buffer).ToLowerInvariant()
}
if (-not (Test-Path -LiteralPath $privateMarker)) {
    # Only the verified external upstream checkout is edited; no default public credentials survive.
    $envLines = [IO.File]::ReadAllLines($privateEnv)
    $overrides = @{
        DOC_ENGINE = 'elasticsearch'; DEVICE = 'cpu'; DB_TYPE = 'mysql'; METADATA_DB_PROFILE = 'mysql'
        COMPOSE_PROFILES = 'elasticsearch,cpu,metadata-mysql'
        RAGFLOW_IMAGE = 'infiniflow/ragflow:v1.0.0'; MEM_LIMIT = '4294967296'
        SECRET_KEY = (New-PrivatePassword); REGISTER_ENABLED = '0'; ENABLE_REGISTER = '0'
    }
    foreach ($line in $envLines) {
        if ($line -match '^([A-Z][A-Z0-9_]*PASSWORD)=') { $overrides[$Matches[1]] = New-PrivatePassword }
    }
    $overrides['ADMIN_DEFAULT_PASSWORD'] = New-PrivatePassword
    $kept = foreach ($line in $envLines) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=' -and $overrides.ContainsKey($Matches[1])) { continue }
        $line
    }
    $privateLines = @($kept) + @($overrides.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value })
    [IO.File]::WriteAllLines($privateEnv, $privateLines, [Text.UTF8Encoding]::new($false))
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $privateEnv /inheritance:r /grant:r "${currentIdentity}:(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict private environment ACL.' }
    [IO.File]::WriteAllText($privateMarker, $pinnedCommit)
}
# Keep signup disabled on reruns as well, without rotating existing service credentials.
$privateText = [IO.File]::ReadAllText($privateEnv)
$privateText = [regex]::Replace($privateText, '(?m)^(REGISTER_ENABLED|ENABLE_REGISTER)=.*$', '$1=0')
[IO.File]::WriteAllText($privateEnv, $privateText, [Text.UTF8Encoding]::new($false))
# Resolve upstream env_file values privately; never print expanded config or keys.
$composeOutput = & docker compose --project-name mes-ragflow-isolated --project-directory $dockerPath --env-file $privateEnv -f (Join-Path $dockerPath 'docker-compose.yml') config --format json 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Upstream compose validation failed (details suppressed to avoid secret disclosure).' }
$resolved = ($composeOutput -join "`n") | ConvertFrom-Json -AsHashtable
$resolved['name'] = 'mes-ragflow-isolated'
foreach ($service in $resolved.services.Values) {
    [void]$service.Remove('container_name')
    [void]$service.Remove('ports')
    $service['restart'] = 'no'
}
$resolved.services['ragflow-cpu']['ports'] = @(
    @{ target = 9380; published = '19380'; host_ip = '127.0.0.1'; protocol = 'tcp' },
    @{ target = 80; published = '19381'; host_ip = '127.0.0.1'; protocol = 'tcp' }
)
[IO.File]::WriteAllText($generatedCompose, ($resolved | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))
$currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $generatedCompose /inheritance:r /grant:r "${currentIdentity}:(F)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict private compose ACL.' }
& docker compose --project-name mes-ragflow-isolated --env-file $privateEnv -f $generatedCompose config --quiet 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Isolated compose failed validation.' }
if ($Action -eq 'Start') {
    & docker compose --project-name mes-ragflow-isolated --env-file $privateEnv -f $generatedCompose up -d
    if ($LASTEXITCODE -ne 0) { throw 'RAGFlow startup failed; existing MES services were not targeted.' }
    Write-Output 'RAGFlow startup requested. Wait for readiness, configure the private dataset/model, then use the draft ingestion CLI.'
} else {
    Write-Output 'Pinned compose prepared and validated. No images pulled or services started. Private credentials stay in the external checkout.'
}
