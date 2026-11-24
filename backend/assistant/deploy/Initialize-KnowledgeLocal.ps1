# Fresh Windows bootstrap. Reruns retain credentials, archives and verified releases.
[CmdletBinding()]
param([string]$Python)
$ErrorActionPreference = 'Stop'
$rag = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\rag'))
$data = Join-Path $rag 'data'
$venv = Join-Path $rag '.venv'
$venvPython = Join-Path $venv 'Scripts\python.exe'
$helper = Join-Path $PSScriptRoot 'Initialize-KnowledgeLocal.py'
$knowledgeProject = 'mes-knowledge-local'
$qdrantConfig = Join-Path $PSScriptRoot '.env.qdrant.local'

function Assert-ComposeProject {
    param([string]$Project, [string]$CredentialFile)
    $containers = @(& docker ps -aq --filter "label=com.docker.compose.project=$Project")
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop in Linux container mode and retry.' }
    foreach ($container in $containers) {
        $output = & docker inspect --format '{{json .Config.Labels}}' $container
        if ($LASTEXITCODE -ne 0) { throw "Cannot identify existing $Project container." }
        $origin = ($output | ConvertFrom-Json).'com.docker.compose.project.working_dir'
        if (!$origin -or [IO.Path]::GetFullPath($origin) -ne $PSScriptRoot) {
            throw "$Project belongs to a different checkout ($origin). Use its initializer or an isolated Docker host."
        }
    }
    $volumes = @(& docker volume ls -q --filter "label=com.docker.compose.project=$Project")
    if ($LASTEXITCODE -ne 0) { throw "Could not check $Project volumes." }
    if (($containers.Count -or $volumes.Count) -and !(Test-Path -LiteralPath $CredentialFile -PathType Leaf)) {
        throw "$Project already exists. Restore its private configuration from backup before continuing."
    }
}

function Invoke-RagPython {
    param([string[]]$Arguments)
    & $venvPython @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Knowledge preparation failed (exit $LASTEXITCODE). Correct the reported error and rerun Initialize-KnowledgeLocal.ps1." }
}

New-Item -ItemType Directory -Path $data -Force | Out-Null
$lock = [IO.File]::Open((Join-Path $data '.knowledge-initialize.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$oldPath = $env:PYTHONPATH
$oldUtf8 = $env:PYTHONUTF8
$oldEncoding = $env:PYTHONIOENCODING
$oldDownloadTimeout = $env:HF_HUB_DOWNLOAD_TIMEOUT
try {
    Assert-ComposeProject $knowledgeProject $qdrantConfig
    Assert-ComposeProject 'mes-graph-local' (Join-Path $PSScriptRoot '.env.neo4j.local')
    Assert-ComposeProject 'mes-ingestion-local' (Join-Path $PSScriptRoot '.env.ingestion.local')
    foreach ($name in @('QDRANT__SERVICE__API_KEY','QDRANT__SERVICE__READ_ONLY_API_KEY','NEO4J_AUTH','MES_INGEST_GRAPH_PASSWORD')) {
        if ([Environment]::GetEnvironmentVariable($name)) {
            throw "Clear process variable $name so Docker Compose uses the private configuration files."
        }
    }
    $env:PYTHONPATH = Join-Path $rag 'src'
    $env:PYTHONUTF8 = '1'
    $env:PYTHONIOENCODING = 'utf-8'
    if (!$env:HF_HUB_DOWNLOAD_TIMEOUT) { $env:HF_HUB_DOWNLOAD_TIMEOUT = '120' }
    $versionCheck = 'import sys; sys.exit(0 if sys.version_info[:2]==(3,13) and sys.maxsize>2**32 else 1)'
    if (!(Test-Path -LiteralPath $venvPython -PathType Leaf)) {
        $selector = @()
        if (!$Python) {
            if (Get-Command py -ErrorAction SilentlyContinue) { $Python = 'py'; $selector = @('-3.13') }
            else { $Python = 'python' }
        }
        & $Python @selector -c $versionCheck
        if ($LASTEXITCODE -ne 0) { throw 'Install 64-bit Python 3.13 or pass its executable with -Python.' }
        & $Python @selector -m venv $venv
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the dedicated RAG virtual environment.' }
    }
    Invoke-RagPython @('-c', $versionCheck)
    Write-Host 'Installing or verifying the locked RAG Python packages.'
    Invoke-RagPython @('-m','pip','--disable-pip-version-check','install','--quiet','-r',(Join-Path $rag 'requirements.lock.txt'))
    Invoke-RagPython @('-m','pip','check')
    Invoke-RagPython @($helper,'configure')
    & docker compose --project-name $knowledgeProject --env-file $qdrantConfig -f (Join-Path $PSScriptRoot 'knowledge.compose.yml') up -d
    if ($LASTEXITCODE -ne 0) { throw 'Qdrant startup failed. Check Docker Desktop and the configured loopback port.' }
    & (Join-Path $PSScriptRoot 'Initialize-IngestionLocal.ps1')
    & (Join-Path $PSScriptRoot 'Start-GraphLocal.ps1')
    Invoke-RagPython @($helper,'wait-stores')
    Write-Host 'Checking pinned E5 files and downloading missing weights.'
    Invoke-RagPython @('-m','mes_knowledge.cli','prepare-model')
    Invoke-RagPython @('-m','mes_knowledge.practice_release')
    & $venvPython $helper graph-current
    if ($LASTEXITCODE -eq 3) { & (Join-Path $PSScriptRoot 'Update-GraphLocal.ps1') -Kind Public }
    elseif ($LASTEXITCODE -ne 0) { throw 'Could not verify the current public graph publication.' }
    & (Join-Path $PSScriptRoot 'Start-KnowledgeLocal.ps1') -SkipQdrantStart
    Invoke-RagPython @($helper,'accept')
} finally {
    $env:PYTHONPATH = $oldPath
    $env:PYTHONUTF8 = $oldUtf8
    $env:PYTHONIOENCODING = $oldEncoding
    $env:HF_HUB_DOWNLOAD_TIMEOUT = $oldDownloadTimeout
    $lock.Dispose()
}
