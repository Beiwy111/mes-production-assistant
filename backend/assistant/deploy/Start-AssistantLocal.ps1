[CmdletBinding()]
param([ValidateRange(30, 600)][int]$TimeoutSeconds = 180)
$ErrorActionPreference = 'Stop'
$ragDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\rag'))

function Invoke-LocalDocker {
    param([string[]]$Arguments)
    # Bound individual probes too: Docker CLI can hang while the engine starts.
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $script:dockerPath
    $info.Arguments = ($Arguments | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) {
            $process.Kill() # This CLI child only, never the engine or containers.
            throw 'Docker CLI timed out; check Docker Desktop and run again.'
        }
        if ($process.ExitCode -ne 0) { throw 'Docker command failed; check Docker Desktop. Credentials are not displayed.' }
        return $stdout.GetAwaiter().GetResult().Trim()
    } finally { $process.Dispose() }
}

function Wait-LocalService {
    param([string]$Name, [scriptblock]$Probe)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (& $Probe) { Write-Host "[OK] $Name"; return }
        if ($timer.Elapsed.TotalSeconds -ge $TimeoutSeconds) { break }
        Write-Host "[WAIT] $Name ($([int]$timer.Elapsed.TotalSeconds)s)"
        Start-Sleep -Seconds 3
    } while ($true)
    throw "$Name did not become ready. Check Docker Desktop or backend/assistant/rag/logs, then run again. Existing services were left running."
}

function Test-LocalHttp {
    param([string]$Url, [int]$Expected = 200, [string]$BodyPattern = '')
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        return ([int]$response.StatusCode -eq $Expected -and (!$BodyPattern -or $response.Content -match $BodyPattern))
    } catch {
        # A known response to an anonymous request can also confirm upstream readiness.
        return ($Expected -ge 400 -and $null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq $Expected)
    }
}

function Get-OwnedListener {
    param([int]$Port, [string]$CommandMarker)
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if (!$listeners.Count) { return $false }
    foreach ($listener in $listeners) {
        $owner = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $listener.OwningProcess)
        if (!$owner -or !$owner.CommandLine -or $owner.CommandLine.IndexOf($CommandMarker, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "Port $Port belongs to another or unverifiable process (PID $($listener.OwningProcess)). Nothing was stopped. Resolve the conflict and run again."
        }
    }
    return $true
}

function Start-LocalRuntime {
    param([string]$Name, [int]$Port, [string]$CommandMarker, [string]$Starter, [string]$Url, [int]$Expected = 200, [string]$BodyPattern = '')
    if (Get-OwnedListener $Port $CommandMarker) { Write-Host "[SKIP] $Name is already running on $Port." }
    else {
        Write-Host "[START] $Name"
        if ($Port -eq 18081) { & (Join-Path $PSScriptRoot $Starter) -SkipQdrantStart }
        else { & (Join-Path $PSScriptRoot $Starter) }
    }
    Wait-LocalService $Name { (Get-OwnedListener $Port $CommandMarker) -and (Test-LocalHttp $Url $Expected $BodyPattern) }
}

function Resume-LocalContainers {
    param([string[]]$Names, [string]$ReadinessLabel)
    foreach ($name in $Names) {
        if ((Invoke-LocalDocker @('inspect','--format','{{.State.Running}}',$name)) -eq 'true') { Write-Host "[SKIP] $name" }
        else { Write-Host "[START] $name"; Invoke-LocalDocker @('start',$name) | Out-Null }
    }
    Wait-LocalService $ReadinessLabel {
        foreach ($name in $Names) {
            $state = Invoke-LocalDocker @('inspect','--format','{{.State.Running}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}',$name)
            if ($state -notin @('true|','true|healthy')) { return $false }
        }
        return $true
    }
}

function Start-InstalledFaceService {
    $project = 'mes-compreface-face-isolated'
    $installed = (Invoke-LocalDocker @('ps','-a','--filter',"label=com.docker.compose.project=$project",'--format','{{.Names}}')) -split '\r?\n'
    $settings = Join-Path $PSScriptRoot '..\..\deploy\compreface\mes-face.local.yaml'
    if (!$installed[0] -and !(Test-Path -LiteralPath $settings)) {
        Write-Host '[SKIP] Local CompreFace is not installed.'
        return
    }
    $names = foreach ($service in @('compreface-postgres-db','compreface-core','compreface-admin','compreface-api','compreface-fe')) {
        $name = "$project-$service-1"
        if ($name -notin $installed) { throw "CompreFace container missing: $name. Restore the existing deployment and its data volume." }
        $name
    }
    $mounts = (Invoke-LocalDocker @('inspect','--format','{{json .Mounts}}',$names[0])) | ConvertFrom-Json
    $data = @($mounts | Where-Object { $_.Destination -eq '/var/lib/postgresql/data' })
    if ($data.Count -ne 1 -or $data[0].Type -ne 'volume' -or $data[0].Name -cne "${project}_postgres-data") {
        throw 'Unexpected CompreFace data volume. Check the existing enrollment storage.'
    }
    $bindings = (Invoke-LocalDocker @('inspect','--format','{{json .HostConfig.PortBindings}}',$names[-1])) | ConvertFrom-Json
    $http = @($bindings.'80/tcp')
    if ($http.Count -ne 1 -or $http[0].HostIp -ne '127.0.0.1' -or $http[0].HostPort -ne '8001') {
        throw 'Expected the local CompreFace endpoint on 127.0.0.1:8001.'
    }
    Resume-LocalContainers $names 'CompreFace containers'
    Wait-LocalService 'CompreFace HTTP (8001)' { Test-LocalHttp 'http://127.0.0.1:8001/' }
    # CompreFace 1.2 returns 400 for a missing API key; nginx may serve the UI earlier.
    Wait-LocalService 'CompreFace recognition API' { Test-LocalHttp 'http://127.0.0.1:8001/api/v1/recognition/subjects' 400 }
}

