# Replenishment E2E — plan → (optional warehouse pick/ship) → check-in → open-door → complete
# Usage:
#   .\scripts\e2e-replenishment.ps1
#   .\scripts\e2e-replenishment.ps1 -BaseUrl http://localhost:18080
#   .\scripts\e2e-replenishment.ps1 -FieldOnly   # 现场补货线；若 plan 已挂出库单仍须 pick/ship，否则 complete 409
#   .\scripts\e2e-replenishment.ps1 -ForceGap    # 无缺口时自动盘点清零一条货道造缺口（**会写真实账面库存**）
#
# 注意：-FieldOnly 不是「跳过出库」，而是「允许在无计划明细时提交现场 RESTOCK」。
# 运营 plan 常会同步生成 outbound；完成任务前必须把关联出库单发运。
#
# ── -ForceGap 是干什么的（2026-09-19 补）────────────────────────────────────────
# 补货建议的数据源是 device_slot：账面 < min_level 才有缺口。而**跑过一次本脚本后**，
# 默认柜机的货道会被补到 max_level（全满）⇒ 之后 /replenishment/suggest 恒 0 条 ⇒
# 脚本第二次必在第 3 步失败。这是「库存真的不缺」的**合法稳态**，不是缺陷，但让 E2E
# 不可重复运行。加了本开关后：无缺口时自动把账面最多的那条货道**盘点清零**，
# 从而稳定造出真实缺口。
# 🔴 它走的是产品自身的盘点接口（不是 DB 硬改），但**会写真实账面库存 + 记盘亏流水**，
#    因此**默认关闭**，必须显式传 -ForceGap。
#
# ── 这个脚本归谁跑（2026-09-18 补写）────────────────────────────────────────────
# 【本地手工工具】Windows + PowerShell 5.1 + 已起的整栈。**CI（Linux）不跑它**，
# 也不该跑：它要真实登录、真实 MQTT 门事件、真实凭证上传。
#   · 它的**签到契约**部分由 `scripts/check-replenishment-checkin-contract.mjs`
#     （`check:replenishment-checkin-contract`，聚合链第 2 位）**静态值级**守着，改文案/常量不改脚本即红；
#   · 它的**实跑**由 `scripts/e2e-checkin-contract.ps1` 覆盖（带夹具，19/19），本脚本负责**全链路**。
# ⚠️ 所以：别把「CI 里没跑它」读成「没被覆盖」（会白修）；也别把「本脚本绿」读成「CI 等价物」（会白信）。
# 前置：目标柜机**必须有货道**（device_slot 非空），否则补货建议恒空 —— 脚本会自动套用
# planogram 模板补齐；若仍无缺口，会**即刻**报错并给出处置方式，而不是把失败推迟到第 3 步。

param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "330449777078",
    [string]$OpsPhone = "13900000001",
    [string]$OpsPassword = "123456",
    [string]$MerchantPhone = "13800138001",
    [string]$MerchantPassword = "123456",
    # 🔴 必须是 0（=「用登录返回的 userId」）。这里曾硬编码 100000002，而该 id 在 user_info 里
    #    根本不存在（商户 13800138001 实为 100000030）⇒ 下面 `-le 0` 的解析分支成了死代码，
    #    plan 写 replenishment_route.assignee_user_id 时撞 FK
    #    `replenishment_route_assignee_user_id_fkey` ⇒ 500「系统繁忙」，
    #    真实的「指派人不存在」被完全盖住（2026-09-18 实测，追踪号 ecbe4dedbcc8）。
    [long]$MerchantUserId = 0,
    [string]$SkuId = "SKU-DEMO-001",
    [string]$SlotId = "A1",
    [int]$Quantity = 1,
    [string]$InternalApiKey = "dev-internal-key-change-me",
    [switch]$FieldOnly,
    # 无缺口时自动盘点清零一条货道以造出缺口（**会写真实账面库存**，默认关闭）
    [switch]$ForceGap
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl

