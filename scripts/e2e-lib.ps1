# Shared E2E helpers: API client, device cleanup, MQTT shopping flow

function Get-E2eLockPath {
    return (Join-Path $env:TEMP "ai-cabinet-e2e.lock")
}

function Enter-E2eLock {
    param([int]$TimeoutSec = 600, [string]$Owner = "e2e")
    $path = Get-E2eLockPath
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            $bytes = [Text.Encoding]::UTF8.GetBytes("$Owner pid=$PID at=$(Get-Date -Format o)")
            $fs.Write($bytes, 0, $bytes.Length)
            $fs.Flush()
            return $fs
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "E2E lock busy after ${TimeoutSec}s ($path). Another smoke/script may be running."
}

function Exit-E2eLock {
    param($LockHandle)
    if ($null -eq $LockHandle) { return }
    try { $LockHandle.Close() } catch { }
    try { Remove-Item -Force (Get-E2eLockPath) -ErrorAction SilentlyContinue } catch { }
}

function Test-E2eHttpOk {
    param([string]$Url, [int]$TimeoutSec = 2)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400)
    } catch {
        return $false
    }
}

function Get-E2eBaseUrl {
    param([string]$Fallback = "http://localhost:8080")
    if (-not [string]::IsNullOrWhiteSpace($env:E2E_BASE_URL)) {
        return $env:E2E_BASE_URL.Trim().TrimEnd('/')
    }
    # Prefer live IDEA (:8080) over Docker full-stack (:18080)
    foreach ($candidate in @("http://localhost:8080", "http://localhost:18080")) {
        if (Test-E2eHttpOk -Url "$candidate/actuator/health") {
            return $candidate
        }
    }
    return $Fallback.TrimEnd('/')
}

function Get-E2eVisionUrl {
    param([string]$Fallback = "http://localhost:8082")
    if (-not [string]::IsNullOrWhiteSpace($env:E2E_VISION_URL)) {
        return $env:E2E_VISION_URL.Trim().TrimEnd('/')
    }
    foreach ($candidate in @("http://localhost:8082", "http://127.0.0.1:8082", "http://localhost:18082")) {
        if (Test-E2eHttpOk -Url "$candidate/health") {
            return $candidate
        }
    }
    return $Fallback.TrimEnd('/')
}

function Get-E2eDeviceUrl {
    param([string]$Fallback = "http://localhost:8081")
    if (-not [string]::IsNullOrWhiteSpace($env:E2E_DEVICE_URL)) {
        return $env:E2E_DEVICE_URL.Trim().TrimEnd('/')
    }
    foreach ($candidate in @("http://localhost:8081", "http://localhost:18081")) {
        if (Test-E2eHttpOk -Url "$candidate/actuator/health") {
            return $candidate
        }
    }
    return $Fallback.TrimEnd('/')
}

function Resolve-E2eBaseUrl {
    param([string]$BaseUrl)
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) { return Get-E2eBaseUrl }
    return $BaseUrl.TrimEnd('/')
}

function Get-E2eVisionApiKey {
    param([string]$Fallback = "dev-vision-key-change-me")
    if (-not [string]::IsNullOrWhiteSpace($env:VISION_API_KEY)) {
        return $env:VISION_API_KEY.Trim()
    }
    $envFile = Join-Path (Split-Path -Parent $PSScriptRoot) "infra\.env"
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^\s*VISION_API_KEY\s*=' } | Select-Object -First 1
        if ($line -match '^\s*VISION_API_KEY\s*=\s*(.+)\s*$') {
            $val = $Matches[1].Trim().Trim('"').Trim("'")
            if (-not [string]::IsNullOrWhiteSpace($val)) { return $val }
        }
    }
    return $Fallback
}

function Set-E2eVisionForceNeedReview {
    param(
        [bool]$Enabled,
        [string]$VisionUrl = "",
        [string]$VisionApiKey = ""
    )
    if ([string]::IsNullOrWhiteSpace($VisionUrl)) { $VisionUrl = Get-E2eVisionUrl }
    if ([string]::IsNullOrWhiteSpace($VisionApiKey)) { $VisionApiKey = Get-E2eVisionApiKey }
    $uri = "$VisionUrl/api/v2/vision/debug/force-need-review"
    $params = @{
        Method      = "POST"
        Uri         = $uri
        ContentType = "application/json"
        Headers     = @{ "X-Internal-Api-Key" = $VisionApiKey }
        Body        = (@{ enabled = $Enabled } | ConvertTo-Json -Compress)
    }
    return Invoke-RestMethod @params
}

