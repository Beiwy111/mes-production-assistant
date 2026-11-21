# Run after Docker Desktop is running. Does not change MES configuration.
[CmdletBinding()]
param(
    [string]$InstallDirectory = 'D:\soft\dify-1.19.0',
    [switch]$Start
)
$ErrorActionPreference = 'Stop'
$release = '1.19.0'
$project = 'mes-dify-local'
$destination = [IO.Path]::GetFullPath($InstallDirectory)
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
    [IO.File]::WriteAllLines($envPath, $lines, (New-Object Text.UTF8Encoding($false)))
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
    Write-Host 'Create the admin account locally, configure a model, then publish the Workflow.'
} else {
    Write-Host 'Files prepared and Compose validated. Use -Start to download images and launch this stack.'
}
Write-Host 'MES and its existing assistant have not been switched or restarted.'
Write-Host 'Docker image/volume storage follows Docker Desktop settings; this script does not relocate it.'
