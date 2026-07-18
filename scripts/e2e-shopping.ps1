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

if (-not $Phone -or -not $DeviceId) {
    $demoCtx = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
    if (-not $Phone) { $Phone = $demoCtx.consumerPhone }
    if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
    Write-Host "==> Demo context from DB: device=$DeviceId phone=$Phone fallbackSku=$($demoCtx.fallbackSkuId)"
}

$expectedChannel = if ($Channel) { $Channel.ToUpper() } else { "" }
$simProc = $null
$startedSimulator = $false

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
    $spent = $before.balanceCents - $after.balanceCents

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
            if ($spent -ne 0) {
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
}

exit 0
