# Production readiness gate for AI cabinet Step 4/5 hardening.
# It validates static release artifacts, optional environment settings,
# targeted backend tests, device compilation, admin build, and optional runtime smoke checks.

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DeviceUrl = "http://localhost:8081",
    [string]$EnvFile = "",
    [switch]$Prod,
    [switch]$BalanceOnly,
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$SkipAdminBuild,
    [switch]$SkipRuntime,
    [switch]$Strict
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Results = New-Object System.Collections.Generic.List[object]

function Add-Result {
    param(
        [ValidateSet("PASS", "WARN", "FAIL")] [string]$Status,
        [string]$Name,
        [string]$Detail = ""
    )
    $Results.Add([pscustomobject]@{
        Status = $Status
        Name = $Name
        Detail = $Detail
    }) | Out-Null
    $color = if ($Status -eq "PASS") { "Green" } elseif ($Status -eq "WARN") { "Yellow" } else { "Red" }
    $suffix = if ($Detail) { " - $Detail" } else { "" }
    Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $suffix) -ForegroundColor $color
}

function Invoke-Checked {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [string]$FilePath,
        [string[]]$Arguments
    )
    Write-Host ""
    Write-Host "==> $Name"
    Push-Location $WorkingDirectory
    try {
        # 2>&1 将原生 stderr 并入成功流，避免 EAP=Stop 时把构建工具的 stderr 提示误判为致命错误
        & $FilePath @Arguments 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Add-Result "FAIL" $Name "exit code $LASTEXITCODE"
        } else {
            Add-Result "PASS" $Name
        }
    } finally {
        Pop-Location
    }
}

function Test-HttpOk {
    param([string]$Url, [int]$TimeoutSec = 5)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400)
    } catch {
        return $false
    }
}

function Get-LatestMigrationNumber {
    $dir = Join-Path $Root "services\trade-service\src\main\resources\db\migration"
    if (-not (Test-Path $dir)) { return $null }
    $max = $null
    Get-ChildItem $dir -Filter "V*.sql" | ForEach-Object {
        if ($_.Name -match "^V(\d+)__") {
            $num = [int]$Matches[1]
            if ($null -eq $max -or $num -gt $max) { $max = $num }
        }
    }
    return $max
}

function Test-RequiredFile {
    param([string]$RelativePath, [string]$Name)
    $full = Join-Path $Root $RelativePath
    if (Test-Path $full) {
        Add-Result "PASS" $Name $RelativePath
    } else {
        Add-Result "FAIL" $Name "$RelativePath missing"
    }
}

function Test-RepositoryUtf8 {
    $extensions = @(".java", ".js", ".json", ".md", ".properties", ".ps1", ".py", ".sql", ".wxml", ".wxss", ".xml", ".yml", ".yaml")
    $strictUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
    $invalid = New-Object System.Collections.Generic.List[string]
    $candidates = Get-ChildItem $Root -Recurse -File | Where-Object {
        ($null -ne $_.Extension -and $extensions -contains $_.Extension.ToLowerInvariant()) -and
        $_.FullName -notmatch '[\\/](\.git|node_modules|target|dist|\.venv|cache)[\\/]'
    }
    foreach ($file in $candidates) {
        try {
            [void]$strictUtf8.GetString([System.IO.File]::ReadAllBytes($file.FullName))
        } catch {
            $invalid.Add($file.FullName.Substring($Root.Length + 1)) | Out-Null
        }
    }
    if ($invalid.Count -eq 0) {
        Add-Result "PASS" "UTF-8 source encoding"
    } else {
        Add-Result "FAIL" "UTF-8 source encoding" (($invalid | Select-Object -First 5) -join ", ")
    }
}

Write-Host "==> AI cabinet production readiness verification"
Write-Host ""

Test-RequiredFile "docs\PRODUCTION.md" "Production runbook"
Test-RequiredFile "docs\MODULES.md" "Module index"
Test-RequiredFile "docs\production-launch-checklist.md" "Launch checklist"
Test-RequiredFile "infra\.env.production.example" "Production env template"
Test-RequiredFile "infra\.env.staging.example" "Staging env template"
Test-RequiredFile "infra\docker\trade-service.Dockerfile" "Trade Dockerfile"
Test-RequiredFile "infra\docker\device-service.Dockerfile" "Device Dockerfile"
Test-RequiredFile "scripts\deploy-production.ps1" "Production deploy script"
Test-RequiredFile "docs\VISION_QUECTEL_INTEGRATION.md" "Edge vision integration runbook"
Test-RequiredFile "infra\docker-compose.production.yml" "Production compose overlay"
Test-RequiredFile "infra\docker-compose.staging.yml" "Staging compose overlay"
Test-RequiredFile "scripts\e2e-shopping.ps1" "Shopping E2E script"
Test-RepositoryUtf8

