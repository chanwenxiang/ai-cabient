# Replenishment E2E — plan → (optional warehouse pick/ship) → check-in → open-door → complete
# Usage:
#   .\scripts\e2e-replenishment.ps1
#   .\scripts\e2e-replenishment.ps1 -BaseUrl http://localhost:18080
#   .\scripts\e2e-replenishment.ps1 -FieldOnly   # 现场补货线；若 plan 已挂出库单仍须 pick/ship，否则 complete 409
#
# 注意：-FieldOnly 不是「跳过出库」，而是「允许在无计划明细时提交现场 RESTOCK」。
# 运营 plan 常会同步生成 outbound；完成任务前必须把关联出库单发运。

param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "CAB-001",
    [string]$OpsPhone = "13900000001",
    [string]$OpsPassword = "123456",
    [string]$MerchantPhone = "13800138001",
    [string]$MerchantPassword = "123456",
    [long]$MerchantUserId = 100000002,
    [string]$SkuId = "SKU-DEMO-001",
    [string]$SlotId = "A1",
    [int]$Quantity = 1,
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [switch]$FieldOnly
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl

function Invoke-E2eRestockDoorCycle {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [string]$DeviceId,
        [string]$InternalApiKey
    )
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
        sessionId = $SessionId
        deviceId  = $DeviceId
        doorState = "OPEN"
        timestamp = $ts
    } | Out-Null
    Start-Sleep -Milliseconds 400
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
        sessionId    = $SessionId
        deviceId     = $DeviceId
        doorState    = "CLOSED"
        timestamp    = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        uploadStatus = "UPLOADED"
        videoUri     = "minio://cabinet-videos/$(Get-E2eSimVideoKey -SessionId $SessionId -DeviceId $DeviceId)"
    } | Out-Null
}

Write-Host "==> 0. Cleanup blocking sessions on $DeviceId"
Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null

Write-Host "==> 1. Ops login ($OpsPhone)"
$opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
    phoneNumber = $OpsPhone
    password    = $OpsPassword
}
$opsAuth = @{ Authorization = "Bearer $($opsLogin.token)" }
$opsUserId = [long]$opsLogin.userId
Write-Host "    opsUserId=$opsUserId"

Write-Host "==> 2. Merchant login ($MerchantPhone)"
$mchLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
    phoneNumber = $MerchantPhone
    password    = $MerchantPassword
}
$mchAuth = @{ Authorization = "Bearer $($mchLogin.token)" }
if (-not $MerchantUserId -or $MerchantUserId -le 0) {
    $MerchantUserId = [long]$mchLogin.userId
}
Write-Host "    merchantUserId=$MerchantUserId"

$today = (Get-Date).ToString("yyyy-MM-dd")
$routeName = "E2E replenishment $today $([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"

Write-Host "==> 2b. Ensure warehouse stock for device replenishment gaps"
Prepare-E2eReplenishmentPlan -BaseUrl $BaseUrl -OpsAuth $opsAuth -DeviceId $DeviceId

Write-Host "==> 3. Plan replenishment route for $DeviceId"
$route = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/replenishment/plan" -Headers $opsAuth -Body @{
    routeName       = $routeName
    plannedDate     = $today
    assigneeUserId  = $MerchantUserId
    deviceIds       = @($DeviceId)
    startLatitude   = $null
    startLongitude  = $null
}
$routeId = $route.routeId
$task = @($route.tasks) | Where-Object { $_.deviceId -eq $DeviceId } | Select-Object -First 1
if (-not $task) {
    throw "Plan created route=$routeId but no task for $DeviceId"
}
$taskId = [long]$task.taskId
Write-Host "    routeId=$routeId taskId=$taskId status=$($task.status)"

$outboundId = $null
$useWarehouse = $false
# plan 常会同步生成出库单；即使 -FieldOnly 也必须先发运，否则 complete 会 409
Write-Host "==> 4. Resolve warehouse outbound (if any)$(if ($FieldOnly) { ' [FieldOnly: still ship when linked]' })"
try {
    $outbounds = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/warehouse/outbounds" -Headers $opsAuth
    $linked = @($outbounds) | Where-Object { $_.routeId -eq $routeId } | Select-Object -First 1
    if (-not $linked) {
        Write-Host "    no outbound for route — field restock path"
    } else {
        $useWarehouse = $true
        $outboundId = [long]$linked.outboundId
        Write-Host "    outboundId=$outboundId status=$($linked.status)"
        $st = [string]$linked.status
        if ($st -notin @("PICKED", "SHIPPED", "IN_TRANSIT", "RECEIVED", "COMPLETED")) {
            Write-Host "==> 4b. Pick outbound (was $st)"
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path "/api/v2/ops/admin/warehouse/outbounds/$outboundId/pick" -Headers $opsAuth | Out-Null
            $st = "PICKED"
        }
        if ($st -notin @("SHIPPED", "IN_TRANSIT", "RECEIVED", "COMPLETED")) {
            Write-Host "==> 4c. Ship outbound"
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path "/api/v2/ops/admin/warehouse/outbounds/$outboundId/ship" -Headers $opsAuth | Out-Null
        }
    }
} catch {
    if (-not $FieldOnly) { throw }
    Write-Warning "Warehouse path failed, continuing field restock: $_"
    $useWarehouse = $false
    $outboundId = $null
}

