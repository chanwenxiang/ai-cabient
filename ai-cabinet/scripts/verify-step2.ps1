# Step 2: IDEA local dev - infra only + local Java/Python services + E2E
# Usage:
#   .\scripts\verify-step2.ps1                    # check infra + already-running local services
#   .\scripts\verify-step2.ps1 -StartLocal        # also start trade/device/vision/simulator
#   .\scripts\verify-step2.ps1 -SkipInfra         # skip stop-apps/start-infra (services already up)

param(
    [switch]$StartLocal,
    [switch]$SkipE2e,
    [switch]$SkipInfra
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

function Test-HttpOk {
    param([string]$Url, [int]$TimeoutSec = 5)
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return ($r.StatusCode -ge 200 -and $r.StatusCode -lt 400)
    } catch {
        return $false
    }
}

function Wait-Service {
    param([string]$Name, [string]$Url, [int]$MaxSec = 180)
    Write-Host "    waiting $Name ..."
    $deadline = (Get-Date).AddSeconds($MaxSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-HttpOk $Url) {
            Write-Host "    $Name OK"
            return $true
        }
        Start-Sleep -Seconds 4
    }
    return $false
}

Write-Host "==> Step 2: IDEA local dev verification"
Write-Host ""

if (-not $SkipInfra) {
    & (Join-Path $Root "scripts\stop-apps.ps1")
    & (Join-Path $Root "scripts\start-infra.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
    Write-Host "==> Skipping infra restart (-SkipInfra)"
}

$localJobs = @()

if ($StartLocal) {
    Write-Host ""
    Write-Host "==> Starting local services (Maven / Python)..."

    $tradeCmd = "Set-Location '$Root'; mvn -f services/trade-service/pom.xml spring-boot:run -DskipTests `"-Dskip.admin.build=true`""
    $deviceCmd = "Set-Location '$Root'; mvn -f services/device-service/pom.xml spring-boot:run -DskipTests"
    $visionCmd = "Set-Location '$Root\vision-service'; if (Test-Path .venv\Scripts\python.exe) { .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8082 } else { python -m uvicorn app.main:app --port 8082 }"
    $simCmd = "Set-Location '$Root'; mvn -f edge/device-simulator/pom.xml exec:java -Dexec.mainClass=com.aicabinet.simulator.DeviceSimulator -Dexec.args=CAB-001"

    $localJobs += Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $tradeCmd
    )
    Start-Sleep -Seconds 5
    $localJobs += Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $deviceCmd
    )
    $localJobs += Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $visionCmd
    )
    Start-Sleep -Seconds 8
    $localJobs += Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $simCmd
    )

    Write-Host "    background PIDs: $($localJobs.Id -join ', ')"
}

Write-Host ""
Write-Host "==> Checking local services..."

$tradeOk = Wait-Service "trade-service" "http://localhost:8080/actuator/health" 240
$deviceOk = Wait-Service "device-service" "http://localhost:8081/actuator/health" 120
$visionOk = Wait-Service "vision-service" "http://localhost:8082/health" 90

if (-not ($tradeOk -and $deviceOk -and $visionOk)) {
    Write-Host ""
    Write-Host "Local services not all up. Start manually in IDEA:" -ForegroundColor Yellow
    Write-Host "  TradeServiceApplication.java      -> :8080"
    Write-Host "  DeviceServiceApplication.java     -> :8081"
    Write-Host "  uvicorn app.main:app --port 8082    -> vision-service/"
    Write-Host "  DeviceSimulator.java  args CAB-001"
    Write-Host ""
    Write-Host "Then re-run: .\scripts\verify-step2.ps1"
    if ($StartLocal) {
        foreach ($p in $localJobs) { try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch {} }
    }
    exit 1
}

Write-Host ""
Write-Host "==> Admin UI probe..."
if (Test-HttpOk "http://localhost:8080/admin/index.html") {
    Write-Host "    http://localhost:8080/admin/index.html OK"
} elseif (Test-HttpOk "http://localhost/admin/index.html") {
    Write-Host "    http://localhost/admin/index.html OK (gateway)"
} else {
    Write-Host "    admin page not reachable" -ForegroundColor Yellow
}

if ($SkipE2e) {
    Write-Host ""
    Write-Host "Skipped E2E (-SkipE2e)"
    exit 0
}

Write-Host ""
Write-Host "==> E2E shopping (local trade :8080)..."
& (Join-Path $Root "scripts\e2e-shopping.ps1") -BaseUrl "http://localhost:8080"
$e2eOk = ($LASTEXITCODE -eq 0)

Write-Host ""
Write-Host "==> Ops API: device list..."
try {
    $login = Invoke-RestMethod -Method POST -Uri "http://localhost:8080/api/v2/auth/login" `
        -ContentType "application/json" `
        -Body '{"phoneNumber":"13900000001","code":"123456"}'
    if ($login.code -ne 0) { throw $login.message }
    $token = $login.data.token
    $devices = Invoke-RestMethod -Method GET -Uri "http://localhost:8080/api/v2/ops/admin/devices" `
        -Headers @{ Authorization = "Bearer $token" }
    if ($devices.code -ne 0) { throw $devices.message }
    $online = ($devices.data | Where-Object { $_.status -eq "ONLINE" }).Count
    Write-Host "    devices total=$($devices.data.Count) online=$online"
    if ($online -eq 0) {
        Write-Host "    (no ONLINE device - run DeviceSimulator CAB-001 in IDEA for MQTT heartbeat)" -ForegroundColor Yellow
    }
} catch {
    Write-Host "    ops API check failed: $_" -ForegroundColor Yellow
    $e2eOk = $false
}

if ($StartLocal) {
    Write-Host ""
    Write-Host "Stopping background local service processes..."
    foreach ($p in $localJobs) {
        try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch {}
    }
}

if (-not $e2eOk) { exit 1 }

Write-Host ""
Write-Host "Step 2 verification passed."
Write-Host "Optional: Run DeviceSimulator (CAB-001) in IDEA for device ONLINE in admin."
Write-Host "Miniapp: import clients/miniapp, BASE_URL=http://localhost:8080"
Write-Host "See docs/LOCAL_SETUP.md section 6"
exit 0
