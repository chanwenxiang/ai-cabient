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
#   腿 4（MA-045）：2 件的单先退 1 件成 PARTIAL_REFUNDED，然后三种「渠道已退成功」的本地落点各重放一次同一笔请求：
#       ①全完成（幂等）；③prepare 已提交、finalize 没跑（倒装状态：行/库存留在退款后，状态回 PAID、已退归 0、标记保留）；
#       ②prepare 完全没跑（行/库存/应付全回退到退款前，并抹掉 [LOCAL_PREPARED] 标记）。
#       判据：三次重放都不 409、都收敛回基准态（金额/状态/行/库存逐项相等），且 REFUND COMPLETED 始终只有 1 条。
#       （1 件的单会直接退成 REFUNDED，被上层「订单已退款」前置闸拦下，走不到幂等恢复分支 ⇒ 所以 Qty=2）
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

function Get-LineSnapshot {
    param([string]$OrderId, [string]$SkuId)
    # 部分退会把行改写（件数/行金额），倒装崩溃现场时要按这两个值复原
    $sql = @"
SELECT COALESCE(sum(quantity),0) || '|' || COALESCE(sum(line_amount_cents),0) || '|' || count(*)
FROM cabinet_order_line WHERE order_id = '$OrderId' AND sku_id = '$SkuId';
"@
    return (Invoke-E2ePsql -Sql $sql).Trim()
}

function Get-StockSnapshot {
    param([string]$DeviceId, [string]$SkuId)
    $sql = @"
SELECT COALESCE((SELECT sum(quantity) FROM device_sku_lot WHERE device_id = '$DeviceId' AND sku_id = '$SkuId'
                AND status IN ('ON_SALE','NEAR_EXPIRY')),0) || '|' ||
       COALESCE((SELECT quantity FROM device_sku_inventory WHERE device_id = '$DeviceId' AND sku_id = '$SkuId'),0);
"@
    return (Invoke-E2ePsql -Sql $sql).Trim()
}

function Invoke-RefundTry {
    param([string]$OrderId, $Body)
    try {
        $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
            -Path "/api/v2/ops/admin/orders/$OrderId/refund" -Headers $script:Ops -Body $Body
        return $null
    } catch {
        return ($_.Exception.Message -replace '\s+', ' ')
    }
}

function Set-FixtureStock {
    param([string]$DevId, [string]$SkuId, [int]$Qty)
    # 账面值夹具（与既有 e2e 一致：演示柜的 quantity 本就是手写账面，不是盘点）
    $sql = @"
UPDATE device_sku_lot SET quantity = $Qty, status = 'ON_SALE' WHERE device_id = '$DevId' AND sku_id = '$SkuId';
UPDATE device_sku_inventory SET quantity = $Qty WHERE device_id = '$DevId' AND sku_id = '$SkuId';
"@
    $null = Invoke-E2ePsql -Sql $sql
    Write-Host "    账面库存置为 $Qty（lot + 汇总同步）"
    return Get-StockSnapshot -DeviceId $DevId -SkuId $SkuId
}

function Assert-StockConsistent {
    param([string]$StockSpec, [string]$Tag)
    # 汇总账面必须等于「可售批次之和」：syncAggregateInventory 就是按这个口径覆盖写回的，
    # 两者不等说明批次状态/效期把货藏起来了（或有人绕着改了汇总）。
    $p = $StockSpec.Split('|')
    Assert-Pass ($p[0] -eq $p[1]) "$Tag 库存自洽：可售批次 $($p[0]) == 汇总账面 $($p[1])"
}

function Get-RefundOpRow {
    param([string]$OrderId)
    $sql = @"
SELECT idempotency_key || '~' || amount_cents::text || '~' || COALESCE(reason,'')
FROM payment_operation WHERE order_id = '$OrderId' AND operation_type = 'REFUND' AND status = 'COMPLETED'
ORDER BY created_at DESC LIMIT 1;
"@
    $raw = (Invoke-E2ePsql -Sql $sql).Trim()
    $p = $raw.Split('~')
    return [pscustomobject]@{ IdemKey = $p[0]; Amount = [int]$p[1]; Reason = $p[2] }
}

