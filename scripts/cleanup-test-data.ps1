# Cleanup E2E/browser test data before business runs (S0).
#
# Default = FullBusiness + WipePlatform：清业务账 + 清台子主数据（柜/商户/SKU/仓/供应商），
# 余额归零；保留登录账号与 RBAC，便于从 S1 空台子搭起。
# Use -Light：仅清阻塞会话 + waive OPEN（保留主数据）。
# Use -KeepPlatform：FullBusiness 但仍保留柜/商户/SKU/仓（旧行为）。
# -RestoreBalanceCents：默认 0；仅当显式 >0 时回填演示余额。
#
# Device: do NOT hard-code. Empty -DeviceId => Resolve-E2eTestDevice（无柜时警告继续）。
#
# Usage:
#   powershell -File scripts/cleanup-test-data.ps1
#   powershell -File scripts/cleanup-test-data.ps1 -KeepPlatform -RestoreBalanceCents 50000
#   powershell -File scripts/cleanup-test-data.ps1 -Light
#   powershell -File scripts/cleanup-test-data.ps1 -DryRun
#
param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "",
    [string]$ConsumerPhone = "13800138000",
    [int]$RestoreBalanceCents = 0,
    [string]$OperatorPhone = "13900000001",
    [string]$OperatorPassword = "123456",
    [string]$PostgresContainer = "ai-cabinet-postgres-1",
    [switch]$Light,
    [switch]$FullBusiness,
    [switch]$KeepPlatform,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
$PostgresContainer = Get-E2ePostgresContainer -PostgresContainer $PostgresContainer
if ([string]::IsNullOrWhiteSpace($PostgresContainer)) {
    throw "postgres container not found"
}

# Default full wipe unless -Light
$doFull = $true
if ($Light) { $doFull = $false }
if ($FullBusiness) { $doFull = $true }
# S0 → S1：默认连台子主数据一起清；-KeepPlatform 才保留柜/商户/SKU/仓
$wipePlatform = $doFull -and (-not $KeepPlatform)

$resolvedDevice = ""
if (-not $wipePlatform) {
    try {
        $resolvedDevice = Resolve-E2eTestDevice -DeviceId $DeviceId -PostgresContainer $PostgresContainer -UnlockSales:(-not $DryRun)
    }
    catch {
        Write-Warning "device resolve: $($_.Exception.Message)"
    }
}

Write-Host "========== Cleanup Test Data =========="
Write-Host "    mode=$(if ($doFull) { 'FullBusiness' } else { 'Light' }) wipePlatform=$wipePlatform restoreBalance=$RestoreBalanceCents DryRun=$DryRun"

if (-not $DryRun) {
    # Cancel blockers on ALL cabinets (device must not be fixed)
    Clear-E2eDeviceBlockingSessions -AllDevices -PostgresContainer $PostgresContainer | Out-Null
}

$opsAuth = $null
if (-not $DryRun) {
    try {
        $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
            phoneNumber = $OperatorPhone
            password    = $OperatorPassword
        }
        $opsAuth = @{ Authorization = "Bearer $($login.token)" }
    }
    catch {
        Write-Warning "Operator login failed: $_"
    }
}

# Always try to close OPEN disputes/exceptions via API first
$openDisputes = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -t -A -c `
    "SELECT ticket_id || '|' || COALESCE(session_id,'') FROM dispute_ticket WHERE status='OPEN';" 2>&1
$disputeRows = @($openDisputes -split "`n" | Where-Object { $_.Trim() })
Write-Host "Open disputes: $($disputeRows.Count)"

if ($disputeRows.Count -gt 0 -and -not $DryRun -and $null -ne $opsAuth) {
    foreach ($row in $disputeRows) {
        $parts = $row.Trim() -split '\|', 2
        if ($parts.Count -lt 1) { continue }
        $ticketId = $parts[0]
        try {
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path "/api/v2/ops/disputes/$ticketId/resolve" -Headers $opsAuth -Body @{
                resolutionType = 'WAIVE'
                items          = @()
            } | Out-Null
            Write-Host "  -> waive dispute $ticketId"
        }
        catch {
            Write-Warning "  failed to waive dispute $ticketId : $_"
        }
    }
}

