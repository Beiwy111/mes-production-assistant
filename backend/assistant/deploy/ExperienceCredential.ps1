function Read-ExperienceCredential {
    $path = Join-Path $PSScriptRoot '.env.experience.local'
    if (-not [IO.File]::Exists($path)) {
        $temporary = $path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
        $random = New-Object byte[] 32
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $generator.GetBytes($random)
            [IO.File]::WriteAllText($temporary, [Convert]::ToBase64String($random))
            try { [IO.File]::Move($temporary, $path) }
            catch [IO.IOException] { if (-not [IO.File]::Exists($path)) { throw } }
        } finally {
            $generator.Dispose()
            if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
        }
    }
    $credential = [IO.File]::ReadAllText($path).Trim()
    if ($credential.Length -lt 24) { throw 'Invalid private experience credential.' }
    return $credential
}
