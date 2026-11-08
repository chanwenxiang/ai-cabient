# R13 · 账期切换边界 · 真库造数 + 真实日结执行（UAT，2026-11-08）

> 本轮把 D1（账期切换窗口的**真实造数** + **真实注册任务执行**）跑成**真库闭环**。
> 目标：验证日结任务对「半开账期窗口 `[from, to)`」的归属语义，并验证任务的幂等性与收口复原。
> 造数**全部**落在 UAT `aicabinet_uat`，`AUDIT-D1-` 前缀，收口后回退到基线、0 残留（§七有实读核对）。
> **证据分级**：A = agent 读到并回读验证；B = 脚本真实跑但输出未落盘；C = 仅对话/文档。
> 本文全部条目为 **A**（真库回读 + 源码 grounding）。

## 一、被测面

- 业务：商户日收入结算通知（落库 `notification_log.template_code='merchant_settlement'`、`biz_type='DAILY_INCOME'`），由 `MerchantDailyIncomeNotifyScheduler` 定时执行。
- 端点（注册任务，带真实操作 token）：
  `POST /api/v2/ops/admin/scheduled-tasks/merchant-daily-income-notify/run`
  - 此 taskKey 在 `ScheduledTaskRegistry.java:162` **已注册**（runnable），可由运维接口手动触发。
- 会话：**真实 login 取 token**（非伪造）：超管 `13900000001 / 123456` → `operator_id=100000001`。
  登录必须带**图形验证码**（`captchaId` + `captchaCode`），否则 400 `请输入图形验证码`。

## 二、grounding（源码，先证窗口语义再跑）

| 项 | 事实 | 位置 |
|---|---|---|
| 账期窗口（半开） | `from = yesterday.atStartOfDay(Asia/Shanghai)`；`to = yesterday.plusDays(1).atStartOfDay(...)`；`[from, to)` | `MerchantDailyIncomeNotifyScheduler.java:135-136` |
| 归集口径 | 按 `order_revenue_split` 的**创建日**分组归集 `merchant_cents`；`VOIDED/REVERSED` **不入账** | `:33`（javadoc 口径）；隔离实现 `:424`（`isReversed` 跳过） |
| 空商户跳过 | 归集额 **≤ 0** 的商户整笔跳过、不发通知 | `:429`（`removeIf(v -> v <= 0)`） |
| 费用侧早退 | `dailyFee <= 0` 时费率分支**早退**（不写钱包） | `:356-361` |
| 金额行 | 明细分 `platform / merchant / gross / fee / net` | `:381+` |
| 任务注册 | `merchant-daily-income-notify` 已注册 runnable | `ScheduledTaskRegistry.java:162` |

**任务可达性边界（本轮不做，诚实列出）**：
- `settlement-bill` **未注册**（仅 `@Scheduled(cron="0 40 2 * * *")`）→ 无运维手动触发端点，**本轮不造数**。
- `warehouse-monthly-close` **已注册**但读 `warehouse_inventory_movement`，**不读** `order_revenue_split` → 造数对其无意义，**本轮不造数**。

## 三、造数：3 张边界点（half-open）

今日 = `2026-11-08`（Asia/Shanghai），昨日 = `2026-11-07` ⇒ 窗口 = `[2026-11-06T16:00Z, 2026-11-07T16:00Z)`。

| split_id | order_id | created_at (UTC) | created_at (SH) | 窗口内？ | merchant_cents |
|---|---|---|---|---|---|
| `AUDIT-D1-S0` | `AUDIT-D1-ORD-S0` | 2026-11-06 15:59:59 | 2026-11-06 23:59:59 | **否（下边界外，早 1s）** | 1000 |
| `AUDIT-D1-S1` | `AUDIT-D1-ORD-S1` | 2026-11-07 15:59:59 | 2026-11-07 23:59:59 | **是（窗口内）** | 1000 |
| `AUDIT-D1-S2` | `AUDIT-D1-ORD-S2` | 2026-11-07 16:00:00 | 2026-11-08 00:00:00 | **否（上边界处，`< to` 不取）** | 1000 |

