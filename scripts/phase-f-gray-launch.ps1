# Phase F gray launch checklist runner (§12 / §14 / §10)
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$VisionHealthUrl = "http://127.0.0.1:8082/health",
    [string]$ConsumerPhone = "13800138000",
    [string]$OperatorPhone = "13900000001",
    [string]$PostgresContainer = "ai-cabinet-postgres-1",
    [string[]]$GrayDeviceIds = @("CAB-001"),
    [int]$ObservationDays = 14,
    [int]$MinGrayBalanceCents = 500,
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

Write-Host "========== Phase F Gray Launch Checklist =========="

$checks = @()

function Add-Check([string]$Name, [bool]$Pass, [string]$Detail) {
    $script:checks += [pscustomobject]@{ Name = $Name; Pass = $Pass; Detail = $Detail }
    $mark = if ($Pass) { "PASS" } else { "FAIL" }
    Write-Host "[$mark] $Name — $Detail"
}

function Invoke-GrayApi {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null)
    if ($Path -like '/api/v2/auth/sms-code*' -and $Path -notlike '*captchaId=*') {
        $capResp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/v2/auth/captcha" -ContentType "application/json"
        if ($capResp.code -ne 0 -or [string]::IsNullOrWhiteSpace($capResp.data.captchaId)) {
            throw "captcha fetch failed: $($capResp.message)"
        }
        $capId = $capResp.data.captchaId
        $code = (docker exec ai-cabinet-redis-1 redis-cli GET "aicabinet:captcha:$capId" 2>&1).Trim()
        if ([string]::IsNullOrWhiteSpace($code)) { throw "captcha redis miss: $capId" }
        $sep = if ($Path.Contains('?')) { '&' } else { '?' }
        $Path = "$Path$sep" + "captchaId=$([uri]::EscapeDataString($capId))&captchaCode=$([uri]::EscapeDataString($code))"
    }
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; ContentType = "application/json" }
    if ($Headers.Count -gt 0) { $params.Headers = $Headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    $resp = Invoke-RestMethod @params
    if ($resp.code -ne 0) { throw "$($resp.message) ($Path)" }
    return $resp.data
}

# Vision production readiness (§10) — required for real-funds gray
try {
    $vision = Invoke-RestMethod -Uri $VisionHealthUrl -TimeoutSec 5
    $recognizerOk = $vision.recognizer_available -eq $true
    $mockOff = $vision.mock_enabled -eq $false
    Add-Check "vision.recognizer_available" $recognizerOk "recognizer_available=$($vision.recognizer_available) load_error=$($vision.load_error)"
    Add-Check "vision.mock_enabled=false" $mockOff "mock_enabled=$($vision.mock_enabled)"
    $modelVer = [string]$vision.model_version
    $isGeneric = $modelVer -match 'yolov8n' -or ([string]$vision.model_path -match 'yolov8n')
    Add-Check "vision.sku_model" (-not $isGeneric) "model_version=$modelVer (generic COCO blocks real-funds gray)"
} catch {
    Add-Check "vision.health" $false $_.Exception.Message
}

# Infrastructure smoke
$infraUrls = @{
    "trade-service" = "$BaseUrl/actuator/health"
    "device-service" = "http://127.0.0.1:8081/actuator/health"
    "gateway"       = "http://127.0.0.1/"
    "grafana"       = "http://127.0.0.1:13000/api/health"
    "prometheus"    = "http://127.0.0.1:9090/-/healthy"
}
foreach ($kv in $infraUrls.GetEnumerator()) {
    try {
        $r = Invoke-WebRequest -Uri $kv.Value -UseBasicParsing -TimeoutSec 5
        Add-Check "infra.$($kv.Key)" ($r.StatusCode -ge 200 -and $r.StatusCode -lt 500) "status=$($r.StatusCode)"
    } catch {
        Add-Check "infra.$($kv.Key)" $false $_.Exception.Message
    }
}

# Gray device whitelist (1–3 cabinets)
Add-Check "gray.devices" ($GrayDeviceIds.Count -ge 1 -and $GrayDeviceIds.Count -le 3) "devices=$($GrayDeviceIds -join ',')"
Add-Check "gray.observation_window" ($ObservationDays -ge 14) "observation_days=$ObservationDays"

# Data cleanliness after E2E
try {
    $clean = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -t -A -c @"
SELECT (SELECT COUNT(*) FROM ops_exception WHERE status IN ('OPEN','PROCESSING')),
       (SELECT COUNT(*) FROM dispute_ticket WHERE status='OPEN'),
       (SELECT COUNT(*) FROM shopping_session WHERE state IN ('DISPUTED','OPENING','SHOPPING','RECOGNIZING','SETTLING') AND device_id IN ('$($GrayDeviceIds -join "','")'));
"@ 2>&1
    $parts = ($clean -split '\|')
    $openEx = [int]$parts[0]
    $openDisp = [int]$parts[1]
    $blocking = [int]$parts[2]
    Add-Check "data.open_exceptions" ($openEx -eq 0) "count=$openEx"
    Add-Check "data.open_disputes" ($openDisp -eq 0) "count=$openDisp"
    Add-Check "data.blocking_sessions" ($blocking -eq 0) "count=$blocking"
} catch {
    Add-Check "data.cleanliness" $false $_.Exception.Message
}

