# MA-039 + MA-040 活栈资金链：活动价单转 PENDING 的金额口径 + 补扣（代收）链禁券
#
# 两条腿，缺一不成立：
#   腿 1（活动单）：CB-082 SKU_REDUCE 活动生效 → 结算转 PENDING → 补缴。
#       MA-040 判据：PENDING 落库 total 必须是折后应付（gross − member − activity − coupon = total），
#                   修复前 total = 活动前毛额（虚高一个活动优惠额）。
#       MA-039 判据：补缴后 coupon_id 仍为 NULL、coupon_discount_cents 仍为 0、实扣 = 活动价小计，
#                   用户那张可用券必须仍是 UNUSED。修复前这里会自动叠一张最优券（少收券面额）。
# 腿 2（无活动对照）：停掉同一活动后重跑一遍，同一个人、同一档券。
#       判据：补缴时券照常被选中并核销（total = 毛额 − 券）。
#       ⇒ 证明这张券确实够格（门槛/范围都过），腿 1 不复用券的唯一原因就是活动闸。
#   腿 1b（MA-043）：腿 1 的单结案后把活动停掉，重开工单再 CONFIRM 同一份 items ⇒ 走「已有订单」的改单分支。
#       判据：单价/活动归属/活动留档逐字不变，补差为 0（不新增扣款流水）。按当前活动重算会跳到 686。
#   腿 4（MA-045）：2 件的单先退 1 件（PARTIAL_REFUNDED），再原样重复同一笔退款请求。
#       判据：第二次不再 409 死路，且不产生第二笔 REFUND 流水、已退累计不变。
#       （1 件的单会直接退成 REFUNDED，被上层「订单已退款」前置闸拦掉，走不到幂等恢复分支 ⇒ 所以 Qty=2）
#
# 夹具与回滚：临时把测试柜 deposit_cents 降到 100 分（让余额不足必然发生）、投放草稿活动、发一张无门槛券。
# 活动状态与设备押金在 finally 里原值还原；订单/券行留在库里（与既有 e2e 脚本同口径），末尾打印增量。
#
# ⚠️ dev 柜的识别源（视觉模型 + 重力兜底）已随 DC-27(b)/批 2 删除 ⇒ 模拟购物会话恒落 DISPUTED，
#    订单由运营「争议确认 CONFIRM」的 items 生成。CONFIRM 在会话尚无订单时调用的就是
#    SettlementConfirmDisputeService.prepareConfirmDispute → settlement.finalizeOrder，
#    与扫码直购同链（含 detectUnpaidBeforeCharge 的 PENDING 分支），故本核验判据仍然成立。
#
# Usage:
#   powershell -File scripts/e2e-activity-unpaid-collect.ps1
#   powershell -File scripts/e2e-activity-unpaid-collect.ps1 -ActivityId 4 -CouponDefId 2
param(
    [string]$BaseUrl = "",
    [long]$ActivityId = 4,
    [long]$CouponDefId = 2,
    [int]$DepositCents = 100,
    [string]$InternalApiKey = "dev-internal-key-change-me"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl

$Failures = @()
function Assert-Pass([bool]$cond, [string]$msg) {
    if ($cond) { Write-Host "PASS $msg" }
    else { $script:Failures += $msg; Write-Host "FAIL $msg" -ForegroundColor Red }
}

function Get-OrderRow([string]$OrderId) {
    # 没有 original_amount_cents 列——那是 @TableField(exist=false) 的瞬态字段（lessons #379）
    # PowerShell 普通字符串不能跨行，这里必须用 here-string
    $sql = @"
SELECT status || '|' || total_amount_cents || '|' || gross_amount_cents || '|' ||
       COALESCE(member_discount_cents,0) || '|' || COALESCE(activity_discount_cents,0) || '|' ||
       COALESCE(coupon_discount_cents,0) || '|' || COALESCE(coupon_id::text,'NULL') || '|' ||
       COALESCE(paid_amount_cents,0) || '|' || COALESCE(refunded_cents,0) FROM cabinet_order WHERE order_id = '$OrderId';
"@
    $raw = Invoke-E2ePsql -Sql $sql
    $p = $raw.Trim().Split('|')
    return [pscustomobject]@{
        Status       = $p[0]
        Total        = [int]$p[1]
        Gross        = [int]$p[2]
        MemberDisc   = [int]$p[3]
        ActivityDisc = [int]$p[4]
        CouponDisc   = [int]$p[5]
        CouponId     = $p[6]
        Paid         = [int]$p[7]
        Refunded     = [int]$p[8]
    }
}

$Auth = @{}
$script:DeviceId = ""

# set-simulator-cart.ps1 写死 slotId=SIM-1/SIM-2，本演示柜的货道编号是 A1/B1…
# ⇒ 模拟器上报的货道匹配不到 device_slot ⇒ 识别 items=[] ⇒ 会话直接落 DISPUTED（实测 2026-11-02）。
# 这里按「该 SKU 实际所在的货道」下发，绕开命名不一致，不动既有共享脚本。
function Set-SimCartOnRealSlot {
    param(
        [string]$SkuId,
        [int]$Qty,
        [string]$SlotCode,
        [int]$Seconds = 20
    )
    $json = ('[{{"skuId":"{0}","delta":-{1},"slotId":"{2}"}}]' -f $SkuId, $Qty, $SlotCode)
    $env:AICABINET_SIM_GRAVITY_JSON = $json
    $env:AICABINET_SIM_SHOPPING_MS = [string]($Seconds * 1000)
    $composeFile = Join-Path $Root 'infra\docker-compose.full.yml'
    $winPorts = Join-Path $Root 'infra\docker-compose.win-ports.yml'
    $envFile = Join-Path $Root 'infra\.env.sandbox'
    if (-not (Test-Path $envFile)) { $envFile = Join-Path $Root 'infra\.env' }
    $composeArgs = @('-p', 'ai-cabinet', '--env-file', $envFile, '-f', $composeFile)
    if (Test-Path $winPorts) { $composeArgs += @('-f', $winPorts) }
    $composeArgs += @('up', '-d', '--no-deps', '--force-recreate', 'device-simulator')
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    docker compose @composeArgs 2>&1 | Out-Null
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) { throw "docker compose 重建 device-simulator 失败（exit=$code）" }
    Write-Host "    模拟器货道=$SlotCode sku=$SkuId x$Qty（force-recreate + 12s MQTT warmup）"
    Start-Sleep -Seconds 12
}

