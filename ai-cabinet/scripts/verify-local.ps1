# One-shot local flow verification (infra + services + E2E)
# Usage:
#   .\scripts\verify-local.ps1                 # services must already be running
#   .\scripts\verify-local.ps1 -StartInfra     # also start docker infra
#   .\scripts\verify-local.ps1 -WithVision     # include step4 vision API checks

param(
    [switch]$StartInfra,
    [switch]$WithVision,
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

Write-Host "========================================"
Write-Host "  AI Cabinet local verification"
Write-Host "========================================"
Write-Host ""

if ($StartInfra) {
    & (Join-Path $Root "scripts\start-infra.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "==> 1/4 Service health"
& (Join-Path $Root "scripts\verify-step2.ps1") -SkipInfra -SkipE2e
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Start local stack:" -ForegroundColor Yellow
    Write-Host "  .\scripts\start-infra.ps1"
    Write-Host "  .\scripts\start-local.ps1"
    Write-Host "Or run trade/device/vision/simulator in IDEA (.run/)"
    exit 1
}

Write-Host ""
Write-Host "==> 2/4 Recharge E2E"
& (Join-Path $Root "scripts\e2e-recharge.ps1") -BaseUrl $BaseUrl
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "==> 3/4 Shopping E2E"
& (Join-Path $Root "scripts\e2e-shopping.ps1") -BaseUrl $BaseUrl
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "If 429 too many opens: restart trade-service (dev profile now allows 200/hour)" -ForegroundColor Yellow
    exit $LASTEXITCODE
}

if ($WithVision) {
    Write-Host ""
    Write-Host "==> 4/4 Vision pipeline"
    & (Join-Path $Root "scripts\verify-step4.ps1") -SkipInfra
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
    Write-Host ""
    Write-Host "==> 4/4 Vision (skipped, use -WithVision)"
}

Write-Host ""
Write-Host "========================================"
Write-Host "  Local verification passed"
Write-Host "========================================"
Write-Host ""
Write-Host "Manual checks:"
Write-Host "  Admin:  $BaseUrl/admin/index.html  (13900000001 / 123456)"
Write-Host "  Miniapp: import clients/miniapp, BASE_URL=$BaseUrl"
Write-Host "  Simulator: DeviceSimulator CAB-001 for device ONLINE"
exit 0
