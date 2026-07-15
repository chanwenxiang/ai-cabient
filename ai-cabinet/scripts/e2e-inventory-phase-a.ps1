# Phase A E2E: replenishment lines, FEFO lots, expiry stats, MQTT shopping

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OpsPhone = "",
    [string]$ConsumerPhone = "",
    [string]$DeviceId = "",
    [string]$SkuId = "",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$SmsMockUrl = "http://localhost:8099",
    [string]$MqttBroker = "tcp://localhost:11883",
    [string]$DevMockCode = "123456",
    [switch]$ForceDevMock,
    [switch]$UseInternalDoor
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot\e2e-sms-auth.ps1"
. "$PSScriptRoot\e2e-door-flow.ps1"

$demoCtx = & "$PSScriptRoot\seed-demo-data.ps1" -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
if (-not $OpsPhone) { $OpsPhone = "13900000001" }
if (-not $ConsumerPhone) { $ConsumerPhone = $demoCtx.consumerPhone }
if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
if (-not $SkuId) { $SkuId = "SKU-DEMO-001" }

function Get-E2eSlotForSku {
    param([string]$Sku, [hashtable]$Auth)
    $slots = Invoke-Api -Method GET -Path "/api/v2/ops/admin/devices/${DeviceId}/slots" -Headers $Auth
    $match = @($slots | Where-Object { $_.assignedSkuId -eq $Sku -and $_.enabled } | Select-Object -First 1)
    if ($match.Count -lt 1) { throw "no planogram slot for sku $Sku on $DeviceId" }
    return $match[0]
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        $Body = $null
    )
    Invoke-E2eApi -BaseUrl $BaseUrl -Method $Method -Path $Path -Headers $Headers -Body $Body
}

function Invoke-Internal {
    param([string]$Path, $Body)
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    Invoke-Api -Method POST -Path $Path -Headers $headers -Body $Body
}

Write-Host "==> Phase A inventory E2E"
Write-Host "    base=$BaseUrl device=$DeviceId sku=$SkuId"

Write-Host "==> 1. Ops login (SMS or dev mock)"
$ops = Invoke-E2eFlexibleLogin -BaseUrl $BaseUrl -Phone $OpsPhone -SmsMockUrl $SmsMockUrl `
    -InternalApiKey $InternalApiKey -LoginPath "/api/v2/auth/admin-login" `
    -DevMockCode $DevMockCode -ForceDevMock:$ForceDevMock
$opsAuth = $ops.Auth
Write-Host "    opsUserId=$($ops.UserId)"

Write-Host "==> 2. Configure SKU shelf-life"
Invoke-Api -Method PUT -Path "/api/v2/ops/admin/skus/$SkuId" -Headers $opsAuth -Body @{
    skuId                     = $SkuId
    skuName                   = "演示商品-可乐"
    priceCents                = 350
    status                    = "ACTIVE"
    visionEnabled             = $true
    shelfLifeDays             = 180
    nearExpiryDays            = 7
    blockSaleDaysBeforeExpiry = 0
    storageType               = "AMBIENT"
} | Out-Null
Write-Host "    SKU shelf-life OK"

Write-Host "==> 3. Create replenishment route + task"
$today = (Get-Date).ToString("yyyy-MM-dd")
$route = Invoke-Api -Method POST -Path "/api/v2/ops/admin/replenishment/routes" -Headers $opsAuth -Body @{
    routeName       = "E2E-PhaseA-$today"
    assigneeUserId  = 100000001
    plannedDate     = $today
    tasks           = @(@{ deviceId = $DeviceId; status = "PENDING" })
}
$taskId = $route.tasks[0].taskId
Write-Host "    taskId=$taskId"

$slot = Get-E2eSlotForSku -Sku $SkuId -Auth $opsAuth
$slotId = $slot.slotCode
$maxLevel = if ($slot.maxLevel -gt 0) { $slot.maxLevel } else { 8 }
$room = $maxLevel - $slot.bookQty
$restockQty = [Math]::Min(8, [Math]::Max(0, $room))
Write-Host "    planogram slot=$slotId book=$($slot.bookQty) max=$maxLevel restockQty=$restockQty"

$batchNo = $null
if ($restockQty -ge 1) {
    Write-Host "==> 4. Submit replenishment lines (RESTOCK batch)"
    $batchNo = "B-E2E-" + (Get-Date -Format "yyyyMMddHHmmss")
    $expiry = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
    $prod = (Get-Date).AddDays(-10).ToString("yyyy-MM-dd")
    $lines = Invoke-Api -Method POST -Path "/api/v2/ops/admin/replenishment/tasks/${taskId}/lines" -Headers $opsAuth -Body @{
        lines = @(
            @{
                lineType        = "RESTOCK"
                skuId           = $SkuId
                batchNo         = $batchNo
                productionDate  = $prod
                expiryDate      = $expiry
                quantity        = $restockQty
                slotId          = $slotId
            }
        )
    }
    if (@($lines).Count -lt 1) { throw "expected >=1 line, got @($lines).Count" }
    Write-Host "    batch=$batchNo expiry=$expiry qty=$restockQty"

    Write-Host "==> 5. Complete replenishment task"
    $completed = Invoke-Api -Method POST -Path "/api/v2/ops/admin/replenishment/tasks/${taskId}/complete" -Headers $opsAuth
    if ($completed.status -ne "COMPLETED") { throw "task not completed: $($completed.status)" }
    Write-Host "    task completed"
} else {
    Write-Host "==> 4-5. Skip RESTOCK (slot full); use existing lots for FEFO check"
}

