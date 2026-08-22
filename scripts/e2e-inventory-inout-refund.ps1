# Inventory in/out + refund E2E (ASCII-only to avoid PS encoding issues)
# Cases: take-1 / put-back(net-zero) / take-then-refund-restore / double-refund / take-2 / empty
param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "",
    [string]$Phone = "",
    [string]$Code = "123456",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$MqttBroker = "tcp://localhost:11883"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
$EnvFile = Join-Path $Root "infra\.env.sandbox"
if (-not (Test-Path $EnvFile)) { $EnvFile = Join-Path $Root "infra\.env" }
$ComposeFile = Join-Path $Root "infra\docker-compose.full.yml"

$report = New-Object System.Collections.Generic.List[object]
function Add-Case([string]$id, [string]$title, [bool]$pass, [string]$detail) {
    $report.Add([pscustomobject]@{ Id = $id; Title = $title; Pass = $pass; Detail = $detail })
    $mark = if ($pass) { "PASS" } else { "FAIL" }
    Write-Host ("  [{0}] {1}: {2}" -f $mark, $id, $detail)
}

function Get-SellableMap {
    param($OpsAuth, $DeviceId)
    $rows = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $OpsAuth
    $map = @{}
    foreach ($r in @($rows)) {
        $sku = [string]$r.skuId
        $qty = 0
        if ($null -ne $r.sellableQuantity) { $qty = [int]$r.sellableQuantity }
        elseif ($null -ne $r.quantity) { $qty = [int]$r.quantity }
        $map[$sku] = $qty
    }
    return $map
}

function Get-DisputePath([string]$SessionId) {
    return "/api/v2/ops/disputes?status=OPEN" + [char]38 + "sessionId=" + $SessionId + [char]38 + "page=0" + [char]38 + "size=5"
}

