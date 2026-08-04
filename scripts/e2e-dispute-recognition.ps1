# E2E: trigger RECOGNITION_FAILED dispute and verify ops_exception + dispute_ticket
# Toggles vision mock_force_need_review via API (no container recreate).
param(
    [string]$BaseUrl = "",
    [string]$VisionUrl = "",
    [string]$DeviceId = "CAB-001",
    [string]$ConsumerPhone = "13800138000",
    [string]$ConsumerPassword = "123456",
    [string]$VisionApiKey = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
if ([string]::IsNullOrWhiteSpace($VisionUrl)) { $VisionUrl = Get-E2eVisionUrl }
if ([string]::IsNullOrWhiteSpace($VisionApiKey)) { $VisionApiKey = Get-E2eVisionApiKey }

function Get-E2eAuth {
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $ConsumerPhone
        password    = $ConsumerPassword
    }
    return @{ Authorization = "Bearer $($login.token)" }
}

Write-Host "========== Recognition Dispute E2E =========="
Write-Host "    BaseUrl=$BaseUrl VisionUrl=$VisionUrl"
Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
Set-E2eConsumerBalance -BalanceCents 11300 | Out-Null

if (-not (Test-ServiceHealth -Url "$BaseUrl/actuator/health")) {
    throw "trade-service not healthy at $BaseUrl"
}
if (-not (Test-ServiceHealth -Url "$VisionUrl/health")) {
    throw "vision-service not healthy at $VisionUrl"
}

Write-Host "==> Enable mock_force_need_review via vision API (no recreate)"
$toggle = Set-E2eVisionForceNeedReview -Enabled $true -VisionUrl $VisionUrl -VisionApiKey $VisionApiKey
if (-not $toggle.mock_force_need_review) {
    throw "failed to enable mock_force_need_review"
}
Write-Host "    mock_force_need_review=$($toggle.mock_force_need_review)"

try {
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
    $dbText = ($db | Out-String)
    Write-Host $dbText

    if ($dbText -notmatch "RECOGNITION_FAILED") {
        throw "Expected ops_exception RECOGNITION_FAILED"
    }
    if ($dbText -notmatch "\bOPEN\b") {
        throw "Expected open dispute_ticket"
    }

    Write-Host ""
    Write-Host "OK recognition dispute E2E passed session=$sessionId"
    Write-Host "Admin exception center: $BaseUrl/admin/ (filter RECOGNITION_FAILED)"
}
finally {
    Write-Host "==> Restore mock_force_need_review=false"
    try {
        Set-E2eVisionForceNeedReview -Enabled $false -VisionUrl $VisionUrl -VisionApiKey $VisionApiKey | Out-Null
    } catch {
        Write-Warning "failed to restore mock_force_need_review: $_"
    }
}

exit 0
