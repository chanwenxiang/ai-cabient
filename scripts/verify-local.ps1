# One-shot local flow verification (services + shopping E2E)
# Usage:
#   .\scripts\verify-local.ps1
#   .\scripts\verify-local.ps1 -StartInfra
#   .\scripts\verify-local.ps1 -WithVision

param(
    [switch]$StartInfra,
    [switch]$WithVision,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$VisionUrl = "http://localhost:8082"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

Write-Host "========================================"
Write-Host "  AI Cabinet local verification"
Write-Host "========================================"
Write-Host ""

if ($StartInfra) {
    & (Join-Path $Root "scripts\start-infra.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "==> 1/3 Service health"
$tradeOk = Test-ServiceHealth "$BaseUrl/actuator/health"
$deviceOk = Test-ServiceHealth "http://localhost:8081/actuator/health"
if (-not $tradeOk -or -not $deviceOk) {
    Write-Host "  FAIL: trade=$tradeOk device=$deviceOk" -ForegroundColor Red
    Write-Host ""
    Write-Host "Start local stack:" -ForegroundColor Yellow
    Write-Host "  .\scripts\start-infra.ps1"
    Write-Host "  .\scripts\start-local.ps1"
    exit 1
}
Write-Host "  PASS: trade-service + device-service healthy" -ForegroundColor Green

Write-Host ""
Write-Host "==> 2/3 Shopping E2E"
& (Join-Path $Root "scripts\e2e-shopping.ps1") -BaseUrl $BaseUrl
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "If 429 too many opens: restart trade-service (dev profile allows higher limit)" -ForegroundColor Yellow
    exit $LASTEXITCODE
}

if ($WithVision) {
    Write-Host ""
    Write-Host "==> 3/3 Vision service"
    if (Test-ServiceHealth "$VisionUrl/health") {
        $vh = Invoke-RestMethod -Uri "$VisionUrl/health" -TimeoutSec 10
        Write-Host "  PASS: vision mock_enabled=$($vh.mock_enabled) recognizer=$($vh.recognizer_available)" -ForegroundColor Green
    } else {
        Write-Host "  FAIL: $VisionUrl/health not reachable" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host ""
    Write-Host "==> 3/3 Vision (skipped, use -WithVision)"
}

Write-Host ""
Write-Host "========================================"
Write-Host "  Local verification passed"
Write-Host "========================================"
Write-Host ""
Write-Host "Manual checks:"
Write-Host "  Admin:   $BaseUrl/admin/index.html  (13900000001 / 123456)"
Write-Host "  Consumer MP: import clients/consumer-mp/dist/dev/mp-weixin"
Write-Host "  Merchant MP: import clients/merchant-mp/dist/dev/mp-weixin"
Write-Host "  Simulator: DeviceSimulator CAB-001 for device ONLINE"
exit 0
