# Step 5: Production readiness — env checklist, gateway security, optional staging smoke
# Usage:
#   .\scripts\verify-step5.ps1 -CheckEnv              # 检查 infra/.env 是否满足 prod 必填项
#   .\scripts\verify-step5.ps1 -CheckEnv -Prod        # 检查 infra/.env.production
#   .\scripts\verify-step5.ps1 -CheckEnv -EnvFile infra\.env.production
#   .\scripts\verify-step5.ps1 -CheckEnv -EnvFile infra\.env.staging
#   .\scripts\verify-step5.ps1 -Staging               # 预发 compose + SMS mock 实链路
#   .\scripts\verify-step5.ps1 -Staging -SkipE2e

param(
    [switch]$CheckEnv,
    [switch]$Staging,
    [switch]$Prod,
    [switch]$SkipE2e,
    [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Infra = Join-Path $Root "infra"
Set-Location $Root

$DevDefaults = @{
    JWT_SECRET = "ai-cabinet-dev-secret-key-32bytes!!"
    INTERNAL_API_KEY = "dev-internal-key-change-me"
    VISION_API_KEY = "dev-vision-key-change-me"
}

function Test-DockerRunning {
    docker info *> $null
    return $LASTEXITCODE -eq 0
}

function Read-DotEnv([string]$Path) {
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $idx = $line.IndexOf("=")
        if ($idx -lt 1) { return }
        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1).Trim()
        if ($val.StartsWith('"') -and $val.EndsWith('"')) { $val = $val.Substring(1, $val.Length - 2) }
        $map[$key] = $val
    }
    return $map
}

function Test-StrongSecret([string]$Name, [string]$Value, [string[]]$Forbidden) {
    $issues = @()
    if (-not $Value -or $Value.Trim().Length -eq 0) {
        $issues += "$Name is empty"
    } elseif ($Value.Length -lt 32) {
        $issues += "$Name must be >= 32 characters"
    } elseif ($Forbidden -contains $Value) {
        $issues += "$Name still uses dev default"
    }
    return ,$issues
}

function Invoke-EnvCheck([hashtable]$Env, [string]$Mode) {
    $errors = @()
    $warnings = @()

    foreach ($key in @("JWT_SECRET", "INTERNAL_API_KEY", "VISION_API_KEY")) {
        $errors += Test-StrongSecret $key $Env[$key] @($DevDefaults[$key])
    }

    if (-not $Env["SMS_WEBHOOK_URL"]) {
        if ($Mode -eq "prod") { $errors += "SMS_WEBHOOK_URL is required for prod" }
        else { $warnings += "SMS_WEBHOOK_URL empty (staging compose provides sms-webhook-mock)" }
    }

    if ($Env["AICABINET_MOCK_ENABLED"] -eq "true") {
        $errors += "AICABINET_MOCK_ENABLED must be false for prod/staging"
    }

    if ($Mode -eq "staging") {
        if ($Env["VISION_MOCK_ENABLED"] -eq "true") {
            $errors += "VISION_MOCK_ENABLED must be false for staging (use real YOLO + DB mappings)"
        }
        if (-not $Env["SMS_WEBHOOK_URL"]) {
            $errors += "SMS_WEBHOOK_URL is required for staging (compose provides sms-webhook-mock)"
        }
        if ($Env["VISION_INSTALL_ML"] -eq "false") {
            $warnings += "VISION_INSTALL_ML=false — staging vision may fail without YOLO in image"
        }
    }

    if ($Mode -eq "prod") {
        if ($Env["SPRING_PROFILES_ACTIVE"] -ne "prod") {
            $warnings += "SPRING_PROFILES_ACTIVE should be prod (current: $($Env['SPRING_PROFILES_ACTIVE']))"
        }
        foreach ($key in @("WECHAT_APP_ID", "WECHAT_MCH_ID", "WECHAT_API_V3_KEY", "WECHAT_MCH_SERIAL",
                           "WECHAT_PRIVATE_KEY", "WECHAT_MINIAPP_ID", "WECHAT_MINIAPP_SECRET", "WECHAT_NOTIFY_URL")) {
            if (-not $Env[$key]) { $errors += "$key is required for prod" }
        }
        if ($Env["MQTT_BROKER"] -match "^tcp://") {
            $errors += "MQTT_BROKER must use ssl:// in prod"
        }
        if ($Env["VISION_MOCK_ENABLED"] -eq "true") {
            $warnings += "VISION_MOCK_ENABLED=true — production should use real recognizer"
        }
        if ($Env["CORS_ORIGIN"] -match "localhost") {
            $warnings += "CORS_ORIGIN still localhost"
        }
        if ($Env["POSTGRES_PASSWORD"] -in @("aicabinet", "", $null)) {
            $errors += "POSTGRES_PASSWORD must be a strong password"
        }
    }

    return @{ Errors = $errors; Warnings = $warnings }
}

