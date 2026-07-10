# Environment checklist for staging / production deploys
# Usage:
#   .\scripts\check-env.ps1 -CheckEnv -EnvFile infra\.env.staging.example
#   .\scripts\check-env.ps1 -CheckEnv -Prod
#   .\scripts\check-env.ps1 -CheckEnv -EnvFile infra\.env.staging

param(
    [switch]$CheckEnv,
    [switch]$Prod,
    [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Infra = Join-Path $Root "infra"

$DevDefaults = @{
    JWT_SECRET       = "ai-cabinet-dev-secret-key-32bytes!!"
    INTERNAL_API_KEY = "dev-internal-key-change-me"
    VISION_API_KEY   = "dev-vision-key-change-me"
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

if (-not $CheckEnv) { $CheckEnv = $true }

$envPath = if ($EnvFile) {
    if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
} elseif ($Prod) {
    Join-Path $Infra ".env.production"
} else {
    Join-Path $Infra ".env"
}

Write-Host "==> Checking env: $envPath"
if (-not (Test-Path $envPath)) {
    Write-Host "  Missing env file. Copy the matching infra/*.example first." -ForegroundColor Yellow
    exit 1
}

$envMap = Read-DotEnv $envPath
$mode = if ($Prod) { "prod" } elseif ($envMap["SPRING_PROFILES_ACTIVE"] -eq "staging") { "staging" } else { "dev" }
$report = Invoke-EnvCheck $envMap $mode

foreach ($w in $report.Warnings) {
    Write-Host "  WARN: $w" -ForegroundColor Yellow
}
foreach ($e in $report.Errors) {
    Write-Host "  FAIL: $e" -ForegroundColor Red
}

if ($report.Errors.Count -eq 0) {
    Write-Host "  Env check passed ($($report.Warnings.Count) warning(s))" -ForegroundColor Green
    exit 0
}
exit 1
