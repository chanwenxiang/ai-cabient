# 补货签到 fail-closed 的**实跑**验证（需要已起的开发栈 + 可用的运营/商户账号）。
#
# 与静态门禁 `scripts/check-replenishment-checkin-contract.mjs` 的分工：
#   静态门禁 —— 证明「脚本里声明的期望值」== 「服务端源码真值」。离线可跑，接在聚合链上。
#   本脚本   —— 证明「那些期望值在真服务上确实成立」，并且**负向验证负向断言助手本身**：
#               如果 Assert-E2eApiRejected 退化成「抛了异常就算过」，下面的对照组会红 ——
#               这正是「假绿」最容易发生的地方（异常可能是超时、403、路径打错）。
#
# 期望值不在这里硬编码：本脚本从 `e2e-replenishment.ps1` 的 `$CheckInContract` 真值块里**读**，
# 所以不存在「两处各写一份、一起写错」的问题。
#
# 用法：
#   .\scripts\e2e-checkin-contract.ps1
#   .\scripts\e2e-checkin-contract.ps1 -BaseUrl http://localhost:18080 -DeviceId 777740024057
#   .\scripts\e2e-checkin-contract.ps1 -NoFixture        # 只用库里已有的可签到任务，绝不造数据
#
# ⚠️ 本脚本会在**目标任务**上真的签到一次，并在用例 8 里把它空取消。
#    默认目标是一个**现场造的夹具任务**（见 New-E2eCheckInFixtureTask），结束后无条件卸载；
#    用 -TaskId 显式指定时则直接打那个任务，请勿指向生产库。

param(
    [string]$BaseUrl = "",
    [string]$DeviceId = "",
    [long]$TaskId = 0,
    [string]$OpsPhone = "13900000001",
    [string]$OpsPassword = "123456",
    [string]$MerchantPhone = "13800138001",
    [string]$MerchantPassword = "123456",
    # 显式要求「不造夹具」：此时库里必须已存在 PENDING/IN_PROGRESS 任务，否则**失败**（不静默跳过）。
    [switch]$NoFixture
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "e2e-lib.ps1")
$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
$MetersPerDegreeLat = 111194.9

# ── 从 e2e-replenishment.ps1 读契约真值块（不复制、不硬编码）────────────────
$contractSrc = Get-Content -LiteralPath (Join-Path $PSScriptRoot "e2e-replenishment.ps1") -Raw
$blockMatch = [regex]::Match($contractSrc, '(?s)\$CheckInContract\s*=\s*@\{(.*?)\}')
if (-not $blockMatch.Success) {
    throw "在 e2e-replenishment.ps1 里找不到 `$CheckInContract 真值块"
}
$CheckInContract = @{}
foreach ($line in ($blockMatch.Groups[1].Value -split "`n")) {
    $m = [regex]::Match($line, '^\s*([A-Za-z]+)\s*=\s*(?:"([^"]*)"|(\d+))\s*$')
    if ($m.Success) {
        if ($m.Groups[2].Success) { $CheckInContract[$m.Groups[1].Value] = $m.Groups[2].Value }
        else { $CheckInContract[$m.Groups[1].Value] = [int]$m.Groups[3].Value }
    }
}
foreach ($k in @('DeviceLocationMissingMessage', 'LocationRequiredMessage', 'TooFarMessage',
        'TaskFinishedMessage', 'MaxDistanceDefaultM', 'BoundaryInsideM', 'BoundaryOutsideM')) {
    if (-not $CheckInContract.ContainsKey($k)) { throw "契约真值块缺少键 $k" }
}
Write-Host "==> 契约真值（读自 e2e-replenishment.ps1）"
Write-Host "    max_distance_m=$($CheckInContract.MaxDistanceDefaultM) 边界 $($CheckInContract.BoundaryInsideM)m/$($CheckInContract.BoundaryOutsideM)m"

$script:results = @()
$script:fixtureTaskId = 0
function Add-Result {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    $script:results += [pscustomobject]@{ ok = $Ok; name = $Name; detail = $Detail }
    if ($Ok) { $mark = 'PASS' } else { $mark = 'FAIL' }
    Write-Host ("  [{0}] {1} — {2}" -f $mark, $Name, $Detail)
}

