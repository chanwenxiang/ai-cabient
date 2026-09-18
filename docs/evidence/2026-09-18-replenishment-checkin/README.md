# 证据：补货签到 fail-closed + 终态闸（2026-09-18）

本目录是这两条改动的**原始命令输出级证据**，不是结论摘要。
审计纪律要求判据可复核，所以保留可复现的输入与输出。

## 背景

`ReplenishmentService.doCheckInTask` 有两条独立缺陷，本轮各修一条：

**① 无坐标放行**（原行为）—— `deviceHasCoords=false` 时**直接跳过地理围栏**：
「建柜时漏填坐标 ⇒ 该柜永久绕开围栏」。改成 fail-closed。

**② 不校验任务状态**（原行为）—— 见下方「§2 实测」。这条的后果不是「字段难看」，
而是**运营的柜机解冻被一次现场签到静默撤销**。

改后 `doCheckInTask` 的闸门顺序（**顺序本身是判据**，错序会把「柜机缺坐标」误报成「用户没开定位」）：

```
1. 终态闸：status ∈ {COMPLETED, CANCELLED}  → 409 REPLENISHMENT_TASK_FINISHED   ← 本轮新增，刻意排最前
2. assertTaskHasFulfillableWork(task)       任务无可履约明细 → 400
3. require_location=true 且 柜机无坐标      → 400 DEVICE_LOCATION_MISSING
4. require_location=true 且 请求无坐标      → 400 LOCATION_REQUIRED
5. 柜机有坐标且请求带坐标                    → 距离校验 max_distance_m（默认 500）
```

终态闸的文案与状态集合跟开门侧 `DeviceValidationService.ensureRestockDoorAllowed` **共用一套**
（`REPLENISHMENT_TASK_FINISHED`）—— 同一个状态在两个入口给出不同说法会把补货员绕晕。

## 文件

| 文件 | 证明了什么 |
|---|---|
| `checkin-phase1-device-without-coords.txt` | **① 的核心主张**。柜机 `777740024057` 坐标为空时：① 空 body → 400；② **带「柜前合法坐标」也 → 400**（旧逻辑此时会放行，因为它只看请求侧）；③ 任务保持 `PENDING`、`check_in_at` 为空 ⇒ 失败是**关闭**的，不是静默跳过。 |
| `checkin-phase2-request-and-distance-gates.txt` | 柜机补坐标后：空 body → 400（请求侧闸）；北京 ≈1067 km → 400 且报文回显 `1066782 米`；**600 m → 400**、**450 m → 200**（`checkInDistanceM=449.99`）⇒ `max_distance_m=500` **真的被消费**；状态转 `IN_PROGRESS` 且 `check_in_lat/lng` 落库。 |
| `backfill-device-coords.sql` / `rollback-device-coords.sql` | 回填三台演示柜机坐标的**正向与反向**脚本，带 `IS NULL` 守卫（实测重复执行 `UPDATE 0`，幂等）。 |
| `static-gate-negative-validation.txt` | **静态门禁自身的负向验证，18/18**。对源码/脚本真注入 18 类漂移（脚本文案、服务端文案、闸门顺序反转、期望值块变装饰品、删掉关键断言、fail-loud 分支被删、DTO 去字段、坐标判据被内联…），逐一证明门禁**会变红**；且含 **1 条 `expect_red=False`** 的反向用例（只换文案、结构不动 ⇒ **必须保持绿**）。末尾 `ALL_RESTORED=True`、还原后复绿。 |
| `e2e-checkin-contract.FIXED.txt` | **修复后**的实跑（19/19 通过，`EXIT=0`）。含防假绿对照组、终态拒签 409 + **整行未动**、以及用例 8 的完整因果链。 |
| `e2e-checkin-contract.PREFIX.txt` | **修复前**的实跑（19 例**失败 9 例**，`EXIT=1`）。这是「e2e 真的能抓到这条回归」的证据，也是 §2 的实测来源。 |
| `e2e-checkin-contract.BEFORE-SCRIPT-FIX.txt` | 历史存档：脚本还有 3 处自身缺陷时的输出（选错目标、误判 status 端点鉴权、CANCELLED 未覆盖）。留作对照，说明「用例通过」需要先证明用例**不是恒真的**。 |
| `task_rows.BEFORE.tsv` / `.AFTER_PREFIX_RUN.tsv` / `.AFTER_FIXED_RUN.tsv` | `replenishment_task` 全字段快照（13 列）。`BEFORE` vs `AFTER_FIXED_RUN` 逐行 `diff` **完全相同** ⇒ 修复后的签到被拒时**一行都没写**。 |
| `restore-task-rows.sql` | 由上表 `BEFORE` 生成的还原脚本。修复前的代码会把存量行改脏（见下），跑完必须还原 —— 开发库是多方共用的。 |
| `ReplenishmentService.java.FIXED.bak` | 「已修复」形态的源文件逐字节备份（md5 `ec266e63326a9fce51c4450f7af5e6d7`）。`patch-*.py restore` 就是拿它还原。 |
| `audit-gates-local.txt` | 本机聚合门禁链 `node scripts/run-audit-gates.mjs`：**17 个门禁，0 失败**。 |
| `checkin-contract-live-verification.txt` | 早期实跑存档（5/5）：负向助手不是「抛异常就算过」。已被 `*.FIXED.txt` 覆盖，保留作演进痕迹。 |

