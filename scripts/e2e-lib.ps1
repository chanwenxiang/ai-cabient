# Shared E2E helpers: API client, device cleanup, MQTT shopping flow

function Get-E2eLockPath {
    return (Join-Path $env:TEMP "ai-cabinet-e2e.lock")
}

function Enter-E2eLock {
    param([int]$TimeoutSec = 600, [string]$Owner = "e2e")
    $path = Get-E2eLockPath
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            $bytes = [Text.Encoding]::UTF8.GetBytes("$Owner pid=$PID at=$(Get-Date -Format o)")
            $fs.Write($bytes, 0, $bytes.Length)
            $fs.Flush()
            return $fs
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "E2E lock busy after ${TimeoutSec}s ($path). Another smoke/script may be running."
}

function Exit-E2eLock {
    param($LockHandle)
    if ($null -eq $LockHandle) { return }
    try { $LockHandle.Close() } catch { }
    try { Remove-Item -Force (Get-E2eLockPath) -ErrorAction SilentlyContinue } catch { }
}

function Test-E2eHttpOk {
    param([string]$Url, [int]$TimeoutSec = 2)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400)
    } catch {
        return $false
    }
}

function Get-E2eBaseUrl {
    param([string]$Fallback = "http://localhost:8080")
    if (-not [string]::IsNullOrWhiteSpace($env:E2E_BASE_URL)) {
        return $env:E2E_BASE_URL.Trim().TrimEnd('/')
    }
    # Prefer live IDEA (:8080) over Docker full-stack (:18080)
    foreach ($candidate in @("http://localhost:8080", "http://localhost:18080")) {
        if (Test-E2eHttpOk -Url "$candidate/actuator/health") {
            return $candidate
        }
    }
    return $Fallback.TrimEnd('/')
}

function Get-E2eVisionUrl {
    param([string]$Fallback = "http://localhost:8082")
    if (-not [string]::IsNullOrWhiteSpace($env:E2E_VISION_URL)) {
        return $env:E2E_VISION_URL.Trim().TrimEnd('/')
    }
    foreach ($candidate in @("http://localhost:8082", "http://127.0.0.1:8082", "http://localhost:18082")) {
        if (Test-E2eHttpOk -Url "$candidate/health") {
            return $candidate
        }
    }
    return $Fallback.TrimEnd('/')
}

function Get-E2eDeviceUrl {
    param([string]$Fallback = "http://localhost:8081")
    if (-not [string]::IsNullOrWhiteSpace($env:E2E_DEVICE_URL)) {
        return $env:E2E_DEVICE_URL.Trim().TrimEnd('/')
    }
    foreach ($candidate in @("http://localhost:8081", "http://localhost:18081")) {
        if (Test-E2eHttpOk -Url "$candidate/actuator/health") {
            return $candidate
        }
    }
    return $Fallback.TrimEnd('/')
}

function Resolve-E2eBaseUrl {
    param([string]$BaseUrl)
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) { return Get-E2eBaseUrl }
    return $BaseUrl.TrimEnd('/')
}

function Get-E2eVisionApiKey {
    param([string]$Fallback = "dev-vision-key-change-me")
    if (-not [string]::IsNullOrWhiteSpace($env:VISION_API_KEY)) {
        return $env:VISION_API_KEY.Trim()
    }
    $envFile = Join-Path (Split-Path -Parent $PSScriptRoot) "infra\.env"
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^\s*VISION_API_KEY\s*=' } | Select-Object -First 1
        if ($line -match '^\s*VISION_API_KEY\s*=\s*(.+)\s*$') {
            $val = $Matches[1].Trim().Trim('"').Trim("'")
            if (-not [string]::IsNullOrWhiteSpace($val)) { return $val }
        }
    }
    return $Fallback
}

function Set-E2eVisionForceNeedReview {
    param(
        [bool]$Enabled,
        [string]$VisionUrl = "",
        [string]$VisionApiKey = ""
    )
    if ([string]::IsNullOrWhiteSpace($VisionUrl)) { $VisionUrl = Get-E2eVisionUrl }
    if ([string]::IsNullOrWhiteSpace($VisionApiKey)) { $VisionApiKey = Get-E2eVisionApiKey }
    $uri = "$VisionUrl/api/v2/vision/debug/force-need-review"
    $params = @{
        Method      = "POST"
        Uri         = $uri
        ContentType = "application/json"
        Headers     = @{ "X-Internal-Api-Key" = $VisionApiKey }
        Body        = (@{ enabled = $Enabled } | ConvertTo-Json -Compress)
    }
    return Invoke-RestMethod @params
}

function Set-E2eConsumerPayChannel {
    param(
        [string]$BaseUrl,
        [hashtable]$Auth,
        [ValidateSet("WECHAT", "ALIPAY", "BALANCE")]
        [string]$Channel,
        [string]$Phone = "13800138000",
        [string]$PostgresContainer = "ai-cabinet-postgres-1"
    )
    switch ($Channel.ToUpper()) {
        "WECHAT" {
            $sign = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/account/payscore/sign" -Headers $Auth
            Write-Host "    pay channel WECHAT (payscore signed contract=$($sign.contractId))"
        }
        "ALIPAY" {
            $sign = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/account/alipay-agreement/sign" -Headers $Auth
            Write-Host "    pay channel ALIPAY (agreement signed contract=$($sign.contractId))"
        }
        "BALANCE" {
            $sql = @"
UPDATE user_info
SET pay_preferred_channel = 'BALANCE'
WHERE phone_number = '$Phone';
"@
            docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "failed to set pay_preferred_channel=BALANCE for $Phone" }
            Write-Host "    pay channel BALANCE (preferred_channel forced via DB)"
        }
    }
}

function Get-E2eSimVideoKey {
    param(
        [string]$DeviceId = "330449777078",
        [long]$UserId = 0,
        [string]$SessionId,
        [string]$Camera = "top"
    )
    $tz = [TimeZoneInfo]::FindSystemTimeZoneById("China Standard Time")
    $now = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $tz)
    $date = $now.ToString("yyyy/MM/dd")
    return "sim/$date/$DeviceId/user-$UserId/$SessionId-$Camera.mp4"
}

