[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PrivateKnowledgeFile.ps1')
$config = Join-Path $PSScriptRoot '.env.ingestion.local'
if (![IO.File]::Exists($config)) {
    function New-IngestionKey {
        $bytes = New-Object byte[] 32
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-','') }
        finally { $generator.Dispose() }
    }
    Initialize-PrivateKnowledgeFile -Path $config -Lines @(
        "MES_KNOWLEDGE_INGESTION_API_KEY=$(New-IngestionKey)",
        "MES_INGEST_GRAPH_PASSWORD=$(New-IngestionKey)",
        'MES_INGEST_GRAPH_URI=bolt://127.0.0.1:17688',
        'MES_INGEST_GRAPH_USER=neo4j'
    )
}
& docker compose --project-name mes-ingestion-local --env-file $config -f (Join-Path $PSScriptRoot 'ingestion.compose.yml') up -d
if ($LASTEXITCODE -ne 0) { throw 'Private knowledge graph startup failed.' }
