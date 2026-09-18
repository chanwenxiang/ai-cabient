# `e2e-replenishment.ps1` 第 3 步恒 `no gaps` —— 根因、修法与实测

**日期**：2026-09-19 · **脚本**：`scripts/e2e-replenishment.ps1` + `scripts/e2e-lib.ps1` · **状态**：已修复并端到端跑通

---

## 1. 现象

`scripts/e2e-replenishment.ps1` 在本机**第一次**能跑通，之后每次都停在第 3 步
（`POST /replenishment/plan` 返回 400「当前无补货缺口」），表面看像「第 3 步恒 `no gaps`」。

## 2. 根因：不是脚本坏了，是它把库存补满了

补货建议的数据源是 `device_slot`：**只有「账面 < `min_level`」的货道才产生缺口**。
而本脚本第 8 步会给货道补货 ⇒ **跑完一次后，货道被补到 `max_level`（满仓）**。

实测 `before-snapshot.txt`（默认柜机 `330449777078`，8 条货道）：

| 货道 | A1 | A2 | A3 | A4 | B1 | B2 | B3 | B4 |
|---|---|---|---|---|---|---|---|---|
| `quantity` | 8 | 8 | 6 | 6 | 8 | 6 | 8 | 4 |
| `max_level` | 8 | 8 | 6 | 6 | 8 | 6 | 8 | 4 |
| `min_level` | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 1 |

**8/8 条货道的 `quantity` 恰等于 `max_level`** ⇒ 「账面 < minLevel」永不成立 ⇒ `suggest` 恒 0 条。

⇒ 这是**「库存真的不缺」这个合法稳态**，不是产品缺陷，也不是脚本缺陷。
但它让 E2E **不可重复运行**，而这正是自动化验证最需要的性质。

> 脚本此前已把「延期失败」改成 fail-fast（并给出三种成因与处置），那部分是对的 ——
> 缺的是**一条能主动造出缺口的路径**。

## 3. 修法：`-ForceGap`（显式开关，默认关闭）

新增 `-ForceGap`：当 `suggest` 为 0 且清理在途出库后仍为 0 时，**走产品自身的盘点接口**
把账面最多的那条货道清零，从而稳定造出真实缺口：

```
POST /api/v2/ops/admin/devices/{deviceId}/slots/stocktake
{"slotCode":"<货道>","physicalQty":0,"adjustBookQty":true}
```

链路依据（读源码确认，非猜测）：
`DeviceSlotService.doStocktakeSlot` → `InventoryLotService.stocktakeAdjustForSlot`
中 `delta = counted(0) - current(<0)` 走**扣减分支**，把该 SKU 的 lot 减到 0 并记一条 `ADJ` 流水；
之后 `suggestSlotsForDevice` 判定 `bookQty(0) < minLevel` ⇒ 产出缺口。

**为什么不直接改 DB**：改 DB 会绕过库存流水与聚合同步（`syncAggregateInventory`），
留下账实不符的中间态；而盘点接口是产品为「修正账实」设计的**正当路径**。

🔴 **它会写真实账面库存**（不是 mock），所以**默认关闭**，必须显式传 `-ForceGap`。

## 4. 证据 A：不带开关 → 快速失败且给可执行处置

`e2e-without-forcegap.txt` / `e2e-without-forcegap-report.txt`

```
==> 1. Ops login (13900000001)
    opsUserId=100000001
==> 2b. Ensure warehouse stock for device replenishment gaps
    device slots=8
    in-transit outbounds: 0
→ 抛错：柜机 330449777078 无补货缺口（/replenishment/suggest 返回 0 条）…
   三种成因，按顺序自查：
     1) 货道库存已 >= minLevel（真的不缺货，属正常状态，不是缺陷）——**本机默认柜机跑过一次后就是这个状态**；
     2) 有货道但都没绑定 SKU；3) 在途补货单已把缺口抵消。
   处置（任选其一）：
     · 自动造缺口（推荐，会写真实账面库存）：.\scripts\e2e-replenishment.ps1 -ForceGap
     · 手工造缺口：POST …/slots/stocktake  body {"slotCode":"<货道>","physicalQty":0,"adjustBookQty":true}
     · 换一台柜机 / 无货道时先套用 planogram 模板
```

**失败点在第 2b 步而不是第 3 步** —— 根因在补货建议这一层就暴露，不再伪装成「计划接口 400」。

## 5. 证据 B：带 `-ForceGap` → 全链路跑通

`e2e-with-forcegap.txt`（`elapsed=9.6s`，无异常）

