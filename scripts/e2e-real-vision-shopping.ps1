# 真实 YOLO 购物 E2E — mock 关闭，simulator 上传图片/视频，vision 识别结算
param(
    [string]$BaseUrl = "http://localhost:18080",
    [string]$Phone = "",
    [string]$Code = "123456",
    [string]$DeviceId = "",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$MqttBroker = "tcp://localhost:11883",
    [string]$EnvFile = "",
    [string]$VideoFile = "/testdata/bottle.jpg",
    [ValidateSet("COMPLETED", "DISPUTED", "ANY")]
    [string]$ExpectedState = "ANY",
    [switch]$SkipSimulatorRecreate,
    [switch]$SkipSimulatorStart,
    [switch]$KeepSimulator
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

if (-not $EnvFile) {
    $EnvFile = Join-Path $Root "infra\.env.sandbox"
    if (-not (Test-Path $EnvFile)) {
        $EnvFile = Join-Path $Root "infra\.env.sandbox.example"
    }
}
$ComposeFile = Join-Path $Root "infra\docker-compose.full.yml"

if ($VideoFile -match '^[A-Za-z]:\\' -or $VideoFile -match '^\\') {
    $leaf = Split-Path -Leaf $VideoFile
    $VideoFile = "/testdata/$leaf"
    $hostPath = Join-Path $Root "testdata\$leaf"
    if (-not (Test-Path $hostPath)) {
        throw "Video file not found under testdata: $hostPath"
    }
} elseif ($VideoFile -notmatch '^/testdata/') {
    $leaf = Split-Path -Leaf $VideoFile
    $hostPath = Join-Path $Root "testdata\$leaf"
    if (-not (Test-Path $hostPath)) {
        throw "Video file not found under testdata: $hostPath"
    }
    $VideoFile = "/testdata/$leaf"
}

if (-not $Phone -or -not $DeviceId) {
    $demoCtx = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
    if (-not $Phone) { $Phone = $demoCtx.consumerPhone }
    if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
}

Write-Host "==> Real vision E2E"
Write-Host "    envFile=$EnvFile"
Write-Host "    video=$VideoFile"
Write-Host "    expectedState=$ExpectedState"

if (-not $SkipSimulatorRecreate) {
    Write-Host "==> Recreate device-simulator (empty gravity, real video)"
    $env:AICABINET_SIM_VIDEO_FILE = $VideoFile
    $env:AICABINET_SIM_GRAVITY_JSON = "[]"
    $env:AICABINET_SIM_SHOPPING_MS = "8000"
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker compose -p ai-cabinet --env-file $EnvFile -f $ComposeFile up -d --no-deps --force-recreate device-simulator 2>&1 | Out-Null
    $composeExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($composeExit -ne 0) { throw "device-simulator recreate failed (exit $composeExit)" }
    Write-Host "    waiting 12s for MQTT warmup..."
    Start-Sleep -Seconds 12
}

$visionBase = if ($BaseUrl -match ':18080$') { "http://localhost:18082" } elseif ($BaseUrl -match ':8080$') { "http://localhost:8082" } else { "http://localhost:18082" }
try {
    $visionHealth = Invoke-RestMethod -Uri "$visionBase/health" -TimeoutSec 10
    if (-not $visionHealth.yolo_loaded) {
        Write-Warning "vision yolo_loaded=false — rebuild with VISION_INSTALL_ML=true"
    } else {
        Write-Host "    vision OK yolo_loaded=true mock=$($visionHealth.mock_enabled)"
    }
} catch {
    Write-Warning "vision health check failed: $_"
}

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

    Write-Host "==> 2. Ensure balance >= 500 cents"
    $acct = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
    if (($acct.balanceCents | ForEach-Object { $_ }) -lt 500) {
        Set-E2eConsumerBalance -BalanceCents 5000 -Phone $Phone | Out-Null
        Write-Host "    topped up balance for E2E"
    }

    Write-Host "==> 3. MQTT shopping (vision path, no gravity cart)"
    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
        -RepoRoot $Root -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey `
        -SkipSimulatorStart:$SkipSimulatorStart -KeepSimulator:$KeepSimulator
    $sessionId = $mqtt.SessionId
    $startedSimulator = $mqtt.SimulatorStarted
    $simProc = $mqtt.SimulatorProcess

    Write-Host "==> 4. Wait terminal state"
    $finalState = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $auth -MaxPolls 45
    Write-Host "    session=$sessionId state=$finalState"

    if ($ExpectedState -ne "ANY" -and $finalState -ne $ExpectedState) {
        throw "Expected session $ExpectedState, got $finalState"
    }

    if ($finalState -eq "COMPLETED") {
        $order = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $sessionId -Auth $auth
        Write-Host "    orderId=$($order.orderId) total=$($order.totalAmountCents) status=$($order.status)"
        if ($order.status -ne "PAID") {
            throw "Expected order PAID, got $($order.status)"
        }
        $after = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $auth
        Write-Host "    balanceAfter=$($after.balanceCents)"
    } elseif ($finalState -eq "DISPUTED") {
        $disputes = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/disputes/mine" -Headers $auth
        $ticket = $disputes | Where-Object { $_.sessionId -eq $sessionId } | Select-Object -First 1
        if ($ticket) {
            Write-Host "    dispute ticket=$($ticket.ticketId) reason=$($ticket.reason)"
        }
    }

    Write-Host "==> PASS real-vision-shopping state=$finalState"
} finally {
    if ($startedSimulator -and -not $KeepSimulator -and $simProc) {
        Stop-Process -Id $simProc.Id -Force -ErrorAction SilentlyContinue
    }
}
