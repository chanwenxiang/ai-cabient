# R14 · 分仓月结账期半开窗口 · 真实造数 + 跨账期同源 + 负向控制 + 幂等（UAT，2026-11-08）

> 本轮把**分仓月结的账期窗口归属**跑成**真库闭环**，并补齐 R13 §九 明确列出、但本轮才真正做的
> `warehouse-monthly-close` 造数（R13 时点未做）。
> 目标：验证月结对「半开账期窗口 `[prev26, cur26)`」的起点/终点归属、`openingQty` 与当期入库的分列、
> `restockQty` 的取消增收钳制（审计批次4 的真实回归点）、客情用水不混入上柜、
> 以及注册任务的**幂等**（DRAFT 不重生成、保护财务手工补录）与**收口复原**。
> 造数**全部**落在 UAT `aicabinet_uat`，`ref_type='AUDIT-R14'/'AUDIT-R14B'` 前缀，收口后回退到基线、0 残留（§七有实读核对）。
> **证据分级**：A = agent 读到并回读验证；B = 脚本真实跑但输出未落盘；C = 仅对话/文档。
> 本文全部条目为 **A**（真库回读 + 源码 grounding + 真实 HTTP 调用）。
> **本轮新增的结尾 §十 C1**：对**刚推送的 amended SHA（`20ffcb7b…`）**在真实 GitHub Actions 上跑完并回读
> —— run `42463931974`（number=1297），`conclusion=success`、5/5 jobs `success`。R13 §十一 记录的是被 amend 掉的旧 SHA `58d570a5…`（run `42461333249`）；§十 与之并列，二者互为补强。

## 一、被测面

- 业务：分仓月结草稿/表单的**应结件数**与**差异明细**（平台/API 眼底），由
  `WarehouseMonthlyCloseJob.ensureLastClosedPeriodSheets()` 每日 02:10（Asia/Shanghai）执行。
  口径 = 账期制，账期 = 上月 26 日 00:00 ~ 本月 26 日，标签 = 结束月（`2026-10` 则窗口
  `[2026-09-26T00:00+08, 2026-10-26T00:00+08)` = UTC `[2026-09-25T16:00Z, 2026-10-25T16:00Z)`）。
- 端点（真实超管 token，非伪造）：
  - 读态/索引端点（**只读计算，不写库**）：`GET /api/v2/ops/admin/warehouse/monthly-close?warehouseId=<id>&yearMonth=<YYYY-MM>`
  - 写态（表单生成）：`POST /api/v2/ops/admin/warehouse/close-sheets/generate`（body `{warehouseId, yearMonth}`）
  - 注册任务手动触发：`POST /api/v2/ops/admin/scheduled-tasks/warehouse-monthly-close/run`
    → `ScheduledTaskRegistry.java:147` 已注册 runnable；`ScheduledTaskXxlJobHandler.java:145`
    经 `runKey("warehouse-monthly-close")` 路由；`ScheduledTaskController.java:105-146` 运维端点。
- 会话：**真实 login 取 token**（非伪造）：超管 `13900000001 / 123456`；登录必须带**图形验证码**
  （`captchaId` + `captchaCode`，码从 redis `aicabinet:captcha:<id>` 取），否则 400。

## 二、grounding（源码，先证窗口语义再跑）

