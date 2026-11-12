# 交接：R15–R19 剩余 A 类轮次 + B 类（需要真机/人）

> **✅ 接手已核正（2026-11-08，对着活库 + 源码逐项实测，勿再照抄旧稿）**：
> 1. **阈值 = ¥500**（活库 `merchant.withdraw.review_threshold_cents = 50000` 分；代码种子注释原文「默认 ¥500」`SystemConfigService.java:897`；判定 `MerchantWithdrawService.java:512 amountCents >= 阈值`）。故 **A=¥100 < ¥500 自动 APPROVED、B=¥1000 ≥ ¥500 PENDING_REVIEW——脚本原判正确**。旧稿「阈值=500=¥5、A/B 都进审核」是把 §3.2 笔误 `500` 误当实测值（没查库），**作废**；§3.2 已改回 `50000` 与 A=¥100/B=¥1000。
> 2. `payee_account_type` 真值 = **`PAYEE_TYPE_COMPANY`**（实测 `merchant_withdraw_request.payee_account_type`，枚举 `PayoutConstants.PAYEE_TYPE_COMPANY`）；脚本断言已由 `COMPANY` 改对。
> 3. `merchant_notify_log` 恒 0 行 ⇒ 该行清理与残留断言空转，覆盖度实为「仅 notification_log」。
> 4. §3.4 流水恒等式旧措辞（"民俗余额""overlay ≡ appliance"）已按源码重写；脚本另修复了 `reqA/reqB`（request_id）被误当金额、`order[12]` 越界、`split/inst.length` 取列数等断言 bug，并补柜机库存 `device_sku_inventory` 与两张 movement 表的还原。

> **给接手的另一个 AI 的第一件事**：先读这份，再回读
> [`coverage-table.md`](coverage-table.md)（§4 是权威排序）+ [`PROJECT_KNOWLEDGE.md`](../PROJECT_KNOWLEDGE.md) §0–§4/§7/§9/§10/§12 + [`lessons-learned.md`](../engineering/lessons-learned.md) #426 ④。
> **不要相信任何自报 `[x]`/绿，一切以脚本 stdout + 库表回读为准。**

- 池：`aicabinet_uat`（Postgres）／靶端：trade-service `127.0.0.1:18080`
- 容器名：`ai-cabinet-postgres-1`（库 `aicabinet_uat`，用户 `aicabinet`）／`ai-cabinet-trade-service-1`
- 分支：`dev`，remote `git@github.com:chanwenxiang/ai-cabient.git`（⚠️ 拼写是 **ai-cabient**，不是手打）
- 打点默认：`node scripts/pre-push-ci-preflight.mjs` **exit 0 才能 `git push`**（已 alwaysApply）

---

## 1. 现在库里/仓库的准确状态（2026-11-08 交接时）

| 项 | 状态 | 证据 |
|---|---|---|
| 工作树 | **干净**（`git status --short` 空） | — |
| 最近提交 | `f5fd66bf` 覆盖总表（coverage-table.md） | `git log -1` |
| 推送 | `origin/dev == HEAD` | R14/总表两轮均已 push |
| A 类闭环 | 17 轮：R1–R14 + A4 | `coverage-table.md §1` |
| CI | run **1297** `20ffcb7b`、run **1298** `2be58f39` 均 `completed/success` | `R14 §十`、`PROJECT_KNOWLEDGE §12` |
| R15 | ❌ **驱动脚本已写好但从未执行** | `.tmp/r15-chain.mjs`（`node --check` 通过） |
| R16–R19 | ❌ 未开始 | — |

---

## 2. 硬约束（用户拍板，不可协商）

1. **禁用 `subagent` / `subagent_fork` / `workflow`**。用户原话「别使用子代理」。全部手工做。
2. **`settlement-bill`（商户月度结算单）不造假数据**。已知其金额列恒空属设计（`order_revenue_split` 实库 3 行全 `VOIDED`，被 `isReversed` 排除），**如实登记为不可测**，不编跑通。
3. **`BarcodeLookupClient` 不许弱化**；不单方面改业务语义 / grace / 阈值。
4. **不明白就停、问用户**。禁止猜、禁止编。
5. **Approvals 被禁用** ⇒ **永远不要传 `sandbox_permissions`**；policy `danger-full-access`；cwd `D:\ai-generated code\ai-cabinet`。
6. **UAT 是活的共享库**。每个写操作必须配**精确清理 + 回读 0 残留**；不干净注销比脏数据更糟。
7. 一轮被判断「造不出数据」时，**如实报不可测**。
8. **用户中途说「咋了？做完了」是对你自夸的质疑** —— 不夸大进度，跑一步报一步。

---

## 3. R15 —— 下一步，一条命令即可跑

### 3.1 现在就可以跑的关系

```powershell
# cwd: D:\ai-generated code\ai-cabinet
node .tmp/r15-chain.mjs
```

它做：`commercial-flow` 跑通真订单 → 分账 → 钱包入账 → 提现 A(¥100 自动审批→payout) → 提现 B(¥1000 走 `/review` approve→payout) → 负控制(¥1/¥10000上限/余额不足/幂等重放/同号异额冲突) → 精确收口还原基线 + 0 残留断言。

