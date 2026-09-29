# Full business test plan: 采购 → 仓储收货 → 补货入柜 → 购物 → 结算 → 分账 (+ 层 A 脚本门禁)
# Usage: .\scripts\e2e-full-flow-milk.ps1
#        .\scripts\e2e-full-flow-milk.ps1 -SkipLayerA   # skip long fund-safety / three-end

#        .\scripts\e2e-full-flow-milk.ps1 -FromStep replenishment   # resume after interrupt

param(
    [string]$SkuId = "",
    [string]$BatchNo = "",
    [string]$DeviceId = "",
    [string]$SupplierId = "",
    [string]$WarehouseId = "",
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
# 注意：设备/仓/供应商/SKU 的运行时 Resolve 延迟到 cleanup+demo-ensure 之后执行
# （WipePlatform 会清空台子，先 resolve 必然空库失败）。

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
        # S0→S1 衔接：WipePlatform 清空台子后，直连 trade 重建演示业务上下文
        # （柜/商户/仓/SKU/供应商/货道绑定/商户门户绑定/演示柜坐标）。
        # 内部端点必须绕网关直连 :18080（网关对 /internal/ 一律 403）。
        Write-Host "`n--- S-06b demo ensure (rebuild S1 stage, direct trade) ---"
        $ctx = Invoke-RestMethod -Method POST -Uri "http://localhost:18080/internal/v1/demo/ensure" `
            -Headers @{ "X-Internal-Api-Key" = "dev-internal-key-change-me" } -TimeoutSec 300
        $ensuredDevice = [string]$ctx.data.deviceId
        if ([string]::IsNullOrWhiteSpace($ensuredDevice)) { throw "demo/ensure 未返回 deviceId" }
        Write-Host "    ensured device=$ensuredDevice skus=$($ctx.data.skuCount) inventoryLines=$($ctx.data.deviceInventoryLines)"
        Record-Step "S-06b-demo-ensure" $true "rebuild S1 stage after wipe"

        # 模拟器容器对齐 ensured 柜：容器命令行写死的柜号在 wipe 后与新建柜不一致，
        # 不重对齐则 open-door 指令发到无订阅主题 → 409。容器可能在清数时被停——
        # 无论 status 是否可达，都无条件按 ensured 柜重建。
        Write-Host "    simulator re-target → $ensuredDevice"
        # rm -f = 停止+删除一条命令；PS5.1 下原生 stderr 在 EAP=Stop 会变终止错误，必须吞掉
        try { docker rm -f ai-cabinet-device-simulator-1 2>$null | Out-Null } catch { }
        try {
            docker run -d --name ai-cabinet-device-simulator-1 --network ai-cabinet_default `
            -e MQTT_USERNAME=aicabinet-device -e MQTT_PASSWORD=dev-mqtt-device-pass `
            -e TRADE_SERVICE_URL=http://trade-service:8080 `
            -e INTERNAL_API_KEY=dev-internal-key-change-me `
            -e MINIO_ENDPOINT=http://minio:9000 -e MINIO_ACCESS_KEY=minioadmin `
            -e MINIO_SECRET_KEY=minioadmin -e MINIO_BUCKET=cabinet-videos `
            -e AICABINET_SIM_SHOPPING_MS=0 `
            ai-cabinet-device-simulator $ensuredDevice tcp://emqx:1883 | Out-Null
        } catch {
            throw "simulator container recreate failed: $_"
        }
        # 等模拟器真上线（MQTT 握手完成后 device_info.online_status=ONLINE）。
        # 固定 sleep 6s 冷启动时不够 ⇒ 补货 open-door 409「设备不在线」（2026-09-29 两连败，
        # 跳过 ensure 重建的 -FromStep 复跑必过 ⇒ 竞态坐实）。轮询最长 90s。
        $pgContainer = Get-E2ePostgresContainer
        $onlineDeadline = (Get-Date).AddSeconds(90)
        do {
            Start-Sleep -Seconds 3
            $onlineStatus = (docker exec $pgContainer psql -U aicabinet -d aicabinet -t -A -c `
                "SELECT COALESCE(UPPER(online_status),'OFFLINE') FROM device_info WHERE device_id='$ensuredDevice';" 2>$null)
            if ([string]::IsNullOrWhiteSpace($onlineStatus)) { $onlineStatus = 'UNKNOWN' }
        } while ($onlineStatus -ne 'ONLINE' -and (Get-Date) -lt $onlineDeadline)
        if ($onlineStatus -ne 'ONLINE') {
            throw "simulator not ONLINE after 90s (device=$ensuredDevice last=$onlineStatus)"
        }
        Write-Host "    simulator ONLINE"
    }

    # 清 ensured 柜的阻塞会话：前轮购物可能留下 WAITING_UPLOAD 占用会话，
    # 不清则补货 open-door 409「设备使用中」。
    Clear-E2eDeviceBlockingSessions -DeviceId $ensuredDevice | Out-Null
    Write-Host "    cleared blocking sessions on $ensuredDevice"

    # 限流计数清理：反复跑全链会把 session-create/open-door 的 20・5/小时配额打满 → 429 假红
    docker exec ai-cabinet-redis-1 redis-cli --no-auth-warning -a devredis --scan --pattern "aicabinet:rate:*" 2>$null | ForEach-Object {
        docker exec ai-cabinet-redis-1 redis-cli --no-auth-warning -a devredis DEL $_ | Out-Null
    }
    Write-Host "    rate-limit counters cleared"

    # S1 台子就绪后运行时解析测试实体（禁写死柜/商户/SKU，lessons #212-#215）
    $DeviceId = Resolve-E2eTestDevice -DeviceId $DeviceId -UnlockSales
    $WarehouseId = Resolve-E2eTestWarehouse -WarehouseId $WarehouseId
    $SupplierId = Resolve-E2eTestSupplier -SupplierId $SupplierId
    if ([string]::IsNullOrWhiteSpace($SkuId)) { $SkuId = Resolve-E2eTestSku -DeviceId $DeviceId }
    if ([string]::IsNullOrWhiteSpace($BatchNo)) { $BatchNo = Resolve-E2eTestBatch -SkuId $SkuId -WarehouseId $WarehouseId }

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
        # V228+：采购单先 PENDING_APPROVAL，须按节点处理人审批至 CREATED 才能收货
        $poId = $po.purchaseOrderId
        $poStatus = [string]$po.status
        $reviewPhones = @("13900000001", "13900000002") # PROCUREMENT / FINANCE demo accounts
        for ($approveAttempt = 0; $approveAttempt -lt 6 -and $poStatus -eq "PENDING_APPROVAL"; $approveAttempt++) {
            $advanced = $false
            foreach ($phone in $reviewPhones) {
                try {
                    $reviewer = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
                        phoneNumber = $phone; password = "123456"
                    }
                    $rh = @{ Authorization = "Bearer $($reviewer.token)" }
                    $po = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                        -Path "/api/v2/ops/admin/purchase-orders/$poId/review" -Headers $rh -Body @{
                        approve = $true
                        remark  = "e2e full flow approve"
                    }
                    $poStatus = [string]$po.status
                    Write-Host "    PO=$poId reviewed by $phone -> $poStatus"
                    $advanced = $true
                    break
                } catch {
                    # 非当前节点处理人则换下一账号
                }
            }
            if (-not $advanced) {
                throw "PO=$poId still PENDING_APPROVAL; no reviewer could advance"
            }
        }
        if ($poStatus -ne "CREATED" -and $poStatus -ne "PARTIAL_RECEIVED") {
            throw "PO=$poId expected CREATED after approval, got $poStatus"
        }
        $recv = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
            -Path "/api/v2/ops/admin/purchase-orders/$poId/receive" -Headers $h -Body @{
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
        Record-Step "1-procurement" ($recv.status -eq "RECEIVED") "PO=$poId status=$($recv.status)"
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
        Prepare-E2eReplenishmentPlan -BaseUrl $BaseUrl -OpsAuth @{ Authorization = "Bearer $($opsPrep.token)" } -DeviceId $DeviceId -WarehouseId $WarehouseId -ForceGap
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
        # KeepPlatform/FullBusiness 清掉对账批次后列表为空；先跑当日 BALANCE 再断言（与 full-round-t5 一致）
        $reconDate = (Get-Date).ToString("yyyy-MM-dd")
        try {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path "/api/v2/ops/admin/reconciliation/run?date=$reconDate&channel=BALANCE" `
                -Headers $h | Out-Null
        } catch {
            Write-Warning "reconciliation/run skipped: $($_.Exception.Message)"
        }
        # GET /reconciliation 返回 data=数组；Invoke-E2eApi 解包后单元素会被 PS 拆成对象，禁读 .items
        $reconBatches = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/ops/admin/reconciliation?page=0&size=5" -Headers $h |
            Where-Object { $null -ne $_ })
        $splits = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/merchants/revenue-splits?page=0&size=3" -Headers $h
        $orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/ops/admin/orders?page=0&size=3&payChannel=BALANCE" -Headers $h
        $numericFlow = $true
        foreach ($o in @($orders.items)) {
            if ($o.paymentOperationId -and $o.paymentOperationId -notmatch '^\d+$') { $numericFlow = $false }
        }
        Record-Step "6-reconciliation" ($reconBatches.Count -ge 1) "batches=$($reconBatches.Count)"
        Record-Step "6-splits" (@($splits.items).Count -ge 1) "count=$(@($splits.items).Count)"
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