function Invoke-E2eApi {
    param(
        [string]$BaseUrl,
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        $Body = $null
    )
    # 运营后台密码登录需要图形验证码：自动从 /captcha 领取并把 Redis 中的答案附到请求体，
    # 让既有 e2e 脚本在 captcha-enabled 环境下无需改造。
    if ($Path -eq '/api/v2/auth/admin-password-login') {
        $needCaptcha = ($null -eq $Body) -or (-not ($Body -is [hashtable])) -or
            ([string]::IsNullOrWhiteSpace([string]$Body.captchaId))
        if ($needCaptcha) {
            $capResp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/v2/auth/captcha" -ContentType "application/json"
            if ($capResp.code -ne 0 -or [string]::IsNullOrWhiteSpace($capResp.data.captchaId)) {
                throw "captcha fetch failed: $($capResp.message)"
            }
            $capId = $capResp.data.captchaId
            $code = (docker exec ai-cabinet-redis-1 redis-cli GET "aicabinet:captcha:$capId" 2>&1).Trim()
            if ([string]::IsNullOrWhiteSpace($code)) {
                throw "captcha code not found in redis for id=$capId"
            }
            if ($null -eq $Body -or -not ($Body -is [hashtable])) { $Body = @{} }
            $Body.captchaId = $capId
            $Body.captchaCode = $code
        }
    }
    # 发短信同样需要图形验证码：自动拼到 query
    if ($Path -like '/api/v2/auth/sms-code*') {
        $needSmsCaptcha = ($Path -notlike '*captchaId=*')
        if ($needSmsCaptcha) {
            $capResp = Invoke-RestMethod -Method GET -Uri "$BaseUrl/api/v2/auth/captcha" -ContentType "application/json"
            if ($capResp.code -ne 0 -or [string]::IsNullOrWhiteSpace($capResp.data.captchaId)) {
                throw "captcha fetch failed: $($capResp.message)"
            }
            $capId = $capResp.data.captchaId
            $code = (docker exec ai-cabinet-redis-1 redis-cli GET "aicabinet:captcha:$capId" 2>&1).Trim()
            if ([string]::IsNullOrWhiteSpace($code)) {
                throw "captcha code not found in redis for id=$capId"
            }
            $sep = if ($Path.Contains('?')) { '&' } else { '?' }
            $Path = "$Path$sep" + "captchaId=$([uri]::EscapeDataString($capId))&captchaCode=$([uri]::EscapeDataString($code))"
        }
    }
    $uri = "$BaseUrl$Path"
    $params = @{
        Method      = $Method
        Uri         = $uri
        ContentType = "application/json"
    }
    if ($Headers.Count -gt 0) { $params.Headers = $Headers }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 8 -Compress) }
    try {
        $resp = Invoke-RestMethod @params
    } catch {
        $statusCode = $null
        $responseBody = $null
        $response = $_.Exception.Response
        if ($null -ne $response) {
            try { $statusCode = [int]$response.StatusCode } catch { }
            try {
                $stream = $response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    try { $responseBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
                }
            } catch { }
        }
        if ([string]::IsNullOrWhiteSpace($responseBody)) {
            $responseBody = $_.Exception.Message
        }
        throw "HTTP request failed: $Method $Path status=$statusCode response=$responseBody"
    }
    if ($resp.code -ne 0) {
        throw "API error: $($resp.message) (path=$Path)"
    }
    return $resp.data
}

function Assert-E2eApiRejected {
    param(
        [string]$BaseUrl,
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        $Body = $null,
        [int]$ExpectStatus = 400,
        [string]$ExpectMessageContains = "",
        [string]$Label = ""
    )
    # 负向用例助手：断言这次调用**必须被拒**，且状态码与报文符合预期。
    # 为什么需要：Invoke-E2eApi 对非 2xx 直接 throw，拿它写「期望被拒」的用例只能 try/catch
    # 字符串，一不小心就变成「抛了异常就算过」的假绿 —— 而异常可能是超时、403、路径打错。
    # 三条断言全部显式化：
    #   ① 请求居然成功了   → 失败（说明该闸门没生效）
    #   ② 状态码 ≠ 期望值  → 失败
    #   ③ 报文不含期望文案 → 失败
    $rejected = $false
    $status = 0
    $bodyText = ""
    try {
        $ok = Invoke-E2eApi -BaseUrl $BaseUrl -Method $Method -Path $Path -Headers $Headers -Body $Body
        $bodyText = ($ok | ConvertTo-Json -Depth 6 -Compress)
    } catch {
        $msg = $_.Exception.Message
        # Invoke-E2eApi 的失败形态：HTTP request failed: <METHOD> <path> status=<code> response=<body>
        $m = [regex]::Match($msg, 'status=(\d+)\s+response=(?s)(.*)$')
        if ($m.Success) {
            $rejected = $true
            $status = [int]$m.Groups[1].Value
            $bodyText = $m.Groups[2].Value.Trim()
        } else {
            $bodyText = $msg
        }
    }
    if (-not $rejected) {
        throw "$Label 期望被拒（HTTP $ExpectStatus），但请求未被拒：$bodyText"
    }
    if ($status -ne $ExpectStatus) {
        throw "$Label 期望 HTTP $ExpectStatus，实得 $status（body=$bodyText）"
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectMessageContains) -and
        -not $bodyText.Contains($ExpectMessageContains)) {
        throw "$Label 期望报文含「$ExpectMessageContains」，实得 body=$bodyText"
    }
    Write-Host "    ✓ $Label → HTTP $status 已拒绝"
    return @{ status = $status; body = $bodyText }
}

