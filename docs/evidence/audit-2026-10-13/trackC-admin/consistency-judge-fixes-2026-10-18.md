# 数据一致性判据修复轮 · 两条假阳性 + 三域泛化 + 流水链（2026-10-18）

承接 [`wallet-unify-runtime-regression-2026-10-18.md`](./wallet-unify-runtime-regression-2026-10-18.md) §4 登记的
两条**既有**巡检口径缺口。用户拍板：**两条都修**，并把两条候选判据（三域钱包泛化 / 流水链连续性）并入同一轮，
一次负向先行、一次隔离栈实测。

栈仍是隔离 project `aicabinet-uat`（宿主 28080 gateway / 25433 postgres / 26379 redis），未碰共享栈。

## 1. 改了什么（四块，都在钱附近）

| 块 | 落点 | 说明 |
|---|---|---|
| 净额口径单一事实源 | `PaymentOperationTypes`（`INBOUND_TYPES_SQL_LIST` = `CHARGE/ADJUST_CHARGE/PREAUTH_CAPTURE`）+ `DataConsistencyService` 4 处 SQL 改调 `netCaseSql()/netTypesFilterSql()` | 修 4×`PAYMENT_AMOUNT` 假阳性：余额预付单收款只写 `PREAUTH_CAPTURE`，巡检侧清单停在 `CHARGE/ADJUST_CHARGE` ⇒ 期望 486 / 实际 0。F-10 只修了读路径，巡检是它没跟着修的副本之一 |
| 退货退款守卫 | `checkCrossLinkRefundInventory()` 加 `AND o.inventory_deducted = TRUE` | 修 2×`CROSS_LINK/RFINV` 假阳性：修法 B 的未扣库存退货压根没扣过库存 ⇒ 没有回架流水才是正确形态 |
| 三域钱包判据泛化 | 删 6 个逐域常量，改 `WALLET_DOMAINS=[MERCHANT,LINE,STAFF]` + 后缀拼 `checkType`；`applyFix` 与前端 `ConsistencyView.vue:isWalletCheckType()` 一律**按后缀识别** | 补上 `DataConsistencyService` **从来没有的 STAFF 钱包判据**（统一前也没有，非统一引入）。按后缀而非逐个枚举 ⇒ 再新增域不会漏登记 |
| 流水链连续性 | 新增 `WALLET_LEDGER_CHAIN`：`LAG(balance_after) OVER (PARTITION BY domain, owner_id ORDER BY created_at, ledger_id)`，备忘行 `effect_cents` 记 0，跳过每 owner 首行 | 闭合「合计与最近流水都自洽、中间某行被改写过」的盲区 |

标签面三处同步 + 一条幂等迁移 `V390__register_wallet_consistency_check_types.sql`
（`STAFF_WALLET` / 三个 `*_WALLET_LEDGER_SUM` / `WALLET_LEDGER_CHAIN`），`SysDictBootstrap` 与 `packages/shared-dict` 同步并重建 `dist/`。

门禁：新建 `scripts/check-payment-net-types-single-source.mjs`（禁任何含 `CHARGE+ADJUST_CHARGE+REFUND` 却缺
`PREAUTH_CAPTURE` 的手写清单；自身 fixture 走 `SELF` 排除，两处**应当**排除的渠道现金口径显式登记豁免与理由），
并入 `check:audit-gates`（聚合 **47** 条）；新 IT 类登记进 `check-docker-tests-guarded`。

> ⚠️ 一个实现期的坑：`WALLET_LEDGER_CHAIN` 是**三域共用一个 checkType**，而
> `resolveStaleFailuresIfComplete(checkType, …)` 会把该类型下本轮没检出的 FAIL 自动置 FIXED
> ⇒ 它必须**一次全域跑完**，不能放进 per-domain 循环，否则 A 域的记录会被 B 域的巡检抹掉。代码注释已钉住。

## 2. 先红再绿：用例

