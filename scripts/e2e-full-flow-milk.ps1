# Full business test plan: 采购 → 仓储收货 → 补货入柜 → 购物 → 结算 → 分账 (+ 层 A 脚本门禁)
# Usage: .\scripts\e2e-full-flow-milk.ps1
#        .\scripts\e2e-full-flow-milk.ps1 -SkipLayerA   # skip long fund-safety / three-end

#        .\scripts\e2e-full-flow-milk.ps1 -FromStep replenishment   # resume after interrupt

param(
    [string]$SkuId = "SKU-MILK-001",
    [string]$BatchNo = "B-WH-MILK-01",
    [string]$DeviceId = "CAB-001",
    [string]$SupplierId = "SUP-DEMO-001",
    [string]$WarehouseId = "WH-DEMO-001",
    [int]$ProcurementQty = 12,
    [ValidateSet("", "cleanup", "procurement", "replenishment", "three-end", "fund-safety", "shopping", "partial-refund", "finance", "gray", "api-tests")]
    [string]$FromStep = "",
    [switch]$SkipLayerA,
    [switch]$SkipCleanup
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl ""

$summary = @()
function Record-Step([string]$Id, [bool]$Ok, [string]$Detail = "") {
    $mark = if ($Ok) { "PASS" } else { "FAIL" }
    $script:summary += [pscustomobject]@{ Id = $Id; Result = $mark; Detail = $Detail }
    Write-Host "[$mark] $Id — $Detail"
}

Write-Host "========== Full Flow (SKU=$SkuId Device=$DeviceId) =========="
Write-Host "    BaseUrl=$BaseUrl FromStep=$FromStep"

function StepEnabled([string]$step) {
    if ([string]::IsNullOrWhiteSpace($FromStep)) { return $true }
    $order = @("cleanup", "procurement", "replenishment", "three-end", "fund-safety", "shopping", "partial-refund", "finance", "gray", "api-tests")
    $start = [array]::IndexOf($order, $FromStep)
    $idx = [array]::IndexOf($order, $step)
    if ($start -lt 0) { return $true }
    return $idx -ge $start
}

# 子脚本各自 Enter-E2eLock；编排器不再持锁，避免嵌套死锁 600s
try {
    if (-not $SkipCleanup -and (StepEnabled "cleanup")) {
        Write-Host "`n--- S-06 cleanup-test-data ---"
        & (Join-Path $PSScriptRoot "cleanup-test-data.ps1") -RestoreBalanceCents 50000
        Record-Step "S-06-cleanup" $true "blocking sessions + disputes"
    }

    if (StepEnabled "procurement") {
    Write-Host "`n--- 1. 采购下单 + 仓储收货 ---"
    try {
        $ops = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
            phoneNumber = "13900000001"; password = "123456"
        }
        $h = @{ Authorization = "Bearer $($ops.token)" }
        $line = @{
            skuId           = $SkuId
            batchNo         = $BatchNo
            orderedQty      = $ProcurementQty
            receivedQty     = 0
            unitCostCents   = 300
            productionDate  = "2026-08-01"
            expiryDate      = "2026-12-31"
        }
        $ref = "E2E-FULL-$(Get-Date -Format 'yyyyMMddHHmmss')"
        $po = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/purchase-orders" -Headers $h -Body @{
            supplierId  = $SupplierId
            warehouseId = $WarehouseId
            refNo       = $ref
            notes       = "full flow procurement"
            lines       = @($line)
        }
        $recv = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
            -Path "/api/v2/ops/admin/purchase-orders/$($po.purchaseOrderId)/receive" -Headers $h -Body @{
            lines = @(@{
                skuId          = $SkuId
                batchNo        = $BatchNo
                orderedQty     = $ProcurementQty
                receivedQty    = $ProcurementQty
                unitCostCents  = 300
                productionDate = "2026-08-01"
                expiryDate     = "2026-12-31"
            })
            notes = "e2e full flow receive"
        }
        Record-Step "1-procurement" ($recv.status -eq "RECEIVED") "PO=$($po.purchaseOrderId) status=$($recv.status)"
    } catch {
        Record-Step "1-procurement" $false $_.Exception.Message
    }
    }

    if (StepEnabled "replenishment") {
    Write-Host "`n--- 2. 仓储出库 + 商户补货入柜 ---"
    try {
        $opsPrep = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
            phoneNumber = "13900000001"; password = "123456"
        }
        Prepare-E2eReplenishmentPlan -BaseUrl $BaseUrl -OpsAuth @{ Authorization = "Bearer $($opsPrep.token)" } -DeviceId $DeviceId -WarehouseId $WarehouseId
        & (Join-Path $PSScriptRoot "e2e-replenishment.ps1") -SkuId $SkuId -Quantity 10 -DeviceId $DeviceId
        Record-Step "2-replenishment" $true "warehouse outbound + merchant restock"
    } catch {
        Record-Step "2-replenishment" $false $_.Exception.Message
    }
    }

    if (-not $SkipLayerA -and (StepEnabled "three-end")) {
        Write-Host "`n--- 3. 三端回归 (e2e-three-end) ---"
        try {
            & (Join-Path $PSScriptRoot "e2e-three-end.ps1") -SkuId $SkuId -DeviceId $DeviceId -IncludeHappyPath
            Record-Step "3-three-end" $true "34 checks"
        } catch {
            Record-Step "3-three-end" $false $_.Exception.Message
        }
    }

    if (-not $SkipLayerA -and (StepEnabled "fund-safety")) {
        Write-Host "`n--- S-02 fund-safety ---"
        try {
            & (Join-Path $PSScriptRoot "e2e-fund-safety.ps1")
            Record-Step "S-02-fund-safety" $true
        } catch {
            Record-Step "S-02-fund-safety" $false $_.Exception.Message
        }
    }

    if (StepEnabled "shopping") {
    Write-Host "`n--- 4. 购物结算 (balance) ---"
    try {
        & (Join-Path $PSScriptRoot "e2e-shopping.ps1") -Channel BALANCE -DeviceId $DeviceId -Phone "13800138000"
        Record-Step "4-shopping" $true "BALANCE channel"
    } catch {
        Record-Step "4-shopping" $false $_.Exception.Message
    }
    }

    if (StepEnabled "partial-refund") {
    Write-Host "`n--- 5. 部分退款行级 ---"
    try {
        & (Join-Path $PSScriptRoot "e2e-partial-refund-line.ps1")
        Record-Step "5-partial-refund" $true
    } catch {
        Record-Step "5-partial-refund" $false $_.Exception.Message
    }
    }

    if (StepEnabled "finance") {
    Write-Host "`n--- 6. 结算/分账/流水号 API 核验 ---"
    try {
        $ops = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
            phoneNumber = "13900000001"; password = "123456"
        }
        $h = @{ Authorization = "Bearer $($ops.token)" }
        $recon = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/reconciliation?page=0&size=5" -Headers $h
        $splits = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/merchants/revenue-splits?page=0&size=3" -Headers $h
        $orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/orders?page=0&size=3&payChannel=BALANCE" -Headers $h
        $numericFlow = $true
        foreach ($o in $orders.items) {
            if ($o.paymentOperationId -and $o.paymentOperationId -notmatch '^\d+$') { $numericFlow = $false }
        }
        Record-Step "6-reconciliation" ($recon.items.Count -ge 1) "batches=$($recon.items.Count)"
        Record-Step "6-splits" ($splits.items.Count -ge 1) "count=$($splits.items.Count)"
        Record-Step "6-flow-numeric" $numericFlow "recent balance orders"
    } catch {
        Record-Step "6-finance-api" $false $_.Exception.Message
    }
    }

    if (StepEnabled "gray") {
    Write-Host "`n--- S-01 gray CheckOnly (dev, non-blocking) ---"
    try {
        & (Join-Path $PSScriptRoot "phase-f-gray-launch.ps1") -CheckOnly -BaseUrl $BaseUrl -VisionHealthUrl "http://localhost:18082/health"
        Record-Step "S-01-gray-check" $true "see log for dev FAIL items"
    } catch {
        Record-Step "S-01-gray-check" $false $_.Exception.Message
    }
    }

    if (StepEnabled "api-tests") {
    Write-Host "`n--- S-05 run-api-tests ---"
    try {
        & (Join-Path $PSScriptRoot "run-api-tests.ps1")
        Record-Step "S-05-api-tests" $true
    } catch {
        Record-Step "S-05-api-tests" $false $_.Exception.Message
    }
    }

} catch {
    Write-Error $_
}

Write-Host ""
Write-Host "========== FULL FLOW SUMMARY =========="
$summary | Format-Table -AutoSize
$fail = @($summary | Where-Object { $_.Result -eq "FAIL" }).Count
$pass = @($summary | Where-Object { $_.Result -eq "PASS" }).Count
Write-Host "PASS=$pass FAIL=$fail"
if ($fail -gt 0) { exit 1 }
exit 0