function Assert-E2eTaskStillTerminal {
    param(
        [string]$BaseUrl,
        [hashtable]$Headers = @{},
        [long]$TaskId,
        [string]$ExpectStatus,
        [string]$Label = ""
    )
    # 「终态没被复活」的实跑证据。
    #
    # 为什么不能只看 409：终态闸若被写成「先 setStatus(IN_PROGRESS) 再抛异常」（或异常被上层
    # 吞掉后事务仍提交），状态码断言照样能过 —— 而后果是柜机重新对消费者停售
    # （冻结判据 = status=IN_PROGRESS 且 checkInAt≠null）。
    # 所以被拒之后必须**重新读一次**，确认状态与签到证据原地未动。
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) { throw "$Label 缺少 BaseUrl" }
    if ($TaskId -le 0) { throw "$Label 缺少 TaskId" }

    # ⚠️ 必须显式带 status：`/replenishment/tasks` 的**默认**状态集是
    # [PENDING, IN_PROGRESS, COMPLETED]（MerchantInventoryPortalService.listReplenishmentTasks:176），
    # **CANCELLED 被刻意排除**（运营/商家列表默认不展示已取消）。
    # 不带 status 时 CANCELLED 任务根本不出现 ⇒ 本断言会「找不到任务」而失败，
    # 把一个其实没被复活的用例判成**假红**；反过来，若把「找不到」当成通过，就是**假绿**。
    $tasksPath = "/api/v2/merchant/replenishment/tasks?status=$ExpectStatus"
    $all = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path $tasksPath -Headers $Headers)
    $found = $all | Where-Object { [long]$_.taskId -eq [long]$TaskId } | Select-Object -First 1
    if (-not $found) {
        throw "$Label 被拒后重新拉取任务列表（status=$ExpectStatus），找不到 task=$TaskId —— 无法证明状态没变"
    }
    if ([string]$found.status -ne $ExpectStatus) {
        throw "$Label 拒签之后任务状态变了：期望仍是 $ExpectStatus，实为 $($found.status)（终态被复活了）"
    }
    Write-Host "    ✓ $Label → task=$TaskId 仍为 $ExpectStatus"
    return $found
}

function Get-E2ePostgresContainer {
    param([string]$PostgresContainer = "")
    if (-not [string]::IsNullOrWhiteSpace($PostgresContainer)) { return $PostgresContainer }
    $name = docker ps `
        --filter "label=com.docker.compose.service=postgres" `
        --format "{{.Names}}" 2>$null | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($name)) {
        foreach ($candidate in @("ai-cabinet-postgres-1", "infra-postgres-1")) {
            $running = docker ps --filter "name=^/$candidate$" --format "{{.Names}}" 2>$null
            if ($running -eq $candidate) { return $candidate }
        }
    }
    return $name
}

function Invoke-E2ePsql {
    param(
        [string]$Sql,
        [string]$PostgresContainer = ""
    )
    $c = Get-E2ePostgresContainer -PostgresContainer $PostgresContainer
    if ([string]::IsNullOrWhiteSpace($c)) {
        throw "找不到运行中的 postgres 容器 —— 无法读取冻结判据真值"
    }
    $out = docker exec $c psql -U aicabinet -d aicabinet -tA -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "psql 执行失败（$c）：$out"
    }
    return ($out | Out-String).Trim()
}

function Get-E2eDeviceStatus {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        # `/api/v2/devices/**` 不在 WebConfig.addInterceptors 的 excludePathPatterns 里
        # ⇒ 默认拒绝：不带 token 一律 401「请先登录」。AuthInterceptor 只校验 token 有效、
        # **不限定角色**，所以运营 token 或商户 token 都能读。
        # 曾经漏了这个参数：本助手不带 token → 永远 401 → 调用方 catch 成「设备离线」，
        # 于是 `Test-E2eDeviceOnline` 对任何柜机都返回 $false（一个静默恒假的判据）。
        [hashtable]$Headers = @{}
    )
    if ([string]::IsNullOrWhiteSpace($DeviceId)) { throw "缺少 DeviceId" }
    return Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $Headers
}

<#
.SYNOPSIS
  冻结判据的**精确**真值（与 DeviceValidationService.hasInProgressReplenishmentTask 同形）。

.DESCRIPTION
  存在 replenishment_task 行满足 status='IN_PROGRESS' AND check_in_at IS NOT NULL，
  或存在阻塞性补货会话（RESTOCK: 幂等键）。

  为什么读 DB 而不是只读接口：`busyReason` 只有四态，OFFLINE / LOCKED 会**遮蔽** REPLENISHMENT
  （见 DeviceValidationService.getDeviceStatus），柜机不在线时接口看不到冻结 —— 那时
  「接口没报冻结」是**没证据**，不是「没冻结」。所以精判据读 DB，接口只作消费者视角的旁证。
#>
function Get-E2eReplenishmentFreezeEvidence {
    param(
        [string]$DeviceId,
        [string]$PostgresContainer = ""
    )
    if ([string]::IsNullOrWhiteSpace($DeviceId)) { throw "缺少 DeviceId" }
    $sql = @"
SELECT
  (SELECT COUNT(*) FROM replenishment_task
     WHERE device_id = '$DeviceId'
       AND status = 'IN_PROGRESS'
       AND check_in_at IS NOT NULL) AS frozen_tasks,
  (SELECT COUNT(*) FROM shopping_session
     WHERE device_id = '$DeviceId'
       AND idempotency_key LIKE 'RESTOCK:%'
       AND state IN ('CREATED','OPENING','SHOPPING','RECOGNIZING','WAITING_UPLOAD','SETTLING')
  ) AS restock_sessions;
"@
    $raw = Invoke-E2ePsql -Sql $sql -PostgresContainer $PostgresContainer
    $parts = $raw -split '\|'
    if ($parts.Count -lt 2) { throw "解析冻结判据真值失败：$raw" }
    $frozenTasks = [int]$parts[0].Trim()
    $restockSessions = [int]$parts[1].Trim()
    return [pscustomobject]@{
        frozenTasks     = $frozenTasks
        restockSessions = $restockSessions
        frozen          = (($frozenTasks + $restockSessions) -gt 0)
    }
}

function Assert-E2eDeviceReplenishmentFrozen {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [string]$Label = "",
        [string]$PostgresContainer = ""
    )
    # 正向前提：柜机确实处于「补货冻结」（消费者开门会被 409 挡住）。
    # 没有这一步，「解冻后未被重新冻结」的断言会**因为从来没冻过**而假绿。
    $ev = Get-E2eReplenishmentFreezeEvidence -DeviceId $DeviceId -PostgresContainer $PostgresContainer
    if (-not $ev.frozen) {
        throw ("$Label 期望设备 $DeviceId 处于补货冻结，实际未冻结" +
            "（frozen_tasks=$($ev.frozenTasks) restock_sessions=$($ev.restockSessions)）——" +
            " 前置条件不成立，后续「未被重新冻结」的断言会变成假绿")
    }
    Write-Host ("    ✓ $Label → device=$DeviceId 处于补货冻结（frozen_tasks=$($ev.frozenTasks)" +
        " restock_sessions=$($ev.restockSessions)）")
    return $ev
}

