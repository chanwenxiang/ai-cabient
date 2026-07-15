# MQTT open-door E2E smoke (createSession -> EMQX -> DeviceSimulator)
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DeviceId = "",
    [string]$Phone = "",
    [string]$Code = "123456",
    [string]$MqttBroker = "tcp://localhost:11883",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [switch]$SkipSimulatorStart,
    [switch]$KeepSimulator
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\e2e-door-flow.ps1"

if (-not $Phone -or -not $DeviceId) {
    $demoCtx = & "$PSScriptRoot\seed-demo-data.ps1" -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
    if (-not $Phone) { $Phone = $demoCtx.consumerPhone }
    if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
}

$simProc = $null
$startedSimulator = $false

try {
    Write-Host "==> MQTT open-door E2E"
    & (Join-Path $PSScriptRoot "e2e-cleanup-device.ps1") -DeviceId $DeviceId | Out-Null

    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/sms-code?phoneNumber=$Phone" | Out-Null
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/login" -Body @{
        phoneNumber = $Phone
        code        = $Code
    }
    $auth = @{ Authorization = "Bearer $($login.token)" }
    Write-Host "    userId=$($login.userId)"

    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
        -RepoRoot $Root -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey `
        -SkipSimulatorStart:$SkipSimulatorStart -KeepSimulator:$KeepSimulator
    $startedSimulator = $mqtt.SimulatorStarted
    $simProc = $mqtt.SimulatorProcess

    Write-Host ""
    Write-Host "OK MQTT open-door E2E passed"
} finally {
    if ($startedSimulator -and -not $KeepSimulator) {
        Write-Host "==> Stopping DeviceSimulator (pid=$($simProc.Id))..."
        Stop-E2eDeviceSimulator $simProc
    } elseif ($startedSimulator) {
        Write-Host "==> DeviceSimulator still running (pid=$($simProc.Id), -KeepSimulator)"
    }
}