if ($BalanceOnly -or $EnvFile) {
    $envPathForMode = if ($EnvFile) {
        if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $Root $EnvFile }
    } else { $null }
    $balanceFlag = $BalanceOnly
    if ($envPathForMode -and (Test-Path $envPathForMode)) {
        Get-Content $envPathForMode | ForEach-Object {
            if ($_ -match '^\s*CHECKOUT_BALANCE_ONLY\s*=\s*true\s*$') { $balanceFlag = $true }
        }
    }
    if ($balanceFlag) {
        Add-Result "PASS" "Balance-only mode" "WeChat merchant certs not required; keep RECON_MOCK_ENABLED=true"
    }
}
$latestMigration = Get-LatestMigrationNumber
if ($null -eq $latestMigration) {
    Add-Result "FAIL" "Flyway migrations" "no migration files found"
} elseif ($latestMigration -lt 34) {
    Add-Result "FAIL" "Flyway migrations" "latest V$latestMigration, expected at least V34"
} else {
    Add-Result "PASS" "Flyway migrations" "latest V$latestMigration"
}

$adminIndex = Join-Path $Root "services\trade-service\src\main\resources\static\admin\index.html"
$adminAssets = Join-Path $Root "services\trade-service\src\main\resources\static\admin\assets"
if ((Test-Path $adminIndex) -and
    (Test-Path $adminAssets) -and
    (Get-ChildItem $adminAssets -Filter "index-*.js" -ErrorAction SilentlyContinue) -and
    (Get-ChildItem $adminAssets -Filter "index-*.css" -ErrorAction SilentlyContinue)) {
    Add-Result "PASS" "Admin static bundle" "index.html and hashed assets present"
} else {
    Add-Result "FAIL" "Admin static bundle" "run clients/admin-vue npm build"
}