```
==> 2b. Ensure warehouse stock for device replenishment gaps [-ForceGap: may zero a slot]
-ForceGap：将对 330449777078 的货道 B3（SKU=SKU-NOODLE-001）执行盘点清零 —— physicalQty=0 且 adjustBookQty=true，**账面由 8 写为 0**
    stocktake 清零完成: slot=B3 bookQty=0 lastPhysicalQty=0        ← 接口回读确认已归零
    -ForceGap: 货道 B3 账面已清零 → suggest=1 条                   ← 关键：0 → 1
    replenishment prep: sku=SKU-NOODLE-001 suggest=8 warehouse=0 inbound=10
==> 3. Plan replenishment route  routeId=9 taskId=16 status=PENDING
==> 4. Resolve warehouse outbound  outboundId=6 status=DRAFT → 4b Pick → 4c Ship
==> 5. Merchant check-in — 契约用例（fail-closed）+ 正式签到
    device coords lat=31.262 lng=121.516
    ✓ P1 空 body（期望 location-required） → HTTP 400 已拒绝
    ✓ P2 距柜机约 600m（上限 500m） → HTTP 400 已拒绝
    ✓ 签到负向用例(P1/P2 均被拒)后整行未动(DB) → task=16 整行未动（status=IN_PROGRESS check_in_at=）
    status=IN_PROGRESS checkInAt=2026-09-18T17:12:39Z distanceM=450.0
==> 6. open-door  sessionId=1789751559372529064518
==> 7. Restock door OPEN → CLOSED
==> 8. Task lines count=1   ==> 9. evidence fileId=3   ==> 10. taskId=16 COMPLETED
==> 11. Assert merchant task list reflects completion
OK replenishment E2E passed (warehouse) taskId=16 sessionId=1789751559372529064518
```

**走完了第 11 步**（此前到不了第 5 步），且第 5 步的三条签到契约断言全绿。

## 6. 数据影响与自还原

对比 `before-snapshot.txt` ↔ `after-snapshot.txt`：

| 项 | 跑之前 | 跑之后 | 说明 |
|---|---|---|---|
| 货道 B3 `quantity` | 8 | **8** | 被清零到 0，又被第 8 步补回 8 ⇒ **自动回到满仓** |
| 其余 7 条货道 | 满仓 | 满仓 | 未受影响 |
| 仓库 `SKU-NOODLE-001` | 110 | **112** | 脚本既有行为（`Prepare` 先入库 10、后被领用 8） |
| `replenishment_task` 16 | — | `COMPLETED` | 本轮新增 |

⇒ **跑完自动回到「满仓无缺口」稳态 ⇒ 下次仍需 `-ForceGap` 造缺口 ⇒ 脚本现在可重复运行**。
（副作用仅限：多一条已完成补货任务 + 仓库某个 SKU 净 +2，属脚本既有行为，不是本次改动引入。）

## 7. 附带修掉的一个真缺陷：端口探测超时误判

验证 `-ForceGap` 时脚本先在**第 1 步登录**就失败，报
`WebException: 无法连接到远程服务器` —— 完全指不到根因。取证后定位：

- `e2e-lib.ps1: Test-E2eHttpOk` 默认 `TimeoutSec = 2`
- 实测 `baseurl-timeout-probe.txt`：`/actuator/health` 响应 **2.1s**
  ⇒ `TimeoutSec=2` **False（2.0s 超时）**，`TimeoutSec=5` **True（2.1s）** ⇒ **临界误判**
- 探测 `8080`（不通）→ 探测 `18080`（误判不通）→ **静默回退**到默认 `http://localhost:8080`
  ⇒ 后续第一个 HTTP 调用炸出 `WebException`，错误信息与真实原因（探测超时回退）毫无关系

**修法**：`TimeoutSec` 2 → 5（消除临界）；并在探测**全部失败**时 `Write-Warning`
明确说出「已试哪些地址、回退到哪、后续会失败、怎么显式指定」——
不再把失败**静默**推迟到第一次业务请求。

## 8. 🔴 这份证据**没有**证明什么

- **没有**证明 `-ForceGap` 对所有柜机都可用：若柜机没有任何「已启用 + 已绑定 SKU」的货道，
  它会抛错并提示先套用 planogram（该分支**未实测**，只做了代码路径检查）。
- **没有**覆盖 `photoStocktake` 策略开启的场景：此时盘点接口会 400「要求盘点必须上传照片凭证」。
  代码里**捕获并给出指引**，但**没有实测过**这条分支（本机默认柜机未开启该策略）。
- **没有**证明第 5 步的签到契约断言是本次新增 —— 它们早已存在，本证据只是复现其仍然全绿。
- **没有**跑第二次 `-ForceGap` 来证明「可重复运行」：该结论由第 6 节的数据对比**推断**
  （满仓稳态 + 已完成任务不会阻塞新一轮），**未做第二次实跑**。
- 未验证 `-ForceGap` 在 CI 下的行为 —— 该脚本**设计上就不在 CI 跑**（需真实登录/MQTT/凭证上传）。

## 9. 文件清单

| 文件 | 内容 |
|---|---|
| `before-snapshot.txt` | 跑之前：8 条货道全满（quantity == max_level）、仓库库存、任务基线 |
| `after-snapshot.txt` | 跑之后：货道回到满仓、仓库 SKU-NOODLE-001 110→112、task 16 COMPLETED |
| `e2e-without-forcegap.txt` | 不带开关的完整输出（fail-fast 与处置提示） |
| `e2e-without-forcegap-report.txt` | 上者的结构化报告（含 ScriptStackTrace 指向 `e2e-lib.ps1:1190`） |
| `e2e-with-forcegap.txt` | 带 `-ForceGap` 的完整输出（11 步全过） |
| `baseurl-timeout-probe.txt` | 端口探测超时对照（2s 误判 / 5s 正常，实测 2.1s） |
