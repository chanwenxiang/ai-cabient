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

# Community Edition 不支持多分支：用两个项目分别跟踪 dev / main
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

Write-Host "==> 1/3 Compile + unit tests + Jacoco..." -ForegroundColor Cyan
& $mvn -B "-Dmaven.test.failure.ignore=true" test jacoco:report `
  "-pl" "services/trade-service,services/device-service,services/common/common-core" "-am"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Maven sonar:sonar 只会扫 Maven 模块；admin-vue 需走 sonar-scanner + sonar-project.properties
Write-Host "==> 2/3 Ensure quality gate (AI Cabinet)..." -ForegroundColor Cyan
& "$PSScriptRoot\setup-sonar-quality-gate.ps1" -SonarHostUrl $SonarHostUrl -SonarToken $SonarToken

Write-Host "==> 3/3 Sonar scan (Java + admin-vue + consumer-mp + merchant-mp) key=$projectKey ..." -ForegroundColor Cyan
if ($scmRevision) { Write-Host "scm.revision=$scmRevision" }

$scannerArgs = @(
  "-Dsonar.projectKey=$projectKey",
  "-Dsonar.projectName=$projectName",
  "-Dsonar.host.url=$SonarHostUrl",
  "-Dsonar.token=$SonarToken",
  "-Dsonar.sourceEncoding=UTF-8",
  "-Dsonar.coverage.jacoco.xmlReportPaths=**/target/site/jacoco/jacoco.xml"
)
if ($scmRevision) {
  $scannerArgs += "-Dsonar.scm.revision=$scmRevision"
}

& $scanner @scannerArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Sonar scan submitted: http://localhost:19002/dashboard?id=$projectKey" -ForegroundColor Green