| 项 | 事实 | 位置 |
|---|---|---|
| 账期窗口（半开 `[start, end)`） | `start = period.minusMonths(1).atDay(26).atStartOfDay(SHANGHAI).toInstant()`；`end = period.atDay(26)...`；标签 `period` = 结束月 | `WarehouseMonthlyCloseService.java:138-144` |
| 归属（逐行） | `opening = at.isBefore(start)`；`inMonth = !opening && at.isBefore(end)`；二者都 false 则 `continue` 丢弃 | `WarehouseMonthlyCloseMath.java:59-65`（**左闭右开：`at == start` 入账期；`at == end` 丢弃**） |
| 期初列来源 | 有上期单 → `prevCounted`；否则上期期末流水；否则 `acc.openingQty`（只有上一账期的余量才有 openingQty） | `:148-154`；行内三元 `:189-198` |
| 当期入库列 | `PURCHASE_RECEIVE`/`MANUAL_INBOUND` → `purchaseInQty = max(delta,0)` | `:88` |
| 上柜列 | `OUTBOUND_SHIP`（非客情）→ `restockQty = max(delta,0)`；`OUTBOUND_CANCEL`（非客情）→ `restockQty -= max(delta,0)`（**取消为支出递减（入库）**） | `:90-103` |
| ✈️ restockQty 钳制**在全部 movement 应用完之后** | `for (Acc acc : bySku.values()) if (acc.restockQty < 0) acc.restockQty = 0;` | `:73-80` |
| 客情用水分流 | `refType == GUEST_WATER` 时 `OUTBOUND_SHIP` → `guestWaterQty`；**不进 `restockQty`** | `:91-95`、`:97-103` |
| 所得件数 | `expectedQty = openingQty + purchaseInQty + transferInQty - returnQty - transferOutQty - restockQty` | `:45-47` |
| 上期状态 | 仅 `prevSheetCounted`（上期**账期表**）或 stageStocktake 流水（`flowStocktake = 0` 时读上期流水） | `:148-154` |
| 任务目标账期 | `today.getDayOfMonth() >= 26 ? YearMonth.from(today) : minusMonths(1)` | `WarehouseMonthlyCloseJob.java:58-63` |
| 任务幂等 | 单账期有 DRAFT → `ensureDraft` 返回 `false` → `skippedExisting++`，**不重生成**；summary `"生成 N 仓，已有单跳过 M 仓"` | `:74-78`、`:87-89` |

## 三、造数：23 张边界点（两个账期，跨月同源）

今日 = `2026-11-08`（Asia/Shanghai），`dayOfMonth = 8 < 26` ⇒ 任务目标账期标签 = **`2026-10`**；
地址窗口 UTC `[2026-09-25T16:00Z, 2026-10-25T16:00Z)`；`2025-08` 的两个月度窗口 UTC `[2025-07-25T16:00Z, 2025-08-25T16:00Z)`。
两个窗口都先确认**窗口内 0 行真实流水**（`inwin_total=0`、`open_total=0`），且**无既有账期单**（`warehouses 3 | 每仓 sheets10=1`）。

