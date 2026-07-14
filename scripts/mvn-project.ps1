[CmdletBinding()]
param(
    [switch]$Offline,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArgs = @("clean", "verify")
)

$ErrorActionPreference = "Stop"

$maven = "D:\devTools\apache-maven-3.9.16\bin\mvn.cmd"
$repository = "D:\devTools\repository"
$projectRoot = Split-Path -Parent $PSScriptRoot
$pom = Join-Path $projectRoot "pom.xml"

if (-not (Test-Path -LiteralPath $maven)) {
    throw "Maven executable not found: $maven"
}

if (-not (Test-Path -LiteralPath $repository)) {
    throw "Maven repository not found: $repository"
}

if (-not (Test-Path -LiteralPath $pom)) {
    throw "Root pom.xml not found: $pom"
}

$arguments = @(
    "-f", $pom,
    "-Dmaven.repo.local=$repository",
    "-Dskip.admin.build=true"
)

if ($Offline) {
    $arguments += "-o"
}

$arguments += $MavenArgs

Write-Host "Maven:     $maven"
Write-Host "Repository: $repository"
Write-Host "Project:    $pom"

& $maven @arguments
exit $LASTEXITCODE