$openExceptions = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -t -A -c `
    "SELECT exception_id || '|' || exception_type || '|' || COALESCE(session_id,'') FROM ops_exception WHERE status IN ('OPEN','PROCESSING');" 2>&1
$rows = @($openExceptions -split "`n" | Where-Object { $_.Trim() })
Write-Host "Open exceptions: $($rows.Count)"

if ($rows.Count -gt 0 -and -not $DryRun -and $null -ne $opsAuth) {
    foreach ($row in $rows) {
        $parts = $row.Trim() -split '\|', 3
        if ($parts.Count -lt 2) { continue }
        $exId = $parts[0]
        $exType = $parts[1]
        $sid = if ($parts.Count -ge 3) { $parts[2] } else { '' }
        try {
            if ($sid -and $exType -in @('BALANCE_INSUFFICIENT', 'RECOGNITION_FAILED', 'RECOGNITION_UNAVAILABLE', 'SETTLEMENT_FAILED')) {
                Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                    -Path "/api/v2/ops/admin/exceptions/$exId/manual-resolve" -Headers $opsAuth -Body @{
                    resolutionType = 'WAIVE'
                    items          = @()
                    reason         = 'e2e cleanup waive'
                    idempotencyKey = "cleanup-waive-$exId"
                } | Out-Null
            }
            else {
                Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                    -Path "/api/v2/ops/admin/exceptions/$exId/resolve" -Headers $opsAuth -Body @{
                    resolution = 'e2e cleanup resolve'
                } | Out-Null
            }
            Write-Host "  -> resolve $exId ($exType)"
        }
        catch {
            Write-Warning "  failed to resolve $exId : $_"
        }
    }
}

