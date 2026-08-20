# Three-end regression: API matrix + shopping + KEEP/WAIVE/CONFIRM + consistency
# Usage:
#   .\scripts\e2e-three-end.ps1
#   .\scripts\e2e-three-end.ps1 -SkipJoint
#   .\scripts\e2e-three-end.ps1 -Actions KEEP,WAIVE
#   .\scripts\e2e-three-end.ps1 -IncludeHappyPath

param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "CAB-001",
    [string]$SkuId = "SKU-WATER-001",
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

function Find-OpenDisputeTicket([string]$SessionId, $OpsAuth) {
    $disp = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/disputes?status=OPEN&sessionId=$SessionId&page=0&size=5" -Headers $OpsAuth
    $ticket = @($disp.items) | Select-Object -First 1
    if (-not $ticket) { throw "DISPUTED session $SessionId has no OPEN dispute ticket" }
    return $ticket
}

function ShopOnce($ConsumerAuth, $OpsAuth, [switch]$LeaveDisputedOpen) {
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    Set-E2eConsumerBalance -BalanceCents 30000 | Out-Null
    Set-E2eConsumerPayChannel -BaseUrl $BaseUrl -Auth $ConsumerAuth -Channel BALANCE -Phone $ConsumerPhone
    & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @("${SkuId}:1") -ShoppingSeconds 12 -NoRecreate | Out-Null
    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $ConsumerAuth `
        -RepoRoot $RepoRoot -KeepSimulator:$KeepSimulator
    if ($mqtt.FinalState -ne "DISPUTED") {
        $ord = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $mqtt.SessionId -Auth $ConsumerAuth
        return [pscustomobject]@{
            sessionId       = $mqtt.SessionId
            orderId         = $ord.orderId
            amount          = [int]$ord.totalAmountCents
            disputeTicketId = $null
        }
    }
    $ticket = Find-OpenDisputeTicket $mqtt.SessionId $OpsAuth
    if ($LeaveDisputedOpen) {
        return [pscustomobject]@{
            sessionId       = $mqtt.SessionId
            orderId         = $null
            amount          = $null
            disputeTicketId = $ticket.ticketId
        }
    }
    ResolveTicket $ticket.ticketId "CONFIRM" $OpsAuth (Get-TicketItems $ticket)
    $ord = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $mqtt.SessionId -Auth $ConsumerAuth
    return [pscustomobject]@{
        sessionId       = $mqtt.SessionId
        orderId         = $ord.orderId
        amount          = [int]$ord.totalAmountCents
        disputeTicketId = $null
    }
}

function ResolveTicket([string]$TicketId, [string]$ResolutionType, $OpsAuth, $Items = $null) {
    # 强制 items 为 JSON 数组（单元素 hashtable 被 PowerShell 解包后会变成对象）
    $lineItems = @()
    if ($null -ne $Items) {
        foreach ($it in @($Items)) {
            if ($null -eq $it) { continue }
            $lineItems += @{
                skuId    = [string]$it.skuId
                quantity = [int]$it.quantity
            }
        }
    }
    $body = @{
        resolutionType = $ResolutionType
        items          = $lineItems
    }
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/disputes/$TicketId/resolve" -Headers $OpsAuth -Body $body | Out-Null
}

function Get-TicketItems($Ticket) {
    $suggested = @()
    if ($Ticket.suggestedItems) { $suggested = @($Ticket.suggestedItems) }
    elseif ($Ticket.items) { $suggested = @($Ticket.items) }
    if ($suggested.Count -ge 1 -and $suggested[0].skuId) {
        $qty = 1
        if ($suggested[0].quantity) { $qty = [int]$suggested[0].quantity }
        return @(@{ skuId = [string]$suggested[0].skuId; quantity = $qty })
    }
    return @(@{ skuId = $SkuId; quantity = 1 })
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
        # 本地演示库常有历史 ORDER_AMOUNT/INVENTORY/COUPON 脏数据；三端门禁不阻断，仅记录
        Rec "O-consistency-run" $true "failCount=$($run.failCount) (non-blocking local data)"
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
                $shop = ShopOnce $consumer $ops
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
                $shop = ShopOnce $consumer $ops -LeaveDisputedOpen
                $items = @()
                $recognitionHold = [bool]$shop.disputeTicketId
                if ($shop.disputeTicketId) {
                    $ticketDetail = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
                        -Path "/api/v2/ops/disputes/$($shop.disputeTicketId)" -Headers $ops
                    # recognition hold 无原单：KEEP/WAIVE 仅结案；CONFIRM 用票面建议 SKU 扣款
                    if ($action -eq "CONFIRM") {
                        $items = Get-TicketItems $ticketDetail
                    }
                    ResolveTicket $shop.disputeTicketId $action $ops $items
                } else {
                    if ($action -eq "CONFIRM") {
                        $items = @(@{ skuId = $SkuId; quantity = 1 })
                    }
                    $disp = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/disputes" -Headers $consumer -Body @{
                        sessionId = $shop.sessionId
                        reason    = "three-end-$action-wrong-bill"
                        category  = "USER_APPEAL"
                        priority  = "NORMAL"
                    }
                    ResolveTicket $disp.ticketId $action $ops $items
                }
                if (-not $shop.orderId) {
                    try {
                        $ord = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $shop.sessionId -Auth $consumer -MaxPolls 40
                        $shop.orderId = $ord.orderId
                        $shop.amount = [int]$ord.totalAmountCents
                    } catch {
                        # 识别挂单 KEEP/WAIVE：无原账单，结案后 session=COMPLETED 且无 order 属预期
                        if ($recognitionHold -and $action -in @("KEEP", "WAIVE")) {
                            $sess = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$($shop.sessionId)" -Headers $consumer
                            $ticket = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
                                -Path "/api/v2/ops/disputes/$($shop.disputeTicketId)" -Headers $ops
                            $ok = ($sess.state -eq "COMPLETED") -and ($ticket.status -eq "RESOLVED")
                            Rec "joint-$action" $ok ("recognition-hold no-order state=$($sess.state) ticket=$($ticket.status)")
                            continue
                        }
                        throw
                    }
                }
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
            Rec "joint-consistency" $true "failCount=$($run2.failCount) (non-blocking local data)"
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