- `DataConsistencyJudgeSqlIT`（Testcontainers 真 Postgres，定向实跑 `mvn -Dtest=… test`）**9/9 绿**。
  其中两条是为链判据专门写的负向证明：
  播「Σ 与最近余额都自洽、只有中间行被改写」的账 ⇒ 前两条判据必须静默、链判据必须报警；
  再播一笔**真实提现形态**（`SETTLEMENT` / `WITHDRAW_FREEZE` 备忘 / `WITHDRAW_PAID` / `WITHDRAW_FEE`）⇒ 链判据必须静默。
  该类已登记进 `check-docker-tests-guarded` 的 GUARDED 表（`*IT.java` ⇒ failsafe/integration job），不会静默漏跑。
- `DataConsistencyServiceTest` **41/41 绿**，含 `runConsistencyCheck_coversAllThreeWalletDomains`（三域都被跑到）、
  链判据 SQL 形态断言（`LAG … PARTITION BY l.domain, l.owner_id` / `THEN 0 ELSE l.amount_cents END AS effect_cents` /
  `prev_balance IS NOT NULL`，且**断言 SQL 里没有 `WHERE l.domain`**——防止有人再把它按域拆开）。
- 聚合门禁 **47** 条全绿。新门禁 `check:payment-net-types-single-source` 另做过一次「植入过期三联清单 ⇒ 确能为红、
  还原后复绿」的人工自校验（它的 `selfCheck()` fixture 也在断言范围内，故用 `SELF` 排除自身）。
- trade-service 全量单测收尾重跑：**Tests run: 2034, Failures: 0, Errors: 0**（`mvn -pl services/trade-service -am test` BUILD SUCCESS）。

## 3. 隔离栈实跑：假阳性清零

就地升级后（V389）本栈钱包面只有 1 个 `MERCHANT` 账户、LINE/STAFF 0 行。重启到新镜像时 Flyway 留痕：

```
Migrating schema "public" to version "390 - register wallet consistency check types"
Successfully applied 1 migration ... now at version v390
```

`POST /api/v2/ops/admin/consistency/run`：

| | failCount | 明细 |
|---|---|---|
| 修复前（上一轮实测） | **8** | 4×`PAYMENT_AMOUNT` + 2×`CROSS_LINK/RFINV` + 1×`CROSS_LINK/DSP` + 1×`POINTS_IDENTITY` |
| 修复后（本轮） | **2** | `POINTS_IDENTITY\|1` 181 vs 149；`CROSS_LINK\|DSP\|1792292735796763657989` |

6 条过期 FAIL 不是被删掉，而是被 `resolveStaleFailuresIfComplete` 正常判为已消失 ⇒ 落库状态：
`PAYMENT_AMOUNT` 4 行全 **FIXED**，`CROSS_LINK` 2 行 **FIXED**、剩 1 行仍 FAIL（争议工单那条是 F2 驱动器留下的测试数据，不是判据缺陷）。

⇒ 运营台「数据一致性」页在真实数据上从 **6/8 是噪音** 收敛到 **0/2 是噪音**，剩下两条都是真待办
（积分恒等式待查口径、争议未结案待处理）。

## 4. STAFF + 链判据的四段负向证明（教训 #325：静默 ≠ 有效）

探针 `domain=STAFF, owner_id=UAT-STAFF-PROBE`，备注 `UAT-RECON-PROBE-STAFF`；账形与真实提现一致
（`COMMISSION +500→500`、`COMMISSION +300→800`、`WITHDRAW_FREEZE −800→800` 冻结 800，账户 800/冻结 800）。

| 段 | 数据 | failCount | 结果 |
|---|---|---|---|
| A 播一致账 | 同上 | **2** | 三条钱包判据全静默。备忘行剔除口径生效（朴素 Σ 会算成 0≠800 恒假阳性）⇒ **STAFF 静默是真绿，不是空转** |
| B 只改中间行 | 行 49 `balance_after` 800 → 700（账户与末行不动） | **4** | 新增 **2 条 `WALLET_LEDGER_CHAIN`**（`STAFF\|49` 应 800 实 700、`STAFF\|50` 应 700 实 800）；`STAFF_WALLET` 与 `STAFF_WALLET_LEDGER_SUM` **仍静默** ⇒ 链判据抓到的正是另两条看不见的盲区 |
| C 只改账户余额 | 还原行 49，`balance_cents` 800 → 1000 | **4** | 新增 `STAFF_WALLET` + `STAFF_WALLET_LEDGER_SUM`（各 1000 vs 800）；链判据回到静默 ⇒ STAFF 域两条判据**能红** |
| D 清理探针 | 删 3 流水 + 1 账户 | **2** | 本轮 4 条 + 上一轮 2 条 LINE，共 6 条全部自动 **FIXED**；`wallet_account` 里 STAFF 归 0，无残留 |

