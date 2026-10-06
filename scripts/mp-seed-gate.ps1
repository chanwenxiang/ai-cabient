# 小程序三端自动化 · 造数闸门（S0 清数可选 + S1 台子检查 + 可选最小造数）
#
# 场景真源：docs/uat/MP_THREE_END_SCENARIOS.md（S0→S1→S2）
# 默认只检查、不写账。
#   -CleanupFirst  先跑 cleanup-test-data.ps1 FullBusiness（测前清业务数据，推荐）
#   -LightCleanup  与 -CleanupFirst 联用：仅轻量清阻塞，不删订单/分账
#   -SeedMinimal   再调用 e2e-shopping（会污染演示账）
#   -DeviceId      可选；空则 Resolve-E2eTestDevice / env E2E_DEVICE_ID（柜子不写死）
#
# 用法：
#   powershell -File scripts/mp-seed-gate.ps1 -CleanupFirst
#   powershell -File scripts/mp-seed-gate.ps1 -CleanupFirst -SeedMinimal
#   $env:E2E_DEVICE_ID='...'; powershell -File scripts/mp-seed-gate.ps1 -CleanupFirst
#
param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "",
    [string]$ConsumerPhone = "13800138000",
    [string]$ConsumerPassword = "123456",
    [string]$MerchantPhone = "13800138001",
    [string]$MerchantPassword = "123456",
    [string]$OpsPhone = "13900000001",
    [string]$OpsPassword = "123456",
    [int]$RestoreBalanceCents = 50000,
    [switch]$CleanupFirst,
    [switch]$LightCleanup,
    [switch]$SeedMinimal,
    [string]$OutJson = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
if ([string]::IsNullOrWhiteSpace($OutJson)) {
    $OutJson = Join-Path $RepoRoot ".tmp/mp-seed-gate.json"
}
$outDir = Split-Path -Parent $OutJson
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

$report = [ordered]@{
    at                   = (Get-Date).ToUniversalTime().ToString("o")
    baseUrl              = $BaseUrl
    deviceId             = $DeviceId
    cleanupFirst         = [bool]$CleanupFirst
    restoreBalanceCents  = $RestoreBalanceCents
    seedMinimal          = [bool]$SeedMinimal
    stage                = "platform"
    checks               = [ordered]@{}
    sample               = [ordered]@{}
    pass                 = $false
    hints                = @()
}

function Set-Check([string]$Name, [bool]$Ok, [string]$Detail = "") {
    $report.checks[$Name] = @{ ok = $Ok; detail = $Detail }
    if ($Ok) { Write-Host "OK  $Name  $Detail" }
    else { Write-Host "FAIL $Name  $Detail" }
}

# Resolve device early (no hard-coded cabinet)
try {
    $DeviceId = Resolve-E2eTestDevice -DeviceId $DeviceId -UnlockSales
}
catch {
    Write-Warning $_.Exception.Message
}
$report.deviceId = $DeviceId

Write-Host "========== mp-seed-gate =========="
Write-Host "    BaseUrl=$BaseUrl DeviceId=$DeviceId CleanupFirst=$CleanupFirst SeedMinimal=$SeedMinimal"

# 1) health
$healthOk = $false
foreach ($u in @("$BaseUrl/actuator/health", "http://localhost:18080/actuator/health", "http://localhost:8080/actuator/health")) {
    if (Test-ServiceHealth -Url $u) {
        $healthOk = $true
        if ($u -notlike "$BaseUrl*") { $BaseUrl = ($u -replace '/actuator/health$', ''); $report.baseUrl = $BaseUrl }
        break
    }
}
Set-Check "trade_health" $healthOk $BaseUrl
if (-not $healthOk) {
    $report.hints += "start stack first: docker-up.ps1 or IDEA+infra"
    $report | ConvertTo-Json -Depth 8 | Set-Content -Path $OutJson -Encoding utf8
    exit 2
}

# Re-resolve after health (postgres must be up)
try {
    $DeviceId = Resolve-E2eTestDevice -DeviceId $DeviceId -UnlockSales
    $report.deviceId = $DeviceId
    Set-Check "device_resolve" (-not [string]::IsNullOrWhiteSpace($DeviceId)) "deviceId=$DeviceId"
}
catch {
    Set-Check "device_resolve" $false $_.Exception.Message
}

