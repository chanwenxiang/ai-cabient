# Extended E2E - uncovered scenarios from TEST_CASES.md v1.1
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$ConsumerPhone = "13800138000",
    [string]$OpsPhone = "13900000001",
    [string]$DeviceId = "CAB-001"
)

$ErrorActionPreference = "Stop"
$passed = 0
$failed = 0
$results = @()

function Record($id, $name, $ok, $detail) {
    $script:results += [pscustomobject]@{ Id = $id; Name = $name; Result = $(if ($ok) { "PASS" } else { "FAIL" }); Detail = $detail }
    if ($ok) { $script:passed++ } else { $script:failed++ }
    $color = if ($ok) { "Green" } else { "Red" }
    Write-Host "[$($(if ($ok) { 'PASS' } else { 'FAIL' }))] $id $name - $detail" -ForegroundColor $color
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

function Invoke-Raw {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null)
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; ContentType = "application/json" }
    if ($Headers.Count -gt 0) { $params.Headers = $Headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Compress) }
    return Invoke-WebRequest @params -SkipHttpErrorCheck
}

function Login([string]$phone) {
    Invoke-Api POST "/api/v2/auth/login" @{} @{ phoneNumber = $phone; code = "123456" }
}

function Login-Ops {
    Invoke-Api POST "/api/v2/auth/admin-login" @{} @{ phoneNumber = $OpsPhone; code = "123456" }
}

function Internal([string]$Path, $Body) {
    Invoke-Api POST $Path @{ "X-Internal-Api-Key" = $InternalApiKey } $Body
}

& (Join-Path $PSScriptRoot "e2e-cleanup-device.ps1") -DeviceId $DeviceId

Write-Host "========================================"
Write-Host "  Extended E2E Tests"
Write-Host "========================================"
Write-Host ""

$consumer = Login $ConsumerPhone
$cAuth = @{ Authorization = "Bearer $($consumer.token)" }
$ops = Login-Ops
$oAuth = @{ Authorization = "Bearer $($ops.token)" }

# --- TC-OPS-001 Restock ---
try {
    $before = Invoke-Api GET "/api/v2/account" $cAuth
    $today = (Get-Date).ToString("yyyy-MM-dd")
    $route = Invoke-Api POST "/api/v2/ops/admin/replenishment/routes" $oAuth @{
        routeName = "E2E-OPS-$today"; assigneeUserId = 100000001; plannedDate = $today
        tasks = @(@{ deviceId = $DeviceId; notes = "TC-OPS-001" })
    }
    $taskId = $route.tasks[0].taskId
    Invoke-Api POST "/api/v2/ops/admin/replenishment/tasks/$taskId/check-in" $oAuth @{
        latitude = 31.2304; longitude = 121.4737
    } | Out-Null
    $r = Invoke-Api POST "/api/v2/ops/restock/open-door" $oAuth @{ deviceId = $DeviceId; taskId = $taskId }
    $sid = $r.sessionId
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sid; deviceId = $DeviceId; doorState = "OPEN"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    } | Out-Null
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sid; deviceId = $DeviceId; doorState = "CLOSED"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        uploadStatus = "LOCAL_QUEUED"
    } | Out-Null
    Start-Sleep -Seconds 1
    $s = Invoke-Api GET "/api/v2/sessions/$sid" $oAuth
    $hasOrder = $false
    try {
        Invoke-Api GET "/api/v2/sessions/$sid/order" $oAuth | Out-Null
        $hasOrder = $true
    } catch { }
    $after = Invoke-Api GET "/api/v2/account" $cAuth
    Record "TC-OPS-001" "Restock no settlement" ($s.state -eq "COMPLETED" -and -not $hasOrder) "state=$($s.state) order=$hasOrder"
} catch {
    Record "TC-OPS-001" "Restock no settlement" $false $_.Exception.Message
}

