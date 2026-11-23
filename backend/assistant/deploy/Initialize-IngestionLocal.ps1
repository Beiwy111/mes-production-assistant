[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$config = Join-Path $PSScriptRoot '.env.ingestion.local'
if (![IO.File]::Exists($config)) {
    function New-IngestionKey {
        $bytes = New-Object byte[] 32
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-','') }
        finally { $generator.Dispose() }
    }
    $tmp = $config + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllLines($tmp, [string[]]@(
            "MES_KNOWLEDGE_INGESTION_API_KEY=$(New-IngestionKey)",
            "MES_INGEST_GRAPH_PASSWORD=$(New-IngestionKey)",
            'MES_INGEST_GRAPH_URI=bolt://127.0.0.1:17688',
            'MES_INGEST_GRAPH_USER=neo4j'
        ))
        try { [IO.File]::Move($tmp,$config) } catch [IO.IOException] { if (![IO.File]::Exists($config)) { throw } }
    } finally { if ([IO.File]::Exists($tmp)) { [IO.File]::Delete($tmp) } }
}
& docker compose --project-name mes-ingestion-local --env-file $config -f (Join-Path $PSScriptRoot 'ingestion.compose.yml') up -d
if ($LASTEXITCODE -ne 0) { throw 'Private knowledge graph startup failed.' }
