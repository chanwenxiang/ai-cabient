# Staging E2E: SMS webhook login + MQTT shopping (mock off, no internal door-event)
# Requires staging stack from verify-step5.ps1 -Staging

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$SmsMockUrl = "http://localhost:8099",
    [string]$InternalApiKey = "staging-internal-api-key-32bytes-min",
    [string]$Phone = "",
    [string]$DeviceId = "",
    [string]$MqttBroker = "tcp://localhost:11883",
    [switch]$SkipSimulatorStart,
    [switch]$KeepSimulator
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\e2e-sms-auth.ps1"
. "$PSScriptRoot\e2e-door-flow.ps1"

if (-not $Phone -or -not $DeviceId) {
    $demoCtx = & "$PSScriptRoot\seed-demo-data.ps1" -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
    if (-not $Phone) { $Phone = $demoCtx.consumerPhone }
    if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
}

$simProc = $null
$startedSimulator = $false

try {
    Write-Host "==> Staging E2E: SMS login + MQTT shopping"
    & (Join-Path $PSScriptRoot "e2e-cleanup-device.ps1") -DeviceId $DeviceId | Out-Null

    $ctx = Ensure-E2eConsumerBalance -BaseUrl $BaseUrl -SmsMockUrl $SmsMockUrl -ConsumerPhone $Phone `
        -InternalApiKey $InternalApiKey
    $auth = $ctx.Auth
    Write-Host "    userId=$($ctx.Login.userId) balanceCents=$($ctx.BalanceCents)"

    $before = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth

    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
        -RepoRoot $Root -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey `
        -SkipSimulatorStart:$SkipSimulatorStart -KeepSimulator:$KeepSimulator
    $startedSimulator = $mqtt.SimulatorStarted
    $simProc = $mqtt.SimulatorProcess
    $sessionId = $mqtt.SessionId

    $order = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $sessionId -Auth $auth
    Write-Host "    orderId=$($order.orderId) total=$($order.totalAmountCents) status=$($order.status)"

    $after = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
    $spent = $before.balanceCents - $after.balanceCents
    if ($order.status -ne "PAID") {
        throw "Expected PAID, got $($order.status)"
    }
    if ($spent -ne $order.totalAmountCents) {
        throw "Balance spent $spent != order total $($order.totalAmountCents)"
    }

    Write-Host ""
    Write-Host "OK staging shopping E2E passed (SMS + MQTT)"
} finally {
    if ($startedSimulator -and -not $KeepSimulator) {
        Stop-E2eDeviceSimulator $simProc
    }
}

exit 0