## 复现方式

前置：开发栈已起（`docker compose` 全栈），trade-service 端口 `18080`，Postgres 端口 `15433`。

```bash
docker exec -it ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet   # 看/改数据
curl -s http://127.0.0.1:18080/v3/api-docs -o /dev/null -w '%{http_code}\n'  # 200
```

### 静态门禁（离线，已接入 `pnpm check:audit-gates` 第 2 位）

```bash
node scripts/check-replenishment-checkin-contract.mjs
python scripts/devops/verify-checkin-gate-drift.py     # 18/18 且逐字节还原
```

### 实跑 e2e（需已起栈）

```powershell
.\scripts\e2e-checkin-contract.ps1
.\scripts\e2e-checkin-contract.ps1 -DeviceId 777740024057   # 指定柜机
.\scripts\e2e-checkin-contract.ps1 -NoFixture               # 只用库里已有任务，绝不造数据
```

`e2e-checkin-contract.ps1` 的期望值**读自** `e2e-replenishment.ps1` 的 `$CheckInContract` 块，
不另存一份 —— 避免「两处各写一份、一起写错」。

**目标任务是现场造的夹具**（`New-E2eCheckInFixtureTask`，PENDING + 1 条 `applied=false` 明细），
跑完在 `finally` 里**无条件卸载** —— 残留一条 `IN_PROGRESS + check_in_at` 的任务会把柜机
对消费者**永久冻结**。为什么必须造夹具：本开发库里 `replenishment_task` 只有终态行
（task 1 `COMPLETED` / task 2 `CANCELLED`），而 `suggest` 常年 no gaps（`Prepare-E2eReplenishmentPlan`
造不出缺口）。没有夹具时这些用例会「选不到目标」，最容易被写成**静默跳过** —— 那正好退化成
永远绿灯的装饰品。

### 运行时负向验证（A/B 对照，约 2 次镜像构建 ≈ 2 分钟）

```bash
python scripts/devops/patch-checkin-gate-prefix.py prefix     # 把终态闸改回修复前
docker compose -f infra/docker-compose.full.yml -f infra/docker-compose.win-ports.yml up -d --build trade-service
.\scripts\e2e-checkin-contract.ps1                            # 期望：失败 9 例、EXIT=1
python scripts/devops/patch-checkin-gate-prefix.py restore    # 逐字节还原（md5 回 ec266e63…）
docker compose ... up -d --build trade-service                # 再重建
.\scripts\e2e-checkin-contract.ps1                            # 期望：失败 0 例、EXIT=0
```

> ⚠️ `prefix` 带幂等护栏：源文件已是「修复前」形态时**拒绝**执行 ——
> 否则第二次 prefix 会把「修复前」的内容写进备份，把还原点毁掉。
> ⚠️ 修复前的代码会真的改动存量行，跑完必须执行 `restore-task-rows.sql` 还原数据。
> ⚠️ Phase1（无坐标拒签）要求柜机**无坐标**，Phase2 要求**有坐标**，顺序不能颠倒。
> **不要**用这套脚本在共享/生产库上跑。