# --- TC-SESS-010/011 Offline upload ---
try {
    $sess = Invoke-Api POST "/api/v2/sessions" $cAuth @{ deviceId = $DeviceId }
    $sid = $sess.sessionId
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sid; deviceId = $DeviceId; doorState = "OPEN"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    } | Out-Null
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sid; deviceId = $DeviceId; doorState = "CLOSED"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        uploadStatus = "LOCAL_QUEUED"
    } | Out-Null
    $wait = Invoke-Api GET "/api/v2/sessions/$sid" $cAuth
    if ($wait.state -ne "WAITING_UPLOAD") { throw "expected WAITING_UPLOAD got $($wait.state)" }
    $videoUri = "minio://cabinet-videos/sim/$sid.mp4"
    & "$PSScriptRoot\upload-e2e-video.ps1" -SessionId $sid | Out-Null
    Internal "/internal/v1/sessions/video" @{
        sessionId = $sid; deviceId = $DeviceId; videoUri = $videoUri
    } | Out-Null
    $final = $null
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Seconds 1
        $final = (Invoke-Api GET "/api/v2/sessions/$sid" $cAuth).state
        if ($final -in @("COMPLETED", "DISPUTED", "FAILED")) { break }
    }
    Record "TC-SESS-011" "Offline upload resume" ($final -in @("COMPLETED", "DISPUTED")) "final=$final"
} catch {
    Record "TC-SESS-011" "Offline upload resume" $false $_.Exception.Message
}

# --- TC-RISK-002/001/003 Blacklist ---
try {
    Invoke-Api POST "/api/v2/ops/admin/risk/blacklist" $oAuth @{
        userId = 10001; reason = "e2e-test"; expiresAt = $null
    } | Out-Null
    $blocked = $false
    try {
        Invoke-Api POST "/api/v2/sessions" $cAuth @{ deviceId = $DeviceId } | Out-Null
    } catch { $blocked = $true }
    Invoke-Api DELETE "/api/v2/ops/admin/risk/blacklist/10001" $oAuth | Out-Null
    $sess2 = Invoke-Api POST "/api/v2/sessions" $cAuth @{ deviceId = $DeviceId }
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sess2.sessionId; deviceId = $DeviceId; doorState = "CLOSED"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        uploadStatus = "LOCAL_QUEUED"
    } | Out-Null
    Record "TC-RISK-001" "Blacklist block and unblock" ($blocked -and $sess2.sessionId) "blocked=$blocked restored=$($sess2.sessionId)"
} catch {
    Record "TC-RISK-001" "Blacklist block and unblock" $false $_.Exception.Message
}

# --- TC-PFREE-002 Alipay agreement ---
try {
    $ali = Invoke-Api POST "/api/v2/account/alipay-agreement/sign" $cAuth
    $acct = Invoke-Api GET "/api/v2/account" $cAuth
    Record "TC-PFREE-002" "Alipay agreement sign" ($acct.alipayAgreementEnabled -eq $true) "agreement=$($ali.contractId)"
} catch {
    Record "TC-PFREE-002" "Alipay agreement sign" $false $_.Exception.Message
}

# --- TC-DISP-003/006 Consumer dispute + CONFIRM ---
try {
    $shop = Invoke-Api POST "/api/v2/sessions" $cAuth @{ deviceId = $DeviceId }
    $sid = $shop.sessionId
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sid; deviceId = $DeviceId; doorState = "OPEN"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    } | Out-Null
    & "$PSScriptRoot\upload-e2e-video.ps1" -SessionId $sid | Out-Null
    $videoUri = "minio://cabinet-videos/sim/$sid.mp4"
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sid; deviceId = $DeviceId; doorState = "CLOSED"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        videoUri = $videoUri; uploadStatus = "UPLOADED"
    } | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        $st = (Invoke-Api GET "/api/v2/sessions/$sid" $cAuth).state
        if ($st -eq "COMPLETED") { break }
    }
    $filed = Invoke-Api POST "/api/v2/disputes" $cAuth @{
        sessionId = $sid; reason = "e2e consumer dispute test"
    }
    $tickets = Invoke-Api GET "/api/v2/ops/disputes?page=0&size=20&status=OPEN" $oAuth
    $ticket = $tickets.items | Where-Object { $_.sessionId -eq $sid } | Select-Object -First 1
    if (-not $ticket) { $ticket = $filed }
    $resolved = Invoke-Api POST "/api/v2/ops/disputes/$($ticket.ticketId)/resolve" $oAuth @{
        resolutionType = "CONFIRM"
        items = @(@{ skuId = "SKU-DEMO-001"; quantity = 1 })
    }
    $finalS = Invoke-Api GET "/api/v2/sessions/$sid" $cAuth
    Record "TC-DISP-006" "Dispute CONFIRM resolve" ($finalS.state -eq "COMPLETED" -and $resolved.order) "order=$($resolved.order.orderId) msg=$($resolved.message)"
} catch {
    Record "TC-DISP-006" "Dispute CONFIRM resolve" $false $_.Exception.Message
}

