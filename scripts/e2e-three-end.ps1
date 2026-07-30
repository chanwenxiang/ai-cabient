# Three-end regression: API matrix + shopping + KEEP/WAIVE/CONFIRM + consistency
# Usage:
#   .\scripts\e2e-three-end.ps1
#   .\scripts\e2e-three-end.ps1 -SkipJoint
#   .\scripts\e2e-three-end.ps1 -Actions KEEP,WAIVE
#   .\scripts\e2e-three-end.ps1 -IncludeHappyPath

param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "CAB-001",
    [string]$SkuId = "SKU-DEMO-001",
    [string]$ConsumerPhone = "13800138000",
    [string]$ConsumerPassword = "123456",
    [string]$MerchantPhone = "13800138001",
    [string]$MerchantPassword = "123456",
    [string]$ReadonlyPhone = "13800138002",
    [string]$ReadonlyPassword = "123456",
    [string]$OperatorPhone = "13900000001",
    [string]$OperatorPassword = "123456",
    [ValidateSet("KEEP", "WAIVE", "CONFIRM")]
    [string[]]$Actions = @("KEEP", "WAIVE", "CONFIRM"),
    [switch]$SkipJoint,
    [switch]$IncludeHappyPath,
    [switch]$KeepSimulator
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl

$script:Pass = 0
$script:Fail = 0
$results = [System.Collections.Generic.List[object]]::new()

function ShortDetail([string]$Detail) {
    if ([string]::IsNullOrEmpty($Detail)) { return "" }
    if ($Detail.Length -le 160) { return $Detail }
    return $Detail.Substring(0, 160) + "..."
}

function Rec([string]$Id, [bool]$Ok, [string]$Detail = "") {
    $detail = ShortDetail $Detail
    $results.Add([pscustomobject]@{ id = $Id; ok = $Ok; detail = $detail })
    if ($Ok) {
        $script:Pass++
        Write-Host "PASS $Id $(if ($detail) { $detail })"
    } else {
        $script:Fail++
        Write-Host "FAIL $Id $detail"
    }
}

function AuthHeader([string]$Phone, [string]$Password, [string]$Path) {
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path $Path -Body @{
        phoneNumber = $Phone
        password    = $Password
    }
    return @{ Authorization = "Bearer $($login.token)" }
}

function AlignThree([string]$OrderId, $ConsumerAuth, $MerchantAuth, $OpsAuth) {
    $c = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/orders/$OrderId" -Headers $ConsumerAuth
    $m = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/merchant/orders/$OrderId" -Headers $MerchantAuth
    $o = $null
    try {
        $o = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/orders/$OrderId" -Headers $OpsAuth
    } catch {
        $list = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/orders?page=0&size=50&keyword=$OrderId" -Headers $OpsAuth
        $o = @($list.items) | Where-Object { $_.orderId -eq $OrderId } | Select-Object -First 1
    }
    if (-not $o) { throw "ops order not found: $OrderId" }
    return [pscustomobject]@{
        cStatus = $c.status; cAmt = [int]$c.totalAmountCents
        mStatus = $m.status; mAmt = [int]$m.totalAmountCents
        oStatus = $o.status; oAmt = [int]$o.totalAmountCents
    }
}

