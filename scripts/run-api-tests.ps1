# API-level smoke tests for local / staging stacks
param([string]$BaseUrl = "")

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
$InternalKey = "dev-internal-key-change-me"
$passed = 0
$failed = 0
$results = @()
$sid = $null

function Record($id, $name, $ok, $detail) {
    $script:results += [pscustomobject]@{ Id = $id; Name = $name; Result = $(if ($ok) { "PASS" } else { "FAIL" }); Detail = $detail }
    if ($ok) { $script:passed++ } else { $script:failed++ }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null)
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; ContentType = "application/json" }
    if ($Headers.Count -gt 0) { $params.Headers = $Headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    $resp = Invoke-RestMethod @params
    if ($resp.code -ne 0) { throw "$($resp.message) ($Path)" }
    return $resp.data
}

function Login([string]$phone = "13800138000") {
    try {
        $capResp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/v2/auth/captcha" -ContentType "application/json"
        if ($capResp.code -ne 0) { throw "captcha fetch failed" }
        $capId = $capResp.data.captchaId
        $capCode = (docker exec ai-cabinet-redis-1 redis-cli GET "aicabinet:captcha:$capId" 2>&1).Trim()
        if ([string]::IsNullOrWhiteSpace($capCode)) { throw "captcha redis miss" }
        $q = "phoneNumber=$([uri]::EscapeDataString($phone))&captchaId=$([uri]::EscapeDataString($capId))&captchaCode=$([uri]::EscapeDataString($capCode))"
        Invoke-Api POST "/api/v2/auth/sms-code?$q" @{} $null | Out-Null
        return Invoke-Api POST "/api/v2/auth/login" @{} @{ phoneNumber = $phone; code = "123456" }
    } catch {
        return Invoke-Api POST "/api/v2/auth/password-login" @{} @{ phoneNumber = $phone; password = "123456" }
    }
}

function OpsLogin([string]$phone = "13900000001") {
    # admin-password-login 需图形验证码；Invoke-E2eApi 会从 Redis 自动补全 captcha 字段
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = $phone; password = "123456"
    }
}

Write-Host "==> API tests against $BaseUrl"
Clear-E2eDeviceBlockingSessions -DeviceId "CAB-001" | Out-Null
Write-Host ""

try {
    $login = Login
    Record "TC-AUTH-002" "Consumer SMS login" ($login.token -and $login.userId -eq 10001) "userId=$($login.userId)"
} catch {
    Record "TC-AUTH-002" "Consumer SMS login" $false $_.Exception.Message
}

$auth = @{ Authorization = "Bearer $($login.token)" }

try {
    $ops = OpsLogin
    Record "TC-AUTH-004" "Operator login" ($ops.userId -ge 100000000) "userId=$($ops.userId)"
} catch {
    Record "TC-AUTH-004" "Operator login" $false $_.Exception.Message
}

$opsAuth = @{ Authorization = "Bearer $($ops.token)" }

try {
    $denied = $false
    try {
        Invoke-Api POST "/api/v2/auth/admin-login" @{} @{ phoneNumber = "13800138000"; code = "123456" } | Out-Null
    } catch { $denied = $true }
    Record "TC-AUTH-005" "Consumer blocked from admin-login" $denied "denied=$denied"
} catch {
    Record "TC-AUTH-005" "Consumer blocked from admin-login" $false $_.Exception.Message
}

try {
    $acct = Invoke-Api GET "/api/v2/account" $auth
    $ok = ($null -ne $acct.balanceCents) -and ($null -ne $acct.verified) -and ($null -ne $acct.passwordFreeReady)
    Record "TC-ACCT-001" "Get account" $ok "balance=$($acct.balanceCents) verified=$($acct.verified) pfree=$($acct.passwordFreeReady)"
} catch {
    Record "TC-ACCT-001" "Get account" $false $_.Exception.Message
}

try {
    $contract = Invoke-Api POST "/api/v2/account/payscore/sign" $auth
    $acct2 = Invoke-Api GET "/api/v2/account" $auth
    Record "TC-PFREE-001" "Sign WeChat PayScore" ($acct2.passwordFreeReady -eq $true) "contract=$($contract.contractId)"
} catch {
    Record "TC-PFREE-001" "Sign WeChat PayScore" $false $_.Exception.Message
}