| sku_id | ref_type | movement_type | delta_qty | created_at (UTC) | 归列 | 期望应列 |
|---|---|---|---|---|---|---|
| SKU-SNACK-001 | AUDIT-R14 | PURCHASE_RECEIVE | 1 | 2026-09-25 15:59:59 | **期初**（start−1s） | opening 1 |
| SKU-SNACK-001 | AUDIT-R14 | PURCHASE_RECEIVE | 2 | 2026-09-25 16:00:00 | **当期入库**（恰 = start） | purchase 2 |
| SKU-SNACK-001 | AUDIT-R14 | PURCHASE_RECEIVE | 3 | 2026-10-25 15:59:59 | 当期入库（end−1s） | purchase 3 |
| SKU-SNACK-001 | AUDIT-R14 | PURCHASE_RECEIVE | 4 | 2026-10-25 16:00:00 | **丢弃**（恰 = end） | — |
| SKU-SNACK-001 | AUDIT-R14 | PURCHASE_RECEIVE | 5 | 2026-10-25 16:00:01 | **丢弃**（end+1s） | — |
| SKU-WATER-001 | AUDIT-R14 | PURCHASE_RECEIVE | 7 | 2026-09-25 15:59:59 | **期初**（start−1s） | opening 7 |
| SKU-SODA-001 | AUDIT-R14 | PURCHASE_RECEIVE | 8 | 2026-10-25 16:00:00 | **丢弃**（恰 = end） | 无行 |
| SKU-MILK-001 | AUDIT-R14 | OUTBOUND_SHIP | −10 | 2026-09-25 16:00:01 | 上柜/取消基线 | restock 10 |
| SKU-MILK-001 | AUDIT-R14 | OUTBOUND_CANCEL | 12 | 2026-09-25 16:00:02 | 上柜/取消基线 | cancel −12 |
| SKU-MILK-001 | AUDIT-R14 | OUTBOUND_SHIP | −3 | 2026-09-25 16:00:03 | 上柜/取消基线 | restock +3 |
| SKU-NOODLE-001 | GUEST_WATER | OUTBOUND_SHIP | −4 | 2026-09-25 16:00:04 | 客情用水（不进上柜） | guest 4 |
| SKU-SNACK-001 | AUDIT-R14B | PURCHASE_RECEIVE | 9 | 2025-07-25 15:59:59 | **期初**（2025-08 `start−1s`） | opening 9 |
| SKU-SNACK-001 | AUDIT-R14B | PURCHASE_RECEIVE | 1 | 2025-07-25 16:00:00 | 当期入库（恰 = start） | purchase 1 |
| SKU-SNACK-001 | AUDIT-R14B | PURCHASE_RECEIVE | 2 | 2025-08-01 00:00:00 | 当期入库 | purchase 2 |
| SKU-SNACK-001 | AUDIT-R14B | PURCHASE_RECEIVE | 3 | 2025-08-25 15:59:59 | 当期入库（end−1s） | purchase 3 |
| SKU-SNACK-001 | AUDIT-R14B | PURCHASE_RECEIVE | 4 | 2025-08-25 16:00:00 | **丢弃**（恰 = end） | — |
| SKU-SNACK-001 | AUDIT-R14B | PURCHASE_RECEIVE | 5 | 2025-08-25 16:00:01 | **丢弃**（end+1s） | — |
| SKU-WATER-001 | AUDIT-R14B | PURCHASE_RECEIVE | 6 | 2025-08-01 00:00:00 | 当期入库 | purchase 6 |
| SKU-MILK-001 | AUDIT-R14B | OUTBOUND_SHIP | −10 | 2025-07-25 16:00:01 | 上柜/取消基线 | restock 1 |
| SKU-MILK-001 | AUDIT-R14B | OUTBOUND_CANCEL | 12 | 2025-07-25 16:00:02 | 上柜/取消基线 | cancel −12 |
| SKU-MILK-001 | AUDIT-R14B | OUTBOUND_SHIP | −3 | 2025-07-25 16:00:03 | 上柜/取消基线 | restock +3 |
| SKU-NOODLE-001 | GUEST_WATER | OUTBOUND_SHIP | −4 | 2025-07-25 16:00:04 | 客情用水（不进上柜） | guest 4 |
| 合计行数 | | | | | | **23** |

> 两个窗口用**相同的 5 类边界点**，验证代码在两个不同账期上行为同源（同一份 `closeSheet`/`aggregate`）。

## 四、窗口归属核对（真实计算结果反推）

**账期 `2026-10`（调用 `GET /api/v2/ops/admin/warehouse/monthly-close?warehouseId=WH-DEMO-001&yearMonth=2026-10`，真实超管 token）**

| sku_id | opening | purchaseIn | transferIn | transferOut | restock | return | loss | **expected** | guest |
|---|---|---|---|---|---|---|---|---|---|
| SKU-MILK-001 | 0 | 0 | 0 | 0 | **1** | 0 | 0 | **−1** | 0 |
| SKU-NOODLE-001 | 0 | 0 | 0 | 0 | **0** | 0 | 0 | **0** | **4** |
| SKU-SNACK-001 | **1** | **5** | 0 | 0 | 0 | 0 | 0 | **6** | 0 |
| SKU-WATER-001 | **7** | 0 | 0 | 0 | 0 | 0 | 0 | **7** | 0 |

