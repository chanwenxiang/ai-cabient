# Phase4: third-party live-cart push + consumer poll
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl ""
$InternalKey = if ($env:INTERNAL_API_KEY) { $env:INTERNAL_API_KEY } else { "dev-internal-key-change-me" }
$internal = @{ "X-Internal-Api-Key" = $InternalKey }

$lock = Enter-E2eLock -Owner "live-cart"
try {
    $demo = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalKey -Ensure
    $DeviceId = $demo.deviceId
    $Phone = $demo.consumerPhone
    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $Phone; password = "123456"
    }
    $auth = @{ Authorization = ("Bearer " + $login.token) }

    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    $session = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions" -Headers $auth -Body @{
        deviceId = $DeviceId
        entryChannel = "WECHAT"
        idempotencyKey = ("live-cart-" + [guid]::NewGuid().ToString("N"))
    }
    $sid = $session.sessionId
    Write-Host ("session=" + $sid + " state=" + $session.state)

    # drive to SHOPPING via door open internal event if needed
    if ($session.state -ne "SHOPPING") {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" -Headers $internal -Body @{
            sessionId = $sid
            deviceId = $DeviceId
            doorState = "OPEN"
            timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        } | Out-Null
        $session = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/sessions/" + $sid) -Headers $auth
        Write-Host ("after open state=" + $session.state)
    }

    $push = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path ("/internal/v1/sessions/" + $sid + "/live-cart") -Headers $internal -Body @{
        mode = "REPLACE"
        items = @(
            @{ skuId = "SKU-DEMO-001"; skuName = "Demo Drink"; quantity = 2; unitPriceCents = 350 }
        )
    }
    Write-Host ("push qty=" + $push.totalQty + " amount=" + $push.totalAmountCents)

    $delta = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path ("/internal/v1/sessions/" + $sid + "/live-cart") -Headers $internal -Body @{
        mode = "DELTA"
        items = @(
            @{ skuId = "SKU-DEMO-001"; quantity = -1; unitPriceCents = 350 }
        )
    }
    Write-Host ("delta qty=" + $delta.totalQty + " amount=" + $delta.totalAmountCents)

    $got = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path ("/api/v2/sessions/" + $sid + "/live-cart") -Headers $auth
    Write-Host ("poll qty=" + $got.totalQty + " amount=" + $got.totalAmountCents)

    $pass = ($push.totalQty -eq 2) -and ($delta.totalQty -eq 1) -and ($got.totalQty -eq 1) -and ($got.totalAmountCents -eq 350)
    Write-Host ("PASS_LIVE_CART=" + $pass)
    if (-not $pass) { exit 1 }

    # cleanup: cancel if still open
    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path ("/api/v2/sessions/" + $sid + "/cancel") -Headers $auth | Out-Null
    } catch {}
}
finally {
    Exit-E2eLock $lock
}