function Assert-E2eDeviceNotReplenishmentFrozen {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [string]$Label = "",
        [string]$PostgresContainer = "",
        # 消费者视角旁证要读 `/api/v2/devices/{id}/status`，而该路径**需要登录**（见 Get-E2eDeviceStatus）。
        # 不传 token 时这一步会 401，旁证拿不到 —— 精判据仍成立，但「消费者视角」这条证据就缺了。
        [hashtable]$Headers = @{}
    )
    # 「签到没能把已取消任务复活」的**产品级**证据。
    #
    # 只看任务 status 少一跳因果：冻结判据是 (IN_PROGRESS 且 checkInAt≠null)，
    # 而这条判据喂给两处消费者可见面 ——
    #   ① SessionOpenService / SessionService → 消费者开门 409「设备补货中」
    #   ② DeviceController /{id}/status → available=false、busyReason=REPLENISHMENT（附近页据此灰显）。
    # 所以拒签之后必须同时确认：精确判据仍为假 + 消费者视角没显示补货冻结。
    $ev = Get-E2eReplenishmentFreezeEvidence -DeviceId $DeviceId -PostgresContainer $PostgresContainer
    if ($ev.frozen) {
        throw ("$Label 设备 $DeviceId 被重新冻结了（frozen_tasks=$($ev.frozenTasks)" +
            " restock_sessions=$($ev.restockSessions)）—— 消费者开门会拿到 409「设备补货中」，" +
            "运营刚做的空取消被签到撤销")
    }
    # 消费者视角旁证；OFFLINE / LOCKED 会遮蔽 REPLENISHMENT，此时如实标为「未观测到」而不是当成通过。
    $st = Get-E2eDeviceStatus -BaseUrl $BaseUrl -DeviceId $DeviceId -Headers $Headers
    $masked = [string]$st.busyReason -in @('OFFLINE', 'LOCKED')
    if ([string]$st.busyReason -eq 'REPLENISHMENT') {
        throw ("$Label 精确判据为假，但消费者接口仍报 busyReason=REPLENISHMENT" +
            "（device=$DeviceId）—— 两处判据漂移，需查 getDeviceStatus 是否用了不同条件")
    }
    if ($masked) {
        Write-Host ("    ✓ $Label → device=$DeviceId 未被补货冻结（frozen_tasks=0 restock_sessions=0）；" +
            "接口 busyReason=$($st.busyReason) 属遮蔽态，未提供旁证")
    } else {
        Write-Host ("    ✓ $Label → device=$DeviceId 未被补货冻结（frozen_tasks=0）；" +
            "消费者接口 available=$($st.available) busyReason=$($st.busyReason)")
    }
    return $ev
}

function Get-E2eTaskRow {
    param(
        [long]$TaskId,
        [string]$PostgresContainer = ""
    )
    if ($TaskId -le 0) { throw "缺少 TaskId" }
    $sql = @"
SELECT status,
       COALESCE(check_in_at::text, ''),
       COALESCE(check_in_lat::text, ''),
       COALESCE(check_in_lng::text, '')
FROM replenishment_task
WHERE task_id = $TaskId;
"@
    $raw = Invoke-E2ePsql -Sql $sql -PostgresContainer $PostgresContainer
    if ([string]::IsNullOrWhiteSpace($raw)) {
        throw "读不到 task=$TaskId 的行（可能已被删除）"
    }
    $parts = $raw -split '\|'
    if ($parts.Count -lt 4) { throw "解析 task=$TaskId 失败：$raw" }
    return [pscustomobject]@{
        taskId     = $TaskId
        status     = $parts[0].Trim()
        checkInAt  = $parts[1].Trim()
        checkInLat = $parts[2].Trim()
        checkInLng = $parts[3].Trim()
        checkedIn  = -not [string]::IsNullOrWhiteSpace($parts[1])
    }
}

function Assert-E2eTaskRowUnchanged {
    param(
        [long]$TaskId,
        [psobject]$Before,
        [string]$Label = "",
        [string]$PostgresContainer = ""
    )
    # 「被拒之后这一行**原地未动**」—— 比的是整行关键字段，不是「状态还是不是终态」。
    #
    # 为什么必须比到 check_in_at / lat / lng 的**值**：修复前代码在 COMPLETED 任务上
    # 状态不会被改（旧条件 `!IN_PROGRESS && !COMPLETED` 挡住了），但 `setCheckInAt(Instant.now())`
    # 已经执行并落库 ⇒ **签到证据被静默覆盖**。只断言「状态仍是 COMPLETED」会漏掉这一半。
    # 实测（2026-09-18 对修复前镜像）：task=1 的 check_in_at 被从 09-13 改成了本次签到时刻 ——
    # 而当时的断言只比「有没有」，于是**判成了通过**。这正是「判据按存在性而非有效性」的坑。
    $after = Get-E2eTaskRow -TaskId $TaskId -PostgresContainer $PostgresContainer
    $diff = @()
    if ($after.status -ne $Before.status) {
        $diff += "status: $($Before.status) -> $($after.status)"
    }
    if ($after.checkInAt -ne $Before.checkInAt) {
        $diff += "check_in_at: [$($Before.checkInAt)] -> [$($after.checkInAt)]"
    }
    if ($after.checkInLat -ne $Before.checkInLat) {
        $diff += "check_in_lat: [$($Before.checkInLat)] -> [$($after.checkInLat)]"
    }
    if ($after.checkInLng -ne $Before.checkInLng) {
        $diff += "check_in_lng: [$($Before.checkInLng)] -> [$($after.checkInLng)]"
    }
    if ($diff.Count -gt 0) {
        throw ("$Label task=$TaskId 被拒之后行内容变了（签到本不该产生任何写入）：" + ($diff -join '；'))
    }
    Write-Host ("    ✓ $Label → 库内 task=$TaskId 整行未动" +
        "（status=$($after.status) check_in_at=$($after.checkInAt)）")
    return $after
}