退出码：`0` = 全 PASS，`3` = 有 FAIL。**必须拿到 `__R15_JSON__` 行 + PASS/FAIL 清单。**

### 3.2 脚本当前依赖的「需从首份真实响应确认」的假设

> ⚠️ 这些是从源码读出来的推断值，**首跑必须先看真实响应再决定断言**。若真实值不同，**按真实值改断言/记不可测，不许改库迁就测试**。

| 假设 | 推断依据 | 风险 |
|---|---|---|
| `CommercialFlowRunResult` 字段 = `(deviceId, skuId, sessionId, orderId, routeId, ...)` + `steps[].(code,status,message)` | `CommercialFlowService.java` | 字段名可能不同，需首跑读 |
| `settle_after` = T+1 ⇒ `'2026-11-09'`（跑日 2026-11-08） | 日结窗口 T+1 | 若配置改了就不是；脚本已按字符串切片前 10 位比较 |
| split `status = LEDGER_ONLY`、`platform_cents = 0` | `platform_rate_bps = 0` | 见 R1 |
| 提现 A（¥100）< 阈值 → 自动通过且 MOCK 申请即打款 `PAID`；提现 B（¥1000）≥ 阈值 → `PENDING_REVIEW`，review approve 即自动 executePayout → `PAID` | UAT `merchant.withdraw.review_threshold_cents = 50000`（=¥500） | 阈值若被改会翻转 |
| 提现手续费 = `amount × 60 / 10000`（fee_bps=60=0.6%）：A=¥100⇒60 分(¥0.60)、B=¥1000⇒600 分(¥6) | `merchant.withdraw.fee_bps = 60, fee_cap_cents = 0` | 同上 |
| **T+1 门闸**：`available = balance − frozen − pendingSettle`，`pendingSettle` 用 `MERCHANT_WITHDRAW_SETTLE_UNLOCK_HOUR`（默认 0）| `MerchantWithdrawService.java:782-792, 875-897` | 这是 V308 收紧闸门，**对不对**值得记录下来 |
| 提现申请体 = `WithdrawApplyRequest(amountCents, requestNo, merchantId)` | `WithdrawApplyRequest.java:11-16` | 只有 `amountCents/requestNo/merchantId` 三字段 |
| 幂等键 `MW:<requestId>` | 同一文件中提到 | 需首跑验 |

### 3.3 失败时的排查顺序

1. `docker exec -i ai-cabinet-trade-service-1 curl -s http://localhost:18080/actuator/health` —— 不是 `ai-cabinet-trade-1`。
2. `AICABINET_MOCK_ENABLED` 必须为 `true`，否则 `POST /api/v2/ops/admin/commercial-flow/run` 直接 403 `…仅限演示编排…`（`CommercialFlowService.java:68-73`）。
3. 登录：`GET /api/v2/auth/captcha` → `captchaFromRedis(captchaId)` → `POST /api/v2/auth/admin-password-login {phoneNumber:13900000001, password:123456, captchaId, captchaCode}` → `data.token`。头：`Authorization: Bearer`、`Content-Type`、`X-Requested-With: XMLHttpRequest`。参考 `scripts/rebuild-demo-rbac-accounts.mjs` :10–56。
4. SQL 分发：**写 `.tmp/r15q.sql` 后 `docker exec -i … psql … -f <path>`**（`-f` 的路径由容器解析 ⇒ 必须用容器内可读路径）。
5. `information_schema.tables` 用 `.table_name`（不是 `.relname`）—— 本库已实测。

### 3.4 真值速查（已实测，别猜）

| 对象 | 真值 |
|---|---|
| 演示设备 | `EG307002410770`（演示智能柜，MCH-DEFAULT 离线前 ONLINE） |
| SKU | `SKU-WATER-001` |
| 演示供应商 | `SUP-DEMO-001` / 演示饮品供应商 / ACTIVE（已存在 ⇒ 幂等 no-op，**别删它**） |
| `cabinet_order` | 主键 `order_id **character varying**` |
| `order_revenue_split` | `split_id **character varying**`；`status` ∈ LEDGER_ONLY/SETTLED/ACCRUED/WECHAT_*；`settle_after` 是**纯日期字符串**（`'2026-11-08'`），`::date` 可用 |
| `wallet_ledger` | `entry_type, amount_cents, balance_after, **frozen_after**, owner_snapshot_name, ref_type, ref_id, remark, created_at` |
| ledger `ref_id` 语义 | 提现流水 ⇒ **提现 request_id（数字）**；分账流水 ⇒ **split_id（字符串）**；`SPLIT_PARTIAL_REVERSE` ⇒ `"<splitId>:g<amount>"` |
| `merchant_withdraw_request` | 有 `idem_key, payee_account_no_mask, request_no, fee_cents, channel_order_no, reviewer_id, review_remark` |
| `approval_instance` | `instance_id, def_id, biz_type, biz_id, status,…`；提现单据 `biz_type='MERCHANT_WITHDRAW'`，`biz_id` = request_id |
| `purchase_order` | `purchase_order_id bigint`, `ref_no character varying`；流链造 `ref_no LIKE 'FLOW-PO-FLOW-%'` |
| `replenishment_route/task`, `warehouse_outbound` | `route_id/task_id/outbound_id` **全是 bigint 雪花** ⇒ JSON 回读会丢精度 ⇒ **收口按 `id > 轮首 max` 跨度删除**，不要按金额/ID 硬删 |
| `wallet_account` | `domain, owner_id, balance_cents, frozen_cents` |
| 流水恒等式 | 冻结腿 `WITHDRAW_FREEZE`：balance 不变、frozen=+申请额；打款腿 `WITHDRAW_PAID`+`WITHDRAW_FEE`：balance−=申请额、frozen 归 0；`净额 + 费 = 申请额`；三腿合计 `freeze − paid − fee = 0` |
| `settlement-bill` 口径 | `isReversed` 排除一切 VOIDED ⇒ 恒空（**设计**），不可测 |

