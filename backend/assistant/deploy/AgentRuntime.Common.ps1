$ErrorActionPreference = 'Stop'
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
    foreach ($key in $allowed | Where-Object { $_ -ne 'MES_AGENT_PORT' }) {
        if (-not $values.ContainsKey($key)) { throw ('Missing local setting: ' + $key) }
    }
    if ($values['MES_AGENT_ALLOWED_ORDER_IDS'] -notmatch '^[1-9][0-9]*(,[1-9][0-9]*)*$') {
        throw 'Set explicit positive order primary keys in MES_AGENT_ALLOWED_ORDER_IDS; no wildcard is accepted.'
    }
    if ($values.ContainsKey('MES_AGENT_PORT') -and $values['MES_AGENT_PORT'] -ne '18082') {
        throw 'Stage5 Dify workflows require port 18082; custom port overrides are not supported.'
    }
    return $values
}
