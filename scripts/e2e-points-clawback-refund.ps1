# E2E: points EARN -> consumer refund -> clawback credits used bucket (CB-058 option A, G10)
# Chain under test:
#   order PAID  -> MemberService.onOrderPaid -> earnPoints (total+=P, available+=P, log EARN)
#   refund      -> OrderPaymentService clawbackPointsOnRefund (used+=P, available-=P, total unchanged)
# Identity invariant: total == available + used + expired at every step (POINTS_IDENTITY judge).
param(
    [string]$BaseUrl = "",
    [string]$PgContainer = "ai-cabinet-postgres-1",
    [string]$MqttBroker = "tcp://localhost:11883",
    [string]$EnvFile = "",
    [string]$ConsumerPhone = "",
    [string]$DeviceId = "",
    [switch]$SkipCartSetup,
    # Stack targeting (defaults = shared dev stack; pass UAT names when driving the isolated stack)
    [string]$RedisContainer = "ai-cabinet-redis-1",
    [string]$RedisPassword = "",
    [string]$TradeContainer = "",
    [string]$InternalApiKey = ""
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
if (-not $RedisPassword) {
    $RedisPassword = (docker inspect $RedisContainer --format '{{range .Config.Env}}{{println .}}{{end}}' |
        Select-String '^REDIS_PASSWORD=').ToString().Split('=')[1]
}
if (-not $InternalApiKey -and $TradeContainer) {
    $InternalApiKey = (docker inspect $TradeContainer --format '{{range .Config.Env}}{{println .}}{{end}}' |
        Select-String '^INTERNAL_API_KEY=').ToString().Split('=')[1]
}
if (-not $InternalApiKey) { $InternalApiKey = "dev-internal-key-change-me" }

function Get-AdminAuth([string]$BaseUrl, [string]$Pg, [string]$RedisC, [string]$RedisP) {
    $cap = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/v2/auth/captcha" -ContentType "application/json"
    $capId = $cap.data.captchaId
    $code = (docker exec $RedisC redis-cli --no-auth-warning -a $RedisP --raw GET "aicabinet:captcha:$capId" 2>$null).Trim()
    if (-not $code) { throw "captcha code not found in redis $RedisC for id=$capId" }
    $login = Invoke-RestMethod -Method POST -Uri "$BaseUrl/api/v2/auth/admin-password-login" -ContentType "application/json" -Body (@{
        phoneNumber = "13900000001"; password = "123456"; captchaId = $capId; captchaCode = $code
    } | ConvertTo-Json)
    if ($login.code -ne 0) { throw "admin login failed: $($login.message)" }
    return @{ Authorization = ("Bearer " + $login.data.token) }
}
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl

function Get-MemberPoints([string]$Phone, [string]$Pg) {
    # Member row is created lazily on first order PAID; returns $null before that.
    $sql = "SELECT m.member_id, COALESCE(m.total_points,0), COALESCE(m.available_points,0), " +
        "COALESCE(m.used_points,0), COALESCE(m.expired_points,0) FROM member m " +
        "JOIN user_info u ON u.user_id = m.user_id WHERE u.phone_number = '$Phone' LIMIT 1;"
    $row = docker exec $Pg psql -U aicabinet -d aicabinet -tA -c $sql
    if (-not $row) { return $null }
    $p = $row -split '\|'
    [pscustomobject]@{ MemberId = [long]$p[0]; Total = [int]$p[1]; Available = [int]$p[2]; Used = [int]$p[3]; Expired = [int]$p[4] }
}

function Assert-Eq([string]$Name, $Expected, $Actual) {
    if ("$Expected" -ne "$Actual") {
        throw "ASSERT FAIL: $Name expected=$Expected actual=$Actual"
    }
    Write-Host ("    ok: {0} = {1}" -f $Name, $Actual)
}

$lock = Enter-E2eLock -Owner "points-clawback-refund"
$dailyCapLifted = $false
try {
    if (-not $DeviceId) {
        $demo = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
        $DeviceId = $demo.deviceId
    }
    $Phone = if ($ConsumerPhone) { $ConsumerPhone } else { $demo.consumerPhone }
    Write-Host "==> device=$DeviceId consumer=$Phone baseUrl=$BaseUrl pg=$PgContainer"

    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $Phone; password = "123456"
    }
    $auth = @{ Authorization = ("Bearer " + $login.token) }
    Set-E2eConsumerBalance -BalanceCents 50000 -Phone $Phone -PostgresContainer $PgContainer | Out-Null

    $ops = Get-AdminAuth -BaseUrl $BaseUrl -Pg $PgContainer -RedisC $RedisContainer -RedisP $RedisPassword

    try {
        Invoke-E2eApi -BaseUrl $BaseUrl -Method PATCH -Path ("/api/v2/ops/admin/devices/" + $DeviceId) -Headers $ops -Body @{
            refundPolicy = "AUTO_REFUND"
        } | Out-Null
        Write-Host "    refundPolicy=AUTO_REFUND (via API)"
    } catch {
        docker exec $PgContainer psql -U aicabinet -d aicabinet -c "UPDATE device_info SET refund_policy='AUTO_REFUND' WHERE device_id='$DeviceId';" | Out-Null
        Write-Host "    refundPolicy set via SQL"
    }

    # Lift consumer self-refund daily cap; restore afterwards.
    docker exec $PgContainer psql -U aicabinet -d aicabinet -c "UPDATE system_config SET config_value='0' WHERE config_key='refund.self.max_daily';" | Out-Null
    $dailyCapLifted = $true

    $memberBefore = Get-MemberPoints -Phone $Phone -Pg $PgContainer
    if ($memberBefore) {
        Write-Host ("    member before: id={0} total={1} avail={2} used={3} expired={4}" -f $memberBefore.MemberId, $memberBefore.Total, $memberBefore.Available, $memberBefore.Used, $memberBefore.Expired)
    } else {
        Write-Host "    member before: (none, will be created on first PAID)"
    }

    $inv = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/ops/admin/inventory?deviceId=" + $DeviceId) -Headers $ops
    $sku = ($inv | Where-Object { $_.quantity -ge 2 } | Sort-Object quantity -Descending | Select-Object -First 1).skuId
    if (-not $sku) { throw "no sku with qty>=2" }
    $q0 = [int](($inv | Where-Object { $_.skuId -eq $sku }).quantity)
    Write-Host ("    sku={0} qty={1}" -f $sku, $q0)

    if (-not $SkipCartSetup) {
        & (Join-Path $PSScriptRoot "set-simulator-cart.ps1") -Items @(($sku + ":2")) -ShoppingSeconds 6 -EnvFile $EnvFile | Out-Null
    } else {
        Write-Host "    SkipCartSetup: cart/env prepared externally (inventory seeded, simulator recreated with GRAVITY_JSON)"
    }
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $auth `
        -RepoRoot $Root -MqttBroker $MqttBroker -InternalApiKey $InternalKey -KeepSimulator
    Start-Sleep -Seconds 2

    $orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/orders?page=0" + [char]38 + "size=10") -Headers $auth
    $hit = @($orders.items) | Where-Object { $_.sessionId -eq $mqtt.SessionId } | Select-Object -First 1
    if (-not $hit -and "$($mqtt.FinalState)" -eq "DISPUTED") {
        # Cloud recognition retired: RECOGNITION_FAILED -> ops workbench manual-resolve (CONFIRM)
        # -> confirmDisputedItems -> chargeOrder -> finalizeOrder (freeze + points EARN) -> PAID.
        Write-Host "    session DISPUTED -> ops manual-resolve CONFIRM (real workbench path)"
        $excRow = docker exec $PgContainer psql -U aicabinet -d aicabinet -tA -c `
            "SELECT exception_id FROM ops_exception WHERE session_id='$($mqtt.SessionId)' AND status='OPEN' ORDER BY created_at DESC LIMIT 1;"
        if (-not $excRow) { throw "no OPEN ops exception for disputed session" }
        $idem = "e2e-manual-resolve-$([guid]::NewGuid().ToString('N'))"
        $resolveBody = @{
            resolutionType = "CONFIRM"
            items          = @(@{ skuId = $sku; quantity = 2 })
            idempotencyKey = $idem
            reason         = "e2e points clawback chain: confirm recognized items"
        } | ConvertTo-Json -Depth 5
        $resolved = Invoke-RestMethod -Method POST -Uri "$BaseUrl/api/v2/ops/admin/exceptions/$excRow/manual-resolve" `
            -Headers $ops -ContentType "application/json" -Body $resolveBody
        if ($resolved.code -ne 0) { throw "manual-resolve failed: $($resolved.message)" }
        Write-Host ("    manual-resolve ok: {0}" -f $resolved.data.result)
        $orders = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path ("/api/v2/orders?page=0" + [char]38 + "size=10") -Headers $auth
        $hit = @($orders.items) | Where-Object { $_.sessionId -eq $mqtt.SessionId } | Select-Object -First 1
    }
    if (-not $hit) { throw "order for session $($mqtt.SessionId) not found" }
    Write-Host ("    order={0} status={1} total={2}" -f $hit.orderId, $hit.status, $hit.totalAmountCents)
    if ($hit.status -ne "PAID") { throw "order not PAID: $($hit.status)" }

    $memberAfterEarn = Get-MemberPoints -Phone $Phone -Pg $PgContainer
    if (-not $memberAfterEarn) { throw "member row missing after PAID" }
    $beforeTotal = if ($memberBefore) { $memberBefore.Total } else { 0 }
    $P = $memberAfterEarn.Total - $beforeTotal
    if ($P -le 0) { throw "no points earned on PAID order (P=$P)" }
    Write-Host ("    member after EARN: total={0} avail={1} used={2} (P={3})" -f $memberAfterEarn.Total, $memberAfterEarn.Available, $memberAfterEarn.Used, $P)
    Assert-Eq "identity after EARN" ($memberAfterEarn.Total - $memberAfterEarn.Available - $memberAfterEarn.Used - $memberAfterEarn.Expired) 0

    $rf = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path ("/api/v2/orders/" + $hit.orderId + "/refund") -Headers $auth -Body @{
        reason = "e2e points clawback chain"
    }
    Write-Host ("    refund: status={0} refunded={1}" -f $rf.status, $rf.refundedCents)

    $memberAfterRefund = Get-MemberPoints -Phone $Phone -Pg $PgContainer
    Write-Host ("    member after REFUND: total={0} avail={1} used={2} expired={3}" -f $memberAfterRefund.Total, $memberAfterRefund.Available, $memberAfterRefund.Used, $memberAfterRefund.Expired)

    Assert-Eq "total_points unchanged by refund" $memberAfterEarn.Total $memberAfterRefund.Total
    $usedDelta = $memberAfterRefund.Used - $memberAfterEarn.Used
    $availDelta = $memberAfterEarn.Available - $memberAfterRefund.Available
    Write-Host ("    clawback: usedDelta={0} availDelta={1} (expected {2})" -f $usedDelta, $availDelta, $P)
    Assert-Eq "used bucket credited" $P $usedDelta
    Assert-Eq "available debited" $P $availDelta
    Assert-Eq "identity after REFUND" ($memberAfterRefund.Total - $memberAfterRefund.Available - $memberAfterRefund.Used - $memberAfterRefund.Expired) 0

    $logSql = "SELECT points_type || ':' || points FROM member_points_log WHERE member_id = $($memberAfterRefund.MemberId) " +
        "AND source_id LIKE '%$($hit.orderId)%' ORDER BY id;"
    $logRows = @(docker exec $PgContainer psql -U aicabinet -d aicabinet -tA -c $logSql | Where-Object { $_ })
    Write-Host ("    points_log rows for order: {0}" -f ($logRows -join ", "))
    if (-not ($logRows | Where-Object { $_ -like "EARN:*" })) { throw "no EARN log row for order" }
    if (-not ($logRows | Where-Object { $_ -like "USE:-*" })) { throw "no USE (clawback) log row for order" }

    Write-Host "PASS: EARN -> refund -> clawback credits used bucket; identity invariant holds end to end"
} finally {
    Exit-E2eLock -LockHandle $lock
    if ($dailyCapLifted) {
        docker exec $PgContainer psql -U aicabinet -d aicabinet -c "UPDATE system_config SET config_value='3' WHERE config_key='refund.self.max_daily';" | Out-Null
    }
}