# 1b) S0 cleanup (optional but recommended before business)
if ($CleanupFirst) {
    Write-Host "==> S0 CleanupFirst: cleanup-test-data.ps1 mode=$(if ($LightCleanup) { 'Light' } else { 'FullBusiness' })"
    $cleanup = Join-Path $PSScriptRoot "cleanup-test-data.ps1"
    if (-not (Test-Path $cleanup)) {
        Set-Check "s0_cleanup" $false "missing cleanup-test-data.ps1"
    }
    else {
        try {
            $cargs = @(
                "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $cleanup,
                "-BaseUrl", $BaseUrl,
                "-DeviceId", $DeviceId,
                "-ConsumerPhone", $ConsumerPhone,
                "-RestoreBalanceCents", "$RestoreBalanceCents"
            )
            if ($LightCleanup) { $cargs += "-Light" }
            & powershell @cargs
            $cleanupOk = ($LASTEXITCODE -eq 0)
            Set-Check "s0_cleanup" $cleanupOk "exit=$LASTEXITCODE"
            if ($cleanupOk -and (Test-Path (Join-Path $RepoRoot ".tmp/mp-cleanup-meta.json"))) {
                $meta = Get-Content (Join-Path $RepoRoot ".tmp/mp-cleanup-meta.json") -Raw | ConvertFrom-Json
                if ($meta.deviceId) {
                    $DeviceId = [string]$meta.deviceId
                    $report.deviceId = $DeviceId
                }
            }
            if (-not $cleanupOk) {
                $report.hints += "cleanup failed; do not start S2 main chain"
            }
        }
        catch {
            Set-Check "s0_cleanup" $false $_.Exception.Message
        }
    }
}
else {
    Set-Check "s0_cleanup" $true "skipped (pass -CleanupFirst)"
    $report.hints += "no cleanup: prefer -CleanupFirst before main chain"
}

# 2) logins
$consumerToken = $null
$merchantToken = $null
$opsToken = $null
try {
    $clogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/login" -Body @{
        phoneNumber = $ConsumerPhone
        code        = "123456"
    }
    if (-not $clogin.token) {
        $clogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
            phoneNumber = $ConsumerPhone
            password    = $ConsumerPassword
        }
    }
    $consumerToken = $clogin.token
    Set-Check "consumer_login" (-not [string]::IsNullOrWhiteSpace($consumerToken)) "len=$($consumerToken.Length)"
}
catch {
    Set-Check "consumer_login" $false $_.Exception.Message
}

try {
    $mlogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/merchant-password-login" -Body @{
        phoneNumber = $MerchantPhone
        password    = $MerchantPassword
    }
    $merchantToken = $mlogin.token
    Set-Check "merchant_login" (-not [string]::IsNullOrWhiteSpace($merchantToken)) "len=$($merchantToken.Length)"
}
catch {
    Set-Check "merchant_login" $false $_.Exception.Message
}

try {
    $opath = "/api/v2/auth/password-login"
    try {
        $ologin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
            phoneNumber = $OpsPhone
            password    = $OpsPassword
        }
    }
    catch {
        $ologin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path $opath -Body @{
            phoneNumber = $OpsPhone
            password    = $OpsPassword
        }
    }
    $opsToken = $ologin.token
    Set-Check "ops_login" (-not [string]::IsNullOrWhiteSpace($opsToken)) "len=$($opsToken.Length)"
}
catch {
    Set-Check "ops_login" $false $_.Exception.Message
}

$cAuth = @{ Authorization = "Bearer $consumerToken" }
$mAuth = @{ Authorization = "Bearer $merchantToken" }

