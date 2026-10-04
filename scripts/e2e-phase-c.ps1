# Phase C E2E: COGS, write-off, stocktake, GPS check-in

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$OpsPhone = "",
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
if (-not $DeviceId) { $DeviceId = $demoCtx.deviceId }
if (-not $SkuId) { $SkuId = $demoCtx.fallbackSkuId }

function Invoke-Api {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null)
    Invoke-E2eApi -BaseUrl $BaseUrl -Method $Method -Path $Path -Headers $Headers -Body $Body
}

Write-Host "==> Phase C finance E2E"
& (Join-Path $PSScriptRoot "e2e-cleanup-device.ps1") -DeviceId $DeviceId | Out-Null

Write-Host "==> Ops login (SMS or dev mock)"
$ops = Invoke-E2eFlexibleLogin -BaseUrl $BaseUrl -Phone $OpsPhone -SmsMockUrl $SmsMockUrl `
    -InternalApiKey $InternalApiKey -LoginPath "/api/v2/auth/admin-login" `
    -DevMockCode $DevMockCode -ForceDevMock:$ForceDevMock
$oAuth = $ops.Auth

Write-Host "==> 1. Finance stats"
$fin = Invoke-Api GET "/api/v2/ops/admin/finance/stats" $oAuth
Write-Host "    revenueToday=$($fin.revenueTodayCents) cogsToday=$($fin.cogsTodayCents) margin=$($fin.grossMarginTodayCents)"

Write-Host "==> 2. Plan route + check-in (GPS near CAB-001)"
$route = Invoke-Api POST "/api/v2/ops/admin/replenishment/plan" $oAuth @{
    routeName = "Phase-C-E2E"
    assigneeUserId = $ops.UserId
    deviceIds = @($DeviceId)
}
$taskId = $route.tasks[0].taskId
$checked = Invoke-Api POST "/api/v2/ops/admin/replenishment/tasks/$taskId/check-in" $oAuth @{
    latitude = 31.2304
    longitude = 121.4737
}
if (-not $checked.checkInAt) { throw "check-in failed" }
Write-Host "    taskId=$taskId checkInAt=$($checked.checkInAt)"

Write-Host "==> 3. Stocktake adjust"
$lots = Invoke-Api GET "/api/v2/ops/admin/devices/$DeviceId/lots" $oAuth
$lot = @($lots) | Where-Object { $_.skuId -eq $SkuId } | Select-Object -First 1
$counted = if ($lot) { [Math]::Max($lot.quantity, 1) } else { 5 }
$adj = Invoke-Api POST "/api/v2/ops/admin/inventory/stocktake" $oAuth @{
    deviceId = $DeviceId
    skuId = $SkuId
    countedQuantity = $counted
    note = "phase-c-e2e"
}
Write-Host "    inventory qty=$($adj.quantity)"

Write-Host "==> 4. Write-off 1 unit"
$batch = if ($lot -and $lot.batchNo) { $lot.batchNo } else { $null }
$wo = Invoke-Api POST "/api/v2/ops/admin/inventory/write-off" $oAuth @{
    deviceId = $DeviceId
    skuId = $SkuId
    batchNo = $batch
    quantity = 1
    reason = "DAMAGED"
}
Write-Host "    writeOffId=$($wo.writeOffId) costCents=$($wo.costCents)"

Write-Host "==> 5. Finance stats after write-off"
$fin2 = Invoke-Api GET "/api/v2/ops/admin/finance/stats" $oAuth
Write-Host "    writeOffToday=$($fin2.writeOffTodayCents) qty=$($fin2.writeOffTodayQty)"

Write-Host ""
Write-Host "Phase C E2E PASSED" -ForegroundColor Green