if ($EnvFile -or $Prod) {
    $envArgs = @("-CheckEnv")
    if ($Prod) { $envArgs += "-Prod" }
    if ($EnvFile) { $envArgs += @("-EnvFile", $EnvFile) }
    Invoke-Checked "Environment checklist" $Root "powershell" (@("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $Root "scripts\check-env.ps1")) + $envArgs)
}

if (-not $SkipAdminBuild) {
    Invoke-Checked "Admin frontend build" (Join-Path $Root "clients\admin-vue") "npm" @("run", "build")
} else {
    Add-Result "WARN" "Admin frontend build" "skipped"
}

if (-not $SkipBuild) {
    Invoke-Checked "Device service compile" $Root "mvn" @("-pl", ":device-service", "-am", "-DskipTests", "compile")
} else {
    Add-Result "WARN" "Device service compile" "skipped"
}

if (-not $SkipTests) {
    Invoke-Checked "Trade exception/reconciliation tests" $Root "mvn" @("-pl", ":trade-service", "-am", "-Pskip-admin-ui", "-Dtest=ReconciliationServiceTest,ReplenishmentServiceOutboundTest,MerchantSkuPricingServiceTest,OperatorUserIdAllocatorTest,FinanceReportServiceTest,MerchantAiInsightServiceTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test")
    Invoke-Checked "Device command tracker tests" $Root "mvn" @("-pl", ":device-service", "-am", "-Dtest=DeviceCommandTrackerTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test")
} else {
    Add-Result "WARN" "Backend targeted tests" "skipped"
}

if (-not $SkipRuntime) {
    if (Test-HttpOk "$BaseUrl/actuator/health") {
        Add-Result "PASS" "Trade runtime health" $BaseUrl
        Invoke-Checked "API smoke" $Root "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $Root "scripts\run-api-tests.ps1"), "-BaseUrl", $BaseUrl)
    } else {
        Add-Result "WARN" "Trade runtime health" "$BaseUrl not reachable"
    }

    if (Test-HttpOk "$DeviceUrl/actuator/health") {
        Add-Result "PASS" "Device runtime health" $DeviceUrl
    } else {
        Add-Result "WARN" "Device runtime health" "$DeviceUrl not reachable"
    }

    $visionUrl = if ($BaseUrl -match ":(\d+)") {
        $BaseUrl -replace ":\d+", ":8082"
    } else { "http://127.0.0.1:8082" }
    if (Test-HttpOk "$visionUrl/health") {
        try {
            $vh = Invoke-RestMethod -Uri "$visionUrl/health" -TimeoutSec 5
            $detail = "version=$($vh.model_version) mock=$($vh.mock_enabled) available=$($vh.recognizer_available)"
            if ($vh.recognizer_available -eq $true -and $vh.mock_enabled -eq $false) {
                Add-Result "PASS" "Vision runtime health" $detail
            } else {
                Add-Result "WARN" "Vision runtime health" $detail
            }
        } catch {
            Add-Result "WARN" "Vision runtime health" $_.Exception.Message
        }
    } else {
        Add-Result "WARN" "Vision runtime health" "$visionUrl/health not reachable"
    }
} else {
    Add-Result "WARN" "Runtime smoke" "skipped"
}

# ---- 审计 P1-6b：设备凭据 CSV ↔ 库 一致性（吊销闭环的最后一步）----
# 事前（check-env.ps1）只验 CSV 存在与 backend 行；这里部署后比对「库内 ACTIVE 设备行」
# 与「CSV 设备行」逐行一致——不一致即"运营台吊销/签发后忘重跑生成器"，而 EMQX 旧口令
# 因 restart 不重导 bootstrap 仍有效（原始 P1-6 后果）。
$credCsv = Join-Path $Root "infra\docker\emqxuth-bootstrap.production.csv"
if (Test-Path $credCsv) {
    Write-Host "==> P1-6b: device credential CSV vs DB consistency"
    $csvDeviceIds = @{}
    Get-Content $credCsv | Select-Object -Skip 1 | ForEach-Object {
        if ($_ -match '^(\d{12}),') { $csvDeviceIds[$Matches[1]] = $true }
    }
    $dbDeviceIds = @{}
    try {
        $rows = docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -t -A -c `
            "SELECT device_id FROM device_mqtt_credential WHERE status='ACTIVE'" 2>$null
        foreach ($r in $rows) { if ($r -match '^\d{12}$') { $dbDeviceIds[$r] = $true } }
    } catch {
        Write-Host "  WARN: cannot read device_mqtt_credential (DB not reachable from this host) — skipping strict check" -ForegroundColor Yellow
    }
    if ($dbDeviceIds.Count -gt 0) {
        $onlyCsv = @($csvDeviceIds.Keys | Where-Object { -not $dbDeviceIds.ContainsKey($_) })
        $onlyDb = @($dbDeviceIds.Keys | Where-Object { -not $csvDeviceIds.ContainsKey($_) })
        if ($onlyCsv.Count -gt 0) {
            Write-Host "  FAIL: CSV has device rows absent/revoked in DB: $($onlyCsv -join ', ')" -ForegroundColor Red
            Write-Host "        -> regenerate bootstrap + up -d --force-recreate emqx, or fix revocation state (P1-6)"
            exit 1
        }
        if ($onlyDb.Count -gt 0) {
            Write-Host "  FAIL: DB has ACTIVE device credentials missing from CSV: $($onlyDb -join ', ')" -ForegroundColor Red
            Write-Host "        -> regenerate bootstrap (gen-emqx-auth-bootstrap.ps1 -IncludeDevicesFromDb) then --force-recreate emqx (P1-6)"
            exit 1
        }
        Write-Host "  OK: CSV device rows match DB ACTIVE rows ($($csvDeviceIds.Count) devices)"
    }
}

Write-Host ""
Write-Host "==> Summary"
$Results | Format-Table Status, Name, Detail -AutoSize

$failures = @($Results | Where-Object { $_.Status -eq "FAIL" }).Count
$warnings = @($Results | Where-Object { $_.Status -eq "WARN" }).Count
if ($failures -gt 0 -or ($Strict -and $warnings -gt 0)) {
    Write-Host "Production readiness verification failed." -ForegroundColor Red
    exit 1
}

Write-Host "Production readiness verification passed." -ForegroundColor Green
exit 0
