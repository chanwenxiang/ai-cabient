# One-click demo smoke: mock vision need_review → dispute settle (WAIVE) → mock recharge ¥20
# reviewCode: prefers MOCK list filter; VISION_FORCE_REAL + force-need-review often yields GRAVITY_FILL (accepted).
# Layout/orderId follow-up (filters height + RESOLVED exception orderId): scripts/admin-layout-smoke.ps1
# Usage:
#   powershell -File scripts/e2e-demo-smoke.ps1
#   powershell -File scripts/e2e-demo-smoke.ps1 -Resolution CONFIRM
# Optional: -SkipShopping (reuse latest OPEN MOCK dispute), -SkipRecharge
param(
    [string]$BaseUrl = "",
    [string]$VisionUrl = "",
    [string]$DeviceId = "CAB-001",
    [string]$ConsumerPhone = "13800138000",
    [string]$ConsumerPassword = "123456",
    [string]$OperatorPhone = "13900000001",
    [string]$OperatorPassword = "123456",
    [string]$VisionApiKey = "",
    [ValidateSet("WAIVE", "CONFIRM")]
    [string]$Resolution = "WAIVE",
    [switch]$SkipShopping,
    [switch]$SkipRecharge
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
if ([string]::IsNullOrWhiteSpace($VisionUrl)) { $VisionUrl = Get-E2eVisionUrl }
if ([string]::IsNullOrWhiteSpace($VisionApiKey)) { $VisionApiKey = Get-E2eVisionApiKey }

$e2eLock = Enter-E2eLock -Owner "e2e-demo-smoke"
try {
# --- body continues below; closing brace added at file end via second patch ---
function Get-ConsumerAuth {
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $ConsumerPhone
        password    = $ConsumerPassword
    }
    return @{ Authorization = "Bearer $($login.token)" }
}

function Get-OperatorAuth {
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = $OperatorPhone
        password    = $OperatorPassword
    }
    return @{ Authorization = "Bearer $($login.token)" }
}

function Assert-True($cond, $msg) {
    if (-not $cond) { throw "ASSERT FAIL: $msg" }
    Write-Host "OK  $msg"
}

Write-Host "========== Demo Smoke (mock vision / pay) =========="
Write-Host "    BaseUrl=$BaseUrl VisionUrl=$VisionUrl Resolution=$Resolution"

if (-not (Test-ServiceHealth -Url "$BaseUrl/actuator/health")) {
    throw "trade-service not healthy at $BaseUrl"
}
if (-not (Test-ServiceHealth -Url "$VisionUrl/health")) {
    throw "vision-service not healthy at $VisionUrl"
}

$ops = Get-OperatorAuth
$sessionId = $null
$ticketId = $null

