# Full pipeline verification: build + E2E + optional env check
# Usage:
#   .\scripts\verify-full.ps1
#   .\scripts\verify-full.ps1 -SkipBuild
#   .\scripts\verify-full.ps1 -CheckStagingEnv

param(
    [switch]$SkipBuild,
    [switch]$CheckStagingEnv,
    [switch]$SkipE2e
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "========================================"
Write-Host " AI Cabinet Full Verification"
Write-Host "========================================"
Write-Host ""

if (-not $SkipBuild) {
    Write-Host "==> 1/3 Maven compile..."
    mvn -q install -DskipTests "-Dskip.admin.build=true" -pl services/common/common-core,services/trade-service,services/device-service -am
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "    Maven OK" -ForegroundColor Green

    Write-Host ""
    Write-Host "==> 2/3 Admin frontend build (clients/admin-vue)..."
    Push-Location (Join-Path $Root "clients\admin-vue")
    npm run build --silent
    if ($LASTEXITCODE -ne 0) { Pop-Location; exit $LASTEXITCODE }
    Pop-Location
    Write-Host "    Vite OK" -ForegroundColor Green
} else {
    Write-Host "==> Skipped build (-SkipBuild)"
}

if ($CheckStagingEnv) {
    Write-Host ""
    Write-Host "==> Staging env template check..."
    & (Join-Path $Root "scripts\check-env.ps1") -CheckEnv -EnvFile "infra\.env.staging.example"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if (-not $SkipE2e) {
    Write-Host ""
    Write-Host "==> E2E (local stack assumed)..."
    & (Join-Path $Root "scripts\verify-local.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
    Write-Host ""
    Write-Host "==> E2E skipped (-SkipE2e)"
}

Write-Host ""
Write-Host "========================================"
Write-Host " Full verification PASSED" -ForegroundColor Green
Write-Host " Admin: http://localhost:8080/admin/index.html"
Write-Host "========================================"