function Invoke-E2eRestockDoorCycle {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [string]$DeviceId,
        [string]$InternalApiKey
    )
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
        sessionId = $SessionId
        deviceId  = $DeviceId
        doorState = "OPEN"
        timestamp = $ts
    } | Out-Null
    Start-Sleep -Milliseconds 400
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
        sessionId    = $SessionId
        deviceId     = $DeviceId
        doorState    = "CLOSED"
        timestamp    = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        uploadStatus = "UPLOADED"
        videoUri     = "minio://cabinet-videos/$(Get-E2eSimVideoKey -SessionId $SessionId -DeviceId $DeviceId)"
    } | Out-Null
}

Write-Host "==> 0. Cleanup blocking sessions on $DeviceId"
Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null

Write-Host "==> 1. Ops login ($OpsPhone)"
$opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
    phoneNumber = $OpsPhone
    password    = $OpsPassword
}
$opsAuth = @{ Authorization = "Bearer $($opsLogin.token)" }
$opsUserId = [long]$opsLogin.userId
Write-Host "    opsUserId=$opsUserId"

Write-Host "==> 2. Merchant login ($MerchantPhone)"
$mchLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
    phoneNumber = $MerchantPhone
    password    = $MerchantPassword
}
$mchAuth = @{ Authorization = "Bearer $($mchLogin.token)" }
if (-not $MerchantUserId -or $MerchantUserId -le 0) {
    $MerchantUserId = [long]$mchLogin.userId
}
Write-Host "    merchantUserId=$MerchantUserId"

$today = (Get-Date).ToString("yyyy-MM-dd")
$routeName = "E2E replenishment $today $([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"

Write-Host "==> 2b. Ensure warehouse stock for device replenishment gaps$(if ($ForceGap) { ' [-ForceGap: may zero a slot]' })"
Prepare-E2eReplenishmentPlan -BaseUrl $BaseUrl -OpsAuth $opsAuth -DeviceId $DeviceId -ForceGap:$ForceGap

Write-Host "==> 3. Plan replenishment route for $DeviceId"
$route = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/replenishment/plan" -Headers $opsAuth -Body @{
    routeName       = $routeName
    plannedDate     = $today
    assigneeUserId  = $MerchantUserId
    deviceIds       = @($DeviceId)
    startLatitude   = $null
    startLongitude  = $null
}
$routeId = $route.routeId
$task = @($route.tasks) | Where-Object { $_.deviceId -eq $DeviceId } | Select-Object -First 1
if (-not $task) {
    throw "Plan created route=$routeId but no task for $DeviceId"
}
$taskId = [long]$task.taskId
Write-Host "    routeId=$routeId taskId=$taskId status=$($task.status)"

$outboundId = $null
$useWarehouse = $false
# plan 常会同步生成出库单；即使 -FieldOnly 也必须先发运，否则 complete 会 409
Write-Host "==> 4. Resolve warehouse outbound (if any)$(if ($FieldOnly) { ' [FieldOnly: still ship when linked]' })"
try {
    # API 返回 PageResult，须取 items；勿把整页对象当出库单数组过滤
    $outboundPage = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/warehouse/outbounds?page=0&size=200" -Headers $opsAuth
    $outboundRows = @()
    if ($null -ne $outboundPage) {
        if ($outboundPage.items) { $outboundRows = @($outboundPage.items) }
        elseif ($outboundPage -is [System.Array]) { $outboundRows = @($outboundPage) }
        else { $outboundRows = @($outboundPage) }
    }
    $linked = $outboundRows | Where-Object { [long]$_.routeId -eq [long]$routeId } | Select-Object -First 1
    if (-not $linked) {
        Write-Host "    no outbound for route — field restock path"
    } else {
        $useWarehouse = $true
        $outboundId = [long]$linked.outboundId
        Write-Host "    outboundId=$outboundId status=$($linked.status)"
        $st = [string]$linked.status
        if ($st -notin @("PICKED", "SHIPPED", "IN_TRANSIT", "RECEIVED", "COMPLETED")) {
            Write-Host "==> 4b. Pick outbound (was $st)"
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path "/api/v2/ops/admin/warehouse/outbounds/$outboundId/pick" -Headers $opsAuth | Out-Null
            $st = "PICKED"
        }
        if ($st -notin @("SHIPPED", "IN_TRANSIT", "RECEIVED", "COMPLETED")) {
            Write-Host "==> 4c. Ship outbound"
            Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
                -Path "/api/v2/ops/admin/warehouse/outbounds/$outboundId/ship" -Headers $opsAuth | Out-Null
        }
    }
} catch {
    if (-not $FieldOnly) { throw }
    Write-Warning "Warehouse path failed, continuing field restock: $_"
    $useWarehouse = $false
    $outboundId = $null
}

