function Enter-GraphMaintenanceLock {
    param([string]$RagDirectory)
    $path = Join-Path $RagDirectory 'data\.graph-maintenance.lock'
    try {
        return [IO.File]::Open($path, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch {
        throw 'Unable to acquire graph maintenance lock; another operation may be running. No service was changed.'
    }
}

function Wait-GraphMode {
    param([string]$PythonPath, [bool]$ReadOnly)
    # Windows PowerShell treats native stderr during Neo4j warmup as a terminating error under Stop.
    $ErrorActionPreference = 'Continue'
    $flag = if ($ReadOnly) { 'True' } else { 'False' }
    $check = 'from mes_knowledge.graph.config import graph_settings,open_graph; g=open_graph(graph_settings()); expected=' + $flag + '; actual=g.readonly(); g.close(); exit(0 if actual==expected else 1)'
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        & $PythonPath -c $check 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}