⇒ 三条新/泛化判据在真 Postgres + 真数据上**能红能绿能自愈**。

## 5. 运营台 UI 实证（本轮改了前端一处分类）

浏览器（http://localhost:28080/admin/consistency）走完「播账 → 点『立即巡检』→ 看表 → 清理 → 再点」：

- 类型列渲染出 V390 的中文标签：**钱包流水链断裂** / **补货员钱包** / **补货员钱包流水合计**（说明迁移 + 字典 + 前端标签面三段都通）。
- 4 条钱包行级别一律「**高**」（`isWalletCheckType()` 生效）。
- 操作列一律是**禁用**态的「需人工：该类仅巡检记录，需人工核对处理」——`applyFix` 的按后缀识别在 UI 上兑现；
  此前 `*_WALLET_LEDGER_SUM` 从 P3-1a 起就在写库，前端却不认识它。
- 「未通过」计数 6 → 清理后回到 2，与 API 侧 `failCount` 一致。

UI 这一趟是把 B、C 两段**同时**播上（重新插 3 行流水 → 行 52 `balance_after` 写成 700 + 账户余额 1000），
于是一张表里同时出现 2 条 `WALLET_LEDGER_CHAIN`（`STAFF|52`/`STAFF|53`）+ `STAFF_WALLET` + `STAFF_WALLET_LEDGER_SUM`
⇒ 「钱包类判据的三条同时在页上能红」是一次点击挣到的，不是三次。

收尾落库核对（`data_consistency_record` 里 `%WALLET%` 共 10 行，全部 **FIXED**、0 行 FAIL）。顺带看出一条**既有口径事实**：
同一个 `(check_type, check_key)` 在被再次检出时会**再插一行**（`STAFF_WALLET|UAT-STAFF-PROBE` 现有 2 行 FIXED），
即记录表按「episode」累积、不按 key 去重 ⇒ 判据的自愈断言只能按 key 判存在，不能按行数判「清了几条」。
本轮未改这个行为（页面上只列 FAIL，重复行不影响运营读数），仅登记。

## 6. 顺带查出的一条**隔离栈环境**缺口（与本轮改动无关，待拍板）

浏览器登录后台一直 **403**，响应体 `Invalid CORS request`（curl 同源不带 `Origin` 则 200，所以纯 API 驱动器测不出来）。
定位：`docker-compose.full.yml:179` 是 `CORS_ORIGIN: ${CORS_ORIGIN:-http://localhost}`，而隔离栈把 gateway 重映射到宿主
**28080** ⇒ 容器里 `CORS_ORIGIN=http://localhost` 与浏览器 `Origin: http://localhost:28080` 不相等，
Spring CORS 处理直接拒。共享栈用 8080 且浏览器同源访问，所以从未暴露。

本轮只在本机用 `CORS_ORIGIN=http://localhost:28080 docker compose … up -d trade-service` 绕过（**未改仓库、未改 .env**）。
要不要把它钉进 `docker-compose.uat-isolated.yml`（隔离栈的 admin UI 才开箱可用），属环境口径 ⇒ 只登记，等拍板。

## 7. 诚实边界

- 4×`PAYMENT_AMOUNT` 清零是在**本栈这 4 笔真实余额预付单**上证明的；口径本身由单一事实源 + grep 门禁钉住，
  但「生产上还有别的收款类型被漏」这类问题只能由门禁防新增、不能追认历史。
- STAFF/LINE/链三判据的运行期证据靠**探针**，不是真实业务写入（本栈无补货员资金流水）。
  链判据的账形取自 F4 实测出的真实提现四行，形态可信，仍不等于生产数据分布。
- 剩 2 条 failCount **不是本轮已修对象**：`POINTS_IDENTITY` 差 32 分**未查到底**（表定位/键口径待确认，本轮只查不改）；
  争议那条是测试残留。`paid_amount_cents` vs `payment_operation` 净额对账按约定**另起一轮**。
- F1「结算绿」仍等 2026-10-19 真将邑柜；本轮没碰 edge。
