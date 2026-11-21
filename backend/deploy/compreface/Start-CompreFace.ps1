[CmdletBinding()]
param(
    [string]$ReleaseDirectory,
    [ValidateRange(1024,65535)][int]$Port = 8000,
    [switch]$Isolated,
    [switch]$LoopbackOnly
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
& (Join-Path $repository 'compreface\Start-CompreFace.ps1') @PSBoundParameters