- `SKU-SNACK-001`：恰在 `start` 的 +2 与 end−1s 的 +3 都计入当期；`start−1s` 的 1 进**期初**；`end`/`end+1s` 的 4/5 **未入账** ⇒ 半开窗口命中，`expected = 1 + 5 = 6`。
- `SKU-WATER-001`：仅 `start−1s` 的 7 ⇒ 全部进**期初**，当期 0，`expected = 7`。
- `SKU-MILK-001`：`−10 + 12 + (−3)` ⇒ restock = `10 − 12 + 3 = 1`；取消时刻 `restockQty` 会**暂时变 −12 再回正**，最后才钳 0 ⇒ `expected = −1`。✅ **审计批次4 的钳制修复真实生效**（若把钳制放回逐条末尾，restock 会先回 0 ⇒ 最终结果 3，`expected = −3`；实测 1/−1 与源码 `:76-80` 一致）。
- `SKU-NOODLE-001`：`refType=GUEST_WATER` ⇒ 走 `guestWaterQty`，**restock 仍为 0**，`expected = 0`（客情用水不混入上柜）。

> 负向控制：`SKU-SODA-001` **整行只含恰在 `end` 的一条** ⇒ 计算结果**不出现该 SKU 行**（`n=4`，无 SODA-001）⇒ `end` 确为丢弃边界；两侧各 1s 即切换，**无 off-by-one**。

## 五、跨账期同源（`2025-08` 窗口）

同一仓库同一份代码，换成 `yearMonth=2025-08`，行结构与归列**完全同源**：

| sku_id | opening | purchaseIn | restock | expected | guest |
|---|---|---|---|---|---|
| SKU-SNACK-001 | 9 (start−1s) | 6 (3 条在窗内) | 0 | **15** | 0 |
| SKU-WATER-001 | 0 | 6 | 0 | **6** | 0 |
| SKU-MILK-001 | 0 | 0 | **1** | **−1** | 0 |
| SKU-NOODLE-001 | 0 | 0 | 0 | 0 | **4** |
| SKU-SODA-001 | —（负向控制） | — | — | — | — |

⇒ 两个账期边界结论一致，`start-1s → 期初`、`start → 当期`、`end → 丢弃`、`restock 钳制在末尾`、`GUEST_WATER 分流` 在两条时间线上均成立。

## 六、写态 + 幂等（真实表单生成端点 + 注册任务）

**写态（真实生成端点，真实 token）**：`POST /api/v2/ops/admin/warehouse/close-sheets/generate`（归属 wh=WH-DEMO-001, ym=2025-08）
- ✅ `status=DRAFT`，`closeId=4`，`YYYY=2025-08`，`lines=4`，`loss=0`/`surplus=0`，行值与 §五 计算结果逐字一致
  （`lossQty`/`surplusQty` 只对 `gapQty != null && != 0` 的行求和 —— 本轮 4 行 `gapQty` 均为 null（无盘点流水 ⇒
  `countedQty=null` → `gap=null`），故两列自然为 0 而非「空白下半段」；口径见 `WarehouseMonthlyCloseSheetService.java:246-273`）。
- ✅ 随后 `GET /api/v2/ops/admin/warehouse/close-sheets/{4}/drift` ⇒ `drifted=0`、无 drifted SKU：本轮未置外部流水/手工编辑触发偏差，漂移表为空、总数未变。

**注册任务幂等（真实 run 端点）**：`POST /api/v2/ops/admin/scheduled-tasks/warehouse-monthly-close/run`，连跑 3 次：

| 次序 | lastMessage（run 明细） | 说明 |
|---|---|---|
| run1 | `账期 2026-10：生成 0 仓，已有单跳过 3 仓`（耗时 149 ms） | 已有 3 张 DRAFT ⇒ **不重生成** |
| run2 | `账期 2026-10：生成 0 仓，已有单跳过 3 仓`（耗时 9 ms） | 幂等，返回缓存的 summary |

