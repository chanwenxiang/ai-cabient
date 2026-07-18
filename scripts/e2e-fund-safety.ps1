# Fund safety E2E: balance insufficient, trade-service outage recovery, idempotency checks
param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "CAB-001",
    [string]$ConsumerPhone = "13800138000",
    [string]$ConsumerPassword = "123456",
    [switch]$SkipBalanceTest,
    [switch]$SkipOutageTest
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl

function Get-E2eAuth {
    param([string]$Phone, [string]$Password, [int]$MaxAttempts = 5)
    for ($i = 1; $i -le $MaxAttempts; $i++) {
        try {
            $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
                phoneNumber = $Phone
                password    = $Password
            }
            if ($login.token) {
                return @{ Authorization = "Bearer $($login.token)" }
            }
        } catch {
            if ($i -eq $MaxAttempts) { throw }
            Start-Sleep -Seconds 2
        }
    }
    throw "login failed after $MaxAttempts attempts"
}

Write-Host "========== Fund Safety E2E =========="
Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
$auth = Get-E2eAuth -Phone $ConsumerPhone -Password $ConsumerPassword

if (-not $SkipBalanceTest) {
    Write-Host "`n--- TC-5.9-01 Balance insufficient -> DISPUTED ---"
    $beforeBalance = (docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -t -A -c `
        "SELECT balance_cents FROM user_account WHERE user_id=10001;").Trim()
    Write-Host "Before balance: $beforeBalance cents"

    Set-E2eConsumerBalance -BalanceCents 600 | Out-Null
    & (Join-Path $RepoRoot "scripts\set-simulator-cart.ps1") -Items @("SKU-DEMO-001:2") -ShoppingSeconds 8

    $result = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
        -RepoRoot $RepoRoot -KeepSimulator
    $sessionId = $result.SessionId
    Write-Host "Session=$sessionId finalState=$($result.FinalState)"

    if ($result.FinalState -ne "DISPUTED") {
        throw "Expected DISPUTED for balance insufficient, got $($result.FinalState)"
    }

    $db = docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -c @"
SELECT ss.session_id, ss.state, ss.order_id,
       (SELECT COUNT(*) FROM cabinet_order o WHERE o.session_id = ss.session_id) AS order_count,
       (SELECT exception_type FROM ops_exception e WHERE e.session_id = ss.session_id ORDER BY created_at DESC LIMIT 1) AS exception_type,
       ua.balance_cents
FROM shopping_session ss, user_account ua
WHERE ss.session_id = '$sessionId' AND ua.user_id = 10001;
"@ 2>&1
    Write-Host $db

    $afterBalance = (docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -t -A -c `
        "SELECT balance_cents FROM user_account WHERE user_id=10001;").Trim()
    if ($afterBalance -ne "600") {
        throw "Balance changed unexpectedly: before=600 after=$afterBalance"
    }
    Write-Host "PASS TC-5.9-01: session=$sessionId balance unchanged at 600 cents"
}

