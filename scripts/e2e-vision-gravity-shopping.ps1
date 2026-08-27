# 识别 + mock 重力 E2E：vision-service mock，模拟器上报重力取货
param(
    [string]$BaseUrl = "",
    [string]$Phone = "",
    [string]$Code = "123456",
    [string]$DeviceId = "",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$MqttBroker = "tcp://localhost:11883",
    [string]$EnvFile = "",
    [string]$VideoFile = "/testdata/take-one-shelf.mp4",
    [string[]]$GravityItems = @("SKU-DEMO-001:1"),
    [ValidateSet("COMPLETED", "DISPUTED", "ANY")]
    [string]$ExpectedState = "COMPLETED",
    [switch]$SkipFetchTestdata,
    [switch]$SkipSimulatorRecreate,
    [switch]$SkipSimulatorStart,
    [switch]$KeepSimulator
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl

if (-not $EnvFile) {
    $EnvFile = Join-Path $Root "infra\.env.sandbox"
    if (-not (Test-Path $EnvFile)) {
        $EnvFile = Join-Path $Root "infra\.env.sandbox.example"
    }
}
$ComposeFile = Join-Path $Root "infra\docker-compose.full.yml"

if (-not $SkipFetchTestdata) {
    & (Join-Path $PSScriptRoot "fetch-shelf-testdata.ps1")
}

if ($VideoFile -match '^[A-Za-z]:\\' -or $VideoFile -match '^\\') {
    $leaf = Split-Path -Leaf $VideoFile
    $VideoFile = "/testdata/$leaf"
} elseif ($VideoFile -notmatch '^/testdata/') {
    $VideoFile = "/testdata/$(Split-Path -Leaf $VideoFile)"
}

if (-not $Phone -or -not $DeviceId) {
    $demoCtx = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
    if (-not $Phone) { $Phone = $demoCtx.consumerPhone }
    if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
}

Write-Host "==> Vision + gravity E2E"
Write-Host "    envFile=$EnvFile"
Write-Host "    video=$VideoFile"
Write-Host "    gravity=$($GravityItems -join ', ')"
Write-Host "    expectedState=$ExpectedState"

if (-not $SkipSimulatorRecreate) {
    Write-Host "==> Recreate device-simulator (real shelf video + mock gravity)"
    & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") `
        -Items $GravityItems `
        -ShoppingSeconds 8 `
        -EnvFile $EnvFile `
        -VideoFile $VideoFile `
        -NoRecreate
    $env:AICABINET_SIM_VIDEO_FILE = $VideoFile
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker compose -p ai-cabinet --env-file $EnvFile -f $ComposeFile up -d --no-deps --force-recreate device-simulator 2>&1 | Out-Null
    $composeExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($composeExit -ne 0) { throw "device-simulator recreate failed (exit $composeExit)" }
    Write-Host "    waiting 12s for MQTT warmup..."
    Start-Sleep -Seconds 12
}

$visionBase = if ($BaseUrl -match ':18080$') { "http://localhost:18082" } else { "http://localhost:8082" }
try {
    $visionHealth = Invoke-RestMethod -Uri "$visionBase/health" -TimeoutSec 10
    Write-Host "    vision OK backend=$($visionHealth.recognizer_backend) mock=$($visionHealth.mock_enabled)"
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

    Write-Host "==> 3. MQTT shopping (vision + gravity)"
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

    Write-Host "==> PASS vision-gravity-shopping state=$finalState"
} finally {
    if ($startedSimulator -and -not $KeepSimulator -and $simProc) {
        Stop-Process -Id $simProc.Id -Force -ErrorAction SilentlyContinue
    }
}