try {
    $sess = Invoke-Api POST "/api/v2/sessions" $auth @{
        deviceId = "CAB-001"
        idempotencyKey = "api-smoke-open-$([guid]::NewGuid().ToString('N'))"
    }
    $sid = $sess.sessionId
    Record "TC-PFREE-003" "Password-free open door" ($null -ne $sid) "session=$sid state=$($sess.state)"
    $headers = @{ "X-Internal-Api-Key" = $InternalKey }
    Invoke-Api POST "/internal/v1/sessions/door-event" $headers @{
        sessionId = $sid; deviceId = "CAB-001"; doorState = "OPEN"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    } | Out-Null
    Invoke-Api POST "/internal/v1/sessions/door-event" $headers @{
        sessionId = $sid; deviceId = "CAB-001"; doorState = "CLOSED"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        uploadStatus = "LOCAL_QUEUED"
    } | Out-Null
} catch {
    Record "TC-PFREE-003" "Password-free open door" $false $_.Exception.Message
}

try {
    $dev = Invoke-Api GET "/api/v2/devices/CAB-001/status" $auth
    Record "TC-DEV-002" "Device status" ($dev.online -eq $true) "online=$($dev.online) available=$($dev.available)"
} catch {
    Record "TC-DEV-002" "Device status" $false $_.Exception.Message
}

try {
    if ($sid) {
        $grav = Invoke-RestMethod -Method POST -Uri "$BaseUrl/internal/v1/sessions/gravity-deltas" `
            -Headers @{ "X-Internal-Api-Key" = $InternalKey; "Content-Type" = "application/json" } `
            -Body (@{
                sessionId = $sid; deviceId = "CAB-001"
                deltas = @(@{ skuId = "SKU-DEMO-001"; delta = 1 })
            } | ConvertTo-Json -Compress)
        Record "TC-GRAV-001" "Gravity deltas attach" ($grav.code -eq 0) "session=$sid"
    } else {
        Record "TC-GRAV-001" "Gravity deltas attach" $false "no session id"
    }
} catch {
    Record "TC-GRAV-001" "Gravity deltas attach" $false $_.Exception.Message
}

try {
    $stats = Invoke-Api GET "/api/v2/ops/admin/stats" $opsAuth
    $ok = ($null -ne $stats.disputeOverdue) -and ($null -ne $stats.recognitionAutoRate24h) -and ($null -ne $stats.lowStockSkuCount)
    Record "TC-ADM-001" "Dashboard stats" $ok "devices=$($stats.deviceTotal) overdue=$($stats.disputeOverdue)"
} catch {
    Record "TC-ADM-001" "Dashboard stats" $false $_.Exception.Message
}

try {
    $disputes = Invoke-Api GET "/api/v2/ops/disputes?page=0&size=5" $opsAuth
    Record "TC-DISP-005" "Ops dispute list" ($null -ne $disputes) "items=$(@($disputes).Count)"
} catch {
    Record "TC-DISP-005" "Ops dispute list" $false $_.Exception.Message
}

foreach ($pair in @(
    @{ Id = "TC-INFRA-002"; Url = "$BaseUrl/actuator/health" },
    @{ Id = "TC-INFRA-003"; Url = "$(Get-E2eDeviceUrl)/actuator/health" },
    @{ Id = "TC-INFRA-004"; Url = "$(Get-E2eVisionUrl)/health" }
)) {
    try {
        $h = Invoke-RestMethod -Uri $pair.Url -TimeoutSec 5
        $up = ($h.status -eq "UP") -or ($h.status -eq "ok") -or ($h.recognizer_available -eq $true)
        Record $pair.Id "Health $($pair.Url)" $up "$($h.status)"
    } catch {
        Record $pair.Id "Health $($pair.Url)" $false $_.Exception.Message
    }
}

Write-Host ""
Write-Host "========== API Test Summary =========="
$results | Format-Table -AutoSize
Write-Host "PASS: $passed  FAIL: $failed  TOTAL: $($passed + $failed)"
if ($failed -gt 0) { exit 1 }
exit 0