Write-Host "==> 5. Merchant check-in — 契约用例（fail-closed）+ 正式签到"

# ── 签到契约真值块 ────────────────────────────────────────────────────────────
# 本块被 scripts/check-replenishment-checkin-contract.mjs **静态校验**：
#   · 三条文案必须是 ApiMessages.java 里对应常量的子串（改了文案不改这里 ⇒ 门禁红）
#   · MaxDistanceDefaultM 必须等于 SystemConfigService 中 upsertIfAbsent 的默认值
#   · BoundaryInsideM < MaxDistanceDefaultM < BoundaryOutsideM
# 背景：本脚本曾把「柜机无坐标」当成「跳过校验、发空 body」的**放行**路径。
#      服务端改成「无坐标拒签」(fail-closed) 后那条路径必然 400 —— 脚本必须跟着契约走，
#      而不是跟着旧假设走；否则只要柜机漏填坐标，脚本第 5 步就必红且原因难辨认。
$CheckInContract = @{
    DeviceLocationMissingMessage = "本柜尚未录入点位坐标"
    LocationRequiredMessage      = "请开启定位后到柜前签到"
    TooFarMessage                = "签到位置距柜机约"
    TaskFinishedMessage          = "补货任务已结束"
    MaxDistanceDefaultM          = 500
    BoundaryInsideM              = 450
    BoundaryOutsideM             = 600
}
# 服务端距离用 haversine（R=6371000）⇒ 1 纬度 ≈ 111194.9 m。边界用例靠纯纬度偏移构造。
$MetersPerDegreeLat = 111194.9

$deviceLat = $null
$deviceLng = $null
try {
    $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/devices/$DeviceId" -Headers $opsAuth
    if ($dev.latitude -ne $null) { $deviceLat = [double]$dev.latitude }
    elseif ($dev.device -and $dev.device.latitude -ne $null) { $deviceLat = [double]$dev.device.latitude }
    if ($dev.longitude -ne $null) { $deviceLng = [double]$dev.longitude }
    elseif ($dev.device -and $dev.device.longitude -ne $null) { $deviceLng = [double]$dev.device.longitude }
} catch {
    throw "读取柜机 $DeviceId 坐标失败（签到契约靠它选分支）：$_"
}
$deviceHasCoords = ($null -ne $deviceLat -and $null -ne $deviceLng)
$checkInPath = "/api/v2/merchant/replenishment/tasks/$taskId/check-in"

if (-not $deviceHasCoords) {
    # fail-closed 分支：柜机无坐标 ⇒ 契约要求**任何**请求体都被拒。
    # 这里必须直接失败退出，而不是「凑合继续」—— 后续 open-door / complete 都依赖签到成功。
    Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
        -Body @{} -ExpectStatus 400 `
        -ExpectMessageContains $CheckInContract.DeviceLocationMissingMessage `
        -Label "P0 柜机无坐标 + 空 body" | Out-Null
    Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
        -Body @{ latitude = 31.2304; longitude = 121.4737 } -ExpectStatus 400 `
        -ExpectMessageContains $CheckInContract.DeviceLocationMissingMessage `
        -Label "P0 柜机无坐标 + 带合法坐标（旧逻辑会放行的那条路径）" | Out-Null
    throw ("柜机 $DeviceId 未配置点位坐标：签到契约要求拒签（fail-closed），补货流程无法继续。" +
        "请先在运营后台补录该柜机经纬度，然后重跑本脚本。")
}

Write-Host "    device coords lat=$deviceLat lng=$deviceLng"

# 负向用例之前先给任务**整行**拍快照（DB 通道）：被拒之后要比的是「值」，
# 而不是「taskId 在不在某个状态列表里」—— 后者在走仓库链路时必然假红，见下方说明。
$taskRowBefore = Get-E2eTaskRow -TaskId $taskId

