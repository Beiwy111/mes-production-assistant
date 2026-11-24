# Run from any directory. Only the assistant's services are installed/started.
[CmdletBinding()]
param(
    [string]$Python,
    [string]$DifyInstallDirectory,
    [ValidatePattern('^[A-Za-z0-9_.:/-]+$')][string]$Model = 'deepseek-flash',
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$deploy = $PSScriptRoot
$root = [IO.Path]::GetFullPath((Join-Path $deploy '..\..\..'))
$rag = Join-Path $root 'backend\assistant\rag'
$venvPython = Join-Path $rag '.venv\Scripts\python.exe'

function Invoke-SetupPython([string]$ScriptName, [string[]]$Options = @()) {
    & $venvPython (Join-Path $deploy $ScriptName) @Options
    if ($LASTEXITCODE -ne 0) { throw "$ScriptName did not finish (exit $LASTEXITCODE)." }
}

function Test-Prerequisites {
    foreach ($tool in @('docker','git','java','javac')) {
        if (!(Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Install $tool and open a new terminal." }
    }
    if (!(Get-Command mvn -ErrorAction SilentlyContinue) -and !(Test-Path (Join-Path $root 'backend\mvnw.cmd'))) {
        throw 'Maven or the Maven Wrapper is required.'
    }
    $compiler = (& javac -version 2>&1 | Out-String).Trim()
    if ($compiler -notmatch '^javac (\d+)' -or [int]$matches[1] -lt 17) { throw 'Install JDK 17 or later.' }
    $engine = & docker info --format '{{.OSType}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or $engine -ne 'linux') { throw 'Start Docker Desktop and switch to Linux containers.' }
    & docker compose version
    if ($LASTEXITCODE -ne 0) { throw 'Install Docker Desktop with Compose v2.' }
    $probe = 'import sys;print(sys.executable);sys.exit(0 if sys.version_info[:2]==(3,13) and sys.maxsize>2**32 else 1)'
    if ($Python) { $candidate = & $Python -c $probe }
    elseif (Get-Command py.exe -ErrorAction SilentlyContinue) { $candidate = & py.exe -3.13 -c $probe 2>$null }
    else { $candidate = & python.exe -c $probe }
    if ($LASTEXITCODE -ne 0 -or !$candidate -or !(Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw 'Install 64-bit CPython 3.13, or pass -Python with its python.exe path.'
    }
    $script:basePython = $candidate
    Write-Host '[OK] Git, Docker Linux/Compose, Java, Maven launcher and Python 3.13 found.'
}

$lock = $null
$step = 'prerequisites'
try {
    Test-Prerequisites
    if ($CheckOnly) { Write-Host 'Preflight only. No services or files changed.'; return }
    $directory = Join-Path $rag 'data'
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    try { $lock = [IO.File]::Open((Join-Path $directory '.new-computer-init.lock'), 'OpenOrCreate','ReadWrite','None') }
    catch { throw 'Another initialization is already running in this checkout.' }

    $step = 'public knowledge and graph'
    Write-Host '[1/6] Prepare vector and graph stores, publish the bundled sources, verify retrieval.'
    & (Join-Path $deploy 'Initialize-KnowledgeLocal.ps1') -Python $basePython

    $step = 'MES access and daily chat'
    Write-Host '[2/6] Configure this computer and verify its MES SELECT accounts.'
    Invoke-SetupPython 'Initialize-MesAssistant.py'

    $step = 'Dify and model provider'
    Write-Host '[3/6] Install/resume local Dify and configure its workspace.'
    $difyOptions = @{Start=$true}
    if ($DifyInstallDirectory) { $difyOptions.InstallDirectory = $DifyInstallDirectory }
    & (Join-Path $deploy 'Prepare-DifyLocal.ps1') @difyOptions
    Invoke-SetupPython 'Configure-DifyProvider.py' @('--model',$Model)

    $step = 'Dify agent publication'
    Write-Host '[4/6] Publish the knowledge tool, six specialists and their supervisor.'
    Invoke-SetupPython 'Provision-Stage5Dify.py' @('--model',$Model)

    $step = 'runtime and gateway'
    Write-Host '[5/6] Compile the independent runtime and start the assistant.'
    & (Join-Path $deploy 'Build-AssistantRuntime.ps1')
    if (Test-Path (Join-Path $directory 'workbench-build\classes')) {
        & (Join-Path $deploy 'Build-AssistantRuntime.ps1') -OutputName workbench-build
    }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $deploy 'Start-AssistantLocal.ps1') -TimeoutSeconds 240
    if ($LASTEXITCODE -ne 0) { throw 'Assistant launcher failed.' }

    $step = 'readiness check'
    Write-Host '[6/6] Verify published supervisor credentials and runtime endpoints.'
    Invoke-SetupPython 'Verify-NewComputer.py'
    Write-Host '[READY] Start MES and frontend normally, open /assistant and select the business workflow.'
    Write-Host 'For a signed-in test including weather and real Agent calls see NEW-COMPUTER.md.'
} catch {
    Write-Host "[FAILED] $step - $($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    if ($lock) { $lock.Dispose() }
}