## §2 实测：签到会「复活」已取消的任务（**已从源码判读升级为实测**）

上一版这里写的是「源码判读，未实测」。本轮做了 A/B 对照，**实测确认**：

**COMPLETED 任务的签到证据被静默覆盖**（状态不变，但证据被改写）：

```
[FAIL] 终态 COMPLETED 拒签后整行未动（DB 通道）
       task=1 被拒之后行内容变了（签到本不该产生任何写入）：
       check_in_at: [2026-09-13 03:02:27.437109+00] -> [2026-09-18 07:38:27.919379+00]；
       check_in_lat: [] -> [31.238]；check_in_lng: [] -> [121.48]
```

**CANCELLED 任务被签到复活为 IN_PROGRESS**（响应体与 DB 双向确认）：

```
[FAIL] 终态 CANCELLED 拒签 → 409 且文案正确
       期望被拒（HTTP 409），但请求未被拒：
       {"taskId":2,…,"status":"IN_PROGRESS",…,"checkInAt":"2026-09-18T07:38:28.998313514Z"…}
[FAIL] 终态 CANCELLED 拒签后整行未动（DB 通道）
       status: CANCELLED -> IN_PROGRESS；check_in_at: [2026-09-18 04:44:53.294196+00] -> […7.998314+00]
```

**产品后果：运营刚做的解冻被撤销，柜机重新对消费者停售**：

```
[FAIL] 因果链③ 解冻生效（消费者可开门）
       设备 777740024057 被重新冻结了（frozen_tasks=1）
       —— 消费者开门会拿到 409「设备补货中」，运营刚做的空取消被签到撤销
[FAIL] 因果链⑥ 拒签后柜机仍可售（P0 已修复）
       设备 777740024057 被重新冻结了（frozen_tasks=2）
```

`frozen_tasks` 从 0 变成 2 的因果链：运营 `cancel-empty` 把任务取消 ⇒ 柜机解冻；
现场再签到一次把任务改回 `IN_PROGRESS`，而 `check_in_at` 还在 ⇒
**冻结判据 `status=IN_PROGRESS AND check_in_at IS NOT NULL` 重新成立** ⇒ 柜机重新停售。

消费者侧的因果链是**可观测的**，不是推测：
`DeviceValidationService.ensureConsumerShoppingAllowed`（`DeviceValidationService.java:94`）
被 `SessionOpenService:65` / `SessionService:162` 调用 ⇒ 消费者开门 409「设备补货中」；
`getDeviceStatus` 也读同一条判据 ⇒ `available=false` / `busyReason=REPLENISHMENT`，附近页据此灰显。

**为什么必须做这次对照**：静态门禁只能证明「源码与脚本的期望值一致」。
少了对照，「用例通过」与「用例恒真」是分不开的 —— 而一个恒真的判据比没有判据更糟。

## 已知边界（都不假装已守住）

1. **消费者视角只是旁证，且会被遮蔽**。`Assert-E2eDeviceNotReplenishmentFrozen` 读
   `/api/v2/devices/{id}/status` 作旁证，但 `busyReason` 是四态，`OFFLINE` / `LOCKED` 会
   **遮蔽** `REPLENISHMENT`。本轮实测该柜机就是 `busyReason=OFFLINE`，助手如实输出
   「属遮蔽态，未提供旁证」而**不是**当成通过。精判据始终读 DB。
2. **终态任务的存在性依赖库内数据**。用例 6/7 用的是**库里真实的**终态任务（不是夹具），
   因为它们要证明的是「这条闸对既有数据生效」。若某个环境里连一个终态任务都没有，
   用例会明确记 `[FAIL] …本用例**未覆盖**` —— 未覆盖与通过必须长得不一样。
3. **`scripts/e2e-replenishment.ps1` 第 3 步在本机跑不起来**（`400 当前无补货缺口` /
   `500 系统繁忙`，`Prepare-E2eReplenishmentPlan` 恒 `no gaps`）⇒ 它**根本走不到第 5 步**，
   这就是第 5 步旧契约能悄悄过期的原因。第 3 步与 `HEAD` 逐字节相同（非本轮引入）。