# 主流程整体包在 try/finally 里：夹具任务必须在**任何**退出路径上被卸载 ——
# 否则残留一条 IN_PROGRESS+checkInAt 的任务会把柜机对消费者**永久冻结**（测试污染生产语义）。
try {

    # ── 登录 ───────────────────────────────────────────────────────────────
    Write-Host "==> 登录取 token"
    $opsLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" `
        -Body @{ phoneNumber = $OpsPhone; password = $OpsPassword }
    $opsAuth = @{ Authorization = "Bearer $($opsLogin.token)" }
    $mchLogin = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" `
        -Body @{ phoneNumber = $MerchantPhone; password = $MerchantPassword }
    $mchAuth = @{ Authorization = "Bearer $($mchLogin.token)" }

    # ── 选目标柜机：必须在「商户作用域内」且「已录坐标」两个集合的交集里 ──────
    #   作用域：requireScopedTask → requireDevicePack(userId, deviceId, FIELD)，越界 403；
    #   坐标  ：柜机无坐标时签到先被「无坐标拒签」闸挡下（400），拿它测 450m/600m 毫无意义。
    # 所以用「商户可见任务所在的柜机」∩「运营视角已录坐标」，两个条件各自有真实来源。
    $deviceRows = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/devices?page=0&size=500" -Headers $opsAuth
    if ($null -ne $deviceRows -and $null -ne $deviceRows.items) { $deviceRows = @($deviceRows.items) }
    else { $deviceRows = @($deviceRows) }
    $withCoords = @($deviceRows | Where-Object { $null -ne $_.latitude -and $null -ne $_.longitude })
    $coordIds = @($withCoords | ForEach-Object { [string]$_.deviceId })

    if ([string]::IsNullOrWhiteSpace($DeviceId)) {
        # 商户列表默认状态集是 [PENDING, IN_PROGRESS, COMPLETED]，足够用来取「作用域内柜机」。
        $scopedTasks = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/merchant/replenishment/tasks" -Headers $mchAuth)
        $pick = $scopedTasks | Where-Object { $coordIds -contains [string]$_.deviceId } | Select-Object -First 1
        if (-not $pick) {
            throw "找不到任何「在商户作用域内、且柜机已录坐标」的补货任务；请用 -DeviceId 显式指定"
        }
        $DeviceId = [string]$pick.deviceId
        Write-Host "    自动选中 device=$DeviceId（来源：商户可见任务 ∩ 已录坐标）"
    }

    $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/devices/$DeviceId" -Headers $opsAuth
    $deviceLat = [double]$dev.latitude
    $deviceLng = [double]$dev.longitude
    Write-Host "    device coords lat=$deviceLat lng=$deviceLng"

    # ── 确定「被测任务」：默认造夹具，而不是复用存量任务 ────────────────────
    # 为什么不能复用存量任务：本库里 task 1=COMPLETED、task 2=CANCELLED，**全是终态**；
    # 拿它们跑「空 body→400」「450m→200」会全部撞上终态闸（409），用例集体假失败。
    # 而 `suggest` 常年 no gaps（Prepare-E2eReplenishmentPlan 造不出缺口），也造不出可签到任务。
    if ($TaskId -gt 0) {
        Write-Host "==> 使用显式指定的 task=$TaskId（不造夹具）"
    } elseif ($NoFixture) {
        # 不带 status 参数（默认集已含 PENDING/IN_PROGRESS），再在客户端筛 ——
        # 写 `status=IN_PROGRESS` 会把 PENDING 任务漏掉，而 PENDING 恰恰是「可签到」的常态。
        $deviceTasks = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/merchant/replenishment/tasks?deviceId=$DeviceId" -Headers $mchAuth)
        $open = @($deviceTasks | Where-Object { $_.status -in @('PENDING', 'IN_PROGRESS') } | Select-Object -First 1)
        if (-not $open) {
            # -NoFixture 是「我确认库里已有可签到任务」的显式声明；声明不成立就必须失败，
            # 不能悄悄降级成「跳过用例」—— 那正是判据退化成装饰品的路径。
            throw "-NoFixture 已指定，但设备 $DeviceId 上没有 PENDING/IN_PROGRESS 的补货任务"
        }
        $TaskId = [long]$open.taskId
        Write-Host "==> 使用库内既有 task=$TaskId（-NoFixture）"
    } else {
        Write-Host "==> 造夹具任务（PENDING，可签到）"
        $script:fixtureTaskId = New-E2eCheckInFixtureTask -DeviceId $DeviceId `
            -AssigneeUserId ([long]$mchLogin.userId)
        $TaskId = $script:fixtureTaskId
    }

    $checkInPath = "/api/v2/merchant/replenishment/tasks/$TaskId/check-in"
    Write-Host "==> 目标 device=$DeviceId task=$TaskId"

    $insideLat = $deviceLat + ($CheckInContract.BoundaryInsideM / $MetersPerDegreeLat)
    $outsideLat = $deviceLat + ($CheckInContract.BoundaryOutsideM / $MetersPerDegreeLat)

    Write-Host ""
    Write-Host "==> 用例 1-2：负向断言助手对**真拒绝**应通过"

    # 1) 请求侧闸：空 body
    try {
        Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
            -Body @{} -ExpectStatus 400 `
            -ExpectMessageContains $CheckInContract.LocationRequiredMessage `
            -Label "空 body" | Out-Null
        Add-Result "空 body → 400 location-required" $true $CheckInContract.LocationRequiredMessage
    } catch {
        Add-Result "空 body → 400 location-required" $false $_.Exception.Message
    }

    # 2) 距离闸上界
    try {
        Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
            -Body @{ latitude = $outsideLat; longitude = $deviceLng } -ExpectStatus 400 `
            -ExpectMessageContains $CheckInContract.TooFarMessage `
            -Label "超距 $($CheckInContract.BoundaryOutsideM)m" | Out-Null
        Add-Result "距柜机约 $($CheckInContract.BoundaryOutsideM)m → 400 too-far" $true $CheckInContract.TooFarMessage
    } catch {
        Add-Result "距柜机约 $($CheckInContract.BoundaryOutsideM)m → 400 too-far" $false $_.Exception.Message
    }

    Write-Host ""
    Write-Host "==> 用例 3-4：对照组 —— 助手**必须**能失败（这组才是防假绿的关键）"

    # 3) 假绿对照：请求其实会成功，却声明「期望被拒」⇒ 助手必须抛错。
    #    刻意换成**只读**的 GET：这里要证明的只是「成功不被误判成拒绝」这一条分支，
    #    用签到接口会在对照里真的把任务签掉（副作用），让后续用例的前置变得难以推理。
    $controlA = 'PASS'
    try {
        Assert-E2eApiRejected -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/merchant/replenishment/tasks?status=COMPLETED" -Headers $mchAuth `
            -ExpectStatus 400 -ExpectMessageContains $CheckInContract.LocationRequiredMessage `
            -Label "假绿对照（该 GET 实际 200）" | Out-Null
        $controlA = 'FAIL'
    } catch {
        $controlA = 'PASS'
    }
    if ($controlA -eq 'PASS') {
        Add-Result "对照：请求成功却期望被拒 ⇒ 助手报错" $true "未被「异常即通过」吞掉"
    } else {
        Add-Result "对照：请求成功却期望被拒 ⇒ 助手报错" $false "助手把它当成「已拒绝」放过了 = 假绿"
    }

    # 4) 报文对照：状态码对但文案错 ⇒ 必须抛错
    $controlB = 'PASS'
    try {
        Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
            -Body @{} -ExpectStatus 400 -ExpectMessageContains "这段文案不可能出现在响应里" `
            -Label "报文对照" | Out-Null
        $controlB = 'FAIL'
    } catch {
        $controlB = 'PASS'
    }
    if ($controlB -eq 'PASS') {
        Add-Result "对照：状态码对但文案不匹配 ⇒ 助手报错" $true "文案断言真的在比"
    } else {
        Add-Result "对照：状态码对但文案不匹配 ⇒ 助手报错" $false "文案断言形同虚设"
    }

    Write-Host ""
    Write-Host "==> 用例 5：正式签到（边界内 $($CheckInContract.BoundaryInsideM)m）"
    try {
        $checked = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
            -Body @{ latitude = $insideLat; longitude = $deviceLng }
        if ($null -eq $checked.checkInAt -or $null -eq $checked.checkInLat -or $null -eq $checked.checkInLng) {
            Add-Result "边界内签到 → 200 且落库" $false "checkInAt/checkInLat/checkInLng 有空值"
        } else {
            Add-Result "边界内签到 → 200 且落库" $true `
                ("status={0} distanceM={1}" -f $checked.status, $checked.checkInDistanceM)
        }
        # deviceHasCoords 必须**真的出现在响应里**：只断言 DTO 有字段＝只证明「声明」。
        # 客户端的前置拦完全建立在这个字段上，若它恒为 null，按钮就永远不会被禁用。
        if ($checked.deviceHasCoords -eq $true) {
            Add-Result "响应带 deviceHasCoords=true（客户端前置拦有依据）" $true `
                "device=$DeviceId 已录坐标"
        } else {
            Add-Result "响应带 deviceHasCoords=true（客户端前置拦有依据）" $false `
                ("实得 deviceHasCoords={0}（本柜已配置坐标，应为 true）" -f $checked.deviceHasCoords)
        }
    } catch {
        Add-Result "边界内签到 → 200 且落库" $false $_.Exception.Message
    }

    # 5b) DB 通道核对：签到确实落库，并且任务进入 IN_PROGRESS。
    #     这一步同时是**用例 8 的前置**——冻结判据是 (status=IN_PROGRESS 且 check_in_at≠null)，
    #     不先证明前置成立，后面「解冻没被撤销」就可能只是因为「从来没冻过」。
    try {
        $row = Assert-E2eTaskRowEquals -TaskId $TaskId -ExpectStatus 'IN_PROGRESS' `
            -ExpectCheckedIn $true -Label "签到落库核对"
        Add-Result "签到落库核对（DB 通道：IN_PROGRESS + check_in_at 已写）" $true `
            "check_in_at=$($row.checkInAt) lat=$($row.checkInLat) lng=$($row.checkInLng)"
    } catch {
        Add-Result "签到落库核对（DB 通道：IN_PROGRESS + check_in_at 已写）" $false $_.Exception.Message
    }

    Write-Host ""
    Write-Host "==> 用例 6-7：终态不可复活（COMPLETED / CANCELLED）"
    # 为什么必须实跑：静态门禁只能证明「签到与开门用了同一条文案 + 同在源码里」——
    # 两处一起写错（或顺序写反后仍保留文案）在源码层是看不出来的，只有真打一次 409 才算数。
    # ⚠️ 必须显式带 status：默认状态集 [PENDING, IN_PROGRESS, COMPLETED] **不含 CANCELLED**
    #    （MerchantInventoryPortalService.listReplenishmentTasks:176），不带就找不出 CANCELLED 任务，
    #    用例会「未覆盖」—— 而未覆盖与通过必须长得不一样。
    foreach ($st in @('COMPLETED', 'CANCELLED')) {
        $found = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
            -Path "/api/v2/merchant/replenishment/tasks?status=$st" -Headers $mchAuth)
        $pick = $found | Where-Object { [string]$_.status -eq $st } | Select-Object -First 1
        if (-not $pick) {
            # 不静默跳过：「没验证」与「验证通过」在结果里必须长得不一样，
            # 否则这条判据就会退化成「永远绿灯的装饰品」。
            Add-Result "终态 $st 拒签" $false `
                "找不到任何 $st 的补货任务（已按 ?status=$st 显式查询）—— 本用例**未覆盖**"
            continue
        }
        $tid = [long]$pick.taskId
        $tPath = "/api/v2/merchant/replenishment/tasks/$tid/check-in"
        # 请求体故意带**完全合法**的坐标：若终态闸没生效，这一发就会成功并把终态改回 IN_PROGRESS。
        $pickDeviceId = [string]$pick.deviceId
        $lat = $deviceLat
        $lng = $deviceLng
        if (-not [string]::IsNullOrWhiteSpace($pickDeviceId)) {
            try {
                $pd = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
                    -Path "/api/v2/ops/admin/devices/$pickDeviceId" -Headers $opsAuth
                if ($null -ne $pd.latitude -and $null -ne $pd.longitude) {
                    $lat = [double]$pd.latitude
                    $lng = [double]$pd.longitude
                }
            } catch {
                # 拿不到就退回目标柜机坐标，不影响「必须被拒」这个断言
            }
        }
        $before = Get-E2eTaskRow -TaskId $tid
        try {
            Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $tPath -Headers $mchAuth `
                -Body @{ latitude = $lat; longitude = $lng } -ExpectStatus 409 `
                -ExpectMessageContains $CheckInContract.TaskFinishedMessage `
                -Label "终态 $st 任务签到" | Out-Null
            Add-Result "终态 $st 拒签 → 409 且文案正确" $true `
                ("task=$tid / " + $CheckInContract.TaskFinishedMessage)
        } catch {
            Add-Result "终态 $st 拒签 → 409 且文案正确" $false $_.Exception.Message
        }
        # 通道一：API 重新拉列表
        try {
            Assert-E2eTaskStillTerminal -BaseUrl $BaseUrl -Headers $mchAuth -TaskId $tid `
                -ExpectStatus $st -Label "终态 $st 状态未变(API)" | Out-Null
            Add-Result "终态 $st 拒签后状态未变（API 通道）" $true "task=$tid 仍为 $st"
        } catch {
            Add-Result "终态 $st 拒签后状态未变（API 通道）" $false $_.Exception.Message
        }
        # 通道二：直接读原始行。只看 409 会被「先改状态再抛异常」骗过；
        # 而只比「状态还是终态」会被「状态没动但 check_in_at 被覆盖」骗过 ——
        # 所以这里比的是**整行关键字段的值**（status + check_in_at + lat + lng）。
        try {
            $after = Assert-E2eTaskRowUnchanged -TaskId $tid -Before $before -Label "终态 $st 整行未动(DB)"
            Add-Result "终态 $st 拒签后整行未动（DB 通道）" $true `
                "status=$($after.status) check_in_at=$($after.checkInAt)"
        } catch {
            Add-Result "终态 $st 拒签后整行未动（DB 通道）" $false $_.Exception.Message
        }
    }

    Write-Host ""
    Write-Host "==> 用例 8：运营空取消解冻 → 现场签到**不得**把它冻回去（完整因果链）"
    # 这一组复现的是本闸门的**产品后果**，不是「状态字段对不对」：
    #   ① 已签到未上架的任务会冻结柜机（消费者开门 409「设备补货中」、附近页灰显）；
    #   ② 运营用 cancel-empty 解冻（BUG-010 定下的手段）；
    #   ③ 若终态闸缺失，现场再签到一次就会把 CANCELLED 改回 IN_PROGRESS，
    #      而 checkInAt 还在 ⇒ 冻结判据重新成立 ⇒ 运营刚做的解冻被静默撤销。
    # 五步必须**按序**观察：少了 ① 与 ③，「事后没被冻结」可能只是因为「从来没冻过」= 假绿。
    $opsCancelPath = "/api/v2/ops/admin/replenishment/tasks/$TaskId/cancel-empty"

    try {
        Assert-E2eDeviceReplenishmentFrozen -BaseUrl $BaseUrl -DeviceId $DeviceId `
            -Label "前置：签到后柜机被冻结" | Out-Null
        Add-Result "因果链① 签到后柜机对消费者冻结" $true "device=$DeviceId 冻结判据成立"
    } catch {
        Add-Result "因果链① 签到后柜机对消费者冻结" $false $_.Exception.Message
    }

    try {
        $cancelled = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path $opsCancelPath -Headers $opsAuth
        if ([string]$cancelled.status -ne 'CANCELLED') {
            Add-Result "因果链② 运营 cancel-empty 解冻" $false `
                "task=$TaskId 状态为 $($cancelled.status)，期望 CANCELLED"
        } else {
            Add-Result "因果链② 运营 cancel-empty 解冻" $true "task=$TaskId → CANCELLED"
        }
    } catch {
        Add-Result "因果链② 运营 cancel-empty 解冻" $false $_.Exception.Message
    }

    try {
        Assert-E2eDeviceNotReplenishmentFrozen -BaseUrl $BaseUrl -DeviceId $DeviceId `
            -Label "解冻后柜机可售" -Headers $opsAuth | Out-Null
        Add-Result "因果链③ 解冻生效（消费者可开门）" $true "device=$DeviceId 未被补货冻结"
    } catch {
        Add-Result "因果链③ 解冻生效（消费者可开门）" $false $_.Exception.Message
    }

    # 坐标**完全合法**：终态闸若缺失，这一发就会成功并复活任务（这正是负向对照的原理）
    try {
        Assert-E2eApiRejected -BaseUrl $BaseUrl -Method POST -Path $checkInPath -Headers $mchAuth `
            -Body @{ latitude = $insideLat; longitude = $deviceLng } -ExpectStatus 409 `
            -ExpectMessageContains $CheckInContract.TaskFinishedMessage `
            -Label "已空取消任务再签到" | Out-Null
        Add-Result "因果链④ 已取消任务再签到 → 409" $true $CheckInContract.TaskFinishedMessage
    } catch {
        Add-Result "因果链④ 已取消任务再签到 → 409" $false $_.Exception.Message
    }

    # ⑤ 的 DB 断言里 checkedIn=true 是**防假绿的关键**：
    #    它证明「这个任务确实带着签到证据」—— 也就是说，若状态被改回 IN_PROGRESS，
    #    冻结判据**一定**会重新成立。只看「没被冻结」是分不清「解冻没被撤销」和「从没签到过」的。
    try {
        Assert-E2eTaskRowEquals -TaskId $TaskId -ExpectStatus 'CANCELLED' `
            -ExpectCheckedIn $true -Label "解冻后状态与签到证据" | Out-Null
        Add-Result "因果链⑤ 已取消 + 签到证据仍在（DB 通道）" $true `
            "status=CANCELLED 且 check_in_at 未清空 ⇒ 冻结本可重新成立"
    } catch {
        Add-Result "因果链⑤ 已取消 + 签到证据仍在（DB 通道）" $false $_.Exception.Message
    }

    try {
        Assert-E2eDeviceNotReplenishmentFrozen -BaseUrl $BaseUrl -DeviceId $DeviceId `
            -Label "拒签后柜机仍可售（解冻未被撤销）" -Headers $opsAuth | Out-Null
        Add-Result "因果链⑥ 拒签后柜机仍可售（P0 已修复）" $true "device=$DeviceId 未被重新冻结"
    } catch {
        Add-Result "因果链⑥ 拒签后柜机仍可售（P0 已修复）" $false $_.Exception.Message
    }

} finally {
    if ($script:fixtureTaskId -gt 0) {
        Write-Host ""
        Write-Host "==> 卸载夹具任务"
        try {
            Remove-E2eCheckInFixtureTask -TaskId $script:fixtureTaskId
        } catch {
            Write-Warning ("夹具任务 task=$script:fixtureTaskId 卸载失败：$($_.Exception.Message)" +
                " —— 请手工清理，否则它（IN_PROGRESS+checkInAt）会持续把柜机对消费者冻结")
        }
    }
}

Write-Host ""
$failed = @($script:results | Where-Object { -not $_.ok })
Write-Host ("[e2e-checkin-contract] {0} 个用例，失败 {1} 个" -f $script:results.Count, $failed.Count)
if ($failed.Count -gt 0) { exit 1 }
Write-Host "[e2e-checkin-contract] OK"
exit 0