# Gray consumer + ops auth smoke (§5.6)
$auth = @{}

try {
    try {
        Invoke-GrayApi POST "/api/v2/auth/sms-code?phoneNumber=$ConsumerPhone" @{} $null | Out-Null
        $consumer = Invoke-GrayApi POST "/api/v2/auth/login" @{} @{ phoneNumber = $ConsumerPhone; code = "123456" }
    } catch {
        $consumer = Invoke-GrayApi POST "/api/v2/auth/password-login" @{} @{ phoneNumber = $ConsumerPhone; password = "123456" }
    }
    $auth = @{ Authorization = "Bearer $($consumer.token)" }
    $acct = Invoke-GrayApi GET "/api/v2/account" $auth
    Add-Check "gray.consumer_login" ($consumer.userId -gt 0) "userId=$($consumer.userId)"
    Add-Check "gray.consumer_balance" ($acct.balanceCents -ge $MinGrayBalanceCents) "balance_cents=$($acct.balanceCents)"
} catch {
    Add-Check "gray.consumer_login" $false $_.Exception.Message
    Add-Check "gray.consumer_balance" $false "skipped"
}

try {
    $ops = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = $OperatorPhone; password = "123456"
    }
    $opsAuth = @{ Authorization = "Bearer $($ops.token)" }
    $users = Invoke-GrayApi GET "/api/v2/ops/admin/users?page=0&size=20" $opsAuth
    $grayUser = @($users.items) | Where-Object { $_.phoneNumber -eq $ConsumerPhone } | Select-Object -First 1
    Add-Check "gray.ops_login" ($ops.userId -ge 100000000) "userId=$($ops.userId)"
    Add-Check "gray.ops_user_list" ($null -ne $grayUser) "consumer=$ConsumerPhone balance=$($grayUser.balanceCents)"
} catch {
    Add-Check "gray.ops_login" $false $_.Exception.Message
    Add-Check "gray.ops_user_list" $false "skipped"
}

# Device online for gray cabinets
foreach ($deviceId in $GrayDeviceIds) {
    try {
        $dev = Invoke-GrayApi GET "/api/v2/devices/$deviceId/status" $auth
        Add-Check "gray.device_online.$deviceId" ($dev.online -eq $true) "online=$($dev.online) available=$($dev.available)"
    } catch {
        Add-Check "gray.device_online.$deviceId" $false $_.Exception.Message
    }
}

# API smoke subset (optional gate for dev gray)
if (-not $CheckOnly) {
    Write-Host ""
    Write-Host "==> Running run-api-tests.ps1 ..."
    & (Join-Path $PSScriptRoot "run-api-tests.ps1") -BaseUrl $BaseUrl
    $apiExit = $LASTEXITCODE
    Add-Check "api.smoke_tests" ($apiExit -eq 0) "exit_code=$apiExit"
}

$failed = @($checks | Where-Object { -not $_.Pass })
$blocking = @($failed | Where-Object { $_.Name -like "vision.*" })
$devGrayFailed = @($failed | Where-Object { $_.Name -notlike "vision.*" })

Write-Host ""
if ($blocking.Count -gt 0) {
    Write-Host "Blocked items for real-funds gray launch:"
    $blocking | ForEach-Object { Write-Host "  - $($_.Name): $($_.Detail)" }
    Write-Host ""
    Write-Host "See ai-cabinet/docs/BROWSER_MIN_UAT.md §10 / §14"
}

if ($devGrayFailed.Count -gt 0) {
    Write-Host "Dev/staging gray items to fix:"
    $devGrayFailed | ForEach-Object { Write-Host "  - $($_.Name): $($_.Detail)" }
}

Write-Host ""
Write-Host "Gray launch pre-check complete. Manual steps remaining:"
Write-Host "  1. Install ultralytics + SKU model (vision-service/requirements-ml.txt)"
Write-Host "  2. Set VISION_MOCK_ENABLED=false, AICABINET_MOCK_ENABLED=false in production .env"
Write-Host "  3. Configure HTTPS domain + mini-program legal domains"
Write-Host "  4. Deploy 1-3 cabinets with gray whitelist; run daily reconciliation for $ObservationDays days"

if ($blocking.Count -gt 0 -and -not $CheckOnly) { exit 1 }
if ($failed.Count -gt 0 -and -not $CheckOnly) { exit 2 }
exit 0
