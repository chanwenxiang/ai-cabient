# Phase B E2E: 仓库入库 → 规划路线出库 → 补货上架 → 柜内商品 API

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OpsPhone = "",
    [string]$ConsumerPhone = "",
    [string]$DeviceId = "",
    [string]$SkuId = "",
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [string]$SmsMockUrl = "http://localhost:8099",
    [string]$DevMockCode = "123456",
    [switch]$ForceDevMock
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\e2e-sms-auth.ps1"

$demoCtx = & "$PSScriptRoot\seed-demo-data.ps1" -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
if (-not $OpsPhone) { $OpsPhone = "13900000001" }
if (-not $ConsumerPhone) { $ConsumerPhone = $demoCtx.consumerPhone }
if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
if (-not $SkuId) { $SkuId = $demoCtx.fallbackSkuId }

function Invoke-Api {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null)
    Invoke-E2eApi -BaseUrl $BaseUrl -Method $Method -Path $Path -Headers $Headers -Body $Body
}

Write-Host "==> Phase B warehouse E2E"
Write-Host "    device=$DeviceId sku=$SkuId"

Write-Host "==> 1. Ops login (SMS or dev mock)"
$ops = Invoke-E2eFlexibleLogin -BaseUrl $BaseUrl -Phone $OpsPhone -SmsMockUrl $SmsMockUrl `
    -InternalApiKey $InternalApiKey -LoginPath "/api/v2/auth/admin-login" `
    -DevMockCode $DevMockCode -ForceDevMock:$ForceDevMock
$opsAuth = $ops.Auth

Write-Host "==> 2. Warehouse inventory before"
$invBefore = Invoke-Api -Method GET -Path "/api/v2/ops/admin/warehouse/inventory" -Headers $opsAuth
$batch = "B-E2E-WH-" + (Get-Date -Format "HHmmss")
$lotBefore = ($invBefore | Where-Object { $_.skuId -eq $SkuId } | Measure-Object -Property quantity -Sum).Sum
if ($null -eq $lotBefore) { $lotBefore = 0 }
Write-Host "    sku $SkuId total qty=$lotBefore"

Write-Host "==> 3. Warehouse inbound +20"
$expiry = (Get-Date).AddDays(60).ToString("yyyy-MM-dd")
Invoke-Api -Method POST -Path "/api/v2/ops/admin/warehouse/inbound" -Headers $opsAuth -Body @{
    warehouseId = "WH-DEMO-001"
    refNo       = "E2E-IN"
    lines       = @(@{
        skuId = $SkuId; batchNo = $batch; quantity = 20
        productionDate = (Get-Date).AddDays(-5).ToString("yyyy-MM-dd")
        expiryDate = $expiry
    })
} | Out-Null

Write-Host "==> 4. Plan route (auto outbound)"
$today = (Get-Date).ToString("yyyy-MM-dd")
$route = Invoke-Api -Method POST -Path "/api/v2/ops/admin/replenishment/plan" -Headers $opsAuth -Body @{
    routeName = "E2E-WH-$today"
    assigneeUserId = 100000001
    plannedDate = $today
    deviceIds = @($DeviceId)
    startLatitude = 31.23
    startLongitude = 121.47
}
Write-Host "    routeId=$($route.routeId)"

Write-Host "==> 5. Find outbound for route"
$outbounds = Invoke-Api -Method GET -Path "/api/v2/ops/admin/warehouse/outbounds" -Headers $opsAuth
$outbound = $outbounds | Where-Object { $_.routeId -eq $route.routeId } | Select-Object -First 1
if (-not $outbound) { throw "no outbound created for route" }
Write-Host "    outboundId=$($outbound.outboundId) lines=$(@($outbound.lines).Count)"

Write-Host "==> 6. Pick + ship outbound"
Invoke-Api -Method POST -Path "/api/v2/ops/admin/warehouse/outbounds/$($outbound.outboundId)/pick" -Headers $opsAuth | Out-Null
Invoke-Api -Method POST -Path "/api/v2/ops/admin/warehouse/outbounds/$($outbound.outboundId)/ship" -Headers $opsAuth | Out-Null
Write-Host "    shipped"

Write-Host "==> 6b. Verify in-transit on device"
$inTransit = Invoke-Api -Method GET -Path "/api/v2/ops/admin/warehouse/in-transit?deviceId=$DeviceId" -Headers $opsAuth
$transitQty = (@($inTransit) | Measure-Object -Property quantity -Sum).Sum
if ($transitQty -le 0) { throw "expected in-transit rows after ship" }
Write-Host "    in-transit rows=$(@($inTransit).Count) totalQty=$transitQty"

Write-Host "==> 7. Replenishment suggest (in-transit deducted)"
$suggest = Invoke-Api -Method GET -Path "/api/v2/ops/admin/replenishment/suggest?deviceId=$DeviceId" -Headers $opsAuth
$withTransit = @($suggest) | Where-Object { $_.inTransitQty -gt 0 } | Select-Object -First 1
if ($withTransit) {
    Write-Host "    sku=$($withTransit.skuId) inTransit=$($withTransit.inTransitQty) suggest=$($withTransit.suggestQty)"
} else {
    Write-Host "    low-stock skus=$(@($suggest).Count) (no inTransitQty on suggest rows)"
}

Write-Host "==> 8. Complete replenishment (auto lines from outbound ship)"
$taskId = $route.tasks[0].taskId
$lines = Invoke-Api -Method GET -Path "/api/v2/ops/admin/replenishment/tasks/${taskId}/lines" -Headers $opsAuth
if (-not @($lines).Count) { throw "expected auto-generated replenishment lines after ship" }
Write-Host "    auto lines=$(@($lines).Count) slot=$($lines[0].slotId) batch=$($lines[0].batchNo)"
Invoke-Api -Method POST -Path "/api/v2/ops/admin/replenishment/tasks/${taskId}/complete" -Headers $opsAuth | Out-Null
Write-Host "    replenishment task completed"

Write-Host "==> 8b. In-transit cleared after complete"
$inTransitAfter = Invoke-Api -Method GET -Path "/api/v2/ops/admin/warehouse/in-transit?deviceId=$DeviceId" -Headers $opsAuth
if (@($inTransitAfter).Count -gt 0) { throw "in-transit not cleared after complete" }
Write-Host "    in-transit cleared"

Write-Host "==> 9. Device products API (consumer SMS login)"
$consumer = Invoke-E2eFlexibleLogin -BaseUrl $BaseUrl -Phone $ConsumerPhone -SmsMockUrl $SmsMockUrl `
    -InternalApiKey $InternalApiKey -DevMockCode $DevMockCode -ForceDevMock:$ForceDevMock
$products = Invoke-Api -Method GET -Path "/api/v2/devices/${DeviceId}/products" -Headers $consumer.Auth
Write-Host "    products on cabinet=$(@($products).Count)"

Write-Host ""
Write-Host "Phase B E2E PASSED" -ForegroundColor Green