function Write-EnvReport([hashtable]$Result) {
    foreach ($w in $Result.Warnings) {
        Write-Host "  WARN: $w" -ForegroundColor Yellow
    }
    foreach ($e in $Result.Errors) {
        Write-Host "  FAIL: $e" -ForegroundColor Red
    }
    if ($Result.Errors.Count -eq 0) {
        Write-Host "  Env check passed ($($Result.Warnings.Count) warning(s))" -ForegroundColor Green
        return $true
    }
    return $false
}

Write-Host "==> Step 5: Production readiness"
Write-Host ""

if (-not $CheckEnv -and -not $Staging) {
    $CheckEnv = $true
}

$envPath = if ($EnvFile) {
    if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
} elseif ($Prod) {
    Join-Path $Infra ".env.production"
} elseif ($Staging) {
    Join-Path $Infra ".env.staging"
} else {
    Join-Path $Infra ".env"
}

if ($CheckEnv) {
    Write-Host "==> Checking env: $envPath"
    if (-not (Test-Path $envPath)) {
        $example = if ($Prod) { ".env.production.example" }
                     elseif ($Staging) { ".env.staging.example" }
                     else { ".env.example" }
        Write-Host "  Missing env file. Copy infra\$example to $(Split-Path -Leaf $envPath) first." -ForegroundColor Yellow
        exit 1
    }
    $envMap = Read-DotEnv $envPath
    $mode = if ($Prod) { "prod" } elseif ($Staging -or $envMap["SPRING_PROFILES_ACTIVE"] -eq "staging") { "staging" } else { "dev" }
    $report = Invoke-EnvCheck $envMap $mode
    if (-not (Write-EnvReport $report)) { exit 1 }
}

if (-not $Staging) {
    Write-Host ""
    Write-Host "Gateway /internal block probe (requires Step 1 stack on :80)..."
    try {
        $r = Invoke-WebRequest -Uri "http://localhost/internal/health" -UseBasicParsing -TimeoutSec 5 -SkipHttpErrorCheck
        if ($r.StatusCode -eq 403) {
            Write-Host "  /internal/ blocked (403) — OK" -ForegroundColor Green
        } else {
            Write-Host "  WARN: /internal/ returned $($r.StatusCode), expected 403" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "  Skip gateway probe (stack not running?)" -ForegroundColor Yellow
    }
    Write-Host ""
    if ($Prod) {
        Write-Host "Step 5 production env check complete."
    } else {
        Write-Host "Step 5 env check complete. For production: copy infra\.env.production.example to .env.production, then .\scripts\verify-step5.ps1 -CheckEnv -Prod"
        Write-Host "For staging smoke: .\scripts\verify-step5.ps1 -Staging"
    }
    exit 0
}

if (-not (Test-DockerRunning)) {
    Write-Host "Docker is not running. Start Docker Desktop first." -ForegroundColor Yellow
    exit 1
}

$stagingEnv = Join-Path $Infra ".env.staging"
if (-not (Test-Path $stagingEnv)) {
    & (Join-Path $Root "scripts\init-staging-env.ps1")
}
$envMap = Read-DotEnv $stagingEnv

Set-Location $Infra
$compose = @(
    "compose", "--env-file", ".env.staging",
    "-f", "docker-compose.yml", "-f", "docker-compose.apps.yml", "-f", "docker-compose.staging.yml",
    "--profile", "apps"
)

Write-Host ""
Write-Host "==> Starting staging stack..."
docker @compose up -d --build
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> Waiting for trade-service healthy (max 4 min)..."
$deadline = (Get-Date).AddMinutes(4)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    $lines = docker @compose ps --format "{{.Service}}`t{{.Health}}" 2>$null
    foreach ($line in $lines) {
        if ($line -match "^trade-service`thealthy$") {
            $healthy = $true
            break
        }
    }
    if ($healthy) { break }
    Start-Sleep -Seconds 5
}
if (-not $healthy) {
    Write-Host "trade-service not healthy. Logs:" -ForegroundColor Yellow
    Write-Host "  docker compose --env-file .env.staging -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.staging.yml logs trade-service"
    exit 1
}
Write-Host "    trade-service healthy (staging profile)"