function Start-AssistantServices {
    Write-Host 'Starting local assistant services. MES 8088 and frontend 5173 are not managed by this script.'
    $dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe'),
        (Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe')
    )
    $desktop = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if ($dockerCommand) { $script:dockerPath = $dockerCommand.Source }
    elseif ($desktop) { $script:dockerPath = Join-Path (Split-Path $desktop) 'resources\bin\docker.exe' }
    else { throw 'Docker Desktop not found. Install it before using this launcher.' }
    if (!(Test-Path -LiteralPath $script:dockerPath)) { throw 'Docker CLI not found; check your installation.' }
    $env:PATH = (Split-Path $script:dockerPath) + ';' + $env:PATH
    $dockerReady = $false
    try { $dockerReady = !!(Invoke-LocalDocker @('info','--format','{{.ServerVersion}}')) } catch { }
    if ($dockerReady) { Write-Host '[SKIP] Docker engine is already running.' }
    else {
        if (!(Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
            if (!$desktop) { throw 'Docker engine unavailable. Open Docker Desktop manually.' }
            Write-Host '[START] Docker Desktop'
            Start-Process -FilePath $desktop -WindowStyle Hidden | Out-Null
        }
        Wait-LocalService 'Docker engine' {
            try { return !!(Invoke-LocalDocker @('info','--format','{{.ServerVersion}}')) } catch { return $false }
        }
    }
    try { Start-InstalledAssistantStack }
    finally { Start-InstalledFaceService }
}

function Start-InstalledAssistantStack {
    # Resume the installed stack only. Do not rerun completed init-permissions jobs.
    $groups = [ordered]@{
        'mes-dify-local' = @('db_postgres','redis','weaviate','sandbox','local_sandbox','ssrf_proxy','agent_ssrf_proxy','plugin_daemon','agent_backend','api','api_websocket','worker','worker_beat','web','nginx')
        'mes-knowledge-local' = @('qdrant')
        'mes-graph-local' = @('neo4j')
    }
    $containers = @()
    foreach ($project in $groups.Keys) {
        $existing = (Invoke-LocalDocker @('ps','-a','--filter',"label=com.docker.compose.project=$project",'--format','{{.Names}}')) -split '\r?\n'
        foreach ($service in $groups[$project]) {
            $name = "$project-$service-1"
            if ($name -notin $existing) { throw "Missing container: $name. Restore the existing deployment first; this launcher does not create or download services." }
            $containers += $name
        }
    }
    Resume-LocalContainers $containers 'Docker container readiness'
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.env.ingestion.local')) {
        try { & (Join-Path $PSScriptRoot 'Initialize-IngestionLocal.ps1') }
        catch { Write-Warning 'Private upload graph unavailable; starting the remaining services.' }
    }
    Wait-LocalService 'Dify API (18080)' { Test-LocalHttp 'http://127.0.0.1:18080/console/api/setup' 200 '"step"\s*:\s*"finished"' }
    Wait-LocalService 'Neo4j HTTP (17474)' { Test-LocalHttp 'http://127.0.0.1:17474/' }
    Start-LocalRuntime 'Python knowledge API' 18081 (Join-Path $ragDirectory '.venv\Scripts\python.exe') 'Start-KnowledgeLocal.ps1' 'http://127.0.0.1:18081/health' 200 '"status"\s*:\s*"alive"'
    Start-LocalRuntime 'Agent runtime' 18082 (Join-Path $ragDirectory 'data\stage5-java.args') 'Start-AgentRuntimeLocal.ps1' 'http://127.0.0.1:18082/health' 200 '"status"\s*:\s*"OK"'
    Start-LocalRuntime 'Chat gateway' 18083 (Join-Path $ragDirectory 'data\workbench-java.args') 'Start-WorkbenchLocal.ps1' 'http://127.0.0.1:18083/assistant-workbench/status' 401
    Write-Host ''
    Write-Host '[READY] Assistant services are running. Open http://localhost:5173/assistant after starting MES and frontend.'
    Write-Host 'These checks do not log in or call the paid model API. Database/model credentials are verified when you submit a question.'
    foreach ($port in @(8088,5173)) {
        if (!(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) { Write-Host "[NOTE] Your service on $port is not running. Start it in your usual terminal." }
    }
}

# Dot-sourcing exposes helpers for non-disruptive tests, without starting services.
if ($MyInvocation.InvocationName -ne '.') {
    $lock = $null
    $oldPath = $env:PATH
    try {
        $dataDirectory = Join-Path $ragDirectory 'data'
        New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null
        try { $lock = [IO.File]::Open((Join-Path $dataDirectory '.assistant-start.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
        catch { throw 'Another assistant launcher is running. Wait for it to finish.' }
        Start-AssistantServices
    } catch {
        Write-Host ('[FAILED] ' + $_.Exception.Message) -ForegroundColor Red
        exit 1
    } finally {
        $env:PATH = $oldPath
        if ($lock) { $lock.Dispose() }
    }
}
