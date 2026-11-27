$ErrorActionPreference = 'Stop'
function Resolve-AssistantJava {
    # Explorer, npm and a Maven terminal can inherit different PATHs on the same PC.
    $candidates = @(
        foreach ($scope in @('Process', 'User', 'Machine')) {
            $jdk = [Environment]::GetEnvironmentVariable('JAVA_HOME', $scope)
            if ($jdk) { Join-Path ($jdk.Trim().Trim('"')) 'bin\java.exe' }
        }
        Get-Command java.exe -CommandType Application -All -ErrorAction SilentlyContinue | ForEach-Object { $_.Source }
    )
    foreach ($exe in ($candidates | Select-Object -Unique)) {
        if (!(Test-Path -LiteralPath $exe -PathType Leaf)) { continue }
        try {
            $version = & $exe --version 2>$null
            if ($LASTEXITCODE -eq 0 -and ($version -join "`n") -match '(?m)^(?:java|openjdk)\s+(\d+)' -and [int]$Matches[1] -ge 17) {
                return $exe
            }
        } catch { continue }
    }
    throw 'The assistant requires Java 17 or newer. Set JAVA_HOME to the JDK used by mvn -version and rerun the launcher.'
}

function Read-AgentRuntimeConfig {
    param([string]$Path = (Join-Path $PSScriptRoot '.env.agents.local'))
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Missing .env.agents.local; follow STAGE5-AGENTS-SETUP.md.' }
    $allowed = @('MES_AGENT_BOOTSTRAP_KEY','MES_AGENT_SUP_KEY','MES_AGENT_ORDER_KEY','MES_AGENT_SUPPLY_KEY',
        'MES_AGENT_PRODUCTION_KEY','MES_AGENT_QUALITY_KEY','MES_AGENT_EQUIPMENT_KEY','MES_AGENT_AFTERSALES_KEY',
        'MES_AGENT_ALLOWED_ORDER_IDS','MES_AGENT_JDBC_URL','MES_AGENT_JDBC_USER','MES_AGENT_JDBC_PASSWORD','MES_AGENT_PORT')
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines([IO.Path]::GetFullPath($Path))) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        $parts = ($line -split '=', 2)
        if ($parts.Count -ne 2 -or $parts[0] -notin $allowed -or $values.ContainsKey($parts[0])) {
            throw 'Unknown or duplicate private runtime configuration; values are not displayed.'
        }
        $values[$parts[0]] = $parts[1]
    }
    foreach ($key in $allowed | Where-Object { $_ -ne 'MES_AGENT_PORT' -and $_ -notlike 'MES_AGENT_JDBC_*' }) {
        if (-not $values.ContainsKey($key)) { throw ('Missing local setting: ' + $key) }
    }
    if ($values['MES_AGENT_ALLOWED_ORDER_IDS'] -notmatch '^[1-9][0-9]*(,[1-9][0-9]*)*$') {
        throw 'Set explicit positive order primary keys in MES_AGENT_ALLOWED_ORDER_IDS; no wildcard is accepted.'
    }
    if ($values.ContainsKey('MES_AGENT_PORT') -and $values['MES_AGENT_PORT'] -ne '18082') {
        throw 'Stage5 Dify workflows require port 18082; custom port overrides are not supported.'
    }
    foreach ($key in @('MES_AGENT_JDBC_URL','MES_AGENT_JDBC_USER','MES_AGENT_JDBC_PASSWORD')) {
        $values.Remove($key)
    }
    return $values
}

function Read-AssistantReaderConfig {
    param([Parameter(Mandatory=$true)][ValidateSet('agent','workbench')][string]$Reader)
    $path = Join-Path $PSScriptRoot ('.env.' + $Reader + '-read.local')
    if (-not [IO.File]::Exists($path)) { throw 'Initialize local SELECT accounts with Prepare-AssistantReadersLocal.py.' }
    $result = @{}
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        if ($line -notmatch '^(MES_AGENT_JDBC_URL|MES_AGENT_JDBC_USER|MES_AGENT_JDBC_PASSWORD)=(.+)$' -or
            $result.ContainsKey($matches[1])) { throw 'Invalid dedicated MySQL reader file.' }
        $result[$matches[1]] = $matches[2]
    }
    if ($result.Count -ne 3 -or $result['MES_AGENT_JDBC_USER'] -ne ('mes_asst_ro_' + $Reader) -or
        $result['MES_AGENT_JDBC_URL'] -notmatch '^jdbc:mysql://127\.0\.0\.1:[0-9]+/[A-Za-z0-9_]+\?') {
        throw 'Unexpected MySQL reader identity or endpoint.'
    }
    return $result
}
