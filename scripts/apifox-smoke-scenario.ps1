# Apifox-aligned API smoke: critical mock-demo path (login → device → open → settle readiness).
# Mirrors the scenario list you can recreate in Apifox after OAS import (project 8780097).
#
# Usage:
#   .\scripts\apifox-smoke-scenario.ps1
#   $env:E2E_BASE_URL='http://127.0.0.1:18080'; .\scripts\apifox-smoke-scenario.ps1
#
# Does NOT open a door / create shopping sessions by default (read + auth only).
# Set -WithOpenDoor to also POST /api/v2/sessions then cancel (needs DeviceSimulator ONLINE).
param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "CAB-001",
    [string]$ConsumerPhone = "13800138000",
    [string]$ConsumerPassword = "123456",
    [string]$MerchantPhone = "13800138001",
    [string]$MerchantPassword = "123456",
    [string]$OperatorPhone = "13900000001",
    [string]$OperatorPassword = "123456",
    [switch]$WithOpenDoor
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl

$pass = 0
$fail = 0
$steps = [System.Collections.Generic.List[object]]::new()

function Step([string]$Id, [string]$ApifoxPath, [scriptblock]$Action) {
    try {
        $detail = & $Action
        $script:pass++
        $steps.Add([pscustomobject]@{ id = $Id; path = $ApifoxPath; ok = $true; detail = "$detail" })
        Write-Host "PASS $Id  $ApifoxPath  $detail"
    } catch {
        $script:fail++
        $msg = $_.Exception.Message
        $steps.Add([pscustomobject]@{ id = $Id; path = $ApifoxPath; ok = $false; detail = $msg })
        Write-Host "FAIL $Id  $ApifoxPath  $msg"
    }
}

Write-Host "========== Apifox smoke scenario =========="
Write-Host "BaseUrl=$BaseUrl  (map these paths in Apifox project 8780097)"
Write-Host ""

if (-not (Test-ServiceHealth -Url "$BaseUrl/actuator/health")) {
    throw "trade-service not healthy at $BaseUrl"
}

$consumer = $null
$merchant = $null
$ops = $null

Step "S01" "POST /api/v2/auth/password-login" {
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $ConsumerPhone
        password    = $ConsumerPassword
    }
    $script:consumer = @{ Authorization = "Bearer $($login.token)" }
    "consumer userId=$($login.userId)"
}

Step "S02" "POST /api/v2/auth/admin-password-login (merchant)" {
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = $MerchantPhone
        password    = $MerchantPassword
    }
    $script:merchant = @{ Authorization = "Bearer $($login.token)" }
    "merchant userId=$($login.userId)"
}

Step "S03" "POST /api/v2/auth/admin-password-login (ops)" {
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = $OperatorPhone
        password    = $OperatorPassword
    }
    $script:ops = @{ Authorization = "Bearer $($login.token)" }
    "ops userId=$($login.userId)"
}

Step "S04" "GET /api/v2/account" {
    $a = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/account" -Headers $consumer
    "balanceCents=$($a.balanceCents) verified=$($a.verified)"
}

Step "S05" "GET /api/v2/devices/{deviceId}/status" {
    $d = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $consumer
    "online=$($d.online) available=$($d.available)"
}

Step "S06" "GET /api/v2/orders?page=0&size=5" {
    $o = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/orders?page=0&size=5" -Headers $consumer
    $n = @($o.items).Count
    "items=$n total=$($o.total)"
}

Step "S07" "GET /api/v2/merchant/orders?deviceId=&page=0&size=5" {
    $o = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/merchant/orders?deviceId=$DeviceId&page=0&size=5" -Headers $merchant
    $n = @($o.items).Count
    "items=$n total=$($o.total)"
}

Step "S08" "GET /api/v2/ops/disputes?page=0&size=5" {
    $d = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/disputes?page=0&size=5" -Headers $ops
    $n = @($d.items).Count
    "items=$n total=$($d.total)"
}

Step "S09" "GET /api/v2/ops/admin/exceptions?page=0&size=5" {
    $e = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/exceptions?page=0&size=5" -Headers $ops
    $n = @($e.items).Count
    "items=$n total=$($e.total)"
}

Step "S10" "GET /api/v2/ops/admin/stats" {
    $s = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/stats" -Headers $ops
    "deviceTotal=$($s.deviceTotal) disputeOverdue=$($s.disputeOverdue)"
}

if ($WithOpenDoor) {
    Step "S11" "POST /api/v2/sessions (open door)" {
        Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
        $sess = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions" -Headers $consumer -Body @{
            deviceId       = $DeviceId
            idempotencyKey = "apifox-smoke-$([guid]::NewGuid().ToString('N'))"
        }
        $sid = $sess.sessionId
        try {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions/$sid/cancel" -Headers $consumer | Out-Null
        } catch {
            try {
                Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions/$sid/demo-close" -Headers $consumer -Body @{} | Out-Null
            } catch { }
        }
        "sessionId=$sid state=$($sess.state) (cancelled/closed)"
    }
}

$outDir = Join-Path (Split-Path -Parent $PSScriptRoot) ".tmp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$report = Join-Path $outDir "apifox-smoke-report.json"
@{
    baseUrl = $BaseUrl
    pass    = $pass
    fail    = $fail
    steps   = $steps
    apifoxHint = @(
        "Import OAS via scripts/sync-apifox-oas.ps1",
        "Create a Scenario folder 'Mock demo smoke' with steps S01-S10 (S11 optional)",
        "Environments: Base URL = $BaseUrl ; vars consumer_token / merchant_token / ops_token from login responses"
    )
} | ConvertTo-Json -Depth 6 | Set-Content -Path $report -Encoding UTF8

Write-Host ""
Write-Host "==== SUMMARY PASS=$pass FAIL=$fail ===="
Write-Host "Report: $report"
Write-Host "Apifox: recreate Scenario using step ids S01-S10 against project 8780097"
if ($fail -gt 0) { exit 1 }
Write-Host "OK apifox smoke scenario passed"
exit 0