- 均写入 `cabinet_order`（`PAID`，`MCH-DEFAULT`）、`shopping_session`（`OPEN`）、`order_revenue_split`（`SUCCESS`）。
- 注入前后实读：`order_revenue_split` **3 行 / gross 1824** → **6 行 / gross 4824**（新增 3×1000）。

## 四、第一次真实运行（注册任务）

- 请求：上述端点 + Bearer token。
- 应答：`code=0`、`taskKey=merchant-daily-income-notify`、`result=TRIGGERED`、
  `message` = `日结(2026-11-07)日结通知发送 1 商户（耗时 68 ms）`。
- 落库：`notification_log id=106`，内容原样抄录（A 级，真库回读）：

```
template_code   : merchant_settlement
biz_type        : DAILY_INCOME
biz_id          : 2026-11-07
merchant_id     : MCH-DEFAULT
title           : 收入结算通知
body            : 商户名称：默认商户
                  结算周期：2026年11月7日
                  收入金额：10.00 元
                  平台费用：0.00 元
                  净入账：10.00 元
```

- **钱包/费用侧零副作用（实读）**：
  - `wallet_account.MCH-DEFAULT` = `balance_cents 209900 / frozen 0`（**与基线一致**）
  - `wallet_ledger` = `26`（**与基线一致**，无新增）
  - `merchant_fee_ledger` = `0`（**本商户 `data_fee_cents`/`rent_fee_cents` 均 NULL** ⇒ `dailyFee <= 0` 早退）

## 五、窗口归属核对（真实业务输出反推）

真实日结只归集到 **¥10.00（= 1000 分）**，且恰好落在 `biz_id 2026-11-07`。
- 反事实核对：本记账周期下「昨日自然日」恰好等于半开窗口本身（今日=11-08），故**不能用自然日截断当反例**；真正的反例是——
  ① 若误用**闭区间** `[from,to]`，`S2`（恰在 `to`）会被误计 ⇒ 金额会算成 **2000**；
  ② 若窗口起点算错一天（`from` 早/晚一天），`S1` 会掉出 ⇒ 金额会算成 **0**。
- 实测金额恰为 **1000**（只有 `S1`），`S0`、`S2` **未入账** ⇒ 窗口按 **半开 `[from, to)`** 命中，与源码 `:135-136` 一致。**PASS / 0 FAIL（本轮注入行）**。

> 负控制：`S0`（早 1s）与 `S2`（恰在 `to`）为**负控制**，两者均未被归集，边界两侧各 1s 即切换，**无 off-by-one**。

## 六、幂等性（连续 3 次再触发）

对已在昨日（`2026-11-07`）发过通知的商户，连续再触发 3 次，实读应答：

| 次序 | result | message |
|---|---|---|
| run2 | `TRIGGERED` | `日结(2026-11-07)日结通知发送 0 商户（耗时 9 ms）` |
| run3 | `TRIGGERED` | `日结(2026-11-07)日结通知发送 0 商户（耗时 5 ms）` |
| run4 | `TRIGGERED` | `日结(2026-11-07)日结通知发送 0 商户（耗时 12 ms）` |

- ⇒ 同一业务日**不重复发通知**（`notification_log` `biz_id=2026-11-07` 仍恰为 **1**）。**幂等 PASS**。

## 七、收口（基线精确复原，实读）

删除：3 张 `order_revenue_split` + 3 张 `cabinet_order` + 3 张 `shopping_session` + 1 条由造数派生的假收入通知（`id=106`，`biz_id=2026-11-07`）。

| 项 | 造数/运行后 | 收口后 | 基线 |
|---|---|---|---|
| `order_revenue_split` | 6 / gross 4824 | **3 / 1824** | 3 / 1824 |
| `notification_log` | 106 | **105** | 105 |
| `notification_log` `biz_id=2026-11-07` | 1 | **0** | 0 |
| `notification_log` `biz_id=2026-11-06` | 1 | **1** | 1 |
| `wallet_account.MCH-DEFAULT` | 209900 / 0 | **209900 / 0** | 一致 |
| `wallet_ledger` | 26 | **26** | 26 |
| `merchant_fee_ledger` | 0 | **0** | 0 |
| `AUDIT-D1-%` 残留 | — | **0** | 0 |