<# 把订单倒回「渠道退款已完成、本地只跑到某一步」的崩溃现场（状态级注入，不往生产码塞故障钩子）：
   · PreparedOnly=false ⇒ 行/库存/应付全部回到退款前，并抹掉 [LOCAL_PREPARED] 标记（落点②）
   · PreparedOnly=true  ⇒ 行与库存保持退款后，只把状态/已退累计回退（落点③：prepare 已提交、finalize 没跑） #>
function Set-RefundCrashState {
    param(
        [string]$OrderId, [string]$SkuId, [string]$DevId,
        [int]$Total, [string]$LineSpec, [string]$StockSpec,
        [bool]$Prepared, [string]$IdemKey, [string]$Reason
    )
    $lp = $LineSpec.Split('|')
    $sp = $StockSpec.Split('|')
    $marker = if ($Prepared) { " [LOCAL_PREPARED]" } else { "" }
    $reason = ($Reason -replace ' \[LOCAL_PREPARED\]', '') + $marker
    $reason = $reason -replace "'", "''"
    $sql = @"
UPDATE cabinet_order SET status = 'PAID', total_amount_cents = $Total, refunded_cents = 0,
       refunded_at = NULL, updated_at = NOW() WHERE order_id = '$OrderId';
UPDATE cabinet_order_line SET quantity = $($lp[0]), line_amount_cents = $($lp[1])
       WHERE order_id = '$OrderId' AND sku_id = '$SkuId';
UPDATE device_sku_lot SET quantity = $($sp[0]), status = 'ON_SALE' WHERE device_id = '$DevId' AND sku_id = '$SkuId';
UPDATE device_sku_inventory SET quantity = $($sp[1]) WHERE device_id = '$DevId' AND sku_id = '$SkuId';
UPDATE payment_operation SET reason = '$reason' WHERE idempotency_key = '$IdemKey';
"@
    $null = Invoke-E2ePsql -Sql $sql
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
    # 🔴 $Sku 必须以**夹具活动的目标 SKU** 为准，不能信 seed-demo-data 的 fallbackSkuId：
    #    那个值是「当前有货的某款」按库里的数据挑的，腿 5 给 100014 建了账面之后它就会改挑 100014
    #    （实测 2026-11-03：整轮判据因此集体错位）。活动 4 打的是 100013，购物就必须买 100013。
    $skuActSql = @"
SELECT e FROM promotion_activity a, jsonb_array_elements_text(COALESCE(a.rule_config -> 'skuIds', '[]'::jsonb)) e
WHERE a.activity_id = $ActivityId ORDER BY e LIMIT 1;
"@
    $SkuFromActivity = (Invoke-E2ePsql -Sql $skuActSql).Trim()
    if ($SkuFromActivity) { $Sku = $SkuFromActivity }
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
    # 🔴 批次状态域是 ON_SALE / NEAR_EXPIRY / DEPLETED（DeviceSkuLot.status 默认 ON_SALE），
    #    **没有 ACTIVE 这个值**。手写夹具成 ACTIVE 会让批次对消费者不可售：
    #    sumSellableQuantity 只认 IN ('ON_SALE','NEAR_EXPIRY')，开门直接 409，
    #    且 syncAggregateInventory 会把汇总账面覆盖成 0（2026-11-02 本脚本自己踩过）。
    $sellableSql = @"
SELECT COALESCE(sum(quantity),0) FROM device_sku_lot
WHERE device_id = '$DeviceId' AND sku_id = '$Sku' AND status IN ('ON_SALE','NEAR_EXPIRY');
"@
    $sellable = [int](Invoke-E2ePsql -Sql $sellableSql)
    if ($sellable -lt 2) {
        throw "演示柜 $Sku 可售批次库存只有 $sellable（本脚本要 2 件）⇒ 先补账面：UPDATE device_sku_lot SET quantity=8, status='ON_SALE' WHERE device_id='$DeviceId' AND sku_id='$Sku'"
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

    # ============ 腿 5（MA-043 另一半）：改单**新增**的 SKU 必须按当前规则定价 ============
    # 同一次改单里同时验两件事：原有行沿用成交时的活动决策（腿 1b 已证），新增行读当下活动。
    # 演示柜在售只有 100013 ⇒ 为 $Sku2 建账面批次与汇总（跑完**刻意保留**：删掉会让这单的销售流水对不上批次，
    # 触发巡检 MISSING_SALE；等价于「新 SKU 上架后卖掉一件」的正常终态）。
    $Sku2 = "100014"
    if ($Sku2 -eq $Sku) { throw "腿5 需要第二款 SKU，但 $Sku2 就是主测 SKU——换一个或先修正夹具活动的目标 SKU" }
    $Sku2ListPrice = [int](Invoke-E2ePsql -Sql "SELECT price_cents FROM sku_catalog WHERE sku_id = '$Sku2';")
    $Sku2PreExisted = (Invoke-E2ePsql -Sql "SELECT count(*) FROM device_sku_inventory WHERE device_id = '$DeviceId' AND sku_id = '$Sku2';").Trim()
    $script:Sku2Cleanup = $Sku2PreExisted -eq "0"
    $bannerAsset = (Invoke-E2ePsql -Sql "SELECT asset_id FROM promotion_placement WHERE position = 'BANNER' ORDER BY activity_id LIMIT 1;").Trim()
    if (-not $bannerAsset -or -not $Sku2ListPrice) { throw "腿5 前置缺失：bannerAsset=$bannerAsset sku2Price=$Sku2ListPrice" }
    $invSql2 = @"
INSERT INTO device_sku_inventory (device_id, sku_id, quantity, capacity, low_threshold, version, updated_at)
VALUES ('$DeviceId', '$Sku2', 8, 10, 2, 0, NOW())
ON CONFLICT (device_id, sku_id) DO UPDATE SET quantity = 8, updated_at = NOW();
INSERT INTO device_sku_lot (lot_id, device_id, sku_id, batch_no, expiry_date, quantity, slot_id, status, created_at, updated_at)
SELECT 'E2EMA043B', '$DeviceId', '$Sku2', 'E2E-MA043', (CURRENT_DATE + 180), 8, 'A2', 'ON_SALE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM device_sku_lot WHERE device_id = '$DeviceId' AND sku_id = '$Sku2' AND batch_no = 'E2E-MA043');
"@
    $null = Invoke-E2ePsql -Sql $invSql2

    $actBody = @{
        activityName = "MA043-新增行现价-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        activityType = "SKU_REDUCE"
        startTime = [DateTime]::UtcNow.AddMinutes(-60).ToString("o")
        endTime = [DateTime]::UtcNow.AddDays(7).ToString("o")
        budgetCents = 0
        userLimit = 0
        deviceScope = "ALL"
        ruleConfig = (@{ deviceIds = @(); merchantIds = @(); skuIds = @($Sku2); totalOrderLimit = 0 } | ConvertTo-Json -Compress)
        description = "e2e MA-043 腿5：新增行按当前活动定价"
        reduceCents = 50
    }
    # 可重跑：上一轮留下的 ACTIVE 活动 B 会被复用（定价取最优，同折扣时低编号先赢 ⇒ 不能盲目再建一个）
    $reuseSql = @"
SELECT activity_id FROM promotion_activity a
WHERE a.is_deleted = FALSE AND a.status = 'ACTIVE' AND a.activity_type = 'SKU_REDUCE'
  AND COALESCE(a.reduce_cents, 0) = 50 AND EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(COALESCE(a.rule_config -> 'skuIds', '[]'::jsonb)) e WHERE e = '$Sku2')
ORDER BY a.activity_id LIMIT 1;
"@
    $ActivityB = [long]("0" + (Invoke-E2ePsql -Sql $reuseSql).Trim())
    if ($ActivityB -gt 0) {
        Write-Host "    复用已有的 $Sku2 活动 B=$ActivityB（上一轮残留，避免两个同额活动抢定价）"
    } else {
        $actBResp = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/promotions" -Headers $Ops -Body $actBody
        $ActivityB = [long]$actBResp.activityId
        if (-not $ActivityB) { throw "腿5 创建活动 B 失败：$($actBResp | ConvertTo-Json -Compress)" }
    }
    $script:ActivityB = $ActivityB
    # 🔴 单个 placement 不能用 Invoke-E2eApi：e2e-lib 的 `$Body | ConvertTo-Json` 会把
    #    1 元素数组拆成裸对象（后端收到 `{"position":...}` 而非 `[{"position":...}]`）⇒ 400「请求体格式错误」。
    #    这里直接发手写 JSON 数组，绕开序列化陷阱（两个以上元素时不会踩，但夹具只挂一张横幅）。
    $placedCount = [int](Invoke-E2ePsql -Sql "SELECT count(*) FROM promotion_placement WHERE activity_id = $ActivityB AND position = 'BANNER';")
    if ($placedCount -lt 1) {
        $placeResp = Invoke-RestMethod -Method PUT -Uri "$BaseUrl/api/v2/ops/promotions/$ActivityB/placements" `
            -Headers $Ops -ContentType 'application/json' `
            -Body ('[{"position":"BANNER","assetId":' + [long]$bannerAsset + '}]')
        if ([int]$placeResp.code -ne 0) { throw "配置活动 B 展示位失败：code=$($placeResp.code) msg=$($placeResp.message)" }
    }
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/promotions/$ActivityB/launch" -Headers $Ops
    $actBStatus = (Invoke-E2ePsql -Sql "SELECT status FROM promotion_activity WHERE activity_id = $ActivityB;").Trim()
    Assert-Pass ($actBStatus -eq "ACTIVE") "腿5：活动 B=$ActivityB（$Sku2 立减 50）已投放（status=$actBStatus）"

    $before5 = Get-LineSnapshot -OrderId $leg1.OrderId -SkuId $Sku
    $balBefore5 = [int](Invoke-E2ePsql -Sql "SELECT balance_cents FROM user_account WHERE user_id = $UserId;")
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path "/api/v2/ops/disputes/$($leg1.TicketId)/reopen" -Headers $Ops -Body @{ note = 'e2e MA-043 新增行复验' }
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path "/api/v2/ops/disputes/$($leg1.TicketId)/resolve" -Headers $Ops -Body @{
        resolutionType = "CONFIRM"
        videoReviewed  = $true
        items          = @(@{ skuId = $Sku; quantity = $Qty }, @{ skuId = $Sku2; quantity = 1 })
    }
    $r5 = Get-OrderRow $leg1.OrderId
    $oldLine5 = Get-LineSnapshot -OrderId $leg1.OrderId -SkuId $Sku
    $newLine5 = (Invoke-E2ePsql -Sql "SELECT unit_price_cents || '|' || COALESCE(activity_id::text,'NULL') FROM cabinet_order_line WHERE order_id = '$($leg1.OrderId)' AND sku_id = '$Sku2';").Trim()
    $lineSum5 = (Invoke-E2ePsql -Sql "SELECT COALESCE(sum(line_amount_cents),0) FROM cabinet_order_line WHERE order_id = '$($leg1.OrderId)';").Trim()
    # 🔴 改单补差走 ADJUST_CHARGE（OrderPaymentService.chargeDisputeDelta），**不是** CHARGE：
    #    按 CHARGE 计数判「有没有补收」必假红（实测第一轮就被这条判据骗过一次）
    $chargeSql = @"
SELECT operation_type || '|' || amount_cents FROM payment_operation
WHERE order_id = '$($leg1.OrderId)' AND status = 'COMPLETED' ORDER BY created_at;
"@
    $ops5 = (Invoke-E2ePsql -Sql $chargeSql)
    $adjust5 = @($ops5 -split "\r?\n" | Where-Object { $_ -like 'ADJUST_CHARGE|*' })
    $balAfter5 = [int](Invoke-E2ePsql -Sql "SELECT balance_cents FROM user_account WHERE user_id = $UserId;")
    $newUnit5 = [int]$newLine5.Split('|')[0]
    Write-Host ("    腿5 改单后 total=" + $r5.Total + " 行合计=" + $lineSum5 + " 原行=" + $oldLine5 +
        " 新增行=" + $newLine5 + " 券=" + $r5.CouponDisc + "/" + $r5.CouponId +
        " 流水=" + (($ops5 -split "\r?\n") -join ' / ') + " 余额 $balBefore5→$balAfter5")

    Assert-Pass ($oldLine5 -eq $before5) "MA-043：新增行的同时，原 100013 行仍逐字不变（$oldLine5）"
    Assert-Pass ($newLine5.Split('|')[1] -eq "$ActivityB") "MA-043：新增行按**当前**活动定价（activity_id=$ActivityB）"
    Assert-Pass ([int]$newLine5.Split('|')[0] -lt $Sku2ListPrice) `
        "MA-043：新增行单价低于目录价（$($newLine5.Split('|')[0]) < $Sku2ListPrice ⇒ 确实读到当下规则，不是漏定价）"
    Assert-Pass ($r5.Total -eq [int]$lineSum5) "MA-043：改单应付=行合计（$($r5.Total)，新增行命中活动 ⇒ 订单级仍禁券）"
    Assert-Pass ($r5.CouponDisc -eq 0 -and $r5.CouponId -eq "NULL") "MA-043：带活动的改单不叠券（coupon_id=$($r5.CouponId)）"
    Assert-Pass ($adjust5.Count -eq 1) "MA-043：新增行产生一条补差流水 ADJUST_CHARGE（实得 $($adjust5.Count) 条）"
    Assert-Pass (($balBefore5 - $balAfter5) -eq $newUnit5) `
        "MA-043：补差实扣=新增行金额（$balBefore5 → $balAfter5 = $newUnit5）"


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

    # ============ 腿 4（MA-045）：渠道已退成功之后，三种本地落点都必须收敛 ============
    # 落点①渠道+本地全完成 ⇒ 幂等成功；落点③prepare 已提交、finalize 没跑 ⇒ 只补 finalize；
    # 落点②prepare 完全没跑（行/库存/应付都在退款前）⇒ 重跑 prepare+finalize，且绝不再调用渠道。
    # 注入方式是**状态级**（拿一次真实成功退款后的库状态往回倒），不往生产代码里塞故障钩子。
    $OrderId4 = $leg1.OrderId
    $refundBody = @{
        reason = "e2e MA-045 按行退款（腿1 活动单）"
        lines  = @(@{ skuId = $Sku; quantity = 1; restoreInventory = $true })
    }
    # 容量 10：账面先压到 8，退 1 件回补 1 件才看得见（贴着容量会被夹住，判据失去牙齿）
    $null = Set-FixtureStock -DevId $DeviceId -SkuId $Sku -Qty 8
    $preRow = Get-OrderRow $OrderId4
    $preLine = Get-LineSnapshot -OrderId $OrderId4 -SkuId $Sku
    $preStock = Get-StockSnapshot -DeviceId $DeviceId -SkuId $Sku
    $null = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
        -Path "/api/v2/ops/admin/orders/$OrderId4/refund" -Headers $Ops -Body $refundBody
    $gold = Get-OrderRow $OrderId4
    $goldLine = Get-LineSnapshot -OrderId $OrderId4 -SkuId $Sku
    $goldStock = Get-StockSnapshot -DeviceId $DeviceId -SkuId $Sku
    $op = Get-RefundOpRow -OrderId $OrderId4
    $hasMarker = $op.Reason.Contains('[LOCAL_PREPARED]')
    Write-Host ("    腿4 基准：退款前 total=$($preRow.Total) 行=$($preLine) 库存=$($preStock) ⇒ 退款后 " +
        "$($gold.Status) total=$($gold.Total) refunded=$($gold.Refunded) 行=$($goldLine) 库存=$($goldStock) 幂等键=$($op.IdemKey)")

    Assert-Pass ($gold.Status -eq "PARTIAL_REFUNDED" -and $gold.Refunded -eq $op.Amount) `
        "腿4：退 1 件留 1 件 ⇒ PARTIAL_REFUNDED，已退累计=渠道退款额（$($gold.Refunded)）"
    Assert-Pass $hasMarker "腿4：prepare 与 [LOCAL_PREPARED] 标记同事务落库"
    Assert-StockConsistent -StockSpec $goldStock -Tag "腿4 基准"

    # ---- 落点①：一切完成后同参重放 ----
    $errOne = Invoke-RefundTry -OrderId $OrderId4 -Body $refundBody
    $rOne = Get-OrderRow $OrderId4
    Assert-Pass (-not $errOne) ("落点①：同参重复退款不再 409 死路（修复前必 409「请勿重复提交」）" +
        $(if ($errOne) { " ⇒ 实际：$errOne" } else { "" }))
    Assert-Pass ($rOne.Total -eq $gold.Total -and $rOne.Refunded -eq $gold.Refunded) `
        "落点①：金额与已退累计未被二次摊薄（$($rOne.Total)/$($rOne.Refunded)）"

    # ---- 落点③：prepare 已提交、finalize 没跑（行与库存留在退款后，状态/已退回退，标记保留）----
    Set-RefundCrashState -OrderId $OrderId4 -SkuId $Sku -DevId $DeviceId -Total $gold.Total `
        -LineSpec $goldLine -StockSpec $goldStock -Prepared $true -IdemKey $op.IdemKey -Reason $op.Reason
    Write-Host "    注入落点③：状态回 PAID、已退归 0、行与库存留在退款后、标记保留"
    $errThree = Invoke-RefundTry -OrderId $OrderId4 -Body $refundBody
    $rThree = Get-OrderRow $OrderId4
    $stockThree = Get-StockSnapshot -DeviceId $DeviceId -SkuId $Sku
    $cntThree = (Invoke-E2ePsql -Sql "SELECT count(*) FROM payment_operation WHERE order_id = '$OrderId4' AND operation_type='REFUND' AND status='COMPLETED';").Trim()
    Assert-Pass (-not $errThree) ("落点③：只补 finalize 即可收敛，不再 409" + $(if ($errThree) { " ⇒ 实际：$errThree" } else { "" }))
    Assert-Pass ($rThree.Status -eq $gold.Status -and $rThree.Total -eq $gold.Total -and $rThree.Refunded -eq $gold.Refunded) `
        "落点③：收敛到基准态（$($rThree.Status) total=$($rThree.Total) refunded=$($rThree.Refunded)）"
    Assert-Pass ($cntThree -eq "1") "落点③：渠道未被二次调用（REFUND COMPLETED=$cntThree）"
    Assert-StockConsistent -StockSpec $stockThree -Tag "落点③"

    # ---- 落点②：渠道已退、prepare 完全没跑（行/库存/应付全回退，标记抹掉）----
    Set-RefundCrashState -OrderId $OrderId4 -SkuId $Sku -DevId $DeviceId -Total $preRow.Total `
        -LineSpec $preLine -StockSpec $preStock -Prepared $false -IdemKey $op.IdemKey -Reason $op.Reason
    Write-Host "    注入落点②：行/库存/应付全部回到退款前，并抹掉 [LOCAL_PREPARED] 标记"
    $errTwo = Invoke-RefundTry -OrderId $OrderId4 -Body $refundBody
    $rTwo = Get-OrderRow $OrderId4
    $lineTwo = Get-LineSnapshot -OrderId $OrderId4 -SkuId $Sku
    $stockTwo = Get-StockSnapshot -DeviceId $DeviceId -SkuId $Sku
    $cntTwo = (Invoke-E2ePsql -Sql "SELECT count(*) FROM payment_operation WHERE order_id = '$OrderId4' AND operation_type='REFUND' AND status='COMPLETED';").Trim()
    Assert-Pass (-not $errTwo) ("落点②：重跑 prepare+finalize 收敛，不再 409" + $(if ($errTwo) { " ⇒ 实际：$errTwo" } else { "" }))
    Assert-Pass ($rTwo.Status -eq $gold.Status -and $rTwo.Total -eq $gold.Total -and $rTwo.Refunded -eq $gold.Refunded) `
        "落点②：收敛到基准态（$($rTwo.Status) total=$($rTwo.Total) refunded=$($rTwo.Refunded)）"
    Assert-Pass ($lineTwo -eq $goldLine) "落点②：行只被扣了一次（$lineTwo = 基准 $goldLine）"
    Assert-Pass ($stockTwo -eq $goldStock) "落点②：库存只回补了一次（$stockTwo = 基准 $goldStock）"
    Assert-Pass ($cntTwo -eq "1") "落点②：渠道未被二次调用（REFUND COMPLETED=$cntTwo）"
    Assert-StockConsistent -StockSpec $stockTwo -Tag "落点②"

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
    if ($script:ActivityB) {
        $st = (Invoke-E2ePsql -Sql "SELECT status FROM promotion_activity WHERE activity_id = $script:ActivityB;").Trim()
        Write-Host "    残留（刻意保留，与夹具活动 4 同性质）：活动 $script:ActivityB status=$st；演示柜 100014 的账面批次/汇总与它的销售流水一起留着，删了会让台账 MISSING_SALE"
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



