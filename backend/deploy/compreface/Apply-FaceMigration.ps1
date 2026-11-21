[CmdletBinding()]
param([switch]$Apply, [switch]$Check, [string]$MysqlDriverPath)
$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if ($Apply -and $Check) { throw 'Use either -Check (read only) or -Apply, not both.' }
if (-not $Apply -and -not $Check) {
    Write-Host 'Dry run: creates mes_face_binding, mes_face_cleanup, mes_face_audit only.'
    Write-Host 'No database connection or changes. Set FACE_DB_URL / FACE_DB_USER / FACE_DB_PASSWORD, then use -Check or -Apply.'
    return
}
if (-not $env:FACE_DB_URL -or -not $env:FACE_DB_USER -or -not $env:FACE_DB_PASSWORD) { throw 'Set the three FACE_DB_* process variables first. No database connection was opened.' }
if (-not $MysqlDriverPath) {
    $profileDirectory = [Environment]::GetFolderPath('UserProfile')
    $driverCache = Join-Path $profileDirectory '.m2\repository\com\mysql\mysql-connector-j'
    $driver = Get-ChildItem -LiteralPath $driverCache -Filter 'mysql-connector-j-*.jar' -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq "mysql-connector-j-$($_.Directory.Name).jar" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($driver) { $MysqlDriverPath = $driver.FullName }
}
if (-not $MysqlDriverPath -or -not (Test-Path -LiteralPath $MysqlDriverPath)) { throw 'Existing MySQL JDBC driver not found. Build the backend first, or pass -MysqlDriverPath. No installer was run.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$driverArchive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $MysqlDriverPath).Path)
try {
    if (-not $driverArchive.GetEntry('com/mysql/cj/jdbc/Driver.class')) {
        throw 'The selected JAR is not an executable MySQL JDBC driver. Select mysql-connector-j-VERSION.jar, not a sources or javadoc JAR. No database connection was opened.'
    }
} finally { $driverArchive.Dispose() }
Write-Host "MySQL JDBC driver: $([IO.Path]::GetFileName($MysqlDriverPath))"
$classPath = (Join-Path $backend 'src\main\resources') + ';' + (Resolve-Path -LiteralPath $MysqlDriverPath).Path
$source = Join-Path $backend 'src\main\java\com\upc\computer\face\FaceSchemaTool.java'
$mode = if ($Check) { '--check' } else { '--apply' }
& java '-cp' $classPath $source $mode
if ($LASTEXITCODE -ne 0) { throw 'Schema utility failed. Review the safe diagnostic above.' }