# 负向用例 1：请求侧闸 —— 柜机有坐标时签到必须带定位
Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
    -Body @{} -ExpectStatus 400 `
    -ExpectMessageContains $CheckInContract.LocationRequiredMessage `
    -Label "P1 空 body（期望 location-required）" | Out-Null

# 负向用例 2：距离闸上界 —— 超出 max_distance_m 必须被拒
$outsideLat = $deviceLat + ($CheckInContract.BoundaryOutsideM / $MetersPerDegreeLat)
Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
    -Body @{ latitude = $outsideLat; longitude = $deviceLng } -ExpectStatus 400 `
    -ExpectMessageContains $CheckInContract.TooFarMessage `
    -Label ("P2 距柜机约 {0}m（上限 {1}m）" -f $CheckInContract.BoundaryOutsideM, $CheckInContract.MaxDistanceDefaultM) | Out-Null

# 负向用例不得产生任何写入（防「先写 check_in_at 再抛错」这种半成品闸门）。
#
# 🔴 判据必须比「值」，不能比「存在性」。旧判据是「taskId 有没有出现在
#    ?status=IN_PROGRESS 列表里」—— 在走仓库链路的完整流程里**必然假红**：
#    第 4c 步发运出库时 OpsWarehouseAdminService.shipWarehouseOutbound()（第 101 行）
#    会调 generateLinesFromOutbound() → generateLinesForTask()，而该方法**只改 status、
#    不写 check_in_at**（ReplenishmentService.java:547-550），于是任务在负向签到**之前**
#    就已经是 IN_PROGRESS。
#    实测 2026-09-18 run5：task=14 status=IN_PROGRESS 且 check_in_at / check_in_lat /
#    check_in_lng 三字段全空 ⇒ 负向用例零写入，是**判据错了**，不是产品错了。
#    （设计上「IN_PROGRESS + check_in_at=NULL」是合法稳态：ReplenishmentTimeoutScheduler
#     只收口 check_in_at < cutoff 的行，SQL 里 NULL 不参与比较，不会被误收口。）
#
# 新判据：负向用例前后逐字段比对整行（status + check_in_at / lat / lng）。
# 它同时覆盖原意 —— doCheckInTask 里 setCheckInAt（621 行）先于 setStatus（624 行），
# 若闸门顺序被改坏（先落库再抛错），check_in_at 必被改写，此处会红。
Assert-E2eTaskRowUnchanged -TaskId $taskId -Before $taskRowBefore `
    -Label "签到负向用例(P1/P2 均被拒)后整行未动(DB)" | Out-Null

# 正式签到：距离闸下界内（< max_distance_m）
$insideLat = $deviceLat + ($CheckInContract.BoundaryInsideM / $MetersPerDegreeLat)
$checked = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
    -Body @{ latitude = $insideLat; longitude = $deviceLng }
if ($null -eq $checked.checkInAt -or $null -eq $checked.checkInLat -or $null -eq $checked.checkInLng) {
    throw "签到成功但未落库 checkInAt/check_in_lat/check_in_lng：$($checked | ConvertTo-Json -Depth 4 -Compress)"
}
Write-Host "    status=$($checked.status) checkInAt=$($checked.checkInAt) distanceM=$($checked.checkInDistanceM)"

Write-Host "==> 6. Merchant open-door"
$session = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
    -Path "/api/v2/merchant/replenishment/tasks/$taskId/open-door" -Headers $mchAuth
$sessionId = $session.sessionId
if (-not $sessionId) { throw "open-door returned empty sessionId" }
Write-Host "    sessionId=$sessionId state=$($session.state)"

Write-Host "==> 7. Restock door OPEN → CLOSED (no consumer bill)"
Invoke-E2eRestockDoorCycle -BaseUrl $BaseUrl -SessionId $sessionId -DeviceId $DeviceId -InternalApiKey $InternalApiKey

$lines = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
    -Path "/api/v2/merchant/replenishment/tasks/$taskId/lines" -Headers $mchAuth)
Write-Host "==> 8. Task lines count=$($lines.Count)"

if ($lines.Count -eq 0) {
    $pickSku = $null
    $pickSlot = $null
    try {
        $slots = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/ops/admin/devices/$DeviceId/slots" -Headers $opsAuth)
        $room = $slots | Where-Object {
            $_.enabled -and $_.assignedSkuId -and ([int]$_.maxLevel - [int]$_.bookQty) -ge 1
        } | Sort-Object { [int]$_.maxLevel - [int]$_.bookQty } -Descending | Select-Object -First 1
        if ($room) {
            $pickSku = [string]$room.assignedSkuId
            $pickSlot = [string]$room.slotCode
            Write-Host "    auto-picked slot=$pickSlot sku=$pickSku headroom=$([int]$room.maxLevel - [int]$room.bookQty)"
        }
    } catch {
        Write-Warning "Slot lookup failed: $_"
    }

    if (-not $pickSku -and $SkuId -and $SlotId) {
        $pickSku = $SkuId
        $pickSlot = $SlotId
        Write-Host "    fallback to -SkuId/-SlotId params: slot=$pickSlot sku=$pickSku"
    }

    if ($pickSku -and $pickSlot) {
        $expiry = (Get-Date).AddMonths(6).ToString("yyyy-MM-dd")
        $qty = [Math]::Max(1, $Quantity)
        Write-Host "    submitting field RESTOCK line sku=$pickSku slot=$pickSlot qty=$qty expiry=$expiry"
        $json = @"
{"lines":[{"lineType":"RESTOCK","skuId":"$pickSku","batchNo":"E2E-$today","productionDate":null,"expiryDate":"$expiry","quantity":$qty,"slotId":"$pickSlot","applied":false}]}
"@
        $uri = "$BaseUrl/api/v2/merchant/replenishment/tasks/$taskId/lines"
        $resp = Invoke-RestMethod -Method POST -Uri $uri -Headers $mchAuth `
            -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
        if ($resp.code -ne 0) { throw "API error: $($resp.message) (path=/lines)" }
    } else {
        Write-Warning "No slot with headroom on $DeviceId; completing without RESTOCK lines"
    }
} else {
    Write-Host "    warehouse lines already present — skip re-submit"
}

