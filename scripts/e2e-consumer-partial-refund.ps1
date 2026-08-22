# Phase3: consumer line-level self refund with policy limits
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl ""
$EnvFile = Join-Path $Root "infra\.env.sandbox"
if (-not (Test-Path $EnvFile)) { $EnvFile = Join-Path $Root "infra\.env" }
$InternalKey = if ($env:INTERNAL_API_KEY) { $env:INTERNAL_API_KEY } else { "dev-internal-key-change-me" }

$lock = Enter-E2eLock -Owner "consumer-partial-refund"
try {
    $demo = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalKey -Ensure
    $DeviceId = $demo.deviceId
    $Phone = $demo.consumerPhone
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $Phone; password = "123456"
    }
    $auth = @{ Authorization = ("Bearer " + $login.token) }
    Set-E2eConsumerBalance -BalanceCents 50000 -Phone $Phone | Out-Null
    $opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = "13900000001"; password = "123456"
    }
    $ops = @{ Authorization = ("Bearer " + $opsLogin.token) }

    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method PATCH -Path ("/api/v2/ops/admin/devices/" + $DeviceId) -Headers $ops -Body @{
            refundPolicy = "AUTO_REFUND"
        } | Out-Null
        Write-Host "    set refundPolicy=AUTO_REFUND"
    } catch {
        Write-Host ("    skip PATCH: {0}" -f $_.Exception.Message)
        docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -c "UPDATE device_info SET refund_policy='AUTO_REFUND' WHERE device_id='$DeviceId';" | Out-Null
        Write-Host "    set refundPolicy via SQL"
    }

    $inv = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $ops
    $sku = ($inv | Where-Object { $_.quantity -ge 2 } | Sort-Object quantity -Descending | Select-Object -First 1).skuId
    if (-not $sku) {
        # restock demo inventory when depleted by prior e2e runs
        & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalKey -Ensure | Out-Null
        $inv = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $ops
        $sku = ($inv | Where-Object { $_.quantity -ge 2 } | Sort-Object quantity -Descending | Select-Object -First 1).skuId
    }
    if (-not $sku) { throw "no sku with qty>=2" }

    function Get-Qty([string]$SkuId) {
        $m = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $ops
        return [int](($m | Where-Object { $_.skuId -eq $SkuId }).quantity)
    }

    Write-Host ("sku=" + $sku)
    $q0 = Get-Qty $sku
    & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @(($sku + ":2")) -ShoppingSeconds 6 -EnvFile $EnvFile | Out-Null
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
        -RepoRoot $Root -InternalApiKey $InternalKey -KeepSimulator
    Start-Sleep -Seconds 2
    $orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path ("/api/v2/orders?page=0" + [char]38 + "size=10") -Headers $auth
    $hit = @($orders.items) | Where-Object { $_.sessionId -eq $mqtt.SessionId } | Select-Object -First 1
    if (-not $hit) { throw ("no order for session " + $mqtt.SessionId) }
    $q1 = Get-Qty $sku
    Write-Host ("order=" + $hit.orderId + " status=" + $hit.status + " stock " + $q0 + "->" + $q1)

    # E2E: lift daily cap so prior failed/ops refunds don't block this check
    docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -c "UPDATE system_config SET config_value='0' WHERE config_key='refund.self.max_daily';" | Out-Null

    $rf = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path ("/api/v2/orders/" + $hit.orderId + "/refund") -Headers $auth -Body @{
        reason = "e2e consumer partial self refund"
        restoreInventory = $true
        lines = @(
            @{ skuId = $sku; quantity = 1; restoreInventory = $true }
        )
    }
    $q2 = Get-Qty $sku
    Write-Host ("consumer partial status=" + $rf.status + " refunded=" + $rf.refundedCents + " stock=" + $q2)

    docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -c "UPDATE system_config SET config_value='3' WHERE config_key='refund.self.max_daily';" | Out-Null

    $passSale = ($q1 -eq ($q0 - 2))
    $passPartial = ($rf.status -eq "PARTIAL_REFUNDED") -and ($q2 -eq ($q1 + 1))
    Write-Host ("PASS_SALE=" + $passSale + " PASS_CONSUMER_PARTIAL=" + $passPartial)
    if (-not ($passSale -and $passPartial)) { exit 1 }
}
finally {
    Exit-E2eLock $lock
}
