param(
  [ValidateSet("dev", "main")]
  [string]$Branch = "dev",
  [string]$SonarHostUrl = $env:SONAR_HOST_URL,
  [string]$SonarToken = $env:SONAR_TOKEN
)

$ErrorActionPreference = "Stop"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location $Root

if (-not $SonarHostUrl) { $SonarHostUrl = "http://localhost:19002" }
if (-not $SonarToken) {
  Write-Error "SONAR_TOKEN is required. Create a token in SonarQube UI and set env SONAR_TOKEN."
}

$projectKey = if ($Branch -eq "main") { "ai-cabinet-main" } else { "ai-cabinet-dev" }
$projectName = if ($Branch -eq "main") { "AI Cabinet (main)" } else { "AI Cabinet (dev)" }

$git = if (Test-Path "D:\devTools\Git\cmd\git.exe") { "D:\devTools\Git\cmd\git.exe" } else { "git" }
$scmRevision = $null
try { $scmRevision = (& $git -C $Root rev-parse HEAD).Trim() } catch { }

$mvn = if (Test-Path "D:\devTools\apache-maven-3.9.16\bin\mvn.cmd") {
  "D:\devTools\apache-maven-3.9.16\bin\mvn.cmd"
} else {
  "mvn"
}

$scanner = if (Test-Path "D:\devTools\sonar-scanner\bin\sonar-scanner.bat") {
  "D:\devTools\sonar-scanner\bin\sonar-scanner.bat"
} else {
  "sonar-scanner"
}

Write-Host "==> 1/4 Compile + unit tests + Jacoco..." -ForegroundColor Cyan
& $mvn -B "-Dmaven.test.failure.ignore=true" test jacoco:report `
  "-pl" "services/trade-service,services/device-service,services/common/common-core" "-am"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> 2/4 Copy compile deps for Sonar java.libraries..." -ForegroundColor Cyan
foreach ($mod in @(
    "services/trade-service",
    "services/device-service",
    "services/common/common-core"
  )) {
  & $mvn -B -q -f "$mod/pom.xml" dependency:copy-dependencies `
    "-DincludeScope=compile" "-DoutputDirectory=target/dependency"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "==> 3/4 Ensure quality gate (AI Cabinet)..." -ForegroundColor Cyan
& "$PSScriptRoot\setup-sonar-quality-gate.ps1" -SonarHostUrl $SonarHostUrl -SonarToken $SonarToken

Write-Host "==> 4/4 Sonar scan (sonar-scanner + sonar-project.properties) key=$projectKey ..." -ForegroundColor Cyan
if ($scmRevision) { Write-Host "scm.revision=$scmRevision" }

$scannerArgs = @(
  "-Dsonar.projectKey=$projectKey",
  "-Dsonar.projectName=$projectName",
  "-Dsonar.host.url=$SonarHostUrl",
  "-Dsonar.token=$SonarToken",
  "-Dsonar.qualitygate.wait=true"
)
if ($scmRevision) {
  $scannerArgs += "-Dsonar.scm.revision=$scmRevision"
}

& $scanner @scannerArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Sonar scan submitted: http://localhost:19002/dashboard?id=$projectKey" -ForegroundColor Green