Write-Host "==> 9. Upload site evidence (when require_evidence=true; skip if ops disabled the gate)"
# Note: require_door / require_location / max_distance_m are also system_config toggles (defaults true/500).

$evidencePng = Join-Path ([IO.Path]::GetTempPath()) "e2e-replenishment-evidence-$taskId.png"
# 1x1 PNG
[IO.File]::WriteAllBytes($evidencePng, [Convert]::FromBase64String(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
$evidenceUri = "$BaseUrl/api/v2/merchant/replenishment/tasks/$taskId/evidence"
$curlArgs = @(
    "-s", "-X", "POST", $evidenceUri,
    "-H", "Authorization: Bearer $($mchLogin.token)",
    "-F", "file=@$evidencePng;type=image/png"
)
$evidenceRaw = & curl.exe @curlArgs
$evidenceResp = $evidenceRaw | ConvertFrom-Json
if ($null -eq $evidenceResp -or [int]$evidenceResp.code -ne 0) {
    throw "evidence upload failed: $evidenceRaw"
}
Write-Host "    evidence fileId=$($evidenceResp.data.fileId)"

Write-Host "==> 10. Complete task"
$done = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST `
    -Path "/api/v2/merchant/replenishment/tasks/$taskId/complete" -Headers $mchAuth
if ($done.status -ne "COMPLETED") {
    throw "Expected task COMPLETED, got $($done.status)"
}
Write-Host "    taskId=$taskId COMPLETED outboundId=$($done.outboundId)"

Write-Host "==> 11. Assert merchant task list reflects completion"
$tasks = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
    -Path "/api/v2/merchant/replenishment/tasks?status=COMPLETED" -Headers $mchAuth
$found = @($tasks) | Where-Object { [long]$_.taskId -eq $taskId } | Select-Object -First 1
if (-not $found) {
    throw "Completed task $taskId not found in merchant COMPLETED list"
}

Write-Host ""
$pathLabel = if ($useWarehouse -and $outboundId) {
    if ($FieldOnly) { "field+shipped-outbound" } else { "warehouse" }
} else {
    "field-only"
}
Write-Host "OK replenishment E2E passed ($pathLabel) taskId=$taskId sessionId=$sessionId"
exit 0
