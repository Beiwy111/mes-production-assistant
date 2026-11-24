. (Join-Path $PSScriptRoot 'PrivateKnowledgeFile.ps1')
function Read-ExperienceCredential {
    $path = Join-Path $PSScriptRoot '.env.experience.local'
    if (-not [IO.File]::Exists($path)) {
        $random = New-Object byte[] 32
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $generator.GetBytes($random)
            Initialize-PrivateKnowledgeFile -Path $path -Lines @([Convert]::ToBase64String($random))
        } finally {
            $generator.Dispose()
        }
    }
    $credential = [IO.File]::ReadAllText($path).Trim()
    if ($credential.Length -lt 24) { throw 'Invalid private experience credential.' }
    return $credential
}