---

## 4. R16 —— `restockDetail` 求和一致性 + `outboundId` 命名耦合

- **已观测事实**：3 张 **DRAFT** 收料单，行数 0，但 `warehouse_movement` 已有 18 行 ⇒ 必须证 `明细列求和 = 单头汇总`、`outboundId` 与 `warehouse_outbound` 的耦合/命名清理证据。
- **起点**：复用 R3/R14 造数；接口清单走 `ReplenishmentService` → `restockDetail`。
- **口径**：每张单先看清「行数 vs movement 行数」为何不一致，**记录差异而非遮掉**。

---

## 5. R17 —— 月结写态 `approve` / `disposeLine` / `updateLine`

- 本轮只读。要转写态需先确认**财务状态机的数据基线**（哪些月结单是 DRAFT、`approval_def_id` 是否启用、双签节点是否存在）。
- 至少覆盖：`approve`（过审）→ `disposeLine`（处置行）→ `updateLine`（改行）三条写接口的真实副作用 + 任一一步失败后的回滚/留痕。
- ⚠️ 未确认基线前不要动写接口。

---

## 6. R18 —— 跨账期窗口写出后被外部流水改动（drift 表）

- 复用 R14 的 `drift` 端点；目标：构造「窗口写出后又被外部流水改写」的数据形状，看 drift 报表是否能正确识别。
- 半开窗口归属口径见 `R13-settlement-period-boundary.md`。

---

## 7. R19 —— `settlement-bill` 无触发入口（只出方案）

- 只写**方案设计文档**（含现状、触发入口缺失真因、可选触发路径、风险面），**不产品改动、不造数**。
- 与 §2.2 用户拍板口径一致。

---

## 8. B 类 —— 需要**你/真机**，AI 替代不了

| 项 | 状态 | 怎么做 |
|---|---|---|
| 小程序四闸门：登录 / 扫码开门 / 支付分 / 弱网 | 🔴 未做（R1 已打门等） | `FULL_TEST_SESSION_PROMPT.md` + `cleanup-test-data.ps1` + `mp-seed-gate.ps1 -CleanupFirst`；**S0+S1 绿才进 S2** |
| 弱网 / 重签 URL UI | 🔴 未做 | DevTools AUTOMATOR（脚本化真点真拍） |
| 小程序三端 backlog | 部分（P0#1 充值 DONE，其余 PARTIAL/PENDING） | `MINIPROGRAM_BACKLOG.md` §1（12 行） |
| 浏览器后台 UI 回刷新 | ⚠️ 历史批 2026-09-26/27 做过，A 类改动后**未逐模块重拍** | `docs/uat/<MODULE>_FULL_BROWSER_UAT.md`（约 70 份） |
| 真机支付分生产 API | 已留痕（生产未接 = 501） | 需真实生产通道 |
| 涉及商家小程序 builder | `MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md` 已备 | — |

> 纪律：禁止带脏会话/争议宣称主链 PASS；禁止未列场景就扩「全业务大套」。

---

## 9. 交接后立刻该做的事（按你的优先级）

1. **先你/另一个 AI 跑 R15**（一条命令，见 §3.1）→ 拿基线 → 收口 → 回填 `docs/uat/rounds/R15-wallet-withdraw-chain.md`（含精确清理 SQL + 0 残留证据）→ `preflight` → push。
2. `PROJECT_KNOWLEDGE §12` 的 **R14 CI bullet 补 run `1298`（`2be58f39`）**（本次已确认 1298 是 R14 文档轮）。
3. R16 → R17 → R18 → R19（各 ~0.5–1 轮），顺序不换。
4. B 类四闸门 + 浏览器 UI 回刷新 → **你来做**。

---

## 10. 我已清理的现场

- 杀掉一个僵死的 `consumer-mp` dev watcher（日志文件已消失、无任何监听、空产出）：`pwsh-5550e1f793ef4d55`。
- 除 `.tmp/r15-chain.mjs`（未跟踪）外**未改动任何已跟踪文件**，工作树保持干净。