if ($doFull -and -not $DryRun) {
    $modeLabel = if ($wipePlatform) { "FullBusiness+WipePlatform (empty stage for S1)" } else { "FullBusiness+KeepPlatform" }
    Write-Host "==> $modeLabel SQL purge (keep users/RBAC)"
    $purge = @"
BEGIN;

-- disputes (RESTRICT on session)
DELETE FROM dispute_message;
DELETE FROM dispute_ticket;

-- order dependents that block delete
DELETE FROM revenue_share_detail;
DELETE FROM order_revenue_split;
DELETE FROM payscore_order WHERE order_id IS NOT NULL;
UPDATE user_coupon SET order_id = NULL, device_id = NULL WHERE order_id IS NOT NULL OR device_id IS NOT NULL;
DELETE FROM invoice_request;
DELETE FROM balance_refund_allocation;
DELETE FROM balance_refund_request;
DELETE FROM payment_operation;

-- recognition / feedback / exceptions referencing sessions/orders
DELETE FROM recognition_result;
UPDATE user_feedback SET session_id = NULL, device_id = NULL WHERE TRUE;
DELETE FROM user_feedback;
UPDATE ops_exception SET order_id = NULL, session_id = NULL, device_id = NULL WHERE TRUE;
DELETE FROM ops_exception;

-- sessions cascade leftovers
UPDATE shopping_session SET order_id = NULL WHERE order_id IS NOT NULL;
DELETE FROM shopping_session;

-- leftover orders (if any)
DELETE FROM cabinet_order_line;
DELETE FROM cabinet_order;

-- recharge / wallet noise
DELETE FROM recharge_order;
DELETE FROM merchant_wallet_ledger;
DELETE FROM line_wallet_ledger;
UPDATE merchant_wallet_account SET balance_cents = 0, frozen_cents = 0, updated_at = NOW();
UPDATE line_wallet_account SET balance_cents = 0, frozen_cents = 0, updated_at = NOW() WHERE TRUE;

-- coupons issued to users (templates kept unless WipePlatform kills catalog deps)
DELETE FROM user_coupon;

-- replenishment / warehouse movement (business, not master)
DELETE FROM replenishment_task_line;
DELETE FROM replenishment_task;
DELETE FROM merchant_replenishment_request_line;
DELETE FROM merchant_replenishment_request;
DELETE FROM warehouse_in_transit;
DELETE FROM warehouse_outbound_line;
DELETE FROM warehouse_outbound;
DELETE FROM purchase_return_line;
DELETE FROM purchase_return;
DELETE FROM supplier_payment;
DELETE FROM supplier_payable;
DELETE FROM warehouse_inbound_line;
DELETE FROM warehouse_inbound;
DELETE FROM purchase_order_line;
DELETE FROM purchase_order;
DELETE FROM warehouse_movement;
DELETE FROM warehouse_stocktake_line;
DELETE FROM warehouse_stocktake;

-- inventory ledgers + all-device ops noise
DELETE FROM inventory_movement;
DELETE FROM inventory_write_off;
DELETE FROM device_ops_event;
DELETE FROM device_lifecycle_event;
DELETE FROM device_temperature_reading;
DELETE FROM device_env_reading;
DELETE FROM device_fault_report;
DELETE FROM repair_ticket;
DELETE FROM risk_event;
DELETE FROM ad_play_event;
DELETE FROM pull_off_task;
DELETE FROM line_commission_daily;
DELETE FROM ota_device_report;
DELETE FROM site_rent_bill;
DELETE FROM device_data_fee_bill;

-- inventory stock
DELETE FROM device_sku_lot;
DELETE FROM device_sku_inventory;
DELETE FROM warehouse_inventory;

-- Drop orphan CAB-001 forever
DELETE FROM device_sku_price WHERE device_id = 'CAB-001';
DELETE FROM promotion_device WHERE device_id = 'CAB-001';
DELETE FROM device_slot WHERE device_id = 'CAB-001';
DELETE FROM device_temp_plan_entry WHERE plan_id IN (SELECT plan_id FROM device_temp_plan WHERE device_id = 'CAB-001');
DELETE FROM device_temp_plan WHERE device_id = 'CAB-001';
DELETE FROM ad_campaign_device WHERE device_id = 'CAB-001';
DELETE FROM line_device WHERE device_id = 'CAB-001';
DELETE FROM ops_device_org WHERE device_id = 'CAB-001';
DELETE FROM ops_user_device_scope WHERE device_id = 'CAB-001';
DELETE FROM site_contract WHERE device_id = 'CAB-001';
DELETE FROM device_info WHERE device_id = 'CAB-001';

COMMIT;
"@
    if ($wipePlatform) {
        # 模拟器命令行写死柜号，清库前先停，否则心跳会立刻再登记柜子
        try {
            docker stop ai-cabinet-device-simulator-1 2>$null | Out-Null
            Write-Host "==> Stopped device-simulator (prevents auto re-register after wipe)"
        } catch {
            Write-Warning "stop simulator: $($_.Exception.Message)"
        }
        $purge = $purge.Replace("COMMIT;", @"
-- WipePlatform: empty stage for S1 (keep users/RBAC only)
DELETE FROM device_sku_price;
DELETE FROM promotion_device;
DELETE FROM device_temperature_reading;
DELETE FROM device_availability_kpi_daily;
DELETE FROM device_info;

DELETE FROM merchant_withdraw_request;
DELETE FROM merchant_wallet_account;
DELETE FROM merchant_notify_log;
DELETE FROM merchant_ops_config;
DELETE FROM merchant_payment_onboarding;
DELETE FROM merchant_subscribe_pref;
DELETE FROM merchant_tax_profile;
DELETE FROM merchant_role_template;
DELETE FROM ops_user_merchant;
DELETE FROM merchant;

DELETE FROM warehouse_bin_stock;
DELETE FROM warehouse_bin;
DELETE FROM warehouse_transfer_line;
DELETE FROM warehouse_transfer_order;
DELETE FROM warehouse;

UPDATE sku_catalog SET supplier_id = NULL WHERE supplier_id IS NOT NULL;
DELETE FROM aliyun_category_mapping;
DELETE FROM sku_vision_mapping;
DELETE FROM sku_delist_review;
DELETE FROM sku_catalog;

DELETE FROM supplier;

-- 补货路线 / 线长
DELETE FROM ops_user_route_scope;
UPDATE warehouse_outbound SET route_id = NULL WHERE route_id IS NOT NULL;
UPDATE replenishment_task SET route_id = NULL WHERE route_id IS NOT NULL;
DELETE FROM replenishment_route;
DELETE FROM line_device;
DELETE FROM line_withdraw_request;
DELETE FROM line_wallet_ledger;
DELETE FROM line_wallet_account;
DELETE FROM line_promo_task;
DELETE FROM line_manager;

-- 优惠券
DELETE FROM user_coupon;
DELETE FROM points_redeem_item;
DELETE FROM coupon_definition;

-- 对账 / 数据一致性 / 公告
DELETE FROM payment_reconciliation;
DELETE FROM data_consistency_record;
DELETE FROM announcement;

UPDATE user_account SET balance_cents = 0, frozen_cents = 0, updated_at = NOW();

COMMIT;
"@)
    }
    # 破坏性 wipe 前自动整库备份——2026-09-29 后台手工添加的设备/商户被清后无法找回的教训。
    # 备份落在 .tmp/pre-wipe-backup-<时间戳>.sql；备份失败仅告警不阻断（回归仍可跑）。
    if ($wipePlatform) {
        $backupDir = Join-Path $PSScriptRoot "..\.tmp"
        New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
        $backupFile = Join-Path $backupDir ("pre-wipe-backup-{0}.sql" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
        cmd /c "docker exec $PostgresContainer pg_dump -U aicabinet -d aicabinet > `"$backupFile`" 2>nul"
        if ((Test-Path $backupFile) -and (Get-Item $backupFile).Length -gt 0) {
            Write-Host "==> Pre-wipe backup saved: $backupFile ($('{0:N0}' -f (Get-Item $backupFile).Length) bytes)"
        } else {
            Write-Warning "pre-wipe backup failed — continuing WITHOUT backup"
        }
    }
    $pout = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -v ON_ERROR_STOP=1 -c $purge 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "FullBusiness purge failed: $pout"
        throw "FullBusiness purge failed"
    }
    Write-Host "OK FullBusiness purge wipePlatform=$wipePlatform"
}
elseif ($DryRun -and $doFull) {
    Write-Host "[DryRun] would FullBusiness purge txn$(if ($wipePlatform) { '+platform masters' } else { '' })"
}

if (-not $DryRun) {
    if ($RestoreBalanceCents -gt 0) {
        Set-E2eConsumerBalance -BalanceCents $RestoreBalanceCents -Phone $ConsumerPhone -PostgresContainer $PostgresContainer | Out-Null
        Write-Host "Restored consumer $ConsumerPhone balance to $RestoreBalanceCents cents"
    }
    else {
        docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c `
            "UPDATE user_account SET balance_cents = 0, frozen_cents = 0, updated_at = NOW();" | Out-Null
        Write-Host "Consumer/demo balances forced to 0 (S1 empty stage; pass -RestoreBalanceCents to refill)"
    }
    if ($resolvedDevice -and -not $wipePlatform) {
        docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c `
            "UPDATE device_info SET sales_locked=false WHERE device_id='$resolvedDevice';" | Out-Null
    }
}

$summary = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c @"
SELECT (SELECT COUNT(*) FROM shopping_session) AS sessions,
       (SELECT COUNT(*) FROM cabinet_order) AS orders,
       (SELECT COUNT(*) FROM device_info) AS devices,
       (SELECT COUNT(*) FROM merchant) AS merchants,
       (SELECT COUNT(*) FROM sku_catalog) AS skus,
       (SELECT COUNT(*) FROM warehouse) AS warehouses,
       (SELECT COUNT(*) FROM supplier) AS suppliers,
       (SELECT COUNT(*) FROM replenishment_route) AS routes,
       (SELECT COUNT(*) FROM coupon_definition) AS coupons,
       (SELECT COUNT(*) FROM payment_reconciliation) AS recon,
       (SELECT COUNT(*) FROM data_consistency_record) AS consistency,
       (SELECT COALESCE(SUM(balance_cents),0) FROM user_account) AS all_balances;
"@
Write-Host $summary

# Write resolved device for callers
$metaPath = Join-Path (Split-Path -Parent $PSScriptRoot) ".tmp/mp-cleanup-meta.json"
$metaDir = Split-Path -Parent $metaPath
if (-not (Test-Path $metaDir)) { New-Item -ItemType Directory -Force -Path $metaDir | Out-Null }
@{
    at            = (Get-Date).ToUniversalTime().ToString("o")
    mode          = $(if ($doFull) { "FullBusiness" } else { "Light" })
    wipePlatform  = [bool]$wipePlatform
    deviceId      = $resolvedDevice
    balanceCents  = $RestoreBalanceCents
} | ConvertTo-Json | Set-Content -Path $metaPath -Encoding utf8

Write-Host "OK cleanup complete wipePlatform=$wipePlatform device=$resolvedDevice meta=$metaPath"