- ⇒ DRAFT **不为/被重生成**（财务手工补录（摇机损耗/费用抵扣/实盘修正）受保护）。
- ⇒ 任务会向**全部**已于 ACTIVE 的库仓建表；已有单则计数为「跳过」。
- 收口后再次触发（run 4）⇒ 同样 `生成 0 仓，已有单跳过 3 仓`，基线不变。

## 七、收口（基线精确复原，实读）

删除：23 条 `warehouse_movement`（`movement_id 19..41`，恰好是造数区间，前后留下 `max_movement_id=18` 与基线一致）+ 1 张生成的表单（`close_id=4`，其 4 行随之删除）。

| 项 | 造数/运行后 | 收口后 | 基线 |
|---|---|---|---|
| `warehouse_movement` 全表 | 41 | **18** | 18 |
| `warehouse_monthly_close` 全表 | 4 | **3** | 3 |
| `warehouse_monthly_close_line` 全表 | 4 | **0** | 0 |
| `warehouse_monthly_close` 表单（ID 1/2/3 + 新增 4） | 1/2/3/4 | **1/2/3** | 1/2/3 |
| `ref_type IN ('AUDIT-R14','AUDIT-R14B','GUEST_WATER')` 残留 | — | **0** | 0 |

**全部精确复原，0 残留。** 收口后复跑计算与任务 ⇒ `n=0`、「已有单跳过 3 仓」，与收口前的断言一致。

## 八、本轮未覆盖（诚实）

- **`outboundId` 与上柜量的耦合未覆盖**：本轮未构造真实出库单（走 `warehouse_outbound` 与 `warehouseAdminService.ship` 写入的流水行）；`SKU-MILK` 的上柜/取消用 `AUDIT-R14/14B` 的独立 `OUTBOUND_SHIP`/`OUTBOUND_CANCEL` 行模拟同款口径。真实出库单仍走 `warehouse_outbound` 表与 `ship` 路径；本轮未验证「出库行与 restock 明细合计」的关联归属。
- **`restockDetail` 明细合计口径未覆盖**：本轮未调用 `GET /api/v2/ops/admin/warehouse/monthly-close/restock-detail`；其口径见 `WarehouseMonthlyCloseService` 的 javadoc（明细合计应等于 `restockQty`）。
- **`settlement-bill` 未构造**（未注册、无手动端点）—— 需用户决定是否补。
- **`claimForPayout` 并发抢行**仍无活库可达路径（见 R7 §10.7）。
- 弱网 / 小程序四闸门人工复验（微信开发者工具）→ 需人档。
- 本轮未调用 `POST /warehouse/close-sheets/{closeId}/approve`（审批锁单）与 `disposeLine`（CLAIM 挂账）—— 因缺该能力所需的上游依赖。

## 九、结论

- 分仓月结账期窗口的**半开区间归属**（`start−1s → 期初`、`start → 当期`、`end → 丢弃`、两侧各 1s 的 off-by-one 边界）**在两个实账期（2026-10 / 2025-08）上均实测命中，无 off-by-one**。
- `restockQty` 的钳制**在全部 movement 应用后**进行 ⇒ 实测 `−10 + 12 + (−3) = 1`（`expected = −1`），与源码一致；审计批次4 的修复**真实生效**。
- 客情用水经 `guestWaterQty` 分流，**未混入上柜列**；上柜列无负向控制可删。
- 表单生成的**写态端点真实可用**，行值与只读计算端点一致（同一 `closeSheet` 实现）；收口后 `close_id=4` 及其 4 行被切干净。
- **任务幂等**：注册任务可触发、DRAFT 存在时不重生成。
- **0 新缺陷，0 资金副作用**：读态端点为纯计算、写态端点为真实表单生成；两者都**不经过 payout/settlement 侧动作**，本队列未产生任何倍率支付（造数行无农户分账、无钱包扣款）。
- R13 §九 中「`warehouse-monthly-close` 未构造」的遗留已按用户拍板节奏补齐。

