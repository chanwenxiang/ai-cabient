# Shared E2E door flows: MQTT (default) vs legacy internal door-event

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
    $resp = Invoke-RestMethod @params
    if ($resp.code -ne 0) {
        throw "API error: $($resp.message) (path=$Path)"
    }
    return $resp.data
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
    $sampleScript = Join-Path $RepoRoot "scripts\ensure-sample-video.ps1"
    if (Test-Path $sampleScript) {
        try {
            $sampleMp4 = & $sampleScript
            if ($sampleMp4 -and (Test-Path $sampleMp4) -and (Get-Item $sampleMp4).Length -gt 20000) {
                $env:AICABINET_SIM_VIDEO_FILE = $sampleMp4
            }
        } catch {
            Write-Host "    WARN: sample video not ready ($($_.Exception.Message))"
        }
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
    if ($SkipSimulatorStart) {
        if (-not (Test-E2eDeviceOnline -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $Auth)) {
            throw "device $DeviceId offline; start DeviceSimulator or omit -SkipSimulatorStart"
        }
        return @{ Started = $false; Process = $null }
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
    $order = $null
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
        & (Join-Path $RepoRoot "scripts\e2e-cleanup-device.ps1") -DeviceId $DeviceId | Out-Null
        Start-Sleep -Seconds 1
    }

    Write-Host "==> Create session (MQTT unlock)"
    $session = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions" -Headers $Auth -Body @{
        deviceId = $DeviceId
    }
    $sessionId = $session.sessionId
    Write-Host "    sessionId=$sessionId state=$($session.state)"

    $final = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $Auth
    if ($final -notin @("COMPLETED", "DISPUTED")) {
        throw "session did not finish via MQTT path, state=$final"
    }
    Write-Host "    final state=$final (DeviceSimulator handled door + video)"

    return @{
        SessionId = $sessionId
        FinalState = $final
        SimulatorStarted = [bool]$sim.Started
        SimulatorProcess = $sim.Process
    }
}

function Invoke-E2eInternalDoorShopping {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [string]$SessionId,
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [string]$ScriptsDir
    )
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }

    Write-Host "==> Simulate door open (internal API)"
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
        sessionId = $SessionId
        deviceId  = $DeviceId
        doorState = "OPEN"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    } | Out-Null

    Write-Host "==> Upload sample video"
    & (Join-Path $ScriptsDir "upload-e2e-video.ps1") -SessionId $SessionId

    Write-Host "==> Simulate door close + video"
    $videoUri = "minio://cabinet-videos/sim/$SessionId.mp4"
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
        sessionId    = $SessionId
        deviceId     = $DeviceId
        doorState    = "CLOSED"
        timestamp    = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        videoUri     = $videoUri
        uploadStatus = "UPLOADED"
    } | Out-Null
}
