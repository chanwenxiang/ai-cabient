# E2E: trigger RECOGNITION_FAILED dispute and verify ops_exception + dispute_ticket
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$DeviceId = "CAB-001",
    [string]$ConsumerPhone = "13800138000",
    [string]$ConsumerPassword = "123456"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $RepoRoot "infra\docker-compose.full.yml"
$EnvFile = Join-Path $RepoRoot "infra\.env"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

function Get-E2eAuth {
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $ConsumerPhone
        password    = $ConsumerPassword
    }
    return @{ Authorization = "Bearer $($login.token)" }
}

Write-Host "========== Recognition Dispute E2E =========="
Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
Set-E2eConsumerBalance -BalanceCents 11300 | Out-Null

Write-Host "==> Enable MOCK_FORCE_NEED_REVIEW on vision-service"
$env:MOCK_FORCE_NEED_REVIEW = "true"
$env:AICABINET_MOCK_ENABLED = "false"
docker compose -p ai-cabinet --env-file $EnvFile -f $ComposeFile up -d --no-deps vision-service trade-service | Out-Null
if ($LASTEXITCODE -ne 0) { throw "failed to restart vision/trade for dispute E2E" }

$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    if (Test-ServiceHealth -Url "$BaseUrl/actuator/health") { break }
    Start-Sleep -Seconds 2
}
if (-not (Test-ServiceHealth -Url "$BaseUrl/actuator/health")) {
    throw "trade-service not healthy"
}

& (Join-Path $RepoRoot "scripts\set-simulator-cart.ps1") -Items @("SKU-DEMO-001:1") -ShoppingSeconds 8 -NoRecreate
$auth = Get-E2eAuth

$result = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
    -RepoRoot $RepoRoot -KeepSimulator
$sessionId = $result.SessionId
Write-Host "Session=$sessionId finalState=$($result.FinalState)"

if ($result.FinalState -ne "DISPUTED") {
    throw "Expected DISPUTED for recognition review, got $($result.FinalState)"
}

$db = docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -c @"
SELECT ss.session_id, ss.state,
       (SELECT COUNT(*) FROM cabinet_order o WHERE o.session_id = ss.session_id) AS order_count,
       (SELECT exception_type FROM ops_exception e WHERE e.session_id = ss.session_id ORDER BY created_at DESC LIMIT 1) AS exception_type,
       (SELECT status FROM dispute_ticket d WHERE d.session_id = ss.session_id LIMIT 1) AS dispute_status
FROM shopping_session ss WHERE ss.session_id = '$sessionId';
"@
Write-Host $db

if ($db -notmatch "RECOGNITION_FAILED") {
    throw "Expected ops_exception RECOGNITION_FAILED"
}
if ($db -notmatch "OPEN") {
    throw "Expected open dispute_ticket"
}

Write-Host ""
Write-Host "OK recognition dispute E2E passed session=$sessionId"
Write-Host "Admin exception center: http://127.0.0.1:8080/admin/ (filter RECOGNITION_FAILED)"

# Restore default mock flags for subsequent tests
Remove-Item Env:MOCK_FORCE_NEED_REVIEW -ErrorAction SilentlyContinue
$env:AICABINET_MOCK_ENABLED = "true"
docker compose -p ai-cabinet --env-file $EnvFile -f $ComposeFile up -d --no-deps vision-service trade-service | Out-Null

exit 0