4. **距离校验用球面近似**（`validateCheckInLocation`），数百米量级误差可忽略，
   但**极点 / 跨经度 180°** 附近未做用例。
5. **e2e 只在 Windows + PowerShell 5.1 上实跑过**；`.ps1` 都带 UTF-8 BOM
   （无 BOM ⇒ PS 5.1 按 ANSI 读中文，报错位置会指向完全无关的地方）。

## 脱敏说明

`checkin-phase1-*.txt` 里的登录响应含 dev 栈 accessToken，已替换为 `<REDACTED-JWT>`。
该 token 本就已过期（`expiresInSeconds=1800`），但原始日志属于「不该留在工作区的凭据形状」，
故在归档时脱敏。`e2e-*.txt` 里的 token 只在内存里用，未落盘。

## 数据还原说明

`replenishment_task` 是**共享 dev 库**里的表，本轮为做 A/B 对照改写过它。收尾状态：

```
=== 与 BEFORE 逐行比对 ===
IDENTICAL ✅ 存量行一字未动
=== 夹具残留 === 0
=== 设备冻结数（应 0）=== 0
```

`task_rows.AFTER_FIXED_RUN.tsv` 与 `task_rows.BEFORE.tsv` 逐行一致，`restore-task-rows.sql`
留作复跑时的还原手段。另注：本库是多方共用，**task 状态会漂移**（上一轮开始 task 2 是
`IN_PROGRESS`，几十分钟后变成 `CANCELLED`，非本目录脚本所为）⇒ 取数前请先现查。

**收尾后再次现查（2026-09-18 15:5x，`docs` 归档之后）**：

```
=== 夹具残留（replenishment_task.notes LIKE '%e2e-checkin-contract fixture%'）===
0
=== 柜机冻结数（status='IN_PROGRESS' AND check_in_at IS NOT NULL）===
0
=== 存量任务行 ===
 task_id |  device_id   |  status   |          check_in_at          |       notes
       1 | 777740024057 | COMPLETED | 2026-09-13 03:02:27.437109+00 | seq=1 dist=0m
       2 | 777740024057 | CANCELLED | 2026-09-18 04:44:53.294196+00 | merchant request 1
```

⚠️ 夹具标识列是 **`notes`**（`e2e-lib.ps1:599-600`），**不是 `remark`**——本目录脚本用 `remark`
查会直接 `ERROR: column "remark" does not exist`。取数时别照抄错列名。

## 编码规范化说明（2026-09-18）

本目录的 `README.md` 与其余 `.txt` 是 UTF-8，但三份 `e2e-checkin-contract.*.txt` 由 PowerShell 重定向产出，
是**混杂编码**（正文 UTF-16LE + 尾部 ANSI 的 `EXIT=n` 标签）⇒ 直接读会显示成 `[ e 2 e - c h e c k …`，
证据「不可读」等于半个失效。

已统一转为 **UTF-8（无 BOM）**，并把尾部被混杂编码打散的退出码标签按**转换前原始字节**还原：

| 文件 | 还原后的尾部 | 依据 |
|---|---|---|
| `e2e-checkin-contract.FIXED.txt` | `EXIT=0` | 转换前原始字节 `45 58 49 54 3D 30`（ASCII `EXIT=0`） |
| `e2e-checkin-contract.PREFIX.txt` | `EXIT=1` | 转换前原始字节 `45 58 49 54 3D 31` |
| `e2e-checkin-contract.BEFORE-SCRIPT-FIX.txt` | 标注「未观测到」 | 该次运行的退出码**未在转换前读到**，**不臆造**；以正文「19 个用例，失败 0 个」为准 |

**除编码与上述尾部标签外，正文逐字未改。** 结论仍以正文的「N 个用例，失败 M 个」为准
（FIXED 0 失败 / PREFIX 9 失败 / BEFORE-SCRIPT-FIX 0 失败 —— 后者正是「脚本自身有缺陷时
反而全绿」的假绿样本，见上表）。

