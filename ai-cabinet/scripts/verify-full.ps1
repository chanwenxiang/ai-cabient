# Full pipeline verification: build + Docker E2E + staging env check
# Usage:
#   .\scripts\verify-full.ps1              # build + step1 E2E
#   .\scripts\verify-full.ps1 -SkipBuild   # only E2E (stack must be up)
#   .\scripts\verify-full.ps1 -WithStaging # also run verify-step5 -Staging

param(
    [switch]$SkipBuild,
    [switch]$WithStaging,
    [switch]$SkipDocker
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "========================================"
Write-Host " AI Cabinet Full Verification"
Write-Host "========================================"
Write-Host ""

if (-not $SkipBuild) {
    Write-Host "==> 1/4 Maven compile (common + trade + device)..."
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.17"
    mvn -q install -DskipTests "-Dskip.admin.build=true" -pl services/common/common-core,services/trade-service,services/device-service -am
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "    Maven OK" -ForegroundColor Green

    Write-Host ""
    Write-Host "==> 2/4 Admin frontend build..."
    Push-Location (Join-Path $Root "clients\admin")
    npm run build --silent
    if ($LASTEXITCODE -ne 0) { Pop-Location; exit $LASTEXITCODE }
    Pop-Location
    Write-Host "    Vite OK" -ForegroundColor Green
} else {
    Write-Host "==> Skipped build (-SkipBuild)"
}

Write-Host ""
Write-Host "==> 3/4 Step 5 staging env template check..."
& (Join-Path $Root "scripts\verify-step5.ps1") -CheckEnv -EnvFile "infra\.env.staging.example"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $SkipDocker) {
    Write-Host ""
    Write-Host "==> 4/4 Step 1 Docker + E2E..."
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "    Docker not running — trying local stack (verify-step2 -StartLocal -SkipInfra)..." -ForegroundColor Yellow
        & (Join-Path $Root "scripts\verify-step2.ps1") -StartLocal -SkipInfra
        if ($LASTEXITCODE -ne 0) {
            Write-Host ""
            Write-Host "Local E2E skipped: start Docker Desktop OR run:" -ForegroundColor Yellow
            Write-Host "  .\scripts\start-infra.ps1"
            Write-Host "  .\scripts\start-local.ps1"
            Write-Host "  .\scripts\verify-local.ps1"
            exit 1
        }
        & (Join-Path $Root "scripts\e2e-recharge.ps1")
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        & (Join-Path $Root "scripts\verify-step1.ps1")
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
} else {
    Write-Host ""
    Write-Host "==> 4/4 E2E (local stack assumed)..."
    & (Join-Path $Root "scripts\e2e-recharge.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & (Join-Path $Root "scripts\e2e-shopping.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if ($WithStaging) {
    Write-Host ""
    Write-Host "==> Bonus: Step 5 staging smoke..."
    & (Join-Path $Root "scripts\verify-step5.ps1") -Staging
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host ""
Write-Host "========================================"
Write-Host " Full verification PASSED" -ForegroundColor Green
Write-Host " Admin: http://localhost/admin/index.html"
Write-Host "========================================"
