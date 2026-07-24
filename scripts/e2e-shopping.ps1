# Shopping E2E — MQTT open-door with DB-backed demo context
# Usage:
#   .\scripts\e2e-shopping.ps1
#   .\scripts\e2e-shopping.ps1 -Channel WECHAT
#   .\scripts\e2e-shopping.ps1 -Channel ALIPAY
#   .\scripts\e2e-shopping.ps1 -Channel BALANCE

param(
    [string]$BaseUrl = "",
    [string]$Phone = "",
    [string]$Code = "123456",
    [string]$DeviceId = "",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$MqttBroker = "tcp://localhost:11883",
    [ValidateSet("", "WECHAT", "ALIPAY", "BALANCE")]
    [string]$Channel = "",
    [switch]$SkipSimulatorStart,
    [switch]$KeepSimulator
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
$demoCtx = $null

if (-not $Phone -or -not $DeviceId) {
    $demoCtx = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
    if (-not $Phone) { $Phone = $demoCtx.consumerPhone }
    if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
    Write-Host "==> Demo context from DB: device=$DeviceId phone=$Phone fallbackSku=$($demoCtx.fallbackSkuId)"
}

$expectedChannel = if ($Channel) { $Channel.ToUpper() } else { "" }
$simProc = $null
$startedSimulator = $false
$e2eLock = Enter-E2eLock -Owner "e2e-shopping"
try {
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null

    Write-Host "==> 1. Login"
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $Phone
        password    = $Code
    }
    $auth = @{ Authorization = "Bearer $($login.token)" }
    Write-Host "    userId=$($login.userId)"

    if ($expectedChannel) {
        Write-Host "==> 1b. Prepare pay channel=$expectedChannel"
        if ($expectedChannel -eq "BALANCE") {
            Set-E2eConsumerBalance -BalanceCents 20000 | Out-Null
        }
        Set-E2eConsumerPayChannel -BaseUrl $BaseUrl -Auth $auth -Channel $expectedChannel -Phone $Phone
    }

    Write-Host "==> 2. Account before"
    $before = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
    Write-Host "    balanceCents=$($before.balanceCents) passwordFree=$($before.passwordFreeReady) preferred=$($before.payPreferredChannel)"

    Write-Host "==> 3. Preset simulator cart"
    & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @("SKU-DEMO-001:1") -ShoppingSeconds 20 -NoRecreate
    $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $auth
    if (-not $dev.available) {
        Write-Host "    device busy after cart setup; restarting simulator"
        Restart-E2eDeviceSimulator
    }

    Write-Host "==> 4. MQTT shopping flow"
    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
        -RepoRoot $Root -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey `
        -SkipSimulatorStart:$SkipSimulatorStart -KeepSimulator:$KeepSimulator
    $sessionId = $mqtt.SessionId
    $startedSimulator = $mqtt.SimulatorStarted
    $simProc = $mqtt.SimulatorProcess
    $finalState = $mqtt.FinalState
    Write-Host "    session finalState=$finalState"

    # Snapshot balance immediately before ops charge (ignore concurrent recharge noise).
    if ($finalState -eq "DISPUTED") {
        Write-Host "==> 4b. DISPUTED -> ops CONFIRM"
        $opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
            phoneNumber = "13900000001"
            password    = "123456"
        }
        $ops = @{ Authorization = "Bearer $($opsLogin.token)" }
        $disp = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/ops/disputes?status=OPEN&sessionId=$sessionId&page=0&size=5" -Headers $ops
        $tickets = @()
        if ($disp.items) { $tickets = @($disp.items) }
        if ($tickets.Count -lt 1) {
            throw "DISPUTED session $sessionId has no OPEN dispute ticket"
        }
        $ticketId = $tickets[0].ticketId
        Write-Host "    ticket=$ticketId reviewCode=$($tickets[0].reviewCode)"
        # 优先用票上建议商品 / demoCtx 兜底 SKU，避免硬编码与库存不一致
        $confirmSku = "SKU-DEMO-001"
        $confirmQty = 1
        $suggested = @()
        if ($tickets[0].suggestedItems) { $suggested = @($tickets[0].suggestedItems) }
        elseif ($tickets[0].items) { $suggested = @($tickets[0].items) }
        if ($suggested.Count -ge 1 -and $suggested[0].skuId) {
            $confirmSku = [string]$suggested[0].skuId
            if ($suggested[0].quantity) { $confirmQty = [int]$suggested[0].quantity }
        } elseif ($demoCtx -and $demoCtx.fallbackSkuId) {
            $confirmSku = [string]$demoCtx.fallbackSkuId
        }
        Write-Host "    confirm sku=$confirmSku qty=$confirmQty"
        $before = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
        Write-Host "    balance before confirm=$($before.balanceCents)"
        $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
            -Path "/api/v2/ops/disputes/$ticketId/resolve" -Headers $ops -Body @{
            resolutionType = "CONFIRM"
            items          = @(@{ skuId = $confirmSku; quantity = $confirmQty })
        }
    } elseif ($finalState -notin @("COMPLETED", $null, "")) {
        Write-Host "    unexpected terminal state=$finalState (will still poll for order)"
    }

    Write-Host "==> 5. Wait for order"
    $order = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $sessionId -Auth $auth
    Write-Host "    orderId=$($order.orderId) total=$($order.totalAmountCents) status=$($order.status) channel=$($order.payChannel)"

    Write-Host "==> 6. Payment assertion"
    $after = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
    Write-Host "    balanceCents=$($after.balanceCents)"

    if ($order.status -ne "PAID") {
        throw "Expected order PAID, got $($order.status)"
    }

    $channel = if ($order.payChannel) { $order.payChannel.ToUpper() } else { "BALANCE" }
    # Prefer ledger for this order to avoid concurrent recharge/charge races.
    $chargeRow = (docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -t -A -c `
        "SELECT amount_cents FROM payment_operation WHERE order_id='$($order.orderId)' AND operation_type='CHARGE' AND status='COMPLETED' ORDER BY created_at DESC LIMIT 1;").Trim()
    if ($chargeRow) {
        $spent = [int]$chargeRow
    } else {
        $spent = $before.balanceCents - $after.balanceCents
    }

    if ($expectedChannel -and $channel -ne $expectedChannel) {
        throw "Expected payChannel=$expectedChannel, got $channel"
    }

    switch ($channel) {
        "BALANCE" {
            if ($spent -ne $order.totalAmountCents) {
                throw "Balance channel: spent $spent != order total $($order.totalAmountCents)"
            }
            Write-Host "    paid via balance (-$spent cents)"
        }
        { $_ -in @("WECHAT", "ALIPAY") } {
            if ($spent -ne 0 -and -not $chargeRow) {
                throw "$channel channel: balance should not change, but spent $spent cents"
            }
            Write-Host "    paid via $channel (balance unchanged)"
        }
        default {
            throw "Unknown payChannel=$channel"
        }
    }

    Write-Host ""
    if ($expectedChannel) {
        Write-Host "OK shopping E2E passed (door=mqtt, channel=$expectedChannel)"
    } else {
        Write-Host "OK shopping E2E passed (door=mqtt)"
    }
} finally {
    if ($startedSimulator -and -not $KeepSimulator) {
        Write-Host "==> Stopping DeviceSimulator..."
        Stop-E2eDeviceSimulator $simProc
    }
    Exit-E2eLock $e2eLock
}

exit 0