Write-Host ""
Write-Host "==> SMS webhook smoke..."
try {
    Invoke-RestMethod -Uri "http://localhost:8099/health" -TimeoutSec 10 | Out-Null
    $body = @{ phoneNumber = "13900000099"; code = "482913" } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "http://localhost:8099/send" -Body $body -ContentType "application/json" -TimeoutSec 10 | Out-Null
    $last = Invoke-RestMethod -Uri "http://localhost:8099/last" -TimeoutSec 10
    if ($last.code -eq "482913") {
        Write-Host "    sms-webhook-mock OK" -ForegroundColor Green
    } else {
        Write-Host "    WARN: unexpected last SMS payload" -ForegroundColor Yellow
    }
} catch {
    Write-Host "    SMS mock probe failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "==> Trigger trade SMS dispatch..."
try {
    Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v2/auth/sms-code?phoneNumber=13800138000" -TimeoutSec 15 | Out-Null
    Start-Sleep -Seconds 2
    $last = Invoke-RestMethod -Uri "http://localhost:8099/last" -TimeoutSec 10
    if ($last.phoneNumber -eq "13800138000" -and $last.code -match "^\d{6}$") {
        Write-Host "    trade -> SMS webhook OK (code received)" -ForegroundColor Green
    } else {
        Write-Host "    WARN: trade SMS not reflected in mock" -ForegroundColor Yellow
    }
} catch {
    Write-Host "    trade SMS call failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "==> Staging admin SMS login..."
try {
    . (Join-Path $Root "scripts\e2e-sms-auth.ps1")
    $internalKey = $envMap["INTERNAL_API_KEY"]
    if (-not $internalKey) { $internalKey = "staging-internal-api-key-32bytes-min" }
    $op = Invoke-E2eSmsLogin -BaseUrl "http://localhost:8080" -Phone "13900000001" `
        -SmsMockUrl "http://localhost:8099" -InternalApiKey $internalKey -LoginPath "/api/v2/auth/admin-login"
    $stats = Invoke-RestMethod -Uri "http://localhost:8080/api/v2/ops/admin/stats" `
        -Headers @{ Authorization = "Bearer $($op.token)" } -TimeoutSec 15
    if ($stats.code -eq 0) {
        Write-Host "    admin login + dashboard stats OK" -ForegroundColor Green
    } else {
        Write-Host "    WARN: admin stats failed: $($stats.message)" -ForegroundColor Yellow
    }
} catch {
    Write-Host "    admin SMS login failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "==> Vision service probe (staging: mock should be off)..."
try {
    $vh = Invoke-RestMethod -Uri "http://localhost:8082/health" -TimeoutSec 10
    if ($vh.mock_enabled) {
        Write-Host "    FAIL: vision-service mock_enabled=true" -ForegroundColor Red
        exit 1
    }
    Write-Host "    vision mock_enabled=false recognizer=$($vh.recognizer_available)" -ForegroundColor Green
} catch {
    Write-Host "    WARN: vision health probe failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

if (-not $SkipE2e) {
    Set-Location $Root
    Write-Host ""
    Write-Host "==> Staging shopping E2E (SMS + MQTT)..."
    $internalKey = $envMap["INTERNAL_API_KEY"]
    if (-not $internalKey) { $internalKey = "staging-internal-api-key-32bytes-min" }
    & (Join-Path $Root "scripts\e2e-staging.ps1") -InternalApiKey $internalKey
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host ""
    Write-Host "==> Staging Phase A inventory E2E..."
    & (Join-Path $Root "scripts\e2e-inventory-phase-a.ps1") -InternalApiKey $internalKey
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host ""
    Write-Host "==> Staging Phase B warehouse E2E..."
    & (Join-Path $Root "scripts\e2e-warehouse-phase-b.ps1") -InternalApiKey $internalKey
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Write-Host ""
    Write-Host "==> Staging restock snapshot E2E..."
    & (Join-Path $Root "scripts\e2e-restock-snapshot.ps1") -InternalApiKey $internalKey
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host ""
Write-Host "Step 5 staging smoke complete."
Write-Host "  Admin: http://localhost/admin/index.html"
Write-Host "  SMS mock last: http://localhost:8099/last"
Write-Host "  Go-live: set SPRING_PROFILES_ACTIVE=prod + WECHAT_* in infra/.env — see docs/PRODUCTION.md"