function Assert-E2eTaskRowEquals {
    param(
        [long]$TaskId,
        [string]$ExpectStatus,
        [bool]$ExpectCheckedIn = $true,
        [string]$Label = "",
        [string]$PostgresContainer = ""
    )
    # 与 Assert-E2eTaskStillTerminal 并行的**第二条独立通道**：
    #   通道一（API）：经 listReplenishmentTasks 的状态过滤 + 设备作用域 + DTO 映射；
    #   通道二（DB）  ：直接读 replenishment_task 原始行。
    # 两条通道的失效方式不同（作用域写错 / DTO 漏字段 / 过滤条件漂移），都绿才算「状态真的是这样」。
    # 用于**合法变更之后**的确认（如 cancel-empty 之后期望 CANCELLED）——
    # 「被拒之后必须原地未动」的场景请用 Assert-E2eTaskRowUnchanged。
    $row = Get-E2eTaskRow -TaskId $TaskId -PostgresContainer $PostgresContainer
    if ($row.status -ne $ExpectStatus) {
        throw "$Label task=$TaskId 状态期望 $ExpectStatus，库内实为 $($row.status)"
    }
    if ($row.checkedIn -ne $ExpectCheckedIn) {
        throw ("$Label task=$TaskId 签到证据期望 checkedIn=$ExpectCheckedIn，库内实为 $($row.checkedIn)" +
            "（check_in_at=$($row.checkInAt)）")
    }
    Write-Host "    ✓ $Label → 库内 task=$TaskId status=$($row.status) check_in_at=$($row.checkInAt)"
    return $row
}

<#
.SYNOPSIS
  造一个「可签到」的补货任务夹具，返回 task_id（用完必须 Remove-E2eCheckInFixtureTask 卸载）。

.DESCRIPTION
  为什么必须有夹具：签到契约里的用例（空 body→400、超距→400、边界内→200、解冻后不可复活）
  只对**非终态**任务成立。而本开发库里可能一条 PENDING/IN_PROGRESS 都没有
  （`/api/v2/ops/admin/replenishment/suggest` 常年 no gaps ⇒ Prepare-E2eReplenishmentPlan 造不出缺口），
  既有任务全是终态（实测 task 1=COMPLETED / task 2=CANCELLED）。没有夹具时这些用例会「选不到目标」，
  最容易被写成**静默跳过** —— 那就退化成永远绿灯的装饰品。

  ⚠️ 夹具只负责**造数据**：任务的创建过程不参与被测判据（被测的是 check-in 的状态闸与坐标闸，
  与任务从哪来无关），而签到本身仍走真实 HTTP 接口、真实服务端代码、真实冻结判据。
#>
function New-E2eCheckInFixtureTask {
    param(
        [string]$DeviceId,
        [long]$AssigneeUserId = 0,
        [string]$SkuId = "SKU-MILK-001",
        [string]$PostgresContainer = ""
    )
    if ([string]::IsNullOrWhiteSpace($DeviceId)) { throw "缺少 DeviceId" }
    # applied 留 false 是刻意的：assertTaskCancellableEmpty 只在「已上架」时拒绝空取消，
    # 夹具必须能被 cancel-empty 解冻 —— 那正是本套用例要复现的运营手段。
    $assignee = if ($AssigneeUserId -gt 0) { "$AssigneeUserId" } else { "NULL" }
    $sql = @"
WITH t AS (
  INSERT INTO replenishment_task (device_id, assignee_user_id, status, notes)
  VALUES ('$DeviceId', $assignee, 'PENDING', 'e2e-checkin-contract fixture')
  RETURNING task_id
)
INSERT INTO replenishment_task_line (task_id, line_type, sku_id, quantity, applied)
SELECT task_id, 'RESTOCK', '$SkuId', 1, false FROM t
RETURNING task_id;
"@
    $raw = Invoke-E2ePsql -Sql $sql -PostgresContainer $PostgresContainer
    # psql 在 RETURNING 结果之外还会打印命令标签（如 `INSERT 0 1`），
    # 所以不能按「整串都是数字」判；取**第一行纯数字**（= RETURNING 出来的 task_id）。
    $m = [regex]::Match($raw, '(?m)^\s*(\d+)\s*$')
    if (-not $m.Success) { throw "夹具任务创建失败（未能取回 task_id）：$raw" }
    $taskId = [long]$m.Groups[1].Value
    Write-Host "    fixture task=$taskId device=$DeviceId（PENDING + 1 条明细 applied=false）"
    return $taskId
}

function Remove-E2eCheckInFixtureTask {
    param(
        [long]$TaskId,
        [string]$PostgresContainer = ""
    )
    if ($TaskId -le 0) { return }
    # 顺序不能反：明细有 FK 指向任务。
    # 刻意不动 shopping_session：签到/空取消都不产生会话，而改它会把本夹具无关的历史证据一起抹掉。
    $sql = @"
DELETE FROM replenishment_task_line WHERE task_id = $TaskId;
DELETE FROM replenishment_task WHERE task_id = $TaskId;
"@
    Invoke-E2ePsql -Sql $sql -PostgresContainer $PostgresContainer | Out-Null
    Write-Host "    fixture task=$TaskId 已卸载"
}