function Set-E2eConsumerPayChannel {
    param(
        [string]$BaseUrl,
        [hashtable]$Auth,
        [ValidateSet("WECHAT", "ALIPAY", "BALANCE")]
        [string]$Channel,
        [string]$Phone = "13800138000",
        [string]$PostgresContainer = "ai-cabinet-postgres-1"
    )
    switch ($Channel.ToUpper()) {
        "WECHAT" {
            $sign = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/account/payscore/sign" -Headers $Auth
            Write-Host "    pay channel WECHAT (payscore signed contract=$($sign.contractId))"
        }
        "ALIPAY" {
            $sign = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/account/alipay-agreement/sign" -Headers $Auth
            Write-Host "    pay channel ALIPAY (agreement signed contract=$($sign.contractId))"
        }
        "BALANCE" {
            $sql = @"
UPDATE user_info
SET pay_preferred_channel = 'BALANCE'
WHERE phone_number = '$Phone';
"@
            docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "failed to set pay_preferred_channel=BALANCE for $Phone" }
            Write-Host "    pay channel BALANCE (preferred_channel forced via DB)"
        }
    }
}

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
    # 运营后台密码登录需要图形验证码：自动从 /captcha 领取并把 Redis 中的答案附到请求体，
    # 让既有 e2e 脚本在 captcha-enabled 环境下无需改造。
    if ($Path -eq '/api/v2/auth/admin-password-login') {
        $needCaptcha = ($null -eq $Body) -or (-not ($Body -is [hashtable])) -or
            ([string]::IsNullOrWhiteSpace([string]$Body.captchaId))
        if ($needCaptcha) {
            $capResp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/v2/auth/captcha" -ContentType "application/json"
            if ($capResp.code -ne 0 -or [string]::IsNullOrWhiteSpace($capResp.data.captchaId)) {
                throw "captcha fetch failed: $($capResp.message)"
            }
            $capId = $capResp.data.captchaId
            $code = (docker exec ai-cabinet-redis-1 redis-cli GET "aicabinet:captcha:$capId" 2>&1).Trim()
            if ([string]::IsNullOrWhiteSpace($code)) {
                throw "captcha code not found in redis for id=$capId"
            }
            if ($null -eq $Body -or -not ($Body -is [hashtable])) { $Body = @{} }
            $Body.captchaId = $capId
            $Body.captchaCode = $code
        }
    }
    # 发短信同样需要图形验证码：自动拼到 query
    if ($Path -like '/api/v2/auth/sms-code*') {
        $needSmsCaptcha = ($Path -notlike '*captchaId=*')
        if ($needSmsCaptcha) {
            $capResp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/v2/auth/captcha" -ContentType "application/json"
            if ($capResp.code -ne 0 -or [string]::IsNullOrWhiteSpace($capResp.data.captchaId)) {
                throw "captcha fetch failed: $($capResp.message)"
            }
            $capId = $capResp.data.captchaId
            $code = (docker exec ai-cabinet-redis-1 redis-cli GET "aicabinet:captcha:$capId" 2>&1).Trim()
            if ([string]::IsNullOrWhiteSpace($code)) {
                throw "captcha code not found in redis for id=$capId"
            }
            $sep = if ($Path.Contains('?')) { '&' } else { '?' }
            $Path = "$Path$sep" + "captchaId=$([uri]::EscapeDataString($capId))&captchaCode=$([uri]::EscapeDataString($code))"
        }
    }
    $uri = "$BaseUrl$Path"
    $params = @{
        Method      = $Method
        Uri         = $uri
        ContentType = "application/json"
    }
    if ($Headers.Count -gt 0) { $params.Headers = $Headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
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
SET state = 'CANCELLED',
    fail_reason = COALESCE(NULLIF(fail_reason, ''), 'e2e-cleanup'),
    updated_at = NOW()
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
    $env:DEVICE_SERVICE_URL = (Get-E2eDeviceUrl)
    $env:INTERNAL_API_KEY = $InternalApiKey
    $env:MINIO_ENDPOINT = "http://localhost:9000"
    $env:MINIO_ACCESS_KEY = "minioadmin"
    $env:MINIO_SECRET_KEY = "minioadmin"
    if ([string]::IsNullOrWhiteSpace($env:AICABINET_SIM_GRAVITY_SKU)) {
        $env:AICABINET_SIM_GRAVITY_SKU = "SKU-DEMO-001"
    }
    if ([string]::IsNullOrWhiteSpace($env:AICABINET_SIM_SHOPPING_MS)) {
        $env:AICABINET_SIM_SHOPPING_MS = "5000"
    }
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

function Wait-E2eSessionLeftStates {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [hashtable]$Auth,
        [string[]]$States = @("CREATED", "OPENING"),
        [int]$TimeoutSec = 15
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $s = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$SessionId" -Headers $Auth
        $last = $s.state
        if ($last -notin $States) { return $last }
        if ($last -in @("COMPLETED", "DISPUTED", "FAILED", "CANCELLED")) { return $last }
        Start-Sleep -Milliseconds 500
    }
    return $last
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

    # Outer retry: concurrent Clear-E2e / missed MQTT ACK can CANCEL an OPENING session.
    $sessionId = $null
    $final = $null
    $usedFallback = $false
    $userId = 0
    for ($round = 1; $round -le 3; $round++) {
        Write-Host "==> Create session (MQTT unlock) attempt $round/3"
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
        try { $userId = [long]$session.userId } catch { $userId = 0 }
        Write-Host "    sessionId=$sessionId state=$($session.state) idempotencyKey=$idempotencyKey"

        # MQTT unlock should move OPENING -> SHOPPING quickly. If the simulator missed
        # OPEN_DOOR (common after broker blips), inject internal door close to finish.
        $progress = Wait-E2eSessionLeftStates -BaseUrl $BaseUrl -SessionId $sessionId -Auth $Auth `
            -States @("CREATED", "OPENING") -TimeoutSec 20
        Write-Host "    progress state=$progress"
        $usedFallback = $false
        if ($progress -eq "CANCELLED") {
            Write-Warning "session cancelled while OPENING (likely concurrent cleanup); retry round $round/3"
            Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
            Start-Sleep -Seconds 2
            continue
        }
        if ($progress -in @("CREATED", "OPENING")) {
            Write-Warning "MQTT unlock stalled at $progress; injecting internal door close fallback"
            Invoke-E2eInternalDoorClose -BaseUrl $BaseUrl -SessionId $sessionId -DeviceId $DeviceId `
                -UserId $userId -InternalApiKey $InternalApiKey | Out-Null
            $usedFallback = $true
        }

        $final = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $Auth
        if ($final -notin @("COMPLETED", "DISPUTED")) {
            # One more fallback attempt if we somehow never left OPENING
            if ($final -in @("CREATED", "OPENING", $null) -or -not $usedFallback) {
                $cur = (Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$sessionId" -Headers $Auth).state
                if ($cur -in @("CREATED", "OPENING", "SHOPPING", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING")) {
                    Write-Warning "session still $cur after wait; retrying internal door close"
                    Invoke-E2eInternalDoorClose -BaseUrl $BaseUrl -SessionId $sessionId -DeviceId $DeviceId `
                        -UserId $userId -InternalApiKey $InternalApiKey | Out-Null
                    $usedFallback = $true
                    $final = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $Auth -MaxPolls 20
                }
            }
        }
        if ($final -in @("COMPLETED", "DISPUTED")) { break }
        if ($final -eq "CANCELLED" -and $round -lt 3) {
            Write-Warning "session ended CANCELLED; retry round $($round + 1)/3"
            Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
            Start-Sleep -Seconds 2
            continue
        }
        throw "session did not finish via MQTT path, state=$final"
    }
    if ($final -notin @("COMPLETED", "DISPUTED")) {
        throw "session did not finish via MQTT path, state=$final"
    }
    $pathLabel = if ($usedFallback) { "internal door fallback" } else { "DeviceSimulator MQTT" }
    Write-Host "    final state=$final ($pathLabel)"

    return @{
        SessionId          = $sessionId
        FinalState         = $final
        SimulatorStarted   = [bool]$sim.Started
        SimulatorProcess   = $sim.Process
        UsedDoorFallback   = $usedFallback
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
        [string]$HealthUrl = "http://127.0.0.1:18080/actuator/health"
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
UPDATE user_account SET balance_cents = $BalanceCents, frozen_cents = 0, updated_at = NOW()
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
    # Gateway blocks /internal/* — always hit trade-service directly.
    $internalBase = Resolve-E2eBaseUrl $BaseUrl
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    $gravity = ConvertTo-Json @(@{ skuId = $SkuId; delta = -$Quantity; slotId = "SIM-1" }) -Compress
    if ([string]::IsNullOrWhiteSpace($VideoUri)) {
        $VideoUri = "minio://cabinet-videos/$(Get-E2eSimVideoKey -SessionId $SessionId -DeviceId $DeviceId -UserId $UserId)"
    }
    Invoke-E2eApi -BaseUrl $internalBase -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
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

# plan 按设备全缺口生成出库；若某 SKU 有建议量但仓库无货会导致整单 400
function Prepare-E2eReplenishmentPlan {
    param(
        [string]$BaseUrl,
        [hashtable]$OpsAuth,
        [string]$DeviceId,
        [string]$WarehouseId = "WH-DEMO-001"
    )
    $suggestions = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/replenishment/suggest?deviceId=$DeviceId" -Headers $OpsAuth)
    if ($suggestions.Count -eq 0) {
        Write-Host "    Prepare-E2eReplenishmentPlan: no gaps on $DeviceId"
        return
    }
    $whInv = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/warehouse/inventory?warehouseId=$WarehouseId" -Headers $OpsAuth)
    $stockBySku = @{}
    $batchBySku = @{}
    foreach ($row in $whInv) {
        $sku = [string]$row.skuId
        if (-not $stockBySku.ContainsKey($sku)) { $stockBySku[$sku] = 0 }
        $stockBySku[$sku] += [int]$row.quantity
        if (-not $batchBySku.ContainsKey($sku) -and $row.batchNo) {
            $batchBySku[$sku] = [string]$row.batchNo
        }
    }
    $defaultBatch = @{
        "SKU-MILK-001"   = "B-WH-MILK-01"
        "SKU-SNACK-001"  = "B-WH-CHIPS-01"
        "SKU-DEMO-001"   = "B-DEMO-01"
        "SKU-SODA-001"   = "B-WH-SODA-01"
        "SKU-WATER-001"  = "B-WH-WATER-01"
        "SKU-NOODLE-001" = "B-WH-NOODLE-01"
    }
    $inboundLines = @()
    foreach ($s in $suggestions) {
        $sku = [string]$s.skuId
        $need = [int]$s.suggestQty
        if ($need -le 0) { continue }
        $have = if ($stockBySku.ContainsKey($sku)) { [int]$stockBySku[$sku] } else { 0 }
        if ($have -ge $need) { continue }
        $gap = $need - $have + 2
        $batch = $batchBySku[$sku]
        if (-not $batch) { $batch = $defaultBatch[$sku] }
        if (-not $batch) { $batch = "E2E-$sku" }
        Write-Host "    replenishment prep: sku=$sku suggest=$need warehouse=$have inbound=$gap batch=$batch"
        $inboundLines += @{
            skuId          = $sku
            batchNo        = $batch
            productionDate = "2026-08-01"
            expiryDate     = "2026-12-31"
            quantity       = $gap
        }
    }
    if ($inboundLines.Count -eq 0) {
        Write-Host "    Prepare-E2eReplenishmentPlan: warehouse stock sufficient"
        return
    }
    $ref = "E2E-PREP-$(Get-Date -Format 'yyyyMMddHHmmss')"
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/warehouse/inbound" -Headers $OpsAuth -Body @{
        warehouseId = $WarehouseId
        refNo       = $ref
        notes       = "e2e replenishment warehouse prep"
        lines       = $inboundLines
    } | Out-Null
    Write-Host "    inbound ref=$ref lines=$($inboundLines.Count)"
}

function Get-E2eAdminCaptchaCode {
    param(
        [string]$CaptchaId,
        [string]$RedisContainer = "ai-cabinet-redis-1"
    )
    if ([string]::IsNullOrWhiteSpace($CaptchaId)) {
        throw "CaptchaId required"
    }
    $raw = docker exec $RedisContainer redis-cli GET "aicabinet:captcha:$CaptchaId" 2>&1
    $code = [string]$raw
    if ($code -match '^\s*$' -or $code -match 'nil|ERR') {
        throw "Captcha not found in redis for id=$CaptchaId"
    }
    return $code.Trim().ToUpper()
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
