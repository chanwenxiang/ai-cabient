# Consumer regression: public config, marketing claim, mock recharge, Alipay prepay form, order points field
# Usage: powershell -File scripts/e2e-consumer-marketing-recharge.ps1

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\e2e-lib.ps1"

$BaseUrl = if ($env:E2E_BASE_URL) { $env:E2E_BASE_URL } else { "http://127.0.0.1" }
$Phone = if ($env:E2E_PHONE) { $env:E2E_PHONE } else { "13800138000" }
$Password = if ($env:E2E_PASSWORD) { $env:E2E_PASSWORD } else { "123456" }

function Assert-True($cond, $msg) {
    if (-not $cond) { throw "ASSERT FAIL: $msg" }
    Write-Host "OK  $msg"
}

Write-Host "== consumer marketing / recharge regression @ $BaseUrl =="

$cfg = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/public/consumer-config"
Assert-True ($cfg.mockEnabled -eq "true" -or $cfg.mockEnabled -eq $true) "mockEnabled should be true for local mock recharge"
Assert-True ($cfg.alipayRechargeEnabled -eq "true" -or $cfg.alipayRechargeEnabled -eq $true) "alipayRechargeEnabled should be true"

$login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
    phoneNumber = $Phone
    password    = $Password
}
$token = $login.token
Assert-True (-not [string]::IsNullOrWhiteSpace($token)) "login token"
$h = @{ Authorization = "Bearer $token" }

$camps = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/marketing/campaigns/active" -Headers $h
Assert-True ($camps.Count -ge 1) "active campaigns not empty"
$claimable = $camps | Where-Object { $_.type -ne "POINTS" } | Select-Object -First 1
Assert-True ($null -ne $claimable) "has non-POINTS campaign"

try {
    $coupon = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/marketing/campaigns/$($claimable.id)/claim" -Headers $h
    Assert-True ($null -ne $coupon.couponId) "claim returns couponId=$($coupon.couponId)"
} catch {
    if ("$_" -match "status=409|已领取|CONFLICT") {
        Write-Host "OK  claim already used (idempotent) for campaign $($claimable.id)"
    } else {
        throw
    }
}

$accBefore = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $h
$balBefore = [int]$accBefore.balanceCents
$key = "e2e-mock-recharge-" + [guid]::NewGuid().ToString("N")
$prepayMock = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/payment/recharge/prepay" -Headers $h -Body @{
    channel        = "WECHAT"
    amountCents    = 1000
    idempotencyKey = $key
}
Assert-True ($null -ne $prepayMock.orderId) "mock prepay orderId"
Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/payment/recharge/$($prepayMock.orderId)/mock-success" -Headers $h | Out-Null
$accAfter = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $h
Assert-True (([int]$accAfter.balanceCents) -eq ($balBefore + 1000)) "mock recharge +¥10 ($balBefore -> $($accAfter.balanceCents))"

$aliKey = "e2e-alipay-prepay-" + [guid]::NewGuid().ToString("N")
$prepayAli = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/payment/recharge/prepay" -Headers $h -Body @{
    channel        = "ALIPAY"
    amountCents    = 2000
    idempotencyKey = $aliKey
}
Assert-True ($null -ne $prepayAli.orderId) "alipay prepay orderId"
$html = $prepayAli.alipayPay.payFormHtml
if ([string]::IsNullOrWhiteSpace($html)) { $html = $prepayAli.payFormHtml }
Assert-True (-not [string]::IsNullOrWhiteSpace($html)) "alipay payFormHtml present"
Assert-True ($html -match "alipaydev|alipay\.com|gateway\.do") "alipay form targets gateway"
# leave PENDING; cancel to avoid clutter
try {
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/payment/recharge/$($prepayAli.orderId)/cancel" -Headers $h | Out-Null
    Write-Host "OK  cancelled pending alipay order $($prepayAli.orderId)"
} catch {
    Write-Warning "cancel pending alipay order skipped: $_"
}

$orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/orders?page=0&size=5" -Headers $h
$items = if ($orders.items) { $orders.items } elseif ($orders -is [array]) { $orders } else { @() }
if ($items.Count -gt 0) {
    $oid = $items[0].orderId
    $detail = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/orders/$oid" -Headers $h
    Assert-True ($null -ne $detail.orderId) "order detail load $oid"
    if ($null -ne $detail.pointsEarned) {
        Write-Host "OK  order pointsEarned=$($detail.pointsEarned)"
    } else {
        Write-Host "OK  order detail has no pointsEarned (zero-pay / old order)"
    }
    Assert-True ($null -ne $detail.lines) "order lines field present"
}

Write-Host ""
Write-Host "ALL CHECKS PASSED"
exit 0
