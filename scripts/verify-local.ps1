# One-shot local flow verification (services + shopping E2E + optional suites)
# Usage:
#   .\scripts\verify-local.ps1
#   .\scripts\verify-local.ps1 -StartInfra
#   .\scripts\verify-local.ps1 -WithReplenishment
#   .\scripts\verify-local.ps1 -WithAlipay -WithDispute -WithPayscore
#   .\scripts\verify-local.ps1 -SkipCleanup
#   $env:E2E_BASE_URL='http://localhost:18080'; .\scripts\verify-local.ps1

param(
    [switch]$StartInfra,
    [switch]$WithReplenishment,
    [switch]$WithAlipay,
    [switch]$WithDispute,
    [switch]$WithPayscore,
    [switch]$SkipCleanup,
    [ValidateSet("", "WECHAT", "ALIPAY", "BALANCE")]
    [string]$ShoppingChannel = "",
    [string]$BaseUrl = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
$DeviceUrl = Get-E2eDeviceUrl
$env:E2E_BASE_URL = $BaseUrl
$env:E2E_DEVICE_URL = $DeviceUrl

Write-Host "========================================"
Write-Host "  AI Cabinet local verification"
Write-Host "  BaseUrl=$BaseUrl"
Write-Host "========================================"
Write-Host ""

if ($StartInfra) {
    & (Join-Path $Root "docker-up.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$steps = 3
if (-not $SkipCleanup) { $steps++ }
if ($WithReplenishment) { $steps++ }
if ($WithAlipay) { $steps++ }
if ($WithPayscore) { $steps++ }
if ($WithDispute) { $steps++ }

$step = 1
Write-Host "==> $step/$steps Service health"
$tradeOk = Test-ServiceHealth "$BaseUrl/actuator/health"
$deviceOk = Test-ServiceHealth "$DeviceUrl/actuator/health"
if (-not $tradeOk -or -not $deviceOk) {
    Write-Host "  FAIL: trade=$tradeOk ($BaseUrl) device=$deviceOk ($DeviceUrl)" -ForegroundColor Red
    Write-Host ""
    Write-Host "Start local stack:" -ForegroundColor Yellow
    Write-Host "  .\docker-up.ps1"
    Write-Host "  .\scripts\start-local.ps1"
    exit 1
}
Write-Host "  PASS: trade-service + device-service healthy" -ForegroundColor Green
$step++

if (-not $SkipCleanup) {
    Write-Host ""
    Write-Host "==> $step/$steps Cleanup stale E2E data"
    & (Join-Path $Root "scripts\cleanup-test-data.ps1") -BaseUrl $BaseUrl
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "cleanup-test-data reported errors; continuing"
    } else {
        Write-Host "  PASS: cleanup finished" -ForegroundColor Green
    }
    $step++
}

Write-Host ""
Write-Host "==> $step/$steps Shopping E2E$(if ($ShoppingChannel) { " (channel=$ShoppingChannel)" })"
$shopArgs = @{
    BaseUrl        = $BaseUrl
    KeepSimulator  = $true
}
if ($ShoppingChannel) { $shopArgs.Channel = $ShoppingChannel }
& (Join-Path $Root "scripts\e2e-shopping.ps1") @shopArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "If 429 too many opens: restart trade-service (dev profile allows higher limit)" -ForegroundColor Yellow
    exit $LASTEXITCODE
}
$step++
if ($WithReplenishment) {
    Write-Host ""
    Write-Host "==> $step/$steps Replenishment E2E"
    & (Join-Path $Root "scripts\e2e-replenishment.ps1") -BaseUrl $BaseUrl
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $step++
}

if ($WithAlipay) {
    Write-Host ""
    Write-Host "==> $step/$steps Alipay marketing / recharge E2E"
    & (Join-Path $Root "scripts\e2e-consumer-marketing-recharge.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $step++
}

if ($WithPayscore) {
    Write-Host ""
    Write-Host "==> $step/$steps PayScore / freepay smoke"
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = "13800138000"
        password    = "123456"
    }
    $auth = @{ Authorization = "Bearer $($login.token)" }
    $acc0 = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
    if (-not ($acc0.payscoreEnabled -eq $true -or $acc0.payscoreEnabled -eq "true")) {
        throw "payscoreEnabled should be true (check PAYSCORE_ENABLED on trade-service)"
    }
    $sign = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/account/payscore/sign" -Headers $auth
    if (-not $sign.active) { throw "payscore sign did not return active=true" }
    $acc1 = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
    if (-not $acc1.passwordFreeReady) { throw "passwordFreeReady expected after payscore sign" }
    Write-Host "  PASS: payscoreEnabled + sign + passwordFreeReady (channel=$($acc1.payPreferredChannel))" -ForegroundColor Green
    $step++
}

if ($WithDispute) {
    Write-Host ""
    Write-Host "==> $step/$steps Recognition dispute seed (MQTT 模拟购物 → DISPUTED + OPEN 工单)"
    & (Join-Path $Root "scripts\create-open-dispute.ps1") -BaseUrl $BaseUrl -Force
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $step++
}

Write-Host ""
Write-Host "========================================"
Write-Host "  Local verification passed"
Write-Host "========================================"
Write-Host ""
Write-Host "Manual checks:"
Write-Host "  Admin:   http://localhost:3000/  or  $BaseUrl/admin/index.html  (13900000001 / 123456)"
Write-Host "  Consumer H5: http://127.0.0.1:3002/"
Write-Host "  Merchant H5: http://127.0.0.1:3001/"
Write-Host "  Simulator: DeviceSimulator <resolved-deviceId> for device ONLINE (via demo/ensure or E2E_DEVICE_ID)"
exit 0
