# Consumer mini program E2E: API flows + mp-weixin bundle assertions

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DeviceId = "CAB-001",
    [string]$DistDir = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
if (-not $DistDir) {
    $DistDir = Join-Path $Root "clients\consumer-mp\dist\build\mp-weixin"
}
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

$pass = 0
$fail = 0

function Assert($name, $cond, $detail = "") {
    if ($cond) {
        Write-Host "  PASS $name" -ForegroundColor Green
        if ($detail) { Write-Host "       $detail" -ForegroundColor DarkGray }
        $script:pass++
    } else {
        Write-Host "  FAIL $name" -ForegroundColor Red
        if ($detail) { Write-Host "       $detail" -ForegroundColor Yellow }
        $script:fail++
    }
}

function Api($Method, $Path, $Headers = @{}, $Body = $null) {
    Invoke-E2eApi -BaseUrl $BaseUrl -Method $Method -Path $Path -Headers $Headers -Body $Body
}

Write-Host "==> Consumer MP E2E"
Write-Host "    base=$BaseUrl device=$DeviceId"
Write-Host ""

$indexJs = Join-Path $DistDir "pages\index\index.js"
$mineWxml = Join-Path $DistDir "pages\mine\mine.wxml"
$verifyWxml = Join-Path $DistDir "pages\verify\verify.wxml"
$appJson = Join-Path $DistDir "app.json"

Write-Host "==> UI bundle"
Assert "index.js" (Test-Path $indexJs)
Assert "mine.wxml" (Test-Path $mineWxml)
Assert "verify.wxml" (Test-Path $verifyWxml)
Assert "app.json" (Test-Path $appJson)

if (Test-Path $indexJs) {
    $txt = Get-Content $indexJs -Raw -Encoding UTF8
    Assert "index links verify page" ($txt -match "/pages/verify/verify")
}
if (Test-Path (Join-Path $DistDir "pages\mine\mine.js")) {
    $txt = Get-Content (Join-Path $DistDir "pages\mine\mine.js") -Raw -Encoding UTF8
    Assert "mine navigates to verify page" ($txt -match "/pages/verify/verify")
}
if (Test-Path $appJson) {
    $txt = Get-Content $appJson -Raw -Encoding UTF8
    Assert "app.json verify route" ($txt -match "pages/verify/verify")
}
Write-Host ""

Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null

Write-Host "==> Flow A: new wx user -> verify -> balance gate"
$wxCode = "e2e_flow_" + [guid]::NewGuid().ToString("N")
$wx = Api -Method POST -Path "/api/v2/auth/wx-login" -Body @{ code = $wxCode }
$auth = @{ Authorization = "Bearer $($wx.token)" }
Assert "wx-login" ($wx.token -and $wx.userId) "userId=$($wx.userId)"

$acc = Api -Method GET -Path "/api/v2/account" -Headers $auth
Assert "account" ($null -ne $acc.balanceCents) "verified=$($acc.verified) balance=$($acc.balanceCents)"

$verified = Api -Method POST -Path "/api/v2/account/verify" -Headers $auth -Body @{ realName = "E2E User"; idCardLast4 = "1234" }
Assert "verify identity" ($verified.verified -eq $true)

$acc2 = Api -Method GET -Path "/api/v2/account" -Headers $auth
Assert "new pilot user starts below opening threshold" ($acc2.balanceCents -lt 500) "balance=$($acc2.balanceCents)"

$prods = Api -Method GET -Path "/api/v2/devices/$DeviceId/products" -Headers $auth
Assert "products" ($prods.Count -gt 0) "count=$($prods.Count)"

$balanceGateRejected = $false
try {
    $null = Api -Method POST -Path "/api/v2/sessions" -Headers $auth -Body @{
        deviceId = $DeviceId
        idempotencyKey = "e2e-balance-gate-" + [guid]::NewGuid().ToString("N")
    }
} catch {
    $balanceGateRejected = $true
}
Assert "insufficient balance blocks session" $balanceGateRejected
Write-Host ""

Write-Host "==> Flow B: funded demo user -> idempotent session + orders"
Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/sms-code?phoneNumber=13800138000" | Out-Null
$demo = Api -Method POST -Path "/api/v2/auth/login" -Body @{ phoneNumber = "13800138000"; code = "123456" }
$demoAuth = @{ Authorization = "Bearer $($demo.token)" }
Assert "demo login" ($demo.userId -eq 10001)
$demoAccount = Api -Method GET -Path "/api/v2/account" -Headers $demoAuth
Assert "demo user has opening balance" ($demoAccount.balanceCents -ge 500) "balance=$($demoAccount.balanceCents)"

try {
    $dev = Api -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $demoAuth
    if (($dev.onlineStatus -as [string]).ToUpper() -ne "ONLINE") {
        Assert "create session (device online)" $false "device offline - start DeviceSimulator"
    } else {
        $openKey = "e2e-open-" + [guid]::NewGuid().ToString("N")
        $sess = Api -Method POST -Path "/api/v2/sessions" -Headers $demoAuth -Body @{
            deviceId = $DeviceId
            idempotencyKey = $openKey
        }
        Assert "create session (open door)" ($sess.sessionId) $sess.sessionId
        $duplicate = Api -Method POST -Path "/api/v2/sessions" -Headers $demoAuth -Body @{
            deviceId = $DeviceId
            idempotencyKey = $openKey
        }
        Assert "session create is idempotent" ($duplicate.sessionId -eq $sess.sessionId) $duplicate.sessionId
    }
} catch {
    Assert "create session (open door)" $false $_.Exception.Message
}

$ordersPath = "/api/v2/orders?page=0" + "&size=5"
$orders = Api -Method GET -Path $ordersPath -Headers $demoAuth
Assert "orders" ($null -ne $orders.items -or $null -ne $orders.content) "count=$($orders.items.Count)$($orders.content.Count)"
Write-Host ""

Write-Host "==> Flow C: password login"
$pwd = Api -Method POST -Path "/api/v2/auth/password-login" -Body @{ phoneNumber = "13800138000"; password = "123456" }
Assert "password login" ($pwd.userId -eq 10001)
Write-Host ""

Write-Host "==> Summary: $pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
exit 0