function ShopOnce($ConsumerAuth) {
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    Set-E2eConsumerBalance -BalanceCents 30000 | Out-Null
    Set-E2eConsumerPayChannel -BaseUrl $BaseUrl -Auth $ConsumerAuth -Channel BALANCE -Phone $ConsumerPhone
    & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @("${SkuId}:1") -ShoppingSeconds 12 -NoRecreate | Out-Null
    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $ConsumerAuth `
        -RepoRoot $RepoRoot -KeepSimulator:$KeepSimulator
    $ord = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $mqtt.SessionId -Auth $ConsumerAuth
    return [pscustomobject]@{
        sessionId = $mqtt.SessionId
        orderId   = $ord.orderId
        amount    = [int]$ord.totalAmountCents
    }
}

function ResolveTicket([string]$TicketId, [string]$ResolutionType, $OpsAuth, $Items = @()) {
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/disputes/$TicketId/resolve" -Headers $OpsAuth -Body @{
        resolutionType = $ResolutionType
        items          = $Items
    } | Out-Null
}

Write-Host "========== Three-end regression =========="
Write-Host "    BaseUrl=$BaseUrl DeviceId=$DeviceId"

if (-not (Test-ServiceHealth -Url "$BaseUrl/actuator/health")) {
    throw "trade-service not healthy at $BaseUrl"
}

$e2eLock = Enter-E2eLock -Owner "e2e-three-end"
try {
    # --- auth ---
    try {
        $ops = AuthHeader $OperatorPhone $OperatorPassword "/api/v2/auth/admin-password-login"
        Rec "auth-ops" $true
    } catch { Rec "auth-ops" $false $_.Exception.Message; throw }

    try {
        $consumer = AuthHeader $ConsumerPhone $ConsumerPassword "/api/v2/auth/password-login"
        Rec "auth-consumer" $true
    } catch { Rec "auth-consumer" $false $_.Exception.Message; throw }

    try {
        $merchant = AuthHeader $MerchantPhone $MerchantPassword "/api/v2/auth/admin-password-login"
        Rec "auth-merchant" $true
    } catch { Rec "auth-merchant" $false $_.Exception.Message; throw }

    try {
        $readonly = AuthHeader $ReadonlyPhone $ReadonlyPassword "/api/v2/auth/admin-password-login"
        Rec "auth-readonly" $true
    } catch { Rec "auth-readonly" $false $_.Exception.Message; throw }

    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
            phoneNumber = $OperatorPhone
            password    = "000000"
        } | Out-Null
        Rec "auth-bad-password" $false "expected reject"
    } catch {
        Rec "auth-bad-password" $true "rejected"
    }

    # --- consumer API (correct paths) ---
    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $consumer | Out-Null
        Rec "C-account" $true
    } catch { Rec "C-account" $false $_.Exception.Message }

    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $consumer | Out-Null
        Rec "C-device-status" $true
    } catch { Rec "C-device-status" $false $_.Exception.Message }

    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/orders?page=0&size=5" -Headers $consumer | Out-Null
        Rec "C-orders" $true
    } catch { Rec "C-orders" $false $_.Exception.Message }

    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/devices/$DeviceId/fault-report" -Headers $consumer -Body @{
            issueType   = "DOOR"
            description = "three-end-uat-$(Get-Date -Format HHmmss)"
        } | Out-Null
        Rec "C-fault-report" $true
    } catch { Rec "C-fault-report" $false $_.Exception.Message }

    # --- merchant API ---
    foreach ($p in @(
        @{ id = "M-workbench"; path = "/api/v2/merchant/workbench" },
        @{ id = "M-devices"; path = "/api/v2/merchant/devices" },
        @{ id = "M-device-detail"; path = "/api/v2/merchant/devices/$DeviceId" },
        @{ id = "M-expiry-alerts"; path = "/api/v2/merchant/expiry-alerts" },
        @{ id = "M-exceptions"; path = "/api/v2/merchant/exceptions?status=OPEN&page=0&size=5" },
        @{ id = "M-settlements"; path = "/api/v2/merchant/settlements/overview" },
        @{ id = "M-pricing"; path = "/api/v2/merchant/pricing/skus?deviceId=$DeviceId" },
        @{ id = "M-disputes"; path = "/api/v2/merchant/disputes?page=0&size=5" },
        @{ id = "M-orders"; path = "/api/v2/merchant/orders?page=0&size=5" },
        @{ id = "M-replenish-tasks"; path = "/api/v2/merchant/replenishment/tasks" }
    )) {
        try {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path $p.path -Headers $merchant | Out-Null
            Rec $p.id $true
        } catch { Rec $p.id $false $_.Exception.Message }
    }

    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method PATCH -Path "/api/v2/merchant/pricing/skus/$SkuId" -Headers $readonly -Body @{
            deviceId   = $DeviceId
            priceCents = 999
        } | Out-Null
        Rec "M-readonly-price" $false "expected 403"
    } catch {
        Rec "M-readonly-price" ($_.Exception.Message -match "403") $_.Exception.Message
    }

    # --- ops API ---
    foreach ($p in @(
        @{ id = "O-workbench"; path = "/api/v2/ops/admin/workbench" },
        @{ id = "O-stats"; path = "/api/v2/ops/admin/stats" },
        @{ id = "O-orders"; path = "/api/v2/ops/admin/orders?page=0&size=5" },
        @{ id = "O-disputes"; path = "/api/v2/ops/disputes?page=0&size=5" },
        @{ id = "O-exceptions"; path = "/api/v2/ops/admin/exceptions?status=OPEN&page=0&size=5" },
        @{ id = "O-devices"; path = "/api/v2/ops/admin/devices?page=0&size=5" },
        @{ id = "O-audit"; path = "/api/v2/ops/admin/audit-logs?page=0&size=5" },
        @{ id = "O-consistency-failures"; path = "/api/v2/ops/admin/consistency/failures" }
    )) {
        try {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path $p.path -Headers $ops | Out-Null
            Rec $p.id $true
        } catch { Rec $p.id $false $_.Exception.Message }
    }

    try {
        $run = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/consistency/run" -Headers $ops -Body @{}
        Rec "O-consistency-run" ($run.failCount -eq 0) "failCount=$($run.failCount)"
    } catch { Rec "O-consistency-run" $false $_.Exception.Message }

    if (-not $SkipJoint) {
        # close leftover OPEN tickets so shopping is not blocked by stale disputes
        try {
            $open = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/disputes?status=OPEN&page=0&size=20" -Headers $ops
            foreach ($t in @($open.items)) {
                try { ResolveTicket $t.ticketId "KEEP" $ops } catch { }
            }
        } catch { }

        if ($IncludeHappyPath) {
            Write-Host "==== joint happy-path ===="
            try {
                $shop = ShopOnce $consumer
                $a = AlignThree $shop.orderId $consumer $merchant $ops
                $ok = ($a.cStatus -eq $a.mStatus) -and ($a.cStatus -eq $a.oStatus) `
                    -and ($a.cAmt -eq $a.mAmt) -and ($a.cAmt -eq $a.oAmt) `
                    -and ($a.cAmt -eq $shop.amount)
                Rec "joint-happy" $ok ("$($a.cStatus)/$($a.cAmt) m=$($a.mStatus)/$($a.mAmt) o=$($a.oStatus)/$($a.oAmt)")
            } catch {
                Rec "joint-happy" $false $_.Exception.Message
            }
        }

        foreach ($action in $Actions) {
            Write-Host "==== joint $action ===="
            try {
                $shop = ShopOnce $consumer
                $disp = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/disputes" -Headers $consumer -Body @{
                    sessionId = $shop.sessionId
                    reason    = "three-end-$action-wrong-bill"
                    category  = "USER_APPEAL"
                    priority  = "NORMAL"
                }
                $items = @()
                if ($action -eq "CONFIRM") {
                    $items = @(@{ skuId = $SkuId; quantity = 1 })
                }
                ResolveTicket $disp.ticketId $action $ops $items
                $a = AlignThree $shop.orderId $consumer $merchant $ops
                $ok = ($a.cStatus -eq $a.mStatus) -and ($a.cStatus -eq $a.oStatus) `
                    -and ($a.cAmt -eq $a.mAmt) -and ($a.cAmt -eq $a.oAmt)
                Rec "joint-$action" $ok ("$($a.cStatus)/$($a.cAmt) m=$($a.mStatus)/$($a.mAmt) o=$($a.oStatus)/$($a.oAmt)")
            } catch {
                Rec "joint-$action" $false $_.Exception.Message
            }
        }

        try {
            $run2 = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/consistency/run" -Headers $ops -Body @{}
            Rec "joint-consistency" ($run2.failCount -eq 0) "failCount=$($run2.failCount)"
        } catch { Rec "joint-consistency" $false $_.Exception.Message }
    }
}
finally {
    Exit-E2eLock $e2eLock
}

Write-Host ""
Write-Host "==== SUMMARY PASS=$script:Pass FAIL=$script:Fail ===="
if ($script:Fail -gt 0) {
    $results | Where-Object { -not $_.ok } | ForEach-Object { Write-Host "  - $($_.id): $($_.detail)" }
    exit 1
}
Write-Host "OK three-end regression passed"
exit 0