Write-Host "==> 6. Verify device lots"
$lots = Invoke-Api -Method GET -Path "/api/v2/ops/admin/devices/${DeviceId}/lots" -Headers $opsAuth
if ($batchNo) {
    $lot = $lots | Where-Object { $_.batchNo -eq $batchNo } | Select-Object -First 1
} else {
    $lot = $lots | Where-Object { $_.skuId -eq $SkuId -and $_.quantity -gt 0 } | Select-Object -First 1
    if ($lot) { $batchNo = $lot.batchNo }
}
if (-not $lot) { throw "lot not found for sku $SkuId" }
if ($lot.quantity -lt 1) { throw "lot qty expected >=1, got $($lot.quantity)" }
Write-Host "    lot batch=$batchNo qty=$($lot.quantity) status=$($lot.status)"

Write-Host "==> 7. Verify aggregate inventory"
$inv = Invoke-Api -Method GET -Path "/api/v2/ops/admin/inventory?deviceId=$DeviceId" -Headers $opsAuth
$skuInv = $inv | Where-Object { $_.skuId -eq $SkuId } | Select-Object -First 1
if (-not $skuInv -or $skuInv.quantity -lt 1) {
    throw "inventory expected >=1, got $($skuInv.quantity)"
}
Write-Host "    inventory qty=$($skuInv.quantity)"

Write-Host "==> 8. Verify dashboard expiry stats fields"
$stats = Invoke-Api -Method GET -Path "/api/v2/ops/admin/stats" -Headers $opsAuth
if ($null -eq $stats.nearExpiryLotCount) { throw "stats missing nearExpiryLotCount" }
if ($null -eq $stats.expiredLotCount) { throw "stats missing expiredLotCount" }
if ($null -eq $stats.pullOffOpenCount) { throw "stats missing pullOffOpenCount" }
Write-Host "    nearExpiry=$($stats.nearExpiryLotCount) expired=$($stats.expiredLotCount) pullOff=$($stats.pullOffOpenCount)"

Write-Host "==> 9. Consumer shopping (FEFO deduct)"
& (Join-Path $PSScriptRoot "e2e-cleanup-device.ps1") -DeviceId $DeviceId | Out-Null
$consumer = Invoke-E2eFlexibleLogin -BaseUrl $BaseUrl -Phone $ConsumerPhone -SmsMockUrl $SmsMockUrl `
    -InternalApiKey $InternalApiKey -DevMockCode $DevMockCode -ForceDevMock:$ForceDevMock
$consumerAuth = $consumer.Auth
$lotQtyBefore = $lot.quantity

$simProc = $null
$startedSimulator = $false
try {
    if ($UseInternalDoor) {
        $session = Invoke-Api -Method POST -Path "/api/v2/sessions" -Headers $consumerAuth -Body @{ deviceId = $DeviceId }
        $sessionId = $session.sessionId
        Invoke-E2eInternalDoorShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -SessionId $sessionId `
            -InternalApiKey $InternalApiKey -ScriptsDir $PSScriptRoot
    } else {
        Write-Host "    door path=mqtt"
        $env:AICABINET_SIM_GRAVITY_SKU = $SkuId
        $env:AICABINET_SIM_GRAVITY_SLOT = $slotId
        $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $consumerAuth `
            -RepoRoot $Root -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey
        $sessionId = $mqtt.SessionId
        $startedSimulator = $mqtt.SimulatorStarted
        $simProc = $mqtt.SimulatorProcess
    }

    $order = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $sessionId -Auth $consumerAuth -MaxPolls 45
    Write-Host "    order=$($order.orderId) total=$($order.totalAmountCents)"
} finally {
    if ($startedSimulator) {
        Stop-E2eDeviceSimulator $simProc
    }
}

Write-Host "==> 10. Verify lot deducted after sale"
Start-Sleep -Seconds 2
$lotsAfter = Invoke-Api -Method GET -Path "/api/v2/ops/admin/devices/${DeviceId}/lots" -Headers $opsAuth
$lotAfter = $lotsAfter | Where-Object { $_.batchNo -eq $batchNo } | Select-Object -First 1
if (-not $lotAfter) { throw "lot missing after sale" }
if ($lotAfter.quantity -ge $lotQtyBefore) {
    throw "FEFO deduct failed: before=$lotQtyBefore after=$($lotAfter.quantity)"
}
Write-Host "    lot qty after sale=$($lotAfter.quantity) (was $lotQtyBefore)"

$invAfter = Invoke-Api -Method GET -Path "/api/v2/ops/admin/inventory?deviceId=$DeviceId" -Headers $opsAuth
$skuInvAfter = $invAfter | Where-Object { $_.skuId -eq $SkuId } | Select-Object -First 1
if ($skuInvAfter.quantity -ge $skuInv.quantity) {
    throw "aggregate inventory not deducted"
}
Write-Host "    inventory after=$($skuInvAfter.quantity)"

Write-Host ""
Write-Host "Phase A E2E PASSED" -ForegroundColor Green