**全部精确复原，0 残留。**

## 八、脚手架失败（非产品，诚实记录）

1. `cabinet_order` **`user_id` NOT NULL**（原 SQL 未给该列）。已补 `10002`。
2. `shopping_session` **`state` NOT NULL**（不显式给会失败）。已补 `OPEN`。
3. 首查尝试直接 `INSERT order_revenue_split` → FK `order_revenue_split_order_id_fkey`（须先有 `cabinet_order.order_id`）。
4. 直接 `INSERT cabinet_order` 缺 `session_id` → FK `cabinet_order_session_id_fkey`（须先有 `shopping_session.session_id`）。
5. 复用既有 `REFUNDED` 订单号会撞 `uk_order_revenue_split_order_id` → 改 `AUDIT-D1-ORD-*` 新号。
6. 登录先用**手工复制粘贴的 JWT** → 因过期/编码失真触发 **401**；改为脚本内**现场取验证码 + 现场登录获 token** 后恢复正常。
7. **Redis 口令**：容器内 `REDIS_PASSWORD=devredis`（`redis-cli -a 'devredis'`）；误用 `aicabinet` 会 `WRONGPASS`。
8. 脚本用 `"{...}" -f` 做 JSON 组装 → PowerShell 把 `{}` 当格式占位符 → 改用 `.Replace('~CID~',…)`。
9. `-replace "`r",""` 中双引号把 `$()` 子表达式引号截断 → 拆成变量。

**未改任何产品代码。**

## 九、本轮未覆盖（诚实）

- `settlement-bill` 账期（月结/结算单）**未造数**（未注册、无手动端点）—— 需用户决定是否补。
- `warehouse-monthly-close` **未造数**（读库存流水，与拆分表无关）。
- `claimForPayout` 并发抢行仍无活库可达路径（见 R7 §10.7）。
- 弱网 / 小程序四闸门人工复验（微信开发者工具）→ 需人档。

## 十、结论

- D1 的「账期切换窗口真实造数 + 真实日结执行 + 幂等 + 收口」**全部闭环**。
- 半开窗口 `[from,to)` 归属语义**实测命中**，边界两侧各 1s 无 off-by-one；负控制有效。
- 任务幂等：**同业务日重复触发不重复发通知**。
- **0 新缺陷**，0 起产品代码改动，0 资金侧副作用（费用因配置为 NULL 而早退，已在 §四 明确为该**单一商户 MCH-DEFAULT** 的 scoped 结论）。

## 十一、CI 门禁验证（C1 · 真实 GitHub Actions，非本地预检）

本地 preflight 通过不等于 CI 通过。本轮把刚推送的 SHA 在**真实 GitHub Actions** 上跑完并回读：

- 推送 SHA：`58d570a5ed068bebbbdaf02b337e9fca5e8212bb`
- Workflow run：`42461333249`（`CI`，event=`push`，`updatedAt=2026-11-08T08:23:46Z`）

| Job | status | conclusion |
|---|---|---|
| build | completed | **success** |
| e2e-h5 | completed | **success** |
| mini-programs | completed | **success** |
| **admin-artifacts** | completed | **success** |
| integration | completed | **success** |

- ⇒ **5/5 全绿，run 整体 `conclusion=success`**；其中 `admin-artifacts` 正是「产物逐字节比对」门禁（此前仅推送后才可在远端验）。
- 证据源：`gh run view 42461333249 --repo chanwenxiang/ai-cabient --json jobs`（A 级，真实读取 GH 返回）。
- ⚠️ **诚实两侧**：① 本轮只改 **docs**（`docs/uat/rounds/R13-*.md` + `docs/PROJECT_KNOWLEDGE.md`），未触碰任何产品源码，故 CI 绿**主要验证「文档增补不破坏门禁」**；② 更早的「`admin-artifacts` 真字节比对」若需证明**源码级产物一致**，仍需在有产品改动的提交上复验。
