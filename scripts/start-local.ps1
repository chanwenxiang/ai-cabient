# Start Java/Python services locally for Step 2 (infra must already be running).
# Usage:
#   .\scripts\start-local.ps1           # start all in background windows
#   .\scripts\start-local.ps1 -TradeOnly
#   .\scripts\start-local.ps1 -Force      # start even if port already in use

param(
    [switch]$TradeOnly,
    [switch]$NoSimulator,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

function Test-PortListening {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Start-LocalService {
    param([string]$Title, [string]$Command, [int]$Port = 0)
    if ($Port -gt 0 -and (Test-PortListening $Port) -and -not $Force) {
        Write-Host "==> Skip $Title (port $Port already in use)" -ForegroundColor Yellow
        return
    }
    Write-Host "==> Starting $Title..."
    Start-Process powershell -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-NoExit", "-Command", "cd '$Root'; $Command"
    ) | Out-Null
}

Start-LocalService "trade-service :8080" `
    "mvn.cmd --% -f services/trade-service/pom.xml spring-boot:run -DskipTests -Dskip.admin.build=true" `
    8080

Start-Sleep -Seconds 3

if (-not $TradeOnly) {
    Start-LocalService "device-service :8081" `
        "mvn.cmd --% -f services/device-service/pom.xml spring-boot:run -DskipTests" `
        8081

    Start-LocalService "vision-service :8082" `
        "cd vision-service; if (Test-Path .venv\Scripts\python.exe) { .\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8082 } else { python -m uvicorn app.main:app --reload --port 8082 }" `
        8082

    if (-not $NoSimulator) {
        . (Join-Path $PSScriptRoot "e2e-lib.ps1")
        $tradeBase = "http://127.0.0.1:8080"
        $simDevice = ""
        Write-Host "==> Waiting for trade-service to resolve a demo device (no hard-coded cabinet id)..."
        for ($i = 0; $i -lt 45; $i++) {
            if (Test-ServiceHealth -Url "$tradeBase/actuator/health" -TimeoutSec 2) {
                try {
                    $resp = Invoke-RestMethod -Method POST -Uri "$tradeBase/internal/v1/demo/ensure" `
                        -Headers @{ "X-Internal-Api-Key" = "dev-internal-key-change-me" } -TimeoutSec 10
                    if ($resp.code -eq 0 -and $resp.data.deviceId) {
                        $simDevice = [string]$resp.data.deviceId
                        break
                    }
                } catch { }
                try {
                    $simDevice = Resolve-E2eTestDevice
                    break
                } catch { }
            }
            Start-Sleep -Seconds 2
        }
        if ([string]::IsNullOrWhiteSpace($simDevice)) {
            Write-Host "==> Skip DeviceSimulator (no device resolved; set DEVICE_ID later)" -ForegroundColor Yellow
        } else {
            Write-Host "==> DeviceSimulator will use deviceId=$simDevice"
            Start-LocalService "DeviceSimulator $simDevice" `
                "mvn.cmd --% -f edge/device-simulator/pom.xml exec:java -Dexec.mainClass=com.aicabinet.simulator.DeviceSimulator -Dexec.args=$simDevice"
        }
    }
}

Write-Host ""
Write-Host "Services starting in separate PowerShell windows."
Write-Host "Tip: only one trade-service on :8080 (IDEA Run OR this script, not both)."
Write-Host "First time? Run once: mvn.cmd --% install -pl services/common/common-core,services/trade-service,services/device-service,edge/device-simulator -am -DskipTests -Dskip.admin.build=true"
Write-Host "Wait ~30-60s then run: .\scripts\verify-local.ps1"
Write-Host "Admin: http://localhost:8080/admin/index.html  (13900000001 / 密码或验证码 123456)"
