# Runs isolated Java -> Dify integration checks. Does not start or switch MES.
[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot '.env.local'),
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    throw 'Missing local configuration. Copy .env.local.example to .env.local and fill the Dify app key locally.'
}

# Parse values as data. Never dot-source, Invoke-Expression, print, or put secrets on the command line.
$allowed = @('ASSISTANT_V2_ENABLED', 'ASSISTANT_V2_DIFY_BASE_URL',
    'ASSISTANT_V2_DIFY_API_KEY', 'ASSISTANT_V2_KNOWLEDGE_ENABLED')
$values = @{}
foreach ($line in [IO.File]::ReadAllLines([IO.Path]::GetFullPath($ConfigPath))) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
    $parts = $trimmed.Split(@('='), 2)
    if ($parts.Count -ne 2 -or $parts[0].Trim() -notin $allowed) {
        throw 'Invalid or unknown entry in local configuration; values are not displayed.'
    }
    $name = $parts[0].Trim()
    if ($values.ContainsKey($name)) { throw 'Duplicate configuration entry; values are not displayed.' }
    $value = $parts[1].Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    $values[$name] = $value
}
$endpoint = $null
if (-not [Uri]::TryCreate($values['ASSISTANT_V2_DIFY_BASE_URL'], [UriKind]::Absolute, [ref]$endpoint) -or
        $endpoint.Scheme -notin @('http', 'https') -or $endpoint.UserInfo -or $endpoint.Query -or
        $endpoint.Fragment -or $endpoint.AbsolutePath.TrimEnd('/') -ne '/v1') {
    throw 'Dify base URL must be an absolute HTTP(S) URL ending in /v1, without credentials, query or fragment.'
}
if ($endpoint.Scheme -eq 'http' -and -not $endpoint.IsLoopback) {
    throw 'Use HTTPS for non-loopback endpoints to protect the application key.'
}
if (-not $values['ASSISTANT_V2_DIFY_API_KEY'] -or $values['ASSISTANT_V2_DIFY_API_KEY'] -notmatch '^app-[^\s]+$') {
    throw 'A Dify Workflow application key (app-...) is required. Do not use the DeepSeek provider key.'
}
if ($ValidateOnly) {
    Write-Host 'Configuration validated. No secrets displayed, no network calls, no MES settings changed.'
    return
}
$maven = Get-Command mvn.cmd -ErrorAction Stop
$pom = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\pom.xml'))
$temporary = @{
    ASSISTANT_V2_DIFY_BASE_URL = $values['ASSISTANT_V2_DIFY_BASE_URL']
    ASSISTANT_V2_DIFY_API_KEY = $values['ASSISTANT_V2_DIFY_API_KEY']
    DIFY_LIVE_TESTS = 'true'
}
$previous = @{}
try {
    foreach ($entry in $temporary.GetEnumerator()) {
        $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    Write-Host 'Running opt-in tests against real Dify. Model calls may consume tokens; MES stays unchanged.'
    & $maven.Source '-o' '-f' $pom '-Dtest=DifyLiveIntegrationTest' 'test'
    if ($LASTEXITCODE -ne 0) { throw 'Live integration checks failed. Inspect the test report; do not share credentials.' }
} finally {
    foreach ($entry in $previous.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}