# ApiRateLimitService 的窗口是**小时级**（KEY_PREFIX + action + ":" + subject），本脚本一轮就要
# 开 2 次柜 + 缴 2 次款，连跑几次必然撞 429「开柜请求过于频繁」。清的都是**本夹具自己**
# 消费者/柜机维度的计数键（不是业务数据），等价于既有 e2e 的 Clear-E2eDeviceBlockingSessions。
function Reset-E2eFixtureRateLimits {
    param([long]$UserId, [string]$DevId)
    $keys = @()
    foreach ($a in @('session_create', 'order_pay', 'open_door', 'open_landing')) {
        $keys += "aicabinet:rate/${a}:$UserId"
    }
    $keys += "aicabinet:rate/open_door_device:$DevId"
    $deleted = (docker exec ai-cabinet-redis-1 redis-cli --no-auth-warning -a devredis DEL $keys 2>&1 | Out-String).Trim()
    $residual = (docker exec ai-cabinet-redis-1 redis-cli --no-auth-warning -a devredis GET "aicabinet:rate:session_create:$UserId" 2>&1 | Out-String).Trim()
    Write-Host "    已清限流计数 user=$UserId device=$DevId（DEL 命中 $deleted / session_create 残余=$residual）"
}

function Invoke-ShoppingRound([string]$Tag) {
    $script:TicketId = $null
    Reset-E2eFixtureRateLimits -UserId $script:UserId -DevId $script:DeviceId
    Write-Host "==> [$Tag] 模拟柜机购物（余额故意不足 → PENDING）"
    $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$script:DeviceId/status" -Headers $script:Auth
    Write-Host "    device online=$($dev.online) available=$($dev.available)"
    $mqtt = Invoke-E2eMqttShopping -BaseUrl $BaseUrl -DeviceId $script:DeviceId -Auth $script:Auth `
        -RepoRoot $Root -InternalApiKey $script:InternalApiKey -KeepSimulator
    # dev 柜的识别源（vision / 重力兜底）已随 DC-27(b)、批 2 删除 ⇒ 会话恒落 DISPUTED，
    # 订单只能在运营「争议确认」里由 items 生成。CONFIRM 无既有订单时走的就是
    # SettlementConfirmDisputeService.prepareConfirmDispute → settlement.finalizeOrder，
    # 与扫码直购同一条链（含 detectUnpaidBeforeCharge 的 PENDING 分支），所以本核验仍然成立。
    if ($mqtt.FinalState -eq "DISPUTED") {
        Write-Host "    DISPUTED ⇒ 运营 CONFIRM items=$($script:Sku)x$($script:Qty)"
        $disp = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/ops/disputes?status=OPEN&sessionId=$($mqtt.SessionId)&page=0&size=5" -Headers $script:Ops
        $ticket = @($disp.items) | Select-Object -First 1
        if (-not $ticket) { throw "DISPUTED 会话 $($mqtt.SessionId) 没有 OPEN 争议工单" }
        $script:TicketId = [string]$ticket.ticketId
        $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
            -Path "/api/v2/ops/disputes/$($script:TicketId)/resolve" -Headers $script:Ops -Body @{
            resolutionType = "CONFIRM"
            videoReviewed  = $true
            items          = @(@{ skuId = $script:Sku; quantity = $script:Qty })
        }
        Write-Host "    工单 $($script:TicketId) 已 CONFIRM"
    }
    $order = Wait-E2eSessionOrder -BaseUrl $BaseUrl -SessionId $mqtt.SessionId -Auth $script:Auth
    return [pscustomobject]@{
        SessionId  = $mqtt.SessionId
        OrderId    = [string]$order.orderId
        FinalState = $mqtt.FinalState
        TicketId   = $script:TicketId
    }
}

$lock = Enter-E2eLock -Owner "activity-unpaid-collect"
$activityWasStatus = $null
$depositWas = $null
$DeviceId = ""
try {
    $demo = & (Join-Path $PSScriptRoot "seed-demo-data.ps1") -BaseUrl $BaseUrl -InternalApiKey $InternalApiKey -Ensure
    $DeviceId = [string]$demo.deviceId
    $script:DeviceId = $DeviceId
    $Phone = [string]$demo.consumerPhone
    $Sku = if ($demo.fallbackSkuId) { [string]$demo.fallbackSkuId } else { Resolve-E2eTestSku -DeviceId $DeviceId }
    # 单笔 2 件：腿 4（MA-045）要「退 1 件留 1 件」才做得到部分退款后的同参重试，1 件的单会直接退成 REFUNDED，
    # 被上层「订单已退款」前置闸拦掉，走不到幂等恢复那条分支。
    $Qty = 2
    $script:Sku = $Sku
    $script:Qty = $Qty
    $UserId = Get-E2eConsumerUserId -Phone $Phone
    $script:UserId = $UserId
    Write-Host "==> 上下文 device=$DeviceId sku=$Sku phone=$Phone userId=$UserId activity=$ActivityId couponDef=$CouponDefId"

    $login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/password-login" -Body @{
        phoneNumber = $Phone; password = "123456"
    }
    $Auth = @{ Authorization = ("Bearer " + $login.token) }

    # 本核验依赖 mock 档：DeviceValidationService.ensureJiangyiOnlyShopping 只在
    # AICABINET_MOCK_ENABLED=true 时豁免「仅将邑柜机可购物」闸，否则演示柜开门直接 409。
    # 早判早失败，别等到 Create session 才拿到一条和根因无关的 409。
    $pub = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/public/consumer-config"
    $mockOn = ($pub.mockEnabled -eq "true" -or $pub.mockEnabled -eq $true)
    if (-not $mockOn) {
        throw "trade-service 当前 AICABINET_MOCK_ENABLED=false ⇒ 演示柜被「仅将邑可购物」闸拦下；请用 mock=true 重启栈（infra/.env 的默认档）"
    }
    Write-Host "    mock 档已开（非将邑柜机购物闸豁免）"

    $opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
        phoneNumber = "13900000001"; password = "123456"
    }
    $Ops = @{ Authorization = ("Bearer " + $opsLogin.token) }

    # ---- 夹具前置 ----
    $activityWasStatus = (Invoke-E2ePsql -Sql "SELECT status FROM promotion_activity WHERE activity_id = $ActivityId;").Trim()
    if (-not $activityWasStatus) { throw "活动 $ActivityId 不存在，无法跑本核验" }
    $ruleSql = @"
SELECT count(*) FROM promotion_activity a,
       jsonb_array_elements_text(COALESCE(a.rule_config -> 'skuIds', '[]'::jsonb)) e
WHERE a.activity_id = $ActivityId AND e = '$Sku';
"@
    $skuInRule = (Invoke-E2ePsql -Sql $ruleSql).Trim()
    Assert-Pass ($skuInRule -eq "1") "夹具活动 $ActivityId 的 rule_config.skuIds 含 $Sku（否则定价闸不会被触发）"
    $reduceCents = [int](Invoke-E2ePsql -Sql "SELECT reduce_cents FROM promotion_activity WHERE activity_id = $ActivityId;")
    Assert-Pass ($reduceCents -ge 1) "夹具活动立减 $reduceCents 分（>0 才会命中活动价）"

    $depositWas = (Invoke-E2ePsql -Sql "SELECT COALESCE(deposit_cents::text,'NULL') FROM device_info WHERE device_id = '$DeviceId';").Trim()
    $null = Invoke-E2ePsql -Sql "UPDATE device_info SET deposit_cents = $DepositCents WHERE device_id = '$DeviceId';"
    Write-Host "    预授权押金 $depositWas -> $DepositCents（让余额不足必然发生；finally 还原）"

    # 演示柜常被「离线超时自动停售」锁住（回线不自解，只有运营解锁），开门会 409 设备已暂停营业。
    $salesLocked = (Invoke-E2ePsql -Sql "SELECT sales_locked::text FROM device_info WHERE device_id = '$DeviceId';").Trim()
    if ($salesLocked -eq "true") {
        $lockReason = (Invoke-E2ePsql -Sql "SELECT COALESCE(sales_lock_reason,'') FROM device_info WHERE device_id = '$DeviceId';").Trim()
        Write-Host "    设备停售中（$lockReason）⇒ 走运营 UNLOCK 命令恢复营业"
        $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/devices/$DeviceId/commands" -Headers $Ops -Body @{
            command = "UNLOCK"; reason = "e2e-activity-unpaid-collect 夹具解锁"
        }
    }
    $stillLocked = (Invoke-E2ePsql -Sql "SELECT sales_locked::text FROM device_info WHERE device_id = '$DeviceId';").Trim()
    Assert-Pass ($stillLocked -eq "false") "设备可开门（sales_locked=$stillLocked）"

    $couponRow = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/coupons/issue" -Headers $Ops -Body @{
        userId = $UserId; couponDefId = $CouponDefId
    }
    $CouponId = [string]$couponRow.couponId
    Assert-Pass (-not [string]::IsNullOrWhiteSpace($CouponId)) "已给消费者发一张券 couponId=$CouponId（无门槛）"
    $denom = [int](Invoke-E2ePsql -Sql "SELECT denomination_cents FROM coupon_definition WHERE coupon_def_id = $CouponDefId;")

    $null = Invoke-E2ePsql -Sql "UPDATE promotion_activity SET status = 'INACTIVE' WHERE activity_id = $ActivityId AND status <> 'INACTIVE';"
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/promotions/$ActivityId/launch" -Headers $Ops
    $launchStatus = (Invoke-E2ePsql -Sql "SELECT status FROM promotion_activity WHERE activity_id = $ActivityId;").Trim()
    Assert-Pass ($launchStatus -eq "ACTIVE") "活动 $ActivityId 已投放（status=$launchStatus）"

    $ordersBefore = [int](Invoke-E2ePsql -Sql "SELECT count(*) FROM cabinet_order;")

    # ===================== 腿 1：活动价单 =====================
    $slotSql = "SELECT slot_code FROM device_slot WHERE device_id = '$DeviceId' AND assigned_sku_id = '$Sku' AND enabled ORDER BY slot_code LIMIT 1;"
    $SlotCode = (Invoke-E2ePsql -Sql $slotSql).Trim()
    if (-not $SlotCode) { throw "SKU $Sku 在设备 $DeviceId 上没有已启用的货道，模拟器无法上报" }
    # 每跑一轮吃掉 1 件账面库存；批次账本在用（deviceUsesLotLedger）时手改汇总表会被 lot 覆盖，
    # 缺货就直接 409「sellable lot inventory insufficient」。判据提前查，别等 CONFIRM 才报错。
    $sellable = [int](Invoke-E2ePsql -Sql "SELECT COALESCE(sum(quantity),0) FROM device_sku_lot WHERE device_id = '$DeviceId' AND sku_id = '$Sku' AND status = 'ACTIVE';")
    if ($sellable -lt 2) {
        throw "演示柜 $Sku 可售批次库存只有 $sellable（本脚本要 2 件）⇒ 先按 RESTOCK 补账面：UPDATE device_sku_lot SET quantity=8, status='ACTIVE' WHERE device_id='$DeviceId' AND sku_id='$Sku'"
    }
    Write-Host "    上报货道 $Sku -> $SlotCode（可售批次 $sellable 件）"
    Set-SimCartOnRealSlot -SkuId $Sku -Qty $Qty -SlotCode $SlotCode
    $null = Set-E2eConsumerBalance -BalanceCents $DepositCents -Phone $Phone
    Set-E2eConsumerPayChannel -BaseUrl $BaseUrl -Auth $Auth -Channel BALANCE -Phone $Phone
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null

    $leg1 = Invoke-ShoppingRound "腿1 活动单"
    $r1 = Get-OrderRow $leg1.OrderId
    Write-Host ("    腿1 order=" + $leg1.OrderId + " status=" + $r1.Status + " total=" + $r1.Total +
        " gross=" + $r1.Gross + " activity=" + $r1.ActivityDisc + " coupon=" + $r1.CouponDisc + " couponId=" + $r1.CouponId)

    Assert-Pass ($r1.Status -eq "PENDING") "MA-040 前置：余额不足已转 PENDING（真走到 clearCouponSelection 那条链）"
    Assert-Pass ($r1.ActivityDisc -eq ($reduceCents * $Qty)) `
        "MA-040：活动优惠已入账 activity_discount_cents=$($r1.ActivityDisc)（立减 $reduceCents × $Qty 件）"
    Assert-Pass ($r1.Total -eq ($r1.Gross - $r1.MemberDisc - $r1.ActivityDisc - $r1.CouponDisc)) `
        ("MA-040：PENDING total 是折后应付（$($r1.Total) = gross $($r1.Gross) - member $($r1.MemberDisc) - activity $($r1.ActivityDisc) - coupon $($r1.CouponDisc)）；修复前这里会落 $($r1.Gross)，虚高 $($r1.ActivityDisc)")
    Assert-Pass ($r1.CouponDisc -eq 0 -and $r1.CouponId -eq "NULL") "MA-040：PENDING 活动单不占券（coupon_id=NULL、券抵扣 0）"

    $null = Set-E2eConsumerBalance -BalanceCents 5000 -Phone $Phone
    $balBefore1 = [int](Invoke-E2ePsql -Sql "SELECT balance_cents FROM user_account WHERE user_id = $UserId;")
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/orders/$($leg1.OrderId)/pay" -Headers $Auth -Body @{
        channel = "BALANCE"
    }
    $r1b = Get-OrderRow $leg1.OrderId
    $balAfter1 = [int](Invoke-E2ePsql -Sql "SELECT balance_cents FROM user_account WHERE user_id = $UserId;")
    $charge1 = [int](Invoke-E2ePsql -Sql "SELECT amount_cents FROM payment_operation WHERE order_id = '$($leg1.OrderId)' AND operation_type='CHARGE' AND status='COMPLETED' ORDER BY created_at DESC LIMIT 1;")
    $couponStatus1 = (Invoke-E2ePsql -Sql "SELECT status FROM user_coupon WHERE coupon_id = $CouponId;").Trim()
    Write-Host ("    腿1 补缴后 status=" + $r1b.Status + " total=" + $r1b.Total + " couponId=" + $r1b.CouponId +
        " 实扣=" + $charge1 + " 券状态=" + $couponStatus1)

    Assert-Pass ($r1b.Total -eq $r1.Total) "MA-039：补缴没有改小应付（total 仍 $($r1b.Total)；修复前会叠一张 $denom 分券）"
    Assert-Pass ($r1b.CouponId -eq "NULL" -and $r1b.CouponDisc -eq 0) "MA-039：活动单补缴后 coupon_id 仍 NULL、券抵扣仍 0"
    Assert-Pass ($charge1 -eq $r1.Total) "MA-039：渠道实扣 = 活动价小计（$charge1）"
    Assert-Pass (($balBefore1 - $balAfter1) -eq $r1.Total) "MA-039：余额恰好减了 $($r1.Total) 分（$balBefore1 → $balAfter1）"
    Assert-Pass ($couponStatus1 -eq "UNUSED") "MA-039：那张券未被核销（$couponStatus1）"
    Assert-Pass ($r1b.Status -in @("PAID", "COMPLETED")) "腿1 补缴完成（status=$($r1b.Status)）"

    # ===================== 腿 2：无活动对照 =====================
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/promotions/$ActivityId/stop" -Headers $Ops
    $stoppedStatus = (Invoke-E2ePsql -Sql "SELECT status FROM promotion_activity WHERE activity_id = $ActivityId;").Trim()
    Write-Host "==> 对照腿：活动已停（status=$stoppedStatus）"

    # ============ 腿 1b（MA-043）：活动停掉后改单，必须沿用原单活动决策 ============
    # reopen 会把订单打回 DISPUTED，再 CONFIRM 同一份 items 走的就是「已有订单」的改单分支。
    # 修复前：applyItemsToOrder 按**当前**活动重定价 ⇒ 活动已停 ⇒ 单价回到目录价 350（会员后 343），
    #        还多扣一笔补差；修复后：行沿用成交时的活动决策，delta=0、金额与活动 id 都不动。
    $before = Get-OrderRow $leg1.OrderId
    $lineBefore = (Invoke-E2ePsql -Sql "SELECT unit_price_cents || '|' || COALESCE(activity_id::text,'NULL') || '|' || COALESCE(activity_discount_cents,0) FROM cabinet_order_line WHERE order_id = '$($leg1.OrderId)' LIMIT 1;").Trim()
    if (-not $leg1.TicketId) { throw "腿1 没有产生争议工单，MA-043 改单腿无法执行" }
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path "/api/v2/ops/disputes/$($leg1.TicketId)/reopen" -Headers $script:Ops -Body @{ note = 'e2e MA-043 改单复验' }
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path "/api/v2/ops/disputes/$($leg1.TicketId)/resolve" -Headers $script:Ops -Body @{
        resolutionType = "CONFIRM"
        videoReviewed  = $true
        items          = @(@{ skuId = $Sku; quantity = $Qty })
    }
    $after1b = Get-OrderRow $leg1.OrderId
    $lineAfter = (Invoke-E2ePsql -Sql "SELECT unit_price_cents || '|' || COALESCE(activity_id::text,'NULL') || '|' || COALESCE(activity_discount_cents,0) FROM cabinet_order_line WHERE order_id = '$($leg1.OrderId)' LIMIT 1;").Trim()
    $chargeCount = (Invoke-E2ePsql -Sql "SELECT count(*) FROM payment_operation WHERE order_id = '$($leg1.OrderId)' AND operation_type='CHARGE' AND status='COMPLETED';").Trim()
    Write-Host ("    MA-043 改单前 total=" + $before.Total + " 行=" + $lineBefore + " ⇒ 改单后 total=" +
        $after1b.Total + " 行=" + $lineAfter + " CHARGE 流水数=" + $chargeCount)

    Assert-Pass ($after1b.Total -eq $before.Total) `
        "MA-043：活动已停仍不改价（改单后 total $($after1b.Total) = 改单前 $($before.Total)；按当前活动重算会跳到 $($after1b.Gross - $after1b.MemberDisc)）"
    Assert-Pass ($lineAfter -eq $lineBefore) "MA-043：行单价与活动归属逐字不变（$lineAfter）"
    Assert-Pass ($chargeCount -eq "1") "MA-043：补差为 0 ⇒ 未新增扣款流水（CHARGE COMPLETED=$chargeCount）"
    Assert-Pass ($after1b.Status -in @("PAID", "COMPLETED")) "MA-043：改单后订单回到已结清（$($after1b.Status)）"

    Set-SimCartOnRealSlot -SkuId $Sku -Qty $Qty -SlotCode $SlotCode
    $null = Set-E2eConsumerBalance -BalanceCents $DepositCents -Phone $Phone
    Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null

    $leg2 = Invoke-ShoppingRound "腿2 无活动对照"
    $r2 = Get-OrderRow $leg2.OrderId
    Write-Host ("    腿2 order=" + $leg2.OrderId + " status=" + $r2.Status + " total=" + $r2.Total +
        " gross=" + $r2.Gross + " activity=" + $r2.ActivityDisc + " couponId=" + $r2.CouponId)
    Assert-Pass ($r2.Status -eq "PENDING") "腿2：同样落到 PENDING"
    Assert-Pass ($r2.ActivityDisc -eq 0) "腿2：活动已停 ⇒ activity_discount_cents=0（与腿1 的唯一差异）"
    # MA-067：券单在「扣款失败转 PENDING」这一支必须先 insert 过，save 走 MP updateById 会跳过 null
    # ⇒ 光把实体上的 couponId 置空落不到库（实测修复前这里 coupon_id 仍留着、券却仍是 UNUSED）。
    Assert-Pass ($r2.CouponId -eq "NULL") ("MA-067：PENDING 单落库后 coupon_id 必须已清空（实测值 " + $r2.CouponId + "）")

    $null = Set-E2eConsumerBalance -BalanceCents 5000 -Phone $Phone
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/orders/$($leg2.OrderId)/pay" -Headers $Auth -Body @{
        channel = "BALANCE"
    }
    $r2b = Get-OrderRow $leg2.OrderId
    # 补扣选中的**不一定是本轮新发的那张**（旧 leftover 券同样够格且排序更靠前）⇒ 判据只认订单实际消费的那张
    $usedCouponStatus = (Invoke-E2ePsql -Sql "SELECT status FROM user_coupon WHERE coupon_id = $($r2b.CouponId);").Trim()
    $usedCouponOrder = (Invoke-E2ePsql -Sql "SELECT COALESCE(order_id,'NULL') FROM user_coupon WHERE coupon_id = $($r2b.CouponId);").Trim()
    $mineStatus = (Invoke-E2ePsql -Sql "SELECT status FROM user_coupon WHERE coupon_id = $CouponId;").Trim()
    Write-Host ("    腿2 补缴后 total=" + $r2b.Total + " couponId=" + $r2b.CouponId + " 券抵扣=" + $r2b.CouponDisc +
        " 该券状态=" + $usedCouponStatus + "（本轮新发的 " + $CouponId + " 状态=" + $mineStatus + "）")

    Assert-Pass ($r2b.CouponId -ne "NULL" -and $r2b.CouponDisc -gt 0) `
        "对照成立：无活动的同一张券在补缴时确实被选中（couponId=$($r2b.CouponId)、抵扣 $($r2b.CouponDisc)）⇒ 腿1 不复用是活动闸而非券不合格"
    Assert-Pass ($r2b.Total -eq ($r2b.Gross - $r2b.MemberDisc - $r2b.ActivityDisc - $r2b.CouponDisc)) `
        "对照腿恒等式同样成立（$($r2b.Total) = $($r2b.Gross) - $($r2b.MemberDisc) - $($r2b.ActivityDisc) - $($r2b.CouponDisc)）"
    Assert-Pass ($usedCouponStatus -eq "USED") "对照腿被折的那张券已核销（$usedCouponStatus）"
    Assert-Pass ($usedCouponOrder -eq $leg2.OrderId) "券的 order_id 回写到本单（$usedCouponOrder）"
    Write-Host ("    本轮新发券 $CouponId 状态=$mineStatus（未被选中就是 UNUSED，属正常）")

    # ============ 腿 4（MA-045）：同一笔按行退款重复提交必须可恢复，不再 409 死路 ============
    # 修复前：渠道已退成功、同参重试命中「订单+金额」幂等键 → 409「请勿重复提交」，
    #         本地行/应付/状态永远补不上（渠道成功 + 本地收尾失败 = 死路）。
    # 修复后：按行集指纹反查已完成退款流水 → resumeLocalPartialRefund → 幂等成功返回。
    $refundBody = @{
        reason = "e2e MA-045 按行退款（腿1 活动单）"
        lines  = @(@{ skuId = $Sku; quantity = 1; restoreInventory = $true })
    }
    $rf1 = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path "/api/v2/ops/admin/orders/$($leg1.OrderId)/refund" -Headers $Ops -Body $refundBody
    $r1c = Get-OrderRow $leg1.OrderId
    Write-Host ("    腿4 首次退款 status=" + $r1c.Status + " refunded=" + $r1c.Refunded + " total=" + $r1c.Total)
    Assert-Pass ($r1c.Status -eq "PARTIAL_REFUNDED") "腿4：退 1 件留 1 件 ⇒ 部分退款（status=$($r1c.Status)）"

    $dupErr = $null
    try {
        $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
            -Path "/api/v2/ops/admin/orders/$($leg1.OrderId)/refund" -Headers $Ops -Body $refundBody
    } catch {
        $dupErr = $_.Exception.Message
    }
    $refundRows = (Invoke-E2ePsql -Sql "SELECT count(*) FROM payment_operation WHERE order_id = '$($leg1.OrderId)' AND operation_type='REFUND' AND status='COMPLETED';").Trim()
    $r1d = Get-OrderRow $leg1.OrderId
    if ($dupErr) { Write-Host ("    腿4 重复退款返回：" + $dupErr.Substring(0, [Math]::Min(160, $dupErr.Length))) }
    Assert-Pass (-not $dupErr) ("MA-045：同参重复退款不再 409 死路（修复前必 409「请勿重复提交」）" + $(if ($dupErr) { " ⇒ 实际：" + $dupErr.Substring(0, [Math]::Min(120, $dupErr.Length)) } else { "" }))
    Assert-Pass ($refundRows -eq "1") "MA-045：重复提交没有二次退款（REFUND COMPLETED=$refundRows）"
    Assert-Pass ($r1d.Total -eq $r1c.Total) "MA-045：重复提交后订单金额未被二次摊薄（$($r1d.Total)）"
    Assert-Pass ($r1d.Refunded -eq $r1c.Refunded) "MA-045：重复提交后已退累计未变（$($r1d.Refunded)）"

    $ordersAfter = [int](Invoke-E2ePsql -Sql "SELECT count(*) FROM cabinet_order;")
    Write-Host ""
    Write-Host ("夹具增量：cabinet_order " + $ordersBefore + " -> " + $ordersAfter +
        "（新增 $($leg1.OrderId) 活动单 / $($leg2.OrderId) 对照单），user_coupon +1（本轮发 $CouponId，" +
        "实际核销的是 $($r2b.CouponId)）")
} finally {
    Write-Host "==> 回滚夹具全局态"
    if ($activityWasStatus) {
        $null = Invoke-E2ePsql -Sql "UPDATE promotion_activity SET status = '$activityWasStatus' WHERE activity_id = $ActivityId;"
        Write-Host "    活动 $ActivityId status 还原为 $activityWasStatus"
    }
    if ($null -ne $depositWas -and $DeviceId) {
        $dep = if ($depositWas -eq "NULL") { "NULL" } else { $depositWas }
        $null = Invoke-E2ePsql -Sql "UPDATE device_info SET deposit_cents = $dep WHERE device_id = '$DeviceId';"
        Write-Host "    设备 $DeviceId deposit_cents 还原为 $depositWas"
    }
    Exit-E2eLock $lock
}

if ($Failures.Count -gt 0) {
    Write-Host ""
    Write-Host ("RESULT: FAIL（" + $Failures.Count + " 条判据未过）")
    $Failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host ""
Write-Host "RESULT: PASS（MA-039/040 活动单补扣与 PENDING 金额 + MA-043 争议沿用原单活动决策 + MA-045 同参退款可恢复 + MA-066/067 欠款落库，含无活动对照腿）"
exit 0