# --- TC-DISP-006b WAIVE ---
try {
    $shop2 = Invoke-Api POST "/api/v2/sessions" $cAuth @{ deviceId = $DeviceId }
    $sid2 = $shop2.sessionId
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sid2; deviceId = $DeviceId; doorState = "OPEN"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    } | Out-Null
    & "$PSScriptRoot\upload-e2e-video.ps1" -SessionId $sid2 | Out-Null
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $sid2; deviceId = $DeviceId; doorState = "CLOSED"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        videoUri = "minio://cabinet-videos/sim/$sid2.mp4"; uploadStatus = "UPLOADED"
    } | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        if ((Invoke-Api GET "/api/v2/sessions/$sid2" $cAuth).state -eq "COMPLETED") { break }
    }
    $t2 = Invoke-Api POST "/api/v2/disputes" $cAuth @{ sessionId = $sid2; reason = "e2e waive test" }
    $waived = Invoke-Api POST "/api/v2/ops/disputes/$($t2.ticketId)/resolve" $oAuth @{
        resolutionType = "WAIVE"; items = @()
    }
    Record "TC-DISP-006b" "Dispute WAIVE resolve" ($waived.resolutionType -eq "WAIVE") "msg=$($waived.message)"
} catch {
    Record "TC-DISP-006b" "Dispute WAIVE resolve" $false $_.Exception.Message
}

# --- TC-COMM-002 Inventory ---
try {
    Invoke-Api PUT "/api/v2/ops/admin/inventory" $oAuth @{
        deviceId = $DeviceId; skuId = "SKU-DEMO-001"; quantity = 10; capacity = 20; lowThreshold = 2
    } | Out-Null
    $inv = Invoke-Api GET "/api/v2/ops/admin/inventory?deviceId=$DeviceId" $oAuth
    $row = $inv | Where-Object { $_.skuId -eq "SKU-DEMO-001" } | Select-Object -First 1
    Record "TC-COMM-002" "Inventory update" ($row.quantity -eq 10) "qty=$($row.quantity)"
} catch {
    Record "TC-COMM-002" "Inventory update" $false $_.Exception.Message
}

# --- TC-GRAV-003 Gravity fallback (vision empty simulation via gravity only on close) ---
try {
    $gs = Invoke-Api POST "/api/v2/sessions" $cAuth @{ deviceId = $DeviceId }
    $gsid = $gs.sessionId
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $gsid; deviceId = $DeviceId; doorState = "OPEN"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    } | Out-Null
    Internal "/internal/v1/sessions/gravity-deltas" @{
        sessionId = $gsid; deviceId = $DeviceId
        deltas = @(@{ skuId = "SKU-DEMO-001"; delta = 1 })
    } | Out-Null
    & "$PSScriptRoot\upload-e2e-video.ps1" -SessionId $gsid | Out-Null
    Internal "/internal/v1/sessions/door-event" @{
        sessionId = $gsid; deviceId = $DeviceId; doorState = "CLOSED"
        timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        videoUri = "minio://cabinet-videos/sim/$gsid.mp4"; uploadStatus = "UPLOADED"
        gravityDeltasJson = '[{"skuId":"SKU-DEMO-001","delta":1}]'
    } | Out-Null
    $gfinal = $null
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Seconds 1
        $gfinal = (Invoke-Api GET "/api/v2/sessions/$gsid" $cAuth).state
        if ($gfinal -in @("COMPLETED", "DISPUTED")) { break }
    }
    Record "TC-GRAV-003" "Gravity fallback settlement" ($gfinal -eq "COMPLETED") "state=$gfinal"
} catch {
    Record "TC-GRAV-003" "Gravity fallback settlement" $false $_.Exception.Message
}

# --- TC-DISP-002 Consumer dispute list ---
try {
    $mine = Invoke-Api GET "/api/v2/disputes/mine" $cAuth
    Record "TC-DISP-002" "Consumer dispute list" ($mine.Count -ge 0) "count=$(@($mine).Count)"
} catch {
    Record "TC-DISP-002" "Consumer dispute list" $false $_.Exception.Message
}

Write-Host ""
Write-Host "========== Extended E2E Summary =========="
$results | Format-Table -AutoSize
Write-Host "PASS: $passed  FAIL: $failed  TOTAL: $($passed + $failed)"
if ($failed -gt 0) { exit 1 }
exit 0
