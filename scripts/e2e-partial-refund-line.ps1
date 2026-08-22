# Line-level partial refund: take 2, refund 1 with restore -> PARTIAL_REFUNDED, stock +1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl ""
$EnvFile = Join-Path $Root "infra\.env.sandbox"
if (-not (Test-Path $EnvFile)) { $EnvFile = Join-Path $Root "infra\.env" }

$lock = Enter-E2eLock -Owner "partial-refund-line"
try {
    $demo = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey "dev-internal-key-change-me" -Ensure
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

    $inv = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $ops
    $sku = ($inv | Where-Object { $_.quantity -ge 4 } | Sort-Object quantity -Descending | Select-Object -First 1).skuId
    if (-not $sku) { throw "no sku with qty>=4" }

    function Get-Qty([string]$SkuId) {
        $m = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $ops
        return [int](($m | Where-Object { $_.skuId -eq $SkuId }).quantity)
    }

    Write-Host ("sku=" + $sku)
    $q0 = Get-Qty $sku
    & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @(($sku + ":2")) -ShoppingSeconds 6 -EnvFile $EnvFile | Out-Null
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
        -RepoRoot $Root -InternalApiKey "dev-internal-key-change-me" -KeepSimulator
    Start-Sleep -Seconds 2
    $orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path ("/api/v2/orders?page=0" + [char]38 + "size=10") -Headers $auth
    $hit = @($orders.items) | Where-Object { $_.sessionId -eq $mqtt.SessionId } | Select-Object -First 1
    if (-not $hit) { throw ("no order for session " + $mqtt.SessionId) }
    $q1 = Get-Qty $sku
    Write-Host ("order=" + $hit.orderId + " status=" + $hit.status + " total=" + $hit.totalAmountCents + " stock " + $q0 + "->" + $q1)

    $rf = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path ("/api/v2/ops/admin/orders/" + $hit.orderId + "/refund") -Headers $ops -Body @{
        reason = "e2e partial restore one of two"
        lines  = @(
            @{ skuId = $sku; quantity = 1; restoreInventory = $true }
        )
    }
    $q2 = Get-Qty $sku
    Write-Host ("partial status=" + $rf.status + " refunded=" + $rf.refundedCents + " partial=" + $rf.partial + " stock=" + $q2)

    $passSale = ($q1 -eq ($q0 - 2))
    $passPartial = ($rf.status -eq "PARTIAL_REFUNDED") -and ($rf.partial -eq $true) -and ($q2 -eq ($q1 + 1))
    Write-Host ("PASS_SALE=" + $passSale + " PASS_PARTIAL=" + $passPartial)
    if (-not ($passSale -and $passPartial)) { exit 1 }

    # second line kept (no restore) should finish as REFUNDED and stock unchanged
    $rf2 = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path ("/api/v2/ops/admin/orders/" + $hit.orderId + "/refund") -Headers $ops -Body @{
        reason = "e2e partial keep last line"
        lines  = @(
            @{ skuId = $sku; quantity = 1; restoreInventory = $false }
        )
    }
    $q3 = Get-Qty $sku
    Write-Host ("keep status=" + $rf2.status + " stock=" + $q3 + " restored=" + $rf2.inventoryRestored)
    $passKeep = ($rf2.status -eq "REFUNDED") -and ($q3 -eq $q2) -and ($rf2.inventoryRestored -eq $false)
    Write-Host ("PASS_KEEP=" + $passKeep)
    if (-not $passKeep) { exit 1 }
} finally {
    Exit-E2eLock $lock
}
