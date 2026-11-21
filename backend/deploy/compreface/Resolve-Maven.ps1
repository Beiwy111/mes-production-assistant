param([Parameter(Mandatory)][string]$BackendDirectory)
$installed = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($installed) { return $installed.Source }
$profileDirectory = [Environment]::GetFolderPath('UserProfile')
$cache = Join-Path $profileDirectory '.m2\wrapper\dists'
if (Test-Path -LiteralPath $cache) {
    $cached = Get-ChildItem -LiteralPath $cache -Filter 'mvn.cmd' -File -Recurse -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($cached) { return $cached.FullName }
}
return (Join-Path $BackendDirectory 'mvnw.cmd')
