# 轮次 R5 · 退款 / 争议 / 钱包

> 归档 2026-11-08。UAT 库 `aicabinet_uat`；后台写路径 target = trade-service `127.0.0.1:18080`。
> **本文件是「归档重建」**：轮次定义取自 [`UAT_TEST_PLAN_V3.md`](../UAT_TEST_PLAN_V3.md) §1 的 R5 行与
> [`UAT_DATA_TEST_PLAN.md`](../../UAT_DATA_TEST_PLAN.md) §2 的 R5 行；本轮只做「文件读核 + 范围对账」，
> **未重跑任何业务写路径**。因此下面刻意把「本轮亲核（A）」「脚本真、输出没入库（B）」「只有会话留痕/文档声明（C）」
> 「要用户侧人工才能补（D）」分档标注——**不许拿 C 冒充 RUN**。

## 1 · 范围（计划原文）

| 要点 | 定义（轮次总览） | 展开（数据测试计划 §2） |
|------|------------------|--------------------------|
| R5 域 | 退款 / 争议 / 钱包 | 部分退（预估==写侧）；争议四裁决；提现全状态机；余额退款审批；跨端同读 |
| 约束 | 后台 Playwright 真点；小程序微信开发者工具 automator 真点；API/SQL 只造数与断言 [`UAT_TEST_PLAN_V3.md §0`](../UAT_TEST_PLAN_V3.md) | 同左 |

## 2 · 本轮读到的产物（证据强度 A：亲自读文件核实）

| 路径 | 说的是什么 | 本轮核实到的内容 |
|------|------------|------------------|
| [`.tmp/uat-waive.json`](../../.tmp/uat-waive.json) | 争议免单裁决 **WAIVE** 的请求留痕 | `{"resolutionType":"WAIVE","reason":"UAT R5 免单裁决验证"}` |
| [`.tmp/close-dispute-round.mjs`](../../.tmp/close-dispute-round.mjs) | 真机轮「争议结案 CONFIRM」 | 针对工单 `1792658820060702642203` / 会话 `1792658173561478465217`；用内部 key + operator `13900000001`；只回显状态/金额/单号 |
| [`.tmp/b2-refund.cjs`](../../.tmp/b2-refund.cjs) | 消费端对**已付订单**发起 `POST /api/v2/orders/{id}/refund` | 订单 `1793498118481606316986`；断言 `cabinet_order.status + refunded_cents` 前后变化 + `payment_operation` 流水 |
| [`.tmp/b2-withdraw.cjs`](../../.tmp/b2-withdraw.cjs) | 商户端提现申请链路实跑 | `POST /api/v2/merchant/wallet/withdraw`（`amountCents=1000`, `BANK`/`R11审计`/`6222000000000000`）；前置 `GET /wallet`；**properties 探测先试 `phoneNumber` 再试 `phone`**（兼容真实渠道） |
| [`.tmp/dispute-detail.mjs`](../../.tmp/dispute-detail.mjs) | 争议详情 / 会话 DTO 读路径 | 工单 `1792577482516395587016`；同时拉 `EG943322621391` 的会话 DTO |
| [`.tmp/uat-resolve.json`](../../.tmp/uat-resolve.json) | 争议 CONFIRM 裁决载荷 | `resolutionType=CONFIRM`，`items=[{skuId: SKU-SNACK-001, qty:2}]`，reason 标注 `UAT-R1 simulator shopping confirm` —— **属 R1 踪影，不计入 R5** |

## 3 · 按要点对账