function Clear-E2eDeviceBlockingSessions {
    param(
        [string]$DeviceId = "330449777078",
        [string]$PostgresContainer = ""
    )
    if ([string]::IsNullOrWhiteSpace($PostgresContainer)) {
        $PostgresContainer = docker ps `
            --filter "label=com.docker.compose.service=postgres" `
            --format "{{.Names}}" 2>$null | Select-Object -First 1
    }
    if ([string]::IsNullOrWhiteSpace($PostgresContainer)) {
        foreach ($candidate in @("ai-cabinet-postgres-1", "infra-postgres-1")) {
            $running = docker ps --filter "name=^/$candidate$" --format "{{.Names}}" 2>$null
            if ($running -eq $candidate) {
                $PostgresContainer = $candidate
                break
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($PostgresContainer)) {
        Write-Warning "Clear-E2eDeviceBlockingSessions: no running postgres container found"
        return $false
    }

    $states = @("CREATED", "OPENING", "SHOPPING", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING")
    $inList = ($states | ForEach-Object { "'$_'" }) -join ","
    $sql = @"
UPDATE shopping_session
SET state = 'CANCELLED',
    fail_reason = COALESCE(NULLIF(fail_reason, ''), 'e2e-cleanup'),
    updated_at = NOW()
WHERE device_id = '$DeviceId'
  AND state IN ($inList);

UPDATE replenishment_task
SET status = 'CANCELLED'
WHERE device_id = '$DeviceId'
  AND status = 'IN_PROGRESS';

DELETE FROM user_blacklist
WHERE user_id IN (SELECT user_id FROM user_info WHERE phone_number = '13800138000');

UPDATE shopping_session
SET created_at = created_at - INTERVAL '2 hours'
WHERE user_id IN (SELECT user_id FROM user_info WHERE phone_number = '13800138000')
  AND created_at > NOW() - INTERVAL '1 hour';
"@
    $out = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Clear-E2eDeviceBlockingSessions: postgres cleanup failed: $out"
        return $false
    }
    Write-Host "==> Cleared blocking sessions on $DeviceId via $PostgresContainer"
    return $true
}

function Test-E2eDeviceOnline {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [hashtable]$Auth
    )
    try {
        $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $Auth
        return [bool]$dev.online
    } catch {
        return $false
    }
}

function Start-E2eDeviceSimulator {
    param(
        [string]$RepoRoot,
        [string]$BaseUrl,
        [string]$DeviceId,
        [string]$MqttBroker = "tcp://localhost:11883",
        [string]$InternalApiKey = "dev-internal-key-change-me"
    )
    $env:TRADE_SERVICE_URL = $BaseUrl
    $env:DEVICE_SERVICE_URL = (Get-E2eDeviceUrl)
    $env:INTERNAL_API_KEY = $InternalApiKey
    $env:MINIO_ENDPOINT = "http://localhost:9000"
    $env:MINIO_ACCESS_KEY = "minioadmin"
    $env:MINIO_SECRET_KEY = "minioadmin"
    if ([string]::IsNullOrWhiteSpace($env:AICABINET_SIM_GRAVITY_SKU)) {
        $env:AICABINET_SIM_GRAVITY_SKU = "SKU-DEMO-001"
    }
    if ([string]::IsNullOrWhiteSpace($env:AICABINET_SIM_SHOPPING_MS)) {
        $env:AICABINET_SIM_SHOPPING_MS = "5000"
    }
    return Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        "Set-Location '$RepoRoot'; mvn -q -f edge/device-simulator/pom.xml exec:java " +
        "'-Dexec.mainClass=com.aicabinet.simulator.DeviceSimulator' " +
        "'-Dexec.args=$DeviceId $MqttBroker'"
    )
}

function Stop-E2eDeviceSimulator {
    param($Process)
    if ($null -eq $Process) { return }
    try {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    } catch { }
}

function Ensure-E2eDeviceOnline {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [hashtable]$Auth,
        [string]$RepoRoot,
        [string]$MqttBroker = "tcp://localhost:11883",
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [switch]$SkipSimulatorStart
    )
    if (Test-E2eDeviceOnline -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $Auth) {
        Write-Host "==> DeviceSimulator already online ($DeviceId), reusing it"
        return @{ Started = $false; Process = $null }
    }

    if ($SkipSimulatorStart) {
        throw "device $DeviceId offline; start DeviceSimulator or omit -SkipSimulatorStart"
    }
    Write-Host "==> Starting DeviceSimulator ($DeviceId)..."
    $proc = Start-E2eDeviceSimulator -RepoRoot $RepoRoot -BaseUrl $BaseUrl -DeviceId $DeviceId `
        -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (Test-E2eDeviceOnline -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $Auth) {
            return @{ Started = $true; Process = $proc }
        }
    }
    Stop-E2eDeviceSimulator $proc
    throw "DeviceSimulator did not bring $DeviceId online within 45s"
}

function Wait-E2eSessionTerminal {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [hashtable]$Auth,
        [int]$MaxPolls = 40,
        [int]$IntervalSec = 2
    )
    $final = $null
    for ($i = 0; $i -lt $MaxPolls; $i++) {
        Start-Sleep -Seconds $IntervalSec
        $s = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$SessionId" -Headers $Auth
        $final = $s.state
        if ($final -in @("COMPLETED", "DISPUTED", "FAILED", "CANCELLED")) { break }
    }
    return $final
}

function Wait-E2eSessionLeftStates {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [hashtable]$Auth,
        [string[]]$States = @("CREATED", "OPENING"),
        [int]$TimeoutSec = 15
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        $s = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$SessionId" -Headers $Auth
        $last = $s.state
        if ($last -notin $States) { return $last }
        if ($last -in @("COMPLETED", "DISPUTED", "FAILED", "CANCELLED")) { return $last }
        Start-Sleep -Milliseconds 500
    }
    return $last
}

function Wait-E2eSessionOrder {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [hashtable]$Auth,
        [int]$MaxPolls = 25
    )
    for ($i = 0; $i -lt $MaxPolls; $i++) {
        Start-Sleep -Seconds 1
        try {
            $order = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$SessionId/order" -Headers $Auth
            if ($null -ne $order -and $order.orderId) { return $order }
        } catch { }
    }
    $state = (Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$SessionId" -Headers $Auth).state
    throw "Order not ready after ${MaxPolls}s, session state=$state"
}

function Invoke-E2eMqttShopping {
    param(
        [string]$BaseUrl,
        [string]$DeviceId,
        [hashtable]$Auth,
        [string]$RepoRoot,
        [string]$MqttBroker = "tcp://localhost:11883",
        [string]$InternalApiKey = "dev-internal-key-change-me",
        [switch]$SkipSimulatorStart,
        [switch]$KeepSimulator
    )
    $sim = Ensure-E2eDeviceOnline -BaseUrl $BaseUrl -DeviceId $DeviceId -Auth $Auth `
        -RepoRoot $RepoRoot -MqttBroker $MqttBroker -InternalApiKey $InternalApiKey `
        -SkipSimulatorStart:$SkipSimulatorStart

    $dev = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/devices/$DeviceId/status" -Headers $Auth
    Write-Host "    device online=$($dev.online) available=$($dev.available)"
    if (-not $dev.available) {
        Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
        Start-Sleep -Seconds 1
    }

    # Outer retry: concurrent Clear-E2e / missed MQTT ACK can CANCEL an OPENING session.
    $sessionId = $null
    $final = $null
    $usedFallback = $false
    $userId = 0
    for ($round = 1; $round -le 3; $round++) {
        Write-Host "==> Create session (MQTT unlock) attempt $round/3"
        $idempotencyKey = "e2e-session-$([guid]::NewGuid().ToString('N'))"
        $session = $null
        for ($attempt = 1; $attempt -le 5; $attempt++) {
            try {
                $session = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/sessions" -Headers $Auth -Body @{
                    deviceId       = $DeviceId
                    idempotencyKey = $idempotencyKey
                }
                break
            } catch {
                if ($_.Exception.Message -notmatch 'status=409' -or $attempt -eq 5) { throw }
                Write-Host "    device busy (409); clearing sessions and retry $attempt/5"
                Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
                Start-Sleep -Seconds 2
                $idempotencyKey = "e2e-session-$([guid]::NewGuid().ToString('N'))"
            }
        }
        $sessionId = $session.sessionId
        try { $userId = [long]$session.userId } catch { $userId = 0 }
        Write-Host "    sessionId=$sessionId state=$($session.state) idempotencyKey=$idempotencyKey"

        # MQTT unlock should move OPENING -> SHOPPING quickly. If the simulator missed
        # OPEN_DOOR (common after broker blips), inject internal door close to finish.
        $progress = Wait-E2eSessionLeftStates -BaseUrl $BaseUrl -SessionId $sessionId -Auth $Auth `
            -States @("CREATED", "OPENING") -TimeoutSec 20
        Write-Host "    progress state=$progress"
        $usedFallback = $false
        if ($progress -eq "CANCELLED") {
            Write-Warning "session cancelled while OPENING (likely concurrent cleanup); retry round $round/3"
            Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
            Start-Sleep -Seconds 2
            continue
        }
        if ($progress -in @("CREATED", "OPENING")) {
            Write-Warning "MQTT unlock stalled at $progress; injecting internal door close fallback"
            Invoke-E2eInternalDoorClose -BaseUrl $BaseUrl -SessionId $sessionId -DeviceId $DeviceId `
                -UserId $userId -InternalApiKey $InternalApiKey | Out-Null
            $usedFallback = $true
        }

        $final = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $Auth
        if ($final -notin @("COMPLETED", "DISPUTED")) {
            # One more fallback attempt if we somehow never left OPENING
            if ($final -in @("CREATED", "OPENING", $null) -or -not $usedFallback) {
                $cur = (Invoke-E2eApi -BaseUrl $BaseUrl -Method GET -Path "/api/v2/sessions/$sessionId" -Headers $Auth).state
                if ($cur -in @("CREATED", "OPENING", "SHOPPING", "RECOGNIZING", "WAITING_UPLOAD", "SETTLING")) {
                    Write-Warning "session still $cur after wait; retrying internal door close"
                    Invoke-E2eInternalDoorClose -BaseUrl $BaseUrl -SessionId $sessionId -DeviceId $DeviceId `
                        -UserId $userId -InternalApiKey $InternalApiKey | Out-Null
                    $usedFallback = $true
                    $final = Wait-E2eSessionTerminal -BaseUrl $BaseUrl -SessionId $sessionId -Auth $Auth -MaxPolls 20
                }
            }
        }
        if ($final -in @("COMPLETED", "DISPUTED")) { break }
        if ($final -eq "CANCELLED" -and $round -lt 3) {
            Write-Warning "session ended CANCELLED; retry round $($round + 1)/3"
            Clear-E2eDeviceBlockingSessions -DeviceId $DeviceId | Out-Null
            Start-Sleep -Seconds 2
            continue
        }
        throw "session did not finish via MQTT path, state=$final"
    }
    if ($final -notin @("COMPLETED", "DISPUTED")) {
        throw "session did not finish via MQTT path, state=$final"
    }
    $pathLabel = if ($usedFallback) { "internal door fallback" } else { "DeviceSimulator MQTT" }
    Write-Host "    final state=$final ($pathLabel)"

    return @{
        SessionId          = $sessionId
        FinalState         = $final
        SimulatorStarted   = [bool]$sim.Started
        SimulatorProcess   = $sim.Process
        UsedDoorFallback   = $usedFallback
    }
}

