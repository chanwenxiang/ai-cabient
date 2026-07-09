# E2E: 补货关门 → 货道重力/视觉快照回写实测数量

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DeviceId = "CAB-001",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$OpsPhone = "13900000001",
    [string]$SmsMockUrl = "http://localhost:8099",
    [string]$DevMockCode = "123456",
    [switch]$ForceDevMock
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\e2e-sms-auth.ps1"

function Invoke-Api {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null)
    Invoke-E2eApi -BaseUrl $BaseUrl -Method $Method -Path $Path -Headers $Headers -Body $Body
}

Write-Host "==> Restock slot snapshot E2E"
$ops = Invoke-E2eFlexibleLogin -BaseUrl $BaseUrl -Phone $OpsPhone -SmsMockUrl $SmsMockUrl `
    -InternalApiKey $InternalApiKey -LoginPath "/api/v2/auth/admin-login" `
    -DevMockCode $DevMockCode -ForceDevMock:$ForceDevMock
$opsAuth = $ops.Auth
$opsToken = $ops.Auth.Authorization -replace '^Bearer\s+', ''

Write-Host "==> 0. Create replenishment task"
$today = (Get-Date).ToString("yyyy-MM-dd")
$route = Invoke-Api -Method POST -Path "/api/v2/ops/admin/replenishment/routes" -Headers $opsAuth -Body @{
    routeName       = "E2E-RESTOCK-$today"
    assigneeUserId  = 100000001
    plannedDate     = $today
    tasks           = @(@{ deviceId = $DeviceId; notes = "restock snapshot e2e" })
}
$taskId = $route.tasks[0].taskId
Write-Host "    taskId=$taskId"

Write-Host "==> 0b. Check-in before open-door"
Invoke-Api -Method POST -Path "/api/v2/ops/admin/replenishment/tasks/${taskId}/check-in" -Headers $opsAuth -Body @{
    latitude = 31.2304
    longitude = 121.4737
} | Out-Null
Write-Host "    checked in"

Write-Host "==> 1. Ops restock open-door (bound to task)"
$session = Invoke-Api -Method POST -Path "/api/v2/ops/restock/open-door" -Headers $opsAuth -Body @{
    deviceId = $DeviceId
    taskId   = $taskId
}
$sid = $session.sessionId
Write-Host "    sessionId=$sid"

Write-Host "==> 2. Simulate door OPEN"
Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" `
    -Headers @{ "X-Internal-Api-Key" = $InternalApiKey } -Body @{
    sessionId = $sid; deviceId = $DeviceId; doorState = "OPEN"
    timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
} | Out-Null

Write-Host "==> 3. Slot-level gravity (+3 on A1)"
$slots = Invoke-Api -Method GET -Path "/api/v2/ops/admin/devices/${DeviceId}/slots" -Headers $opsAuth
$a1 = $slots | Where-Object { $_.slotCode -eq "A1" } | Select-Object -First 1
if (-not $a1) { throw "slot A1 not configured" }
$skuId = $a1.assignedSkuId
Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/gravity-deltas" `
    -Headers @{ "X-Internal-Api-Key" = $InternalApiKey } -Body @{
    sessionId = $sid; deviceId = $DeviceId
    deltas = @(@{ skuId = $skuId; delta = 3; slotId = "A1" })
} | Out-Null

Write-Host "==> 4. Door CLOSED (gravity snapshot, no vision)"
Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" `
    -Headers @{ "X-Internal-Api-Key" = $InternalApiKey } -Body @{
    sessionId = $sid; deviceId = $DeviceId; doorState = "CLOSED"
    timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    gravityDeltasJson = (@(@{ skuId = $skuId; delta = 3; slotId = "A1" }) | ConvertTo-Json -Compress)
} | Out-Null

$final = $null
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 1
    $final = (Invoke-Api -Method GET -Path "/api/v2/sessions/$sid" -Headers $opsAuth).state
    if ($final -eq "COMPLETED") { break }
}
if ($final -ne "COMPLETED") { throw "restock session not completed, state=$final" }
Write-Host "    session COMPLETED"

Write-Host "==> 5. Verify slot A1 physical updated"
$detail = Invoke-Api -Method GET -Path "/api/v2/ops/admin/devices/${DeviceId}/detail" -Headers $opsAuth
$slotA1 = $detail.slots | Where-Object { $_.slotCode -eq "A1" } | Select-Object -First 1
Write-Host "    A1 book=$($slotA1.bookQty) physical=$($slotA1.lastPhysicalQty)"
if ($null -eq $slotA1.lastPhysicalQty) { throw "A1 lastPhysicalQty not set" }
$expected = [Math]::Max(0, $slotA1.bookQty)
if ($slotA1.lastPhysicalQty -lt $expected) {
    throw "physical qty lower than book (book=$($slotA1.bookQty) physical=$($slotA1.lastPhysicalQty))"
}

Write-Host ""
Write-Host "Restock snapshot E2E PASSED" -ForegroundColor Green