### 3.1 争议四裁决
| 裁决 | 实现证据 | 强度 |
|------|----------|------|
| CONFIRM | [`.tmp/close-dispute-round.mjs`](../../.tmp/close-dispute-round.mjs) | A |
| WAIVE | [`.tmp/uat-waive.json`](../../.tmp/uat-waive.json) | A |
| ADJUST | 只有 [`UAT-FINAL.md §四](../UAT-FINAL.md)「争议 ADJUST/KEEP 裁决 —— 同族 CONFIRM/WAIVE 已实测；单测 6 例覆盖」 | **C** |
| KEEP | 同上（同族） | **C** |

### 3.2 退款（全额 / 部分退 / 券单回退）
| 项 | 实现证据 | 强度 |
|-----|----------|------|
| 消费端已付单全额退 | [`.tmp/b2-refund.cjs`](../../.tmp/b2-refund.cjs)（断言 refunded_cents 回读） | A |
| 部分退 / 券订单回退 | 计划称「部分退 / ADJUST / KEEP 已由 E2E + 单测覆盖」 | **C** |
| 退款不重复出账 / 分账与佣金同步回退 | `UAT-FINAL.md §四·补 R8`（部分退线上复证 PARTIAL_REFUNDED 250/500 对平） | **C** |

### 3.3 提现状态机
- 申请路径：[`.tmp/b2-withdraw.cjs`](../../.tmp/b2-withdraw.cjs)（A）
- 阈值/边界 + 状态机：`docs/BUSINESS_FULL_TEST_MATRIX.md`（提现 `MERCHANT_WITHDRAW` 超阈值 经理→财务、节点未过不能打款，PASS）+ [`R9-scheduled-approval-config.md §MERCHANT_WITHDRAW`](R9-scheduled-approval-config.md)（申请→运营审→财务审→自动打款，全链 PAID）；
  配置键家族： [`R9b-config-118-alerts-34.md §二`](R9b-config-118-alerts-34.md)「R5 主库实证家族（12）：merchant/line.withdraw 的 fee_cents/fee_bps/fee_cap_cents/max_cents/daily_limit_cents」 | **C** |
- 佣金日结旁路：`UAT_FINAL.md §十一（b）` 的 `commission(true→false→true)` | **C** |

### 3.4 余额退款 / 审批 / 钱包
| 项 | 实现证据 | 强度 |
|-----|----------|------|
| 余额退款详情页（五 Tab / 写入操作空态） | 计划 §R5 指向各财务小点注 | **B/C** |
| 余额退款审批实例双节点 | `UAT_FINAL.md §五·窗口`：`MERCHANT_WALLET_ADJUST` 为双节点（运营→财务） | **C**（详见 [`R7-boundary.md §5b`](R7-boundary.md)） |
| 钱包写接口幂等（库级兜底） | [`docs/engineering/lessons-learned.md`](../../engineering/lessons-learned.md)（钱包流水幂等唯一索引 `uk_wallet_ledger_ref` 的坑与设计）+ [`docs/AUDIT_THREE_END_FULL_2026-10-18.md:164`](../../AUDIT_THREE_END_FULL_2026-10-18.md)（`WalletRefIdempotencySqlIT` 真库 5/5） | **C** |

### 3.5 跨端同读
消费端依赖当日 token 续期链，当日夜间 token 过期 ⇒ 计划记「消费端依赖项待白天」。本轮**未补**（D 级，需人工在微信开发者工具复验）。

## 4 · 本轮缺口与需复核（诚实表）

| # | 缺口 | 状态 | 影响 |
|---|------|------|------|
| 1 | ADJUST / KEEP 实测请求留痕缺失，只剩文档同族结论 | **C** | 若要「公证给付」级别证据，需补一轮；**不阻断**上线 |
| 2 | 余额退款全链产物在 `r6-balance-refunds.*` / `wallet-it-round*.log`，本轮未逐行读核 | **B** | 可在 R12 之后补读 |
| 3 | 部分退 / 券单回退仅 E2E+单测覆盖，未带完整脚本留痕 | **C** | 同族机制已实证，收敛 |
| 4 | 消费端下单→退款→退款到账跨端同读 | **D** | token 过期；需白天人工 |

## 5 · 结论

- **按 R5 轮次定义，已执行项覆盖**：WAIVE、CONFIRM 两条争议裁决（真机轮）、消费端已付单全额退、商户提现申请链路、两个争议读路径。
- **未重跑**（本轮只归档核对）。因此本文件不重写逐条 PASS/FAIL，**不以 C 冒充已实跑**。
- **新增阻塞项：0**（据 [`UAT-FINAL.md §三`](../UAT-FINAL.md)，上线结论仍为「可上线」附四个前置动作）。

## 6 · 产物清理
本轮**未执行清理**。「所有 `AUDIT-` 前缀造数已清理且归 0」是 [`UAT_TEST_PLAN_V3.md §6 收口标准`](../UAT_TEST_PLAN_V3.md)；本轮未改库、未造新数。