function Test-ServiceHealth {
    param([string]$Url, [int]$TimeoutSec = 5)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
        return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400)
    } catch {
        return $false
    }
}

function Invoke-TradeServiceOutage {
    param(
        [int]$DurationSec = 20,
        [string]$TradeContainer = "ai-cabinet-trade-service-1",
        [string]$HealthUrl = "http://127.0.0.1:18080/actuator/health"
    )
    Write-Host "==> Stopping trade-service ($TradeContainer) for ${DurationSec}s..."
    docker stop $TradeContainer | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "failed to stop $TradeContainer"
    }
    Start-Sleep -Seconds $DurationSec
    Write-Host "==> Starting trade-service ($TradeContainer)..."
    docker start $TradeContainer | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "failed to start $TradeContainer"
    }
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        if (Test-ServiceHealth -Url $HealthUrl -TimeoutSec 3) {
            Write-Host "==> trade-service healthy again"
            return $true
        }
        Start-Sleep -Seconds 2
    }
    throw "trade-service did not become healthy within 90s after restart"
}

function Get-E2eConsumerUserId {
    param([string]$Phone = "13800138000", [string]$PostgresContainer = "ai-cabinet-postgres-1")
    $sql = "SELECT user_id FROM user_info WHERE phone_number = '$Phone' LIMIT 1;"
    $out = docker exec $PostgresContainer psql -U aicabinet -d aicabinet -t -A -c $sql 2>&1
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($out)) {
        throw "consumer user not found for phone=$Phone"
    }
    return [long]($out.Trim())
}

function Set-E2eConsumerBalance {
    param(
        [int]$BalanceCents,
        [string]$Phone = "13800138000",
        [string]$PostgresContainer = "ai-cabinet-postgres-1",
        [string]$Reason = "e2e-test-balance-adjust"
    )
    $userId = Get-E2eConsumerUserId -Phone $Phone -PostgresContainer $PostgresContainer
    $sql = @"
UPDATE user_account SET balance_cents = $BalanceCents, frozen_cents = 0, updated_at = NOW()
WHERE user_id = $userId;
"@
    docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql | Out-Null
    Write-Host "==> Set consumer $Phone (user_id=$userId) balance to $BalanceCents cents"
    return $userId
}

