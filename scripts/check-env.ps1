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
        if ($Mode -eq "dev") {
            $val = $Env[$key]
            if (-not $val -or $DevDefaults[$key] -eq $val) {
                $warnings += "$key uses demo default — localhost only; use strong secrets before any public exposure"
            }
        } else {
            $errors += Test-StrongSecret $key $Env[$key] @($DevDefaults[$key])
        }
    }

    if ($Mode -eq "dev") {
        if ($Env["SPRING_PROFILES_ACTIVE"] -in @("prod", "staging")) {
            $errors += "SPRING_PROFILES_ACTIVE=$($Env['SPRING_PROFILES_ACTIVE']) with dev secrets — copy infra/.env.production.example or .env.staging.example"
        }
    }

    if (-not $Env["SMS_WEBHOOK_URL"]) {
        if ($Mode -eq "prod") { $errors += "SMS_WEBHOOK_URL is required for prod" }
        else { $warnings += "SMS_WEBHOOK_URL empty (staging compose provides sms-webhook-mock)" }
    }

    if ($Env["AICABINET_MOCK_ENABLED"] -eq "true" -and $Mode -ne "dev") {
        $errors += "AICABINET_MOCK_ENABLED must be false for prod/staging"
    }

    if ($Mode -eq "staging") {
        if ($Env["VISION_MOCK_ENABLED"] -eq "true") {
            $warnings += "VISION_MOCK_ENABLED=true — staging should use edge recognition or mock=false with need_review path"
        }
        if (-not $Env["SMS_WEBHOOK_URL"]) {
            $errors += "SMS_WEBHOOK_URL is required for staging (compose provides sms-webhook-mock)"
        }
        $balanceOnly = $Env["CHECKOUT_BALANCE_ONLY"] -eq "true"
        if ($balanceOnly) {
            if ($Env["AICABINET_MOCK_ENABLED"] -eq "true") {
                $errors += "CHECKOUT_BALANCE_ONLY=true requires AICABINET_MOCK_ENABLED=false"
            }
            if ($Env["RECON_MOCK_ENABLED"] -ne "true") {
                $warnings += "CHECKOUT_BALANCE_ONLY without RECON_MOCK_ENABLED=true needs WeChat bill credentials"
            }
            $warnings += "Balance-only staging: WeChat Pay/MiniApp secrets not required yet"
        } else {
            foreach ($key in @("WECHAT_APP_ID", "WECHAT_MCH_ID", "WECHAT_API_V3_KEY")) {
                if (-not $Env[$key]) {
                    $warnings += "$key empty — set CHECKOUT_BALANCE_ONLY=true for no-merchant soak, or fill WeChat Pay"
                }
            }
        }
        if ($Env["VISION_MOCK_ENABLED"] -ne "false" -and -not $Env["VISION_MOCK_ENABLED"]) {
            $warnings += "VISION_MOCK_ENABLED unset — staging compose defaults to false"
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
        if ($Env["VISION_MOCK_ENABLED"] -eq "true") {
            $warnings += "VISION_MOCK_ENABLED=true — production should use edge provider recognition"
        }
        if ($Env["MQTT_BROKER"] -match "^tcp://") {
            $errors += "MQTT_BROKER must use ssl:// in prod"
        }
        # 审计 P1-6：EMQX 凭据轮换「restart ≠ 生效」护栏——bootstrap CSV 必须存在，
        # 且与 .env.production 的 MQTT 账号一致（EMQX 内置库只在**首次创建**容器时导入 bootstrap，
        # docker restart 后旧口令静默有效；轮换/吊销必须 --force-recreate emqx 并以本校验兜底）
        $bootstrapCsv = Join-Path $Infra "docker\emqx\auth-bootstrap.production.csv"
        if (-not (Test-Path $bootstrapCsv)) {
            $errors += "EMQX bootstrap CSV missing: infra/docker/emqx/auth-bootstrap.production.csv — run scripts/gen-emqx-auth-bootstrap.ps1 first (P1-6)"
        } else {
            $csvRows = Get-Content $bootstrapCsv | Where-Object { $_ -match '\S' } | Select-Object -Skip 1
            if ($Env["MQTT_USERNAME"] -and -not ($csvRows | Where-Object { $_.StartsWith($Env["MQTT_USERNAME"] + ",") })) {
                $errors += "MQTT_USERNAME '$($Env['MQTT_USERNAME'])' not in auth-bootstrap.production.csv — regenerate CSV then docker compose ... up -d --force-recreate emqx (P1-6: restart does NOT reload bootstrap)"
            }
            # 审计 P1-6 复核补强：设备凭据行（12 位 deviceId）才是吊销的主战场——
            # 原护栏只比对 backend 一行。这里做两层：
            #   a) 共享设备账号（aicabinet-device）不得出现在生产 CSV（S1 吊销语义）；
            #   b) CSV 与库内 ACTIVE 行的一致性需 DB（validate-production-readiness 阶段做，
            #      见 scripts/verify-production-readiness.ps1 P1-6b 段）。
            if ($csvRows | Where-Object { $_.StartsWith("aicabinet-device,") }) {
                $warnings += "shared device account 'aicabinet-device' present in production bootstrap CSV (S1 revocation semantics violated) — regenerate without -KeepSharedDevice (P1-6)"
            }
            $deviceRows = @($csvRows | Where-Object { $_ -match '^\d{12},' })
            $csvLines = $csvRows.Count
            Write-Host "  EMQX bootstrap CSV OK ($csvLines rows, device rows: $($deviceRows.Count)); rotation reminder: up -d --force-recreate emqx"
            Write-Host "  P1-6b: row-level consistency vs device_mqtt_credential is verified post-deploy by verify-production-readiness.ps1"
        }
        if ($Env["CORS_ORIGIN"] -match "localhost") {
            $warnings += "CORS_ORIGIN still localhost"
        }
        if ($Env["POSTGRES_PASSWORD"] -in @("aicabinet", "", $null)) {
            $errors += "POSTGRES_PASSWORD must be a strong password"
        }
        # 审计 P0-6 附带（env 化绑定设计的兜底）：compose 里 EMQX_MQTT_BIND / EMQX_MQTT_TLS_BIND
        # 默认值是回环，但 env 一旦被设成 0.0.0.0，门禁的「默认值回环即合规」就会漏——在此显式拦截。
        foreach ($bindKey in @("EMQX_MQTT_BIND", "EMQX_MQTT_TLS_BIND")) {
            $v = $Env[$bindKey]
            if ($v -and $v -notmatch "^(127\.0\.0\.1|::1)$") {
                $errors += "$bindKey=$v is not loopback — device MQTT exposure must be reviewed + ALLOWLISTed deliberately (P0-6)"
            }
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