# 3) resolved device must exist (dynamic cabinet, not hard-coded)
$deviceOk = $false
$deviceDetail = ""
if ([string]::IsNullOrWhiteSpace($DeviceId)) {
    $deviceDetail = "empty deviceId after resolve"
}
else {
    try {
        $db = docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -t -A -c `
            "SELECT device_id||'|'||COALESCE(lifecycle_status,'')||'|'||COALESCE(online_status,'')||'|'||COALESCE(merchant_id,'') FROM device_info WHERE device_id='$DeviceId';" 2>$null
        if ($db -and $db.Trim()) {
            $deviceOk = $true
            $deviceDetail = $db.Trim()
            $report.sample.deviceId = $DeviceId
        }
        else {
            $deviceDetail = "device_info miss for $DeviceId"
        }
        if ($merchantToken -and $deviceOk) {
            $devs = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/merchant/devices?page=0&size=50" -Headers $mAuth
            $items = @()
            if ($devs.items) { $items = @($devs.items) }
            elseif ($devs.records) { $items = @($devs.records) }
            $hit = $items | Where-Object { ($_.deviceId -eq $DeviceId) -or ("$($_.id)" -eq $DeviceId) } | Select-Object -First 1
            if ($hit) { $deviceDetail = "$deviceDetail; merchant.list=hit total=$($items.Count)" }
            else { $deviceDetail = "$deviceDetail; merchant.list=miss total=$($items.Count)" }
        }
    }
    catch {
        $deviceDetail = $_.Exception.Message
    }
}
Set-Check "demo_device" $deviceOk $deviceDetail

# 4) consumer balance + orders
$balanceCents = $null
$orderCount = 0
$sampleOrder = $null
if ($consumerToken) {
    try {
        $acc = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $cAuth
        $balanceCents = $acc.balanceCents
        if ($null -eq $balanceCents) { $balanceCents = $acc.availableBalanceCents }
        Set-Check "consumer_balance" ($null -ne $balanceCents) "balanceCents=$balanceCents"
        $report.sample.balanceCents = $balanceCents
    }
    catch {
        Set-Check "consumer_balance" $false $_.Exception.Message
    }
    try {
        $ords = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/orders?page=0&size=5" -Headers $cAuth
        $oitems = @()
        if ($ords.items) { $oitems = @($ords.items) }
        elseif ($ords.records) { $oitems = @($ords.records) }
        elseif ($ords.content) { $oitems = @($ords.content) }
        $orderCount = $oitems.Count
        if ($ords.total) { $orderCount = [int]$ords.total }
        $sampleOrder = $oitems | Select-Object -First 1
        Set-Check "consumer_orders" ($orderCount -ge 1) "count=$orderCount"
        if ($sampleOrder) {
            $report.sample.orderId = $sampleOrder.orderId
            if (-not $report.sample.orderId) { $report.sample.orderId = $sampleOrder.id }
            $report.sample.orderStatus = $sampleOrder.status
            $report.sample.amountCents = $sampleOrder.payAmountCents
            if ($null -eq $report.sample.amountCents) { $report.sample.amountCents = $sampleOrder.amountCents }
        }
    }
    catch {
        Set-Check "consumer_orders" $false $_.Exception.Message
    }
}

# 5) merchant wallet
if ($merchantToken) {
    try {
        $w = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/merchant/wallet" -Headers $mAuth
        $wBal = $w.availableBalanceCents
        if ($null -eq $wBal) { $wBal = $w.balanceCents }
        Set-Check "merchant_wallet" ($null -ne $wBal) "availableBalanceCents=$wBal"
        $report.sample.merchantWalletCents = $wBal
    }
    catch {
        Set-Check "merchant_wallet" $false $_.Exception.Message
    }
}

# Optional seed
if ($SeedMinimal) {
    Write-Host "==> SeedMinimal: e2e-shopping.ps1 (writes demo ledger)"
    $shopping = Join-Path $PSScriptRoot "e2e-shopping.ps1"
    if (-not (Test-Path $shopping)) {
        Set-Check "seed_shopping" $false "missing e2e-shopping.ps1"
    }
    else {
        try {
            & powershell -NoProfile -ExecutionPolicy Bypass -File $shopping `
                -BaseUrl $BaseUrl -Channel BALANCE -DeviceId $DeviceId
            $seedOk = ($LASTEXITCODE -eq 0)
            Set-Check "seed_shopping" $seedOk "exit=$LASTEXITCODE"
            if ($seedOk -and $consumerToken) {
                $ords2 = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/orders?page=0&size=3" -Headers $cAuth
                $oi = @($ords2.items)
                if (-not $oi.Count -and $ords2.records) { $oi = @($ords2.records) }
                $report.sample.seedOrderId = ($oi | Select-Object -First 1).orderId
            }
        }
        catch {
            Set-Check "seed_shopping" $false $_.Exception.Message
        }
    }
}

# S1 platform required. consumer_orders: only required after SeedMinimal (empty after cleanup is OK)
$required = @(
    "trade_health", "device_resolve", "s0_cleanup", "consumer_login", "merchant_login", "ops_login",
    "demo_device", "consumer_balance", "merchant_wallet"
)
if ($SeedMinimal) { $required += "consumer_orders" }
$fail = @()
foreach ($k in $required) {
    if (-not $report.checks.Contains($k) -or -not $report.checks[$k].ok) { $fail += $k }
}
if ($SeedMinimal -and $report.checks.Contains("seed_shopping") -and -not $report.checks["seed_shopping"].ok) {
    $fail += "seed_shopping"
}
# Soft signal: orders empty without seed
if (-not $SeedMinimal -and $report.checks.Contains("consumer_orders") -and -not $report.checks["consumer_orders"].ok) {
    $report.hints += "no consumer orders yet: use -SeedMinimal or e2e-full-flow-milk.ps1 before UI L3"
}
$report.pass = ($fail.Count -eq 0)
$report.failed = $fail
if (-not $report.pass) {
    $report.hints += ("failed: " + ($fail -join ", "))
    $report.hints += "see docs/uat/MP_THREE_END_SCENARIOS.md (S0 cleanup -> S1 platform -> S2 chain)"
    if ($fail -contains "consumer_orders") {
        $report.hints += "retry: powershell -File scripts/mp-seed-gate.ps1 -CleanupFirst -SeedMinimal"
    }
}

$report | ConvertTo-Json -Depth 8 | Set-Content -Path $OutJson -Encoding utf8
Write-Host "----"
Write-Host "pass=$($report.pass)  out=$OutJson"
if ($report.sample.orderId) {
    Write-Host ("sample.orderId=" + $report.sample.orderId + " balanceCents=" + $report.sample.balanceCents)
}
if (-not $report.pass) { exit 2 }
exit 0