function Invoke-E2eInternalDoorClose {
    param(
        [string]$BaseUrl,
        [string]$SessionId,
        [string]$DeviceId = "330449777078",
        [long]$UserId = 0,
        [string]$SkuId = "SKU-DEMO-001",
        [int]$Quantity = 1,
        [string]$UploadStatus = "UPLOADED",
        [string]$VideoUri = "",
        [string]$InternalApiKey = "dev-internal-key-change-me"
    )
    # Gateway blocks /internal/* — always hit trade-service directly.
    $internalBase = Resolve-E2eBaseUrl $BaseUrl
    $headers = @{ "X-Internal-Api-Key" = $InternalApiKey }
    $gravity = ConvertTo-Json @(@{ skuId = $SkuId; delta = -$Quantity; slotId = "SIM-1" }) -Compress
    if ([string]::IsNullOrWhiteSpace($VideoUri)) {
        $VideoUri = "minio://cabinet-videos/$(Get-E2eSimVideoKey -SessionId $SessionId -DeviceId $DeviceId -UserId $UserId)"
    }
    Invoke-E2eApi -BaseUrl $internalBase -Method POST -Path "/internal/v1/sessions/door-event" -Headers $headers -Body @{
        sessionId         = $SessionId
        deviceId          = $DeviceId
        doorState         = "CLOSED"
        timestamp         = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        videoUri          = $VideoUri
        uploadStatus      = $UploadStatus
        gravityDeltasJson = if ($UploadStatus -eq "UPLOADED") { $gravity } else { $null }
    } | Out-Null
    return $VideoUri
}

function Restart-E2eDeviceSimulator {
    param([string]$Container = "ai-cabinet-device-simulator-1")
    Write-Host "==> Restarting $Container to restore MQTT after trade outage"
    docker restart $Container | Out-Null
    Write-Host "    waiting 12s for MQTT warmup after simulator restart"
    Start-Sleep -Seconds 12
}

# plan 按设备全缺口生成出库；若某 SKU 有建议量但仓库无货会导致整单 400
function Prepare-E2eReplenishmentPlan {
    param(
        [string]$BaseUrl,
        [hashtable]$OpsAuth,
        [string]$DeviceId,
        [string]$WarehouseId = "WH-DEMO-001"
    )
    $suggestions = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/replenishment/suggest?deviceId=$DeviceId" -Headers $OpsAuth)
    if ($suggestions.Count -eq 0) {
        Write-Host "    Prepare-E2eReplenishmentPlan: no gaps on $DeviceId"
        return
    }
    $whInv = @(Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
        -Path "/api/v2/ops/admin/warehouse/inventory?warehouseId=$WarehouseId" -Headers $OpsAuth)
    $stockBySku = @{}
    $batchBySku = @{}
    foreach ($row in $whInv) {
        $sku = [string]$row.skuId
        if (-not $stockBySku.ContainsKey($sku)) { $stockBySku[$sku] = 0 }
        $stockBySku[$sku] += [int]$row.quantity
        if (-not $batchBySku.ContainsKey($sku) -and $row.batchNo) {
            $batchBySku[$sku] = [string]$row.batchNo
        }
    }
    $defaultBatch = @{
        "SKU-MILK-001"   = "B-WH-MILK-01"
        "SKU-SNACK-001"  = "B-WH-CHIPS-01"
        "SKU-DEMO-001"   = "B-DEMO-01"
        "SKU-SODA-001"   = "B-WH-SODA-01"
        "SKU-WATER-001"  = "B-WH-WATER-01"
        "SKU-NOODLE-001" = "B-WH-NOODLE-01"
    }
    $inboundLines = @()
    foreach ($s in $suggestions) {
        $sku = [string]$s.skuId
        $need = [int]$s.suggestQty
        if ($need -le 0) { continue }
        $have = if ($stockBySku.ContainsKey($sku)) { [int]$stockBySku[$sku] } else { 0 }
        if ($have -ge $need) { continue }
        $gap = $need - $have + 2
        $batch = $batchBySku[$sku]
        if (-not $batch) { $batch = $defaultBatch[$sku] }
        if (-not $batch) { $batch = "E2E-$sku" }
        Write-Host "    replenishment prep: sku=$sku suggest=$need warehouse=$have inbound=$gap batch=$batch"
        $inboundLines += @{
            skuId          = $sku
            batchNo        = $batch
            productionDate = "2026-08-01"
            expiryDate     = "2026-12-31"
            quantity       = $gap
        }
    }
    if ($inboundLines.Count -eq 0) {
        Write-Host "    Prepare-E2eReplenishmentPlan: warehouse stock sufficient"
        return
    }
    $ref = "E2E-PREP-$(Get-Date -Format 'yyyyMMddHHmmss')"
    Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/ops/admin/warehouse/inbound" -Headers $OpsAuth -Body @{
        warehouseId = $WarehouseId
        refNo       = $ref
        notes       = "e2e replenishment warehouse prep"
        lines       = $inboundLines
    } | Out-Null
    Write-Host "    inbound ref=$ref lines=$($inboundLines.Count)"
}

function Get-E2eAdminCaptchaCode {
    param(
        [string]$CaptchaId,
        [string]$RedisContainer = "ai-cabinet-redis-1"
    )
    if ([string]::IsNullOrWhiteSpace($CaptchaId)) {
        throw "CaptchaId required"
    }
    $raw = docker exec $RedisContainer redis-cli GET "aicabinet:captcha:$CaptchaId" 2>&1
    $code = [string]$raw
    if ($code -match '^\s*$' -or $code -match 'nil|ERR') {
        throw "Captcha not found in redis for id=$CaptchaId"
    }
    return $code.Trim().ToUpper()
}

function Get-E2eDbSnapshot {
    param(
        [string]$SessionId = "",
        [string]$Phone = "13800138000",
        [string]$PostgresContainer = "ai-cabinet-postgres-1"
    )
    $sessionFilter = ""
    if (-not [string]::IsNullOrWhiteSpace($SessionId)) {
        $sessionFilter = "WHERE session_id = '$SessionId'"
    }
    $sql = @"
SELECT 'balance' AS kind, ua.balance_cents::text AS v1, '' AS v2, '' AS v3
FROM user_account ua JOIN user_info ui ON ua.user_id = ui.user_id
WHERE ui.phone_number = '$Phone'
UNION ALL
SELECT 'session', ss.session_id, ss.state, COALESCE(ss.order_id, '')
FROM shopping_session ss $sessionFilter
ORDER BY kind, v1;
"@
    return docker exec $PostgresContainer psql -U aicabinet -d aicabinet -c $sql 2>&1
}