if (-not $SkipShopping) {
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    Set-E2eConsumerBalance -BalanceCents 11300 | Out-Null
    # Demo cabinets may be sales-locked after ops experiments; unlock for smoke.
    docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -c `
        "UPDATE device_info SET sales_locked=false WHERE device_id='$DeviceId';" | Out-Null

    Write-Host "==> Enable mock_force_need_review"
    $toggle = Set-E2eVisionForceNeedReview -Enabled $true -VisionUrl $VisionUrl -VisionApiKey $VisionApiKey
    Assert-True ($toggle.mock_force_need_review -eq $true) "force-need-review enabled"

    try {
        # Docker stack (:18080): recreate container simulator with cart env.
        # IDEA stack (:8080): only export env for local DeviceSimulator process.
        $useDockerSim = ($BaseUrl -match ':18080$') -or ($env:E2E_USE_DOCKER_SIMULATOR -eq '1')
        if ($useDockerSim) {
            & (Join-Path $RepoRoot "scripts\set-simulator-cart.ps1") -Items @("SKU-DEMO-001:1") -ShoppingSeconds 8
        } else {
            & (Join-Path $RepoRoot "scripts\set-simulator-cart.ps1") -Items @("SKU-DEMO-001:1") -ShoppingSeconds 8 -SkipDocker
        }
        $auth = Get-ConsumerAuth
        $result = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
            -RepoRoot $RepoRoot -KeepSimulator
        $sessionId = $result.SessionId
        Write-Host "Session=$sessionId finalState=$($result.FinalState)"
        Assert-True ($result.FinalState -eq "DISPUTED") "session DISPUTED after force-need-review"
    }
    finally {
        Write-Host "==> Restore mock_force_need_review=false"
        try {
            Set-E2eVisionForceNeedReview -Enabled $false -VisionUrl $VisionUrl -VisionApiKey $VisionApiKey | Out-Null
        } catch {
            Write-Warning "failed to restore mock_force_need_review: $_"
        }
    }
}

Write-Host "==> List OPEN disputes (prefer reviewCode=MOCK, else session)"
$page = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
    -Path "/api/v2/ops/disputes?status=OPEN&reviewCode=MOCK&page=0&size=20" -Headers $ops
$items = @()
if ($page.items) { $items = @($page.items) }

$match = $null
if ($sessionId) {
    $match = $items | Where-Object { $_.sessionId -eq $sessionId } | Select-Object -First 1
    if (-not $match) {
        $any = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/ops/disputes?status=OPEN&sessionId=$sessionId&page=0&size=5" -Headers $ops
        $anyItems = @()
        if ($any.items) { $anyItems = @($any.items) }
        $match = $anyItems | Select-Object -First 1
    }
    Assert-True ($null -ne $match) "OPEN dispute for session=$sessionId"
    $ticketId = $match.ticketId
    # With VISION_FORCE_REAL + force-need-review, reviewCode is often GRAVITY_FILL;
    # pure mock vision yields MOCK. Both are valid demo need_review paths.
    $code = "$($match.reviewCode)".ToUpper()
    if ($code -and $code -notin @("MOCK", "GRAVITY_FILL", "GRAVITY_MISMATCH", "NEED_REVIEW", "EMPTY", "LOW_CONF", "UNMAPPED", "WHITELIST")) {
        throw "ASSERT FAIL: unexpected reviewCode=$code"
    }
    Write-Host "OK  reviewCode=$code (MOCK filter listed $($items.Count) open MOCK tickets)"
} else {
    # SkipShopping: accept any OPEN need_review ticket (MOCK preferred, else latest OPEN)
    if ($items.Count -lt 1) {
        $any = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/ops/disputes?status=OPEN&page=0&size=20" -Headers $ops
        if ($any.items) { $items = @($any.items) }
    }
    Assert-True ($items.Count -ge 1) "at least one OPEN dispute (run without -SkipShopping to create)"
    $match = $items | Select-Object -First 1
    $ticketId = $match.ticketId
    $sessionId = $match.sessionId
}

Write-Host "Ticket=$ticketId session=$sessionId reviewCode=$($match.reviewCode)"
# admin-vue 使用 createWebHistory('/admin/')，勿用 #/hash；Gateway :80 优先，否则 trade 直连。
$adminOrigin = 'http://localhost'
try {
    $null = Invoke-WebRequest -Uri 'http://localhost/actuator/health' -UseBasicParsing -TimeoutSec 2
} catch {
    if ($BaseUrl -match '18080') { $adminOrigin = 'http://localhost:18080' }
    else { $adminOrigin = 'http://localhost:8080' }
}
Write-Host "Admin UI: $adminOrigin/admin/disputes?status=OPEN (MOCK/识别争议 filter)"
Write-Host "          $adminOrigin/admin/exceptions?status=OPEN"

Write-Host "==> Resolve $Resolution"
$body = @{ resolutionType = $Resolution; items = @() }
if ($Resolution -eq "CONFIRM") {
    $body.items = @(@{ skuId = "SKU-DEMO-001"; quantity = 1 })
}
$resolve = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
    -Path "/api/v2/ops/disputes/$ticketId/resolve" -Headers $ops -Body $body
Write-Host "    resolve result type=$($resolve.resolutionType) message=$($resolve.message)"
Assert-True ($resolve.resolutionType -eq $Resolution) "争议结案类型应为 $Resolution，实际=$($resolve.resolutionType)"

$after = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
    -Path "/api/v2/ops/disputes?sessionId=$sessionId&page=0&size=5" -Headers $ops
$afterItems = @()
if ($after.items) { $afterItems = @($after.items) }
elseif ($after.content) { $afterItems = @($after.content) }
$closed = $afterItems | Where-Object { $_.ticketId -eq $ticketId } | Select-Object -First 1
if ($closed) {
    Assert-True ($closed.status -ne "OPEN") "ticket no longer OPEN (status=$($closed.status))"
}

if (-not $SkipRecharge) {
    Write-Host "==> Mock recharge ¥20"
    $cAuth = Get-ConsumerAuth
    $accBefore = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $cAuth
    $balBefore = [int]$accBefore.balanceCents
    $key = "demo-smoke-recharge-" + [guid]::NewGuid().ToString("N")
    $prepay = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/payment/recharge/prepay" -Headers $cAuth -Body @{
        channel        = "WECHAT"
        amountCents    = 2000
        idempotencyKey = $key
    }
    Assert-True ($null -ne $prepay.orderId) "prepay orderId"
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path "/api/v2/payment/recharge/$($prepay.orderId)/mock-success" -Headers $cAuth | Out-Null
    $accAfter = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $cAuth
    $balAfter = [int]$accAfter.balanceCents
    Assert-True ($balAfter -eq ($balBefore + 2000)) "balance +¥20 ($balBefore -> $balAfter)"
}

Write-Host ""
Write-Host "OK demo smoke passed ticket=$ticketId session=$sessionId"
} finally {
    Exit-E2eLock $e2eLock
}
exit 0