Write-Host "==> 5. Merchant check-in (no geo)"
$checked = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
    -Path "/api/v2/merchant/replenishment/tasks/$taskId/check-in" -Headers $mchAuth -Body @{}
Write-Host "    status=$($checked.status) checkInAt=$($checked.checkInAt)"

Write-Host "==> 6. Merchant open-door"
$session = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
    -Path "/api/v2/merchant/replenishment/tasks/$taskId/open-door" -Headers $mchAuth
$sessionId = $session.sessionId
if (-not $sessionId) { throw "open-door returned empty sessionId" }
Write-Host "    sessionId=$sessionId state=$($session.state)"

Write-Host "==> 7. Restock door OPEN → CLOSED (no consumer bill)"
Invoke-E2eRestockDoorCycle -BaseUrl $BaseUrl -SessionId $sessionId -DeviceId $DeviceId -InternalApiKey $InternalApiKey

$lines = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
    -Path "/api/v2/merchant/replenishment/tasks/$taskId/lines" -Headers $mchAuth)
Write-Host "==> 8. Task lines count=$($lines.Count)"

if ($lines.Count -eq 0) {
    $pickSku = $null
    $pickSlot = $null
    try {
        $slots = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/ops/admin/devices/$DeviceId/slots" -Headers $opsAuth)
        $room = $slots | Where-Object {
            $_.enabled -and $_.assignedSkuId -and ([int]$_.maxLevel - [int]$_.bookQty) -ge 1
        } | Sort-Object { [int]$_.maxLevel - [int]$_.bookQty } -Descending | Select-Object -First 1
        if ($room) {
            $pickSku = [string]$room.assignedSkuId
            $pickSlot = [string]$room.slotCode
            Write-Host "    auto-picked slot=$pickSlot sku=$pickSku headroom=$([int]$room.maxLevel - [int]$room.bookQty)"
        }
    } catch {
        Write-Warning "Slot lookup failed: $_"
    }

    if (-not $pickSku -and $SkuId -and $SlotId) {
        $pickSku = $SkuId
        $pickSlot = $SlotId
        Write-Host "    fallback to -SkuId/-SlotId params: slot=$pickSlot sku=$pickSku"
    }

    if ($pickSku -and $pickSlot) {
        $expiry = (Get-Date).AddMonths(6).ToString("yyyy-MM-dd")
        $qty = [Math]::Max(1, $Quantity)
        Write-Host "    submitting field RESTOCK line sku=$pickSku slot=$pickSlot qty=$qty expiry=$expiry"
        $json = @"
{"lines":[{"lineType":"RESTOCK","skuId":"$pickSku","batchNo":"E2E-$today","productionDate":null,"expiryDate":"$expiry","quantity":$qty,"slotId":"$pickSlot","applied":false}]}
"@
        $uri = "$BaseUrl/api/v2/merchant/replenishment/tasks/$taskId/lines"
        $resp = Invoke-RestMethod -Method POST -Uri $uri -Headers $mchAuth `
            -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
        if ($resp.code -ne 0) { throw "API error: $($resp.message) (path=/lines)" }
    } else {
        Write-Warning "No slot with headroom on $DeviceId; completing without RESTOCK lines"
    }
} else {
    Write-Host "    warehouse lines already present — skip re-submit"
}

Write-Host "==> 9. Complete task"
$done = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
    -Path "/api/v2/merchant/replenishment/tasks/$taskId/complete" -Headers $mchAuth
if ($done.status -ne "COMPLETED") {
    throw "Expected task COMPLETED, got $($done.status)"
}
Write-Host "    taskId=$taskId COMPLETED outboundId=$($done.outboundId)"

Write-Host "==> 10. Assert merchant task list reflects completion"
$tasks = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
    -Path "/api/v2/merchant/replenishment/tasks?status=COMPLETED" -Headers $mchAuth
$found = @($tasks) | Where-Object { [long]$_.taskId -eq $taskId } | Select-Object -First 1
if (-not $found) {
    throw "Completed task $taskId not found in merchant COMPLETED list"
}

Write-Host ""
$pathLabel = if ($useWarehouse -and $outboundId) {
    if ($FieldOnly) { "field+shipped-outbound" } else { "warehouse" }
} else {
    "field-only"
}
Write-Host "OK replenishment E2E passed ($pathLabel) taskId=$taskId sessionId=$sessionId"
exit 0
