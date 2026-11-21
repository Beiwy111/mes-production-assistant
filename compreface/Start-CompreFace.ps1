[CmdletBinding()]
param(
    [string]$ReleaseDirectory,
    [ValidateRange(1024,65535)][int]$Port = 8000,
    [switch]$Isolated,
    [switch]$LoopbackOnly
)
$ErrorActionPreference = 'Stop'

function Invoke-Docker([string[]]$Arguments) {
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command '$($Arguments[0])' failed (exit $LASTEXITCODE)." }
    $output
}

$moduleDirectory = $PSScriptRoot
$project = 'compreface_120'
$volumeName = 'compreface_120_postgres-data'
# A second MES checkout can use the running API without taking ownership of its containers.
if (-not $Isolated) {
    $existingDb = @(Invoke-Docker @('ps','-a','--filter',"label=com.docker.compose.project=$project",'--filter','label=com.docker.compose.service=compreface-postgres-db','--format','{{.ID}}'))
    if ($existingDb.Count -gt 0) {
        if ($existingDb.Count -ne 1) { throw 'More than one primary CompreFace database container was found.' }
        $labels = (Invoke-Docker @('inspect','--format','{{json .Config.Labels}}',$existingDb[0])) | ConvertFrom-Json
        $owner = [string]$labels.'com.docker.compose.project.working_dir'
        if ($owner.Replace('/','\').TrimEnd('\') -ne $moduleDirectory.Replace('/','\').TrimEnd('\')) {
            $mounts = (Invoke-Docker @('inspect','--format','{{json .Mounts}}',$existingDb[0])) | ConvertFrom-Json
            $data = @($mounts | Where-Object { $_.Destination -eq '/var/lib/postgresql/data' })
            if ($data.Count -ne 1 -or $data[0].Type -ne 'volume' -or $data[0].Name -cne $volumeName) {
                throw 'Existing deployment has an unexpected PostgreSQL data mount.'
            }
            $running = @(Invoke-Docker @('ps','--filter',"label=com.docker.compose.project=$project",'--format','{{.Names}}'))
            $required = @('compreface-postgres-db','compreface-api','compreface-admin','compreface-core','compreface-ui')
            if (@($required | Where-Object { $_ -notin $running }).Count -gt 0) {
                throw "Start the existing CompreFace deployment from its owning directory: $owner"
            }
            $bindings = @(Invoke-Docker @('port','compreface-ui','80/tcp'))
            if (-not ($bindings -match ":$Port$") -or ($LoopbackOnly -and @($bindings | Where-Object { $_ -ne "127.0.0.1:$Port" -and $_ -ne "[::1]:$Port" }).Count -gt 0)) {
                throw "The existing CompreFace port binding differs. Check the deployment at $owner."
            }
            $health = Invoke-Docker @('inspect','--format','{{.State.Health.Status}}','compreface-core')
            if ($health -ne 'healthy') { throw "CompreFace core is $health. Check the deployment at $owner." }
            Write-Host "Reusing CompreFace at http://127.0.0.1:$Port (project $project, volume $volumeName)."
            return
        }
    }
}
$envFile = Join-Path $moduleDirectory '.env'
$template = './upstream/docker-compose.yml'
# Compatibility for callers that explicitly supplied another official release.
if ($ReleaseDirectory) {
    $release = (Resolve-Path -LiteralPath $ReleaseDirectory).Path
    if ($release.TrimEnd('\','/') -ne $moduleDirectory.TrimEnd('\','/')) {
        $envFile = Join-Path $release '.env'
        $template = (Resolve-Path -LiteralPath (Join-Path $release 'docker-compose.yml')).Path.Replace('\','/')
    }
}
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'Missing local .env. For the existing installation, use its original database settings. See compreface/README.md.'
}

if ($Isolated) {
    $project = 'mes-compreface-face-isolated'
    $volumeName = 'mes-compreface-face-isolated_postgres-data'
    if ($Port -eq 8000) { $Port = 8001 }
}
$previousBinding = $env:MES_COMPREFACE_BIND
$previousTemplate = $env:MES_COMPREFACE_TEMPLATE
try {
    $env:MES_COMPREFACE_BIND = if ($LoopbackOnly) { "127.0.0.1:$Port" } else { [string]$Port }
    $env:MES_COMPREFACE_TEMPLATE = $template
    $composeArgs = @('compose','--project-name',$project,'--project-directory',$moduleDirectory,'--env-file',$envFile,'-f',(Join-Path $moduleDirectory 'docker-compose.yml'))
    if ($Isolated) { $composeArgs += @('-f',(Join-Path $moduleDirectory 'compose.isolated.yml')) }
    # Inspect the effective model privately; 'compose config' can contain passwords.
    $config = (Invoke-Docker ($composeArgs + @('config','--format','json')) | Out-String) | ConvertFrom-Json
    $volume = $config.volumes.'postgres-data'
    $db = $config.services.'compreface-postgres-db'
    $dataMount = @($db.volumes | Where-Object { $_.target -eq '/var/lib/postgresql/data' })
    $expectsExternalVolume = -not $Isolated
    if ($volume.name -cne $volumeName -or [bool]$volume.external -ne $expectsExternalVolume -or
        $dataMount.Count -ne 1 -or $dataMount[0].type -ne 'volume' -or $dataMount[0].source -ne 'postgres-data') {
        throw 'Unexpected PostgreSQL storage configuration. Startup stopped before changing containers.'
    }
    if ($config.services.'compreface-api'.environment.SAVE_IMAGES_TO_DB -cne 'false') {
        throw 'Expected SAVE_IMAGES_TO_DB=false. Startup stopped before changing containers.'
    }

    if (-not $Isolated) {
        # External storage is mandatory even when all five original containers were stopped or removed.
        Invoke-Docker @('volume','inspect','--format','{{.Name}}',$volumeName) | Out-Null
        $attached = @(Invoke-Docker @('ps','-a','--filter',"volume=$volumeName",'--format','{{.Names}}'))
        if (@($attached | Where-Object { $_ -ne 'compreface-postgres-db' }).Count -gt 0) {
            throw 'Another container uses the primary PostgreSQL volume. Check its owner before starting.'
        }
        $allNames = @(Invoke-Docker @('ps','-a','--format','{{.Names}}'))
        foreach ($service in $config.services.PSObject.Properties) {
            $name = $service.Value.container_name
            if ($name -notin $allNames) { continue }
            $labels = (Invoke-Docker @('inspect','--format','{{json .Config.Labels}}',$name)) | ConvertFrom-Json
            if ($labels.'com.docker.compose.project' -cne $project -or $labels.'com.docker.compose.service' -cne $service.Name) {
                throw "Container '$name' belongs to a different deployment. Check its owner or use -Isolated."
            }
            if ($labels.'com.docker.compose.project.working_dir'.Replace('/','\').TrimEnd('\') -ne $moduleDirectory.Replace('/','\').TrimEnd('\')) {
                throw "Container '$name' is managed by a different checkout. Start the stack from its owning directory."
            }
        }
        if ('compreface-postgres-db' -in $allNames) {
            $mounts = (Invoke-Docker @('inspect','--format','{{json .Mounts}}','compreface-postgres-db')) | ConvertFrom-Json
            $mount = @($mounts | Where-Object { $_.Destination -eq '/var/lib/postgresql/data' })
            if ($mount.Count -ne 1 -or $mount[0].Type -ne 'volume' -or $mount[0].Name -cne $volumeName) {
                throw 'The current PostgreSQL data mount differs from the configured volume. Startup stopped.'
            }
            # Check the existing database identity before starting services.
            $liveEnv = (Invoke-Docker @('inspect','--format','{{json .Config.Env}}','compreface-postgres-db')) | ConvertFrom-Json
            foreach ($key in @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD')) {
                if (("${key}=" + $db.environment.$key) -cnotin $liveEnv) {
                    throw "Local $key differs from the existing PostgreSQL configuration. Use the original .env."
                }
            }
        }
    }

    $up = @('up','-d','--wait','--wait-timeout','180')
    & docker @composeArgs @up
    if ($LASTEXITCODE -ne 0) { throw 'CompreFace did not become ready. Check docker compose ps and retry.' }
    Write-Host "CompreFace: http://127.0.0.1:$Port  |  Project: $project  |  Volume: $volumeName"
} finally {
    [Environment]::SetEnvironmentVariable('MES_COMPREFACE_BIND',$previousBinding,'Process')
    [Environment]::SetEnvironmentVariable('MES_COMPREFACE_TEMPLATE',$previousTemplate,'Process')
}
