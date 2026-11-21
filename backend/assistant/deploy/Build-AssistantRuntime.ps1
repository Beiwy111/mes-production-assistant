[CmdletBinding()]
param([ValidateSet('runtime-build','workbench-build')][string]$OutputName = 'runtime-build')
$ErrorActionPreference = 'Stop'
$backendRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$dataRoot = Join-Path $backendRoot 'assistant\rag\data'
$buildRoot = Join-Path $dataRoot $OutputName
New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
# Build a disposable descriptor with absolute source paths. Never clean or compile the MES target directory.
$document = New-Object System.Xml.XmlDocument
$document.Load((Join-Path $backendRoot 'pom.xml'))
$xmlNs = 'http://maven.apache.org/POM/4.0.0'
$manager = New-Object System.Xml.XmlNamespaceManager($document.NameTable)
$manager.AddNamespace('m', $xmlNs)
$build = $document.SelectSingleNode('/m:project/m:build', $manager)
function Set-BuildElement($parent, [string]$name, [string]$value) {
    $element = $parent.SelectSingleNode(('m:' + $name), $manager)
    if ($null -eq $element) { $element = $document.CreateElement($name, $xmlNs); [void]$parent.AppendChild($element) }
    $element.InnerText = $value
}
Set-BuildElement $build 'directory' $buildRoot
Set-BuildElement $build 'sourceDirectory' (Join-Path $backendRoot 'src\main\java')
Set-BuildElement $build 'testSourceDirectory' (Join-Path $backendRoot 'src\test\java')
foreach ($spec in @(@('resources','resource','src\main\resources'), @('testResources','testResource','src\test\resources'))) {
    $old = $build.SelectSingleNode(('m:' + $spec[0]), $manager)
    if ($null -ne $old) { [void]$build.RemoveChild($old) }
    $group = $document.CreateElement($spec[0], $xmlNs); [void]$build.AppendChild($group)
    $entry = $document.CreateElement($spec[1], $xmlNs); [void]$group.AppendChild($entry)
    Set-BuildElement $entry 'directory' (Join-Path $backendRoot $spec[2])
}
$pom = Join-Path $buildRoot 'pom.xml'
$document.Save($pom)
$classpath = Join-Path $dataRoot 'stage5-classpath.txt'
& mvn -f $pom '-DskipTests' compile dependency:build-classpath ('-Dmdep.outputFile=' + $classpath) '-Dmdep.includeScope=runtime' -q
if ($LASTEXITCODE -ne 0) { throw 'Isolated assistant build failed; MES target/classes was not modified.' }
Write-Host ('Assistant build ready in assistant/rag/data/' + $OutputName + '/classes; MES output unchanged.')