## 十、CI 门禁验证（C1 · 真实 GitHub Actions，非本地预检）

本地 preflight 通过不等于 CI 通过。本轮对**刚推送的 amended SHA** 在**真实 GitHub Actions** 上跑完并回读：

- 推送 SHA：`20ffcb7ba35b1eba0f353a23a5a14e019834cfbf`（`git rev-parse HEAD` 与 `git rev-parse origin/dev` **同值**）
- Workflow run：`42463931974`（number = **1297**，`event=push`，
  `createdAt=2026-11-08T08:40:35Z`，`updatedAt=2026-11-08T09:01:17Z`，`headSha=20ffcb7b`）

| Job | status | conclusion |
|---|---|---|
| build | completed | **success** |
| **admin-artifacts** | completed | **success** |
| e2e-h5 | completed | **success** |
| mini-programs | completed | **success** |
| **integration** | completed | **success** |

- ⇒ **5/5 全绿，run 整体 `conclusion=success`**；其中 `admin-artifacts` 正是「产物逐字节比对」门禁，`integration` 亦完成。
- 证据源：`gh run view 42463931974 --repo chanwenxiang/ai-cabient --json jobs`、`--json <…>`（A 级，真实读取 GH 返回；`jobs` 直接解析 `conclusion`）。
- **与 R13 §十一 的关系**：R13 的 §十一 记录的是**被 amend 掉的旧 SHA `58d570a5…`**（run `42461333249`，5/5 success）；本次 §十 记录的是**当前已推送的 `20ffcb7b…`**（run `42463931974`，5/5 success）。两者同为「真实 CI 绿」，§十 说明**被 amend 的 commit 重新推送后 CI 复绿**（push 到 `origin/dev`、fast-forward）。
- 🔴 **本轮再核验（诚实标注，2026-11-08 收口）**：写下 §十 时 `gh run view <run-id>` 与 jobs 端点对本仓库偶发 404（`{"message":"Not Found"}`；本 token 对**单 run 读/job 级**受限），而 `gh run list` 正常。据此分两层写死：
  ① 上表**5 个 job 名与 `success`** 系 §十 写下时**当日真实读取**所得（**单源**）；本轮回读该 run 的 job 端点 404，故**不再重复逐 job 主张**。
  ② **run 级**结论用可用接口 `gh run list --limit 3 --json number,headSha,status,conclusion,updatedAt` 多次回读：
  - `1297 / headSha=20ffcb7ba35b1eba0f353a23a5a14e019834cfbf / completed / success`（`updatedAt=2026-11-08T09:01:17Z`）
  - `1298 / headSha=2be58f39702e13ff287b661230075fc211f9af5f / completed / success`（`updatedAt=2026-11-08T10:35:28Z`）
  - `1296 / headSha=58d570a5ed068bebbbdaf02b337e9fca5e8212bb / completed / success`（`updatedAt=2026-11-08T08:23:46Z`）
  ③ `1298` 正是**本文终稿（docs-only commit `2be58f39`）**自己的 run ⇒ 本轮交付物本身在真实 GitHub Actions 上绿；`1297` 覆盖同一源码树状态（本仓本轮只改 docs）。两者 run 级均为 `success`，但**均为 docs-only** ⇒ 主要证明「文档增补不破坏门禁」，源码级产物比对仍未在「含产品改动」的提交上复验。
- 🐚 **踩坑（已记 lessons）**：仓库 slug **从 `git remote get-url origin` 程序化派生**，**绝不手写**——本轮因手写 `ai-cabent`/`ai-cabiert` 等错拼导致数次 404，且控制台换行会把 URL 视觉拆断（`chanwenxiang/` / `ai-cabient/…`）而看起来像不同的 slug。`gh run list` 无 `--repo` 时按当前目录 origin 解析，故可作交叉校验。
