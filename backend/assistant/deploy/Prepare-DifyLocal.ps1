# Run after Docker Desktop is running. Does not change MES configuration.
[CmdletBinding()]
param(
    [string]$InstallDirectory,
    [switch]$Start
)
$ErrorActionPreference = 'Stop'
$release = '1.19.0'
$project = 'mes-dify-local'
$overlay = Join-Path $PSScriptRoot 'dify-local.compose.yml'

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed (exit $LASTEXITCODE). No cleanup or deletion was performed." }
}

function New-LocalSecret {
    param([switch]$Base64Url)
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    if ($Base64Url) { return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_') }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git is required.' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Desktop is required.' }
Invoke-Checked docker @('info', '--format', '{{.ServerVersion}}')
$ids = @(& docker ps -a --filter "label=com.docker.compose.project=$project" --format '{{.ID}}')
if ($LASTEXITCODE -ne 0) { throw 'Could not locate the existing Dify Compose project.' }
$workDirs = @()
if ($ids.Count) {
    $labels = @(& docker inspect --format '{{json .Config.Labels}}' @ids)
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the existing Dify Compose project.' }
    $workDirs = @($labels | ForEach-Object { ($_ | ConvertFrom-Json).'com.docker.compose.project.working_dir' } | Sort-Object -Unique)
}
if ($workDirs.Count -gt 1) { throw 'Multiple checkout paths found for this Dify project. Check the existing Compose installation.' }
if ($workDirs.Count -eq 1) {
    $dockerPath = $workDirs[0]
    if (!$dockerPath -or !(Test-Path -LiteralPath $dockerPath -PathType Container) -or (Split-Path $dockerPath -Leaf) -ne 'docker') {
        throw 'Existing Dify project uses another or missing checkout. Restore its checkout and .env first.'
    }
    $existingRoot = Split-Path $dockerPath -Parent
    if ($InstallDirectory -and ([IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\','/') -ne $existingRoot)) {
        throw 'The installed Dify project uses another checkout. Run without -InstallDirectory to reuse it.'
    }
    if (!(Test-Path -LiteralPath (Join-Path $dockerPath '.env') -PathType Leaf)) {
        throw 'The Dify project already exists. Restore its original docker/.env before restarting it.'
    }
    $InstallDirectory = $existingRoot
} elseif (!$InstallDirectory) {
    $InstallDirectory = Join-Path $env:LOCALAPPDATA "MES-Assistant\dify-$release"
}
$destination = [IO.Path]::GetFullPath($InstallDirectory)
if (-not (Test-Path -LiteralPath $overlay -PathType Leaf)) { throw 'Missing compose overlay.' }

if (Test-Path -LiteralPath $destination) {
    if (-not (Test-Path -LiteralPath (Join-Path $destination '.git'))) {
        throw 'InstallDirectory already exists and is not the expected checkout. Choose an empty path.'
    }
    $origin = & git -C $destination remote get-url origin
    if ($LASTEXITCODE -ne 0 -or $origin -ne 'https://github.com/langgenius/dify.git') { throw 'Unexpected repository origin; not modifying it.' }
    $tag = & git -C $destination describe --tags --exact-match
    if ($LASTEXITCODE -ne 0 -or $tag -ne $release) { throw 'Unexpected release; not checking out or resetting existing files.' }
} else {
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Invoke-Checked git @('-c', 'core.autocrlf=false', 'clone', '--depth', '1', '--filter=blob:none', '--sparse', '--branch', $release,
        'https://github.com/langgenius/dify.git', $destination)
}
Invoke-Checked git @('-C', $destination, 'sparse-checkout', 'set', 'docker')
$dockerDirectory = Join-Path $destination 'docker'
$envPath = Join-Path $dockerDirectory '.env'
$composePath = Join-Path $dockerDirectory 'docker-compose.yaml'
if (-not (Test-Path -LiteralPath $envPath)) {
    $savedVolumes = @(& docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect existing Dify data volumes.' }
    if ($savedVolumes.Count) { throw 'Dify data volumes already exist. Select their original checkout or restore its docker/.env before starting.' }
    $settings = [ordered]@{
        COMPOSE_PROFILES = 'weaviate,postgresql,collaboration'
        DIFY_AGENT_LOCAL_SANDBOX_AUTH_TOKEN = (New-LocalSecret)
        SECRET_KEY = (New-LocalSecret)
        DB_PASSWORD = (New-LocalSecret)
        REDIS_PASSWORD = (New-LocalSecret)
        PLUGIN_DAEMON_KEY = (New-LocalSecret)
        PLUGIN_DIFY_INNER_API_KEY = (New-LocalSecret)
        SANDBOX_API_KEY = (New-LocalSecret)
        DIFY_AGENT_API_TOKEN = (New-LocalSecret)
        DIFY_AGENT_SERVER_SECRET_KEY = (New-LocalSecret -Base64Url)
    }
    $lines = @('# Private local Dify configuration. Do not commit or share.')
    foreach ($entry in $settings.GetEnumerator()) { $lines += "$($entry.Key)=$($entry.Value)" }
    $temporary = "$envPath.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::Create($temporary).Dispose()
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $temporary '/inheritance:r' '/grant:r' ("*${sid}:F") '*S-1-5-18:F' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not protect the private Dify configuration.' }
        [IO.File]::WriteAllLines($temporary, $lines, (New-Object Text.UTF8Encoding($false)))
        [IO.File]::Move($temporary, $envPath)
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
    Write-Host 'Created local service credentials without displaying them.'
} else {
    Write-Host 'Existing .env preserved; credentials were not regenerated.'
}

# Explicit project/path/env prevent accidentally operating on another Compose stack.
$composeArguments = @('compose', '--project-name', $project, '--project-directory', $dockerDirectory,
    '--env-file', $envPath, '-f', $composePath, '-f', $overlay)
Invoke-Checked docker ($composeArguments + @('config', '--quiet'))
if ($Start) {
    Invoke-Checked docker ($composeArguments + @('up', '-d'))
    Invoke-Checked docker ($composeArguments + @('ps', '--all'))
    Write-Host 'Open http://127.0.0.1:18080/install after services become healthy.'
    Write-Host 'Complete local Dify account and model setup, then continue with the MES workspace initializer.'
} else {
    Write-Host 'Files prepared and Compose validated. Use -Start to download images and launch this stack.'
}
Write-Host 'MES and its existing assistant have not been switched or restarted.'
Write-Host 'Docker image/volume storage follows Docker Desktop settings; this script does not relocate it.'
