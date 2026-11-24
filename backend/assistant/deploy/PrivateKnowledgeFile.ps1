function Initialize-PrivateKnowledgeFile {
    param([string]$Path, [string[]]$Lines)
    if ([IO.File]::Exists($Path)) { return }
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::Create($temporary).Dispose()
        $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $temporary '/inheritance:r' '/grant:r' ("*${sid}:F") '*S-1-5-18:F' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not protect the private knowledge credential file.' }
        [IO.File]::WriteAllLines($temporary, $Lines, (New-Object Text.UTF8Encoding($false)))
        try { [IO.File]::Move($temporary, $Path) }
        catch [IO.IOException] { if (![IO.File]::Exists($Path)) { throw } }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}