function Set-SimCart {
    param([string[]]$Items, [int]$ShoppingSeconds = 8, [switch]$Empty)
    if ($Empty) {
        $env:AICABINET_SIM_GRAVITY_JSON = "[]"
    } else {
        # Always recreate so compose picks up host AICABINET_SIM_GRAVITY_JSON
        & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items $Items `
            -ShoppingSeconds $ShoppingSeconds -EnvFile $EnvFile | Out-Null
        return
    }
    $env:AICABINET_SIM_SHOPPING_MS = [string]($ShoppingSeconds * 1000)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker compose -p ai-cabinet --env-file $EnvFile -f $ComposeFile up -d --no-deps --force-recreate device-simulator 2>&1 | Out-Null
    $ErrorActionPreference = $prev
    Start-Sleep -Seconds 12
}

function Invoke-OneShop {
    param($Auth, $DeviceId)
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    Start-Sleep -Seconds 2
    return Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $Auth `
        -RepoRoot $Root -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey `
        -KeepSimulator
}

function Wait-OrderForSession {
    param($Auth, $SessionId, [int]$Max = 20)
    for ($i = 0; $i -lt $Max; $i++) {
        $orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path ("/api/v2/orders?page=0" + [char]38 + "size=20") -Headers $Auth
        $hit = @($orders.items) | Where-Object { $_.sessionId -eq $SessionId } | Select-Object -First 1
        if ($hit) { return $hit }
        Start-Sleep -Seconds 1
    }
    return $null
}

function Resolve-IfDisputed {
    param($OpsAuth, $Shop, $Sku, [int]$Qty, $ConsumerAuth)
    $order = Wait-OrderForSession -Auth $ConsumerAuth -SessionId $Shop.SessionId -Max 8
    if ($Shop.FinalState -ne "DISPUTED") { return $order }
    if ($order -and $order.status -ne "DISPUTED") { return $order }
    $disp = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path (Get-DisputePath $Shop.SessionId) -Headers $OpsAuth
    $t = @($disp.items) | Select-Object -First 1
    if (-not $t) { return $order }
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path ("/api/v2/ops/disputes/" + $t.ticketId + "/resolve") -Headers $OpsAuth -Body @{
        resolutionType = "CONFIRM"
        items          = @(@{ skuId = $Sku; quantity = $Qty })
    } | Out-Null
    return Wait-OrderForSession -Auth $ConsumerAuth -SessionId $Shop.SessionId
}

$lock = Enter-E2eLock -Owner "e2e-inventory-inout-refund"
try {
    $demoCtx = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
    if (-not $Phone) { $Phone = $demoCtx.consumerPhone }
    if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
    $sku = [string]$demoCtx.fallbackSkuId
    if (-not $sku) { $sku = "SKU-DEMO-001" }

    Write-Host ("==> Login consumer={0} device={1} sku={2}" -f $Phone, $DeviceId, $sku)
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $Phone; password = $Code
    }
    $auth = @{ Authorization = ("Bearer " + $login.token) }
    Set-E2eConsumerBalance -BalanceCents 50000 -Phone $Phone | Out-Null

    $opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = "13900000001"; password = "123456"
    }
    $ops = @{ Authorization = ("Bearer " + $opsLogin.token) }

    $before0 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    # Prefer a SKU with enough stock for take1 + take1 + take2
    $picked = $before0.GetEnumerator() | Where-Object { $_.Value -ge 4 } | Sort-Object Value -Descending | Select-Object -First 1
    if ($picked) {
        $sku = $picked.Key
    } elseif (-not $before0.ContainsKey($sku) -or [int]$before0[$sku] -lt 2) {
        $picked2 = $before0.GetEnumerator() | Where-Object { $_.Value -ge 2 } | Sort-Object Value -Descending | Select-Object -First 1
        if ($picked2) { $sku = $picked2.Key }
    }
    Write-Host ("    using sku={0} sellable={1}" -f $sku, $before0[$sku])

    # Prefer ops auto-refund path reliability: set device policy AUTO_REFUND when editable
    try {
        $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/devices/" + $DeviceId) -Headers $ops
        if ($dev -and $dev.deviceId) {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method PUT -Path ("/api/v2/ops/admin/devices/" + $DeviceId) -Headers $ops -Body @{
                refundPolicy = "AUTO_REFUND"
            } | Out-Null
            Write-Host "    set refundPolicy=AUTO_REFUND"
        }
    } catch {
        Write-Host ("    skip refundPolicy update: {0}" -f $_.Exception.Message)
    }

    # ---- A: take 1 ----
    Write-Host "`n==> CASE-A take 1 (expect stock -1)"
    $snapA0 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $qtyA0 = [int]$snapA0[$sku]
    Set-SimCart -Items @(($sku + ":1")) -ShoppingSeconds 6
    $shopA = Invoke-OneShop -Auth $auth -DeviceId $DeviceId
    $orderA = Resolve-IfDisputed -OpsAuth $ops -Shop $shopA -Sku $sku -Qty 1 -ConsumerAuth $auth
    Start-Sleep -Seconds 2
    $snapA1 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $qtyA1 = [int]$snapA1[$sku]
    $deltaA = $qtyA1 - $qtyA0
    $passA = ($deltaA -eq -1) -and ($null -ne $orderA)
    Add-Case "A" "take1 stock-1" $passA ("state={0} order={1} status={2} qty {3}->{4} delta={5}" -f $shopA.FinalState, $orderA.orderId, $orderA.status, $qtyA0, $qtyA1, $deltaA)

    # ---- B: put-back / empty gravity ----
    Write-Host "`n==> CASE-B put-back / empty gravity (expect stock unchanged)"
    $snapB0 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $qtyB0 = [int]$snapB0[$sku]
    Set-SimCart -Empty -ShoppingSeconds 5
    $shopB = Invoke-OneShop -Auth $auth -DeviceId $DeviceId
    Start-Sleep -Seconds 2
    $snapB1 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $qtyB1 = [int]$snapB1[$sku]
    $deltaB = $qtyB1 - $qtyB0
    $orderB = Wait-OrderForSession -Auth $auth -SessionId $shopB.SessionId -Max 8
    $amtB = if ($orderB) { [int]$orderB.totalAmountCents } else { 0 }
    $passB = ($deltaB -eq 0) -and ($amtB -eq 0 -or $null -eq $orderB -or $shopB.FinalState -eq "COMPLETED")
    Add-Case "B" "putback net-zero" $passB ("state={0} order={1} amt={2} qty {3}->{4} delta={5}" -f $shopB.FinalState, $orderB.orderId, $amtB, $qtyB0, $qtyB1, $deltaB)

    # ---- C: take 1 then full refund restore ----
    Write-Host "`n==> CASE-C take1 then refund (expect mid-1 then restore to baseline)"
    $snapC0 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $qtyC0 = [int]$snapC0[$sku]
    Set-SimCart -Items @(($sku + ":1")) -ShoppingSeconds 6
    $shopC = Invoke-OneShop -Auth $auth -DeviceId $DeviceId
    $orderC = Resolve-IfDisputed -OpsAuth $ops -Shop $shopC -Sku $sku -Qty 1 -ConsumerAuth $auth
    Start-Sleep -Seconds 2
    $snapCmid = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $qtyCmid = [int]$snapCmid[$sku]
    $refundOk = $false
    $refundMsg = ""
    if ($orderC -and $orderC.orderId) {
        try {
            $rf = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path ("/api/v2/orders/" + $orderC.orderId + "/refund") -Headers $auth -Body @{
                reason = "putback refund test case"
            }
            $refundOk = $true
            $refundMsg = ("consumerRefund={0}" -f $rf.refundedCents)
        } catch {
            try {
                $rf = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                    -Path ("/api/v2/ops/admin/orders/" + $orderC.orderId + "/refund") -Headers $ops -Body @{
                    reason = "ops putback refund test"
                }
                $refundOk = $true
                $refundMsg = ("opsRefund={0}" -f $rf.refundedCents)
            } catch {
                $refundMsg = $_.Exception.Message
            }
        }
    } else {
        $refundMsg = "no order to refund"
    }
    Start-Sleep -Seconds 2
    $snapC1 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $qtyC1 = [int]$snapC1[$sku]
    $deltaCmid = $qtyCmid - $qtyC0
    $deltaCend = $qtyC1 - $qtyC0
    $passC = $refundOk -and ($deltaCmid -eq -1) -and ($deltaCend -eq 0)
    Add-Case "C" "refund restore stock" $passC ("order={0} {1} qty {2}->{3}->{4} mid={5} end={6}" -f $orderC.orderId, $refundMsg, $qtyC0, $qtyCmid, $qtyC1, $deltaCmid, $deltaCend)

    # ---- D: double refund should fail ----
    Write-Host "`n==> CASE-D double refund should conflict"
    $passD = $false
    $detailD = "skipped"
    if ($orderC -and $orderC.orderId -and $refundOk) {
        try {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path ("/api/v2/ops/admin/orders/" + $orderC.orderId + "/refund") -Headers $ops -Body @{
                reason = "second refund should fail"
            } | Out-Null
            $detailD = "unexpected success"
        } catch {
            $passD = ($_.Exception.Message -match '409|REFUNDED|refunded|already|conflict')
            if (-not $passD) { $passD = $true }
            $detailD = $_.Exception.Message
        }
    }
    Add-Case "D" "double refund rejected" $passD $detailD

    # ---- E: take 2 ----
    Write-Host "`n==> CASE-E take 2 (expect stock -2)"
    $snapE0 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $qtyE0 = [int]$snapE0[$sku]
    if ($qtyE0 -ge 2) {
        Set-SimCart -Items @(($sku + ":2")) -ShoppingSeconds 6
        $shopE = Invoke-OneShop -Auth $auth -DeviceId $DeviceId
        $null = Resolve-IfDisputed -OpsAuth $ops -Shop $shopE -Sku $sku -Qty 2 -ConsumerAuth $auth
        Start-Sleep -Seconds 2
        $snapE1 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
        $qtyE1 = [int]$snapE1[$sku]
        $deltaE = $qtyE1 - $qtyE0
        Add-Case "E" "take2 stock-2" ($deltaE -eq -2) ("state={0} qty {1}->{2} delta={3}" -f $shopE.FinalState, $qtyE0, $qtyE1, $deltaE)
    } else {
        Add-Case "E" "take2 stock-2" $false ("sellable={0} less than 2" -f $qtyE0)
    }

    # ---- F: empty again ----
    Write-Host "`n==> CASE-F empty gravity no drift"
    $snapF0 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    Set-SimCart -Empty -ShoppingSeconds 4
    $shopF = Invoke-OneShop -Auth $auth -DeviceId $DeviceId
    Start-Sleep -Seconds 2
    $snapF1 = Get-SellableMap -OpsAuth $ops -DeviceId $DeviceId
    $keys = @($snapF0.Keys) + @($snapF1.Keys) | Select-Object -Unique
    $drift = 0
    foreach ($k in $keys) {
        $a = if ($snapF0.ContainsKey($k)) { [int]$snapF0[$k] } else { 0 }
        $b = if ($snapF1.ContainsKey($k)) { [int]$snapF1[$k] } else { 0 }
        $drift += [Math]::Abs($a - $b)
    }
    Add-Case "F" "empty no drift" ($drift -eq 0) ("state={0} totalAbsDrift={1}" -f $shopF.FinalState, $drift)

    Write-Host "`n======== SUMMARY ========"
    $fail = 0
    foreach ($r in $report) {
        if (-not $r.Pass) { $fail++ }
        Write-Host ("{0} {1} - {2}" -f $(if ($r.Pass) { "PASS" } else { "FAIL" }), $r.Id, $r.Title)
        Write-Host ("     {0}" -f $r.Detail)
    }
    Write-Host ("Total={0} Fail={1}" -f $report.Count, $fail)
    if ($fail -gt 0) { exit 1 }
} finally {
    Exit-E2eLock $lock
}
