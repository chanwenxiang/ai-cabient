# Shared E2E helpers: API client, device cleanup, MQTT shopping flow

function Get-E2eSimVideoKey {
    param(
        [string]$DeviceId = "CAB-001",
        [long]$UserId = 0,
        [string]$SessionId,
        [string]$Camera = "top"
    )
    $tz = [TimeZoneInfo]::FindSystemTimeZoneById("China Standard Time")
    $now = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $tz)
    $date = $now.ToString("yyyy/MM/dd")
    return "sim/$date/$DeviceId/user-$UserId/$SessionId-$Camera.mp4"
}

function Invoke-E2eApi {
    param(
        [string]$BaseUrl,
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        $Body = $null
    )
    $uri = "$BaseUrl$Path"
    $params = @{
        Method      = $Method
        Uri         = $uri
        ContentType = "application/json"
    }
    if ($Headers.Count -gt 0) { $params.Headers = $Headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    try {
        $resp = Invoke-RestMethod @params
    } catch {
        $statusCode = $null
        $responseBody = $null
        $response = $_.Exception.Response
        if ($null -ne $response) {
            try { $statusCode = [int]$response.StatusCode } catch { }
            try {
                $stream = $response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    try { $responseBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
                }
            } catch { }
        }
        if ([string]::IsNullOrWhiteSpace($responseBody)) {
            $responseBody = $_.Exception.Message
        }
        throw "HTTP request failed: $Method $Path status=$statusCode response=$responseBody"
    }
    if ($resp.code -ne 0) {
        throw "API error: $($resp.message) (path=$Path)"
    }
    return $resp.data
}

function Clear-E2eDeviceBlockingSessions {
    param(
        [string]$DeviceId = "CAB-001",
        [string]$PostgresContainer = ""
    )
    if ([string]::IsNullOrWhiteSpace($PostgresContainer)) {
        $PostgresContainer = docker ps `
            --filter "label=com.docker.compose.service=postgres" `
            --format "{{.Names}}" 2>$null | Select-Object -First 1
    }
    if ([string]::IsNullOrWhiteSpace($PostgresContainer)) {
        foreach ($candidate in @("ai-cabinet-postgres-1", "infra-postgres-1")) {
            $running = docker ps --filter "name=^/$candidate$" --format "{{.Names}}" 2>$null
            if ($running -eq $candidate) {
                $PostgresContainer = $candidate
                break
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($PostgresContainer)) {
        Write-Warning "Clear-E2eDeviceBlockingSessions: no running postgres container found"
        return $false
    }

    $states = @("CREATED", "OPENING", "SHOPPING", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING")
    $inList = ($states | ForEach-Object { "'$_'" }) -join ","
    $sql = @"
UPDATE shopping_session
SET state = 'CANCELLED', updated_at = NOW()
WHERE device_id = '$DeviceId'
  AND state IN ($inList);

UPDATE replenishment_task
SET status = 'CANCELLED'
WHERE device_id = '$DeviceId'
  AND status = 'IN_PROGRESS';

DELETE FROM user_blacklist
WHERE user_id IN (SELECT user_id FROM user_info WHERE phone_number = '13800138000');

UPDATE shopping_session
SET created_at = created_at - INTERVAL '2 hours'
WHERE user_id IN (SELECT user_id FROM user_info WHERE phone_number = '13800138000')
  AND created_at > NOW() - INTERVAL '1 hour';
"@
    $out = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Clear-E2eDeviceBlockingSessions: postgres cleanup failed: $out"
        return $false
    }
    Write-Host "==> Cleared blocking sessions on $DeviceId via $PostgresContainer"
    return $true
}

function Test-E2eDeviceOnline {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [hashtable]$Auth
    )
    try {
        $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $Auth
        return [bool]$dev.online
    } catch {
        return $false
    }
}

function Start-E2eDeviceSimulator {
    param(
        [string]$RepoRoot,
        [string]$BaseUrl,
        [string]$DeviceId,
        [string]$MqttBroker = "tcp://localhost:11883",
        [string]$InternalApiKey = "dev-internal-key-change-me"
    )
    $env:TRADE_SERVICE_URL = $BaseUrl
    $env:DEVICE_SERVICE_URL = "http://localhost:8081"
    $env:INTERNAL_API_KEY = $InternalApiKey
    $env:MINIO_ENDPOINT = "http://localhost:9000"
    $env:MINIO_ACCESS_KEY = "minioadmin"
    $env:MINIO_SECRET_KEY = "minioadmin"
    $env:AICABINET_SIM_GRAVITY_SKU = "SKU-DEMO-001"
    $env:AICABINET_SIM_SHOPPING_MS = "5000"
    return Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        "Set-Location '$RepoRoot'; mvn -q -f edge/device-simulator/pom.xml exec:java " +
        "'-Dexec.mainClass=com.aicabinet.simulator.DeviceSimulator' " +
        "'-Dexec.args=$DeviceId $MqttBroker'"
    )
}

function Stop-E2eDeviceSimulator {
    param($Process)
    if ($null -eq $Process) { return }
    try {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    } catch { }
}

function Ensure-E2eDeviceOnline {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [hashtable]$Auth,
        [string]$RepoRoot,
        [string]$MqttBroker = "tcp://localhost:11883",
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [switch]$SkipSimulatorStart
    )
    if (Test-E2eDeviceOnline -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $Auth) {
        Write-Host "==> DeviceSimulator already online ($DeviceId), reusing it"
        return @{ Started = $false; Process = $null }
    }

    if ($SkipSimulatorStart) {
        throw "device $DeviceId offline; start DeviceSimulator or omit -SkipSimulatorStart"
    }
    Write-Host "==> Starting DeviceSimulator ($DeviceId)..."
    $proc = Start-E2eDeviceSimulator -RepoRoot $RepoRoot -BaseUrl $BaseUrl -DeviceId $DeviceId `
        -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Test-E2eDeviceOnline -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $Auth) {
            return @{ Started = $true; Process = $proc }
        }
    }
    Stop-E2eDeviceSimulator $proc
    throw "DeviceSimulator did not bring $DeviceId online within 45s"
}

function Wait-E2eSessionTerminal {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [hashtable]$Auth,
        [int]$MaxPolls = 40,
        [int]$IntervalSec = 2
    )
    $final = $null
    for ($i = 0; $i -lt $MaxPolls; $i++) {
        Start-Sleep -Seconds $IntervalSec
        $s = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$SessionId" -Headers $Auth
        $final = $s.state
        if ($final -in @("COMPLETED", "DISPUTED", "FAILED", "CANCELLED")) { break }
    }
    return $final
}

function Wait-E2eSessionOrder {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [hashtable]$Auth,
        [int]$MaxPolls = 25
    )
    for ($i = 0; $i -lt $MaxPolls; $i++) {
        Start-Sleep -Seconds 1
        try {
            $order = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$SessionId/order" -Headers $Auth
            if ($null -ne $order -and $order.orderId) { return $order }
        } catch { }
    }
    $state = (Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$SessionId" -Headers $Auth).state
    throw "Order not ready after ${MaxPolls}s, session state=$state"
}

function Invoke-E2eMqttShopping {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [hashtable]$Auth,
        [string]$RepoRoot,
        [string]$MqttBroker = "tcp://localhost:11883",
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [switch]$SkipSimulatorStart,
        [switch]$KeepSimulator
    )
    $sim = Ensure-E2eDeviceOnline -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $Auth `
        -RepoRoot $RepoRoot -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey `
        -SkipSimulatorStart:$SkipSimulatorStart

    $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $Auth
    Write-Host "    device online=$($dev.online) available=$($dev.available)"
    if (-not $dev.available) {
        Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
        Start-Sleep -Seconds 1
    }

    Write-Host "==> Create session (MQTT unlock)"
    $idempotencyKey = "e2e-session-$([guid]::NewGuid().ToString('N'))"
    $session = $null
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            $session = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions" -Headers $Auth -Body @{
                deviceId       = $DeviceId
                idempotencyKey = $idempotencyKey
            }
            break
        } catch {
            if ($_.Exception.Message -notmatch 'status=409' -or $attempt -eq 5) { throw }
            Write-Host "    device busy (409); clearing sessions and retry $attempt/5"
            Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
            Start-Sleep -Seconds 2
            $idempotencyKey = "e2e-session-$([guid]::NewGuid().ToString('N'))"
        }
    }
    $sessionId = $session.sessionId
    Write-Host "    sessionId=$sessionId state=$($session.state) idempotencyKey=$idempotencyKey"

    $final = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $Auth
    if ($final -notin @("COMPLETED", "DISPUTED")) {
        throw "session did not finish via MQTT path, state=$final"
    }
    Write-Host "    final state=$final (DeviceSimulator handled door + video)"

    return @{
        SessionId          = $sessionId
        FinalState         = $final
        SimulatorStarted   = [bool]$sim.Started
        SimulatorProcess   = $sim.Process
    }
}

function Test-ServiceHealth {
    param([string]$Url, [int]$TimeoutSec = 5)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400)
    } catch {
        return $false
    }
}

function Invoke-TradeServiceOutage {
    param(
        [int]$DurationSec = 20,
        [string]$TradeContainer = "ai-cabinet-trade-service-1",
        [string]$HealthUrl = "http://127.0.0.1:8080/actuator/health"
    )
    Write-Host "==> Stopping trade-service ($TradeContainer) for ${DurationSec}s..."
    docker stop $TradeContainer | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "failed to stop $TradeContainer"
    }
    Start-Sleep -Seconds $DurationSec
    Write-Host "==> Starting trade-service ($TradeContainer)..."
    docker start $TradeContainer | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "failed to start $TradeContainer"
    }
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if (Test-ServiceHealth -Url $HealthUrl -TimeoutSec 3) {
            Write-Host "==> trade-service healthy again"
            return $true
        }
        Start-Sleep -Seconds 2
    }
    throw "trade-service did not become healthy within 90s after restart"
}

function Get-E2eConsumerUserId {
    param([string]$Phone = "13800138000", [string]$PostgresContainer = "ai-cabinet-postgres-1")
    $sql = "SELECT user_id FROM user_info WHERE phone_number = '$Phone' LIMIT 1;"
    $out = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -t -A -c $sql 2>&1
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($out)) {
        throw "consumer user not found for phone=$Phone"
    }
    return [long]($out.Trim())
}

function Set-E2eConsumerBalance {
    param(
        [int]$BalanceCents,
        [string]$Phone = "13800138000",
        [string]$PostgresContainer = "ai-cabinet-postgres-1",
        [string]$Reason = "e2e-test-balance-adjust"
    )
    $userId = Get-E2eConsumerUserId -Phone $Phone -PostgresContainer $PostgresContainer
    $sql = @"
UPDATE user_account SET balance_cents = $BalanceCents, updated_at = NOW()
WHERE user_id = $userId;
"@
    docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql | Out-Null
    Write-Host "==> Set consumer $Phone (user_id=$userId) balance to $BalanceCents cents"
    return $userId
}

function Invoke-E2eInternalDoorClose {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [string]$DeviceId = "CAB-001",
        [long]$UserId = 0,
        [string]$SkuId = "SKU-DEMO-001",
        [int]$Quantity = 1,
        [string]$UploadStatus = "UPLOADED",
        [string]$VideoUri = "",
        [string]$InternalApiKey = "dev-internal-key-change-me"
    )
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    $gravity = ConvertTo-Json @(@{ skuId = $SkuId; delta = -$Quantity; slotId = "SIM-1" }) -Compress
    if ([string]::IsNullOrWhiteSpace($VideoUri)) {
        $VideoUri = "minio://cabinet-videos/$(Get-E2eSimVideoKey -SessionId $SessionId -DeviceId $DeviceId -UserId $UserId)"
    }
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
        sessionId         = $SessionId
        deviceId          = $DeviceId
        doorState         = "CLOSED"
        timestamp         = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        videoUri          = $VideoUri
        uploadStatus      = $UploadStatus
        gravityDeltasJson = if ($UploadStatus -eq "UPLOADED") { $gravity } else { $null }
    } | Out-Null
    return $VideoUri
}

function Restart-E2eDeviceSimulator {
    param([string]$Container = "ai-cabinet-device-simulator-1")
    Write-Host "==> Restarting $Container to restore MQTT after trade outage"
    docker restart $Container | Out-Null
    Write-Host "    waiting 12s for MQTT warmup after simulator restart"
    Start-Sleep -Seconds 12
}
function Get-E2eDbSnapshot {
    param(
        [string]$SessionId = "",
        [string]$Phone = "13800138000",
        [string]$PostgresContainer = "ai-cabinet-postgres-1"
    )
    $sessionFilter = ""
    if (-not [string]::IsNullOrWhiteSpace($SessionId)) {
        $sessionFilter = "WHERE session_id = '$SessionId'"
    }
    $sql = @"
SELECT 'balance' AS kind, ua.balance_cents::text AS v1, '' AS v2, '' AS v3
FROM user_account ua JOIN user_info ui ON ua.user_id = ui.user_id
WHERE ui.phone_number = '$Phone'
UNION ALL
SELECT 'session', ss.session_id, ss.state, COALESCE(ss.order_id, '')
FROM shopping_session ss $sessionFilter
ORDER BY kind, v1;
"@
    return docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql 2>&1
}