if (-not $SkipOutageTest) {
    Write-Host "`n--- TC-6.1-01 Trade-service outage during shopping ---"
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    Set-E2eConsumerBalance -BalanceCents 11300 | Out-Null
    & (Join-Path $RepoRoot "scripts\set-simulator-cart.ps1") -Items @("SKU-DEMO-001:1") -ShoppingSeconds 30

    $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $auth
    if (-not $dev.available) {
        Write-Host "Device busy after cart setup (online=$($dev.online) available=$($dev.available)); restarting simulator"
        Restart-E2eDeviceSimulator
    }

    $idempotencyKey = "e2e-outage-$([guid]::NewGuid().ToString('N'))"
    $session = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions" -Headers $auth -Body @{
        deviceId       = $DeviceId
        idempotencyKey = $idempotencyKey
    }
    $sessionId = $session.sessionId
    Write-Host "Created session=$sessionId state=$($session.state)"

    $shoppingBeforeOutage = $false
    for ($i = 1; $i -le 30; $i++) {
        Start-Sleep -Seconds 2
        $current = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$sessionId" -Headers $auth
        if ($current.state -eq "SHOPPING") {
            $shoppingBeforeOutage = $true
            Write-Host "Session reached SHOPPING before outage (poll=$i)"
            break
        }
        if ($current.state -in @("COMPLETED", "DISPUTED", "FAILED", "CANCELLED")) {
            throw "Session finished early as $($current.state) before outage simulation"
        }
    }
    if (-not $shoppingBeforeOutage) {
        $stuck = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$sessionId" -Headers $auth
        throw "Session never reached SHOPPING before outage simulation, state=$($stuck.state)"
    }

    Invoke-TradeServiceOutage -DurationSec 15 | Out-Null
    Start-Sleep -Seconds 5

    $loginAfter = $null
    for ($i = 1; $i -le 8; $i++) {
        try {
            $loginAfter = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
                phoneNumber = $ConsumerPhone
                password    = $ConsumerPassword
            }
            if ($loginAfter.token) { break }
        } catch {
            if ($i -eq 8) { throw }
            Start-Sleep -Seconds 2
        }
    }
    $auth = @{ Authorization = "Bearer $($loginAfter.token)" }
    Write-Host "Re-authenticated after outage (boot epoch refreshed)"
    Restart-E2eDeviceSimulator
    $activeAfter = $null
    try {
        $activeAfter = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/active" -Headers $auth
    } catch { }
    $outageDone = $false
    if ($null -ne $activeAfter -and $activeAfter.sessionId -eq $sessionId) {
        Write-Host "Active session restored: $($activeAfter.sessionId) state=$($activeAfter.state)"
        if ($activeAfter.state -eq "SHOPPING") {
            Write-Host "Waiting for simulator auto-close (up to 36s)..."
            $waited = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $auth -MaxPolls 18 -IntervalSec 2
            if ($waited -in @("COMPLETED", "DISPUTED", "FAILED", "CANCELLED")) {
                Write-Host "PASS TC-6.1-01: session=$sessionId recovered and finished as $waited"
                $outageDone = $true
            } else {
                Write-Host "Still shopping; sending internal door-close"
                try { Invoke-E2eInternalDoorClose -BaseUrl $BaseUrl -SessionId $sessionId } catch { Write-Warning $_.Exception.Message }
            }
        }
    } else {
        $direct = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$sessionId" -Headers $auth
        Write-Host "No active session handle; direct query state=$($direct.state)"
        if ($direct.state -in @("COMPLETED", "DISPUTED")) {
            Write-Host "PASS TC-6.1-01: session=$sessionId already finished as $($direct.state)"
            $outageDone = $true
        } elseif ($direct.state -eq "SHOPPING") {
            try { Invoke-E2eInternalDoorClose -BaseUrl $BaseUrl -SessionId $sessionId } catch { Write-Warning $_.Exception.Message }
        }
    }

    if (-not $outageDone) {
        $final = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $auth -MaxPolls 60
        if ($final -notin @("COMPLETED", "DISPUTED")) {
            throw "Session did not reach terminal state after outage, state=$final"
        }
        Write-Host "PASS TC-6.1-01: session=$sessionId recovered and finished as $final"
    }
}

Write-Host "`n--- TC-5.7 Idempotent session replay ---"
Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
$idemKey = "e2e-idem-$([guid]::NewGuid().ToString('N'))"
$s1 = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions" -Headers $auth -Body @{
    deviceId = $DeviceId; idempotencyKey = $idemKey
}
Start-Sleep -Seconds 1
try {
    $s2 = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions" -Headers $auth -Body @{
        deviceId = $DeviceId; idempotencyKey = $idemKey
    }
    if ($s2.sessionId -ne $s1.sessionId) {
        throw "Idempotent replay created different session: $($s1.sessionId) vs $($s2.sessionId)"
    }
    Write-Host "PASS TC-5.7: idempotent replay returned same session $($s1.sessionId)"
} finally {
    if ($s1.state -in @("CREATED", "OPENING", "SHOPPING")) {
        try {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions/$($s1.sessionId)/cancel" -Headers $auth -Body @{}
        } catch { }
    }
}

Write-Host "`n========== Fund Safety E2E PASSED =========="
