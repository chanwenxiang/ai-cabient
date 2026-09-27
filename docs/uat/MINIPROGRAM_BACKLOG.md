# 小程序回补验证待办（Admin 深测后）

> **用途**：运营后台 FULL_BROWSER_UAT 已结案的模块里，哪些还欠 **mp-weixin** 对照；测小程序时按本表回补，勿用 H5 冒充 PASS。  
> **权威**：[`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §6–§7 · 铁律同 workbench FINDINGS。  
> **账号**：消费者 `13800138000` · 商户 `13800138001` · 演示柜 `330449777078`  
> **产物**：`clients/*/dist/dev/mp-weixin` + 微信开发者工具（automator 可选）  
> **更新**：2026-09-27（充值 P0#1 DONE；双端基础烟测见 [`mp-smoke/FINDINGS.md`](../uat-screenshots/2026-09-26/mp-smoke/FINDINGS.md)）

---

## 0. 状态图例

| 标记 | 含义 |
|------|------|
| **DONE** | 本轮或工作台已用 mp-weixin 取证 |
| **PENDING** | Admin 已深测；**回测小程序时必补** |
| **SKIP** | 纯后台只读/无 C·B 端动作；回测时注明即可，不强制开 mp |
| **PARTIAL** | 抽样做过，页级挂钩未闭环 |

回测完成后：把行改为 DONE，并在对应 `*/FINDINGS.md` 追加「MP 回补」一节 + Changelog 一行。

---

## 1. 优先回补（P0 · 有资金/任务挂钩）

| # | Admin 模块 | 册 / 深测文档 | 端 | 最低路径（对照 §7） | 状态 |
|---|------------|---------------|----|---------------------|------|
| 1 | 充值管理 `/recharges` | [`RECHARGES_FULL_BROWSER_UAT.md`](./RECHARGES_FULL_BROWSER_UAT.md) | 消费者 | 登录 → 余额/充值记录；与后台同 userId 金额一致（MP-C-06） | **DONE**（2026-09-27 · ¥193.00 ↔ Admin 19300 · 单 `1789658820257378119` · [`recharges/MP_BACKFILL.md`](../uat-screenshots/2026-09-26/recharges/MP_BACKFILL.md)） |
| 2 | 商户提现 `/merchant-withdraw` | [`MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md`](./MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md) | 商户 | 钱包余额/流水 ↔ 后台商户钱包（MP-M-03） | **PARTIAL**（烟测 L2：钱包 ¥29.15 + 流水；未对 Admin L3；未硬提现 · [`mp-smoke`](../uat-screenshots/2026-09-26/mp-smoke/FINDINGS.md)） |
| 3 | 商户与分账 `/merchants` | [`MERCHANTS_FULL_BROWSER_UAT.md`](./MERCHANTS_FULL_BROWSER_UAT.md) | 商户 | 分账/钱包 ↔ 后台分账差 ≤1 分（MP-M-03） | **PARTIAL**（烟测 L1：分账「失败」诚实空；钱包有分账入账流水；L3 待补） |
| 4 | 余额退款 `/balance-refunds` | FINANCE 册（单页深测待补完） | 消费者 | 余额明细无异常冻结刷屏；退款后余额变化可见 | **PENDING** |
| 5 | 用户余额 `/users` | FINANCE 册 | 消费者 | 同用户余额分→元与后台一致 | **PARTIAL**（烟测 L2：¥193.00 与 P0#1 Admin 19300 一致；专页回补仍待） |
| 6 | 补货调度 `/replenishment` | [`REPLENISHMENT_FULL_BROWSER_UAT.md`](./REPLENISHMENT_FULL_BROWSER_UAT.md) | 商户 | 补货/待办任务与后台规划同源（MP-M-02） | **PARTIAL**（烟测 L2：待办 0/已完成 3 · 柜 330449777078；未对 Admin；未 complete） |
| 7 | 订单 `/orders` | [`ORDERS_FULL_BROWSER_UAT.md`](./ORDERS_FULL_BROWSER_UAT.md) | 消费者 | 同 orderId 金额/状态（MP-C-02）；视频可选 MP-C-03 | **PARTIAL**（烟测 L2：`1790420215052990851425` ¥3.50 双端；视频 404 诚实失败；Admin L3 待补） |
| 8 | 争议 `/disputes` | [`DISPUTES_FULL_BROWSER_UAT.md`](./DISPUTES_FULL_BROWSER_UAT.md) | 消费者 | 申诉入口/状态回写（MP-C-04） | **PARTIAL**（烟测进账单审核页，未提交；状态回写未测） |
| 9 | 设备详情柜机码 | [`DEVICE_DETAIL_FULL_BROWSER_UAT.md`](./DEVICE_DETAIL_FULL_BROWSER_UAT.md) | 消费者 | 扫码/输柜机号开门（§6） | **PENDING**（首页扫码入口可见；本轮未硬开门） |
| 10 | 销售报表/落分账深链 | [`SALES_REPORTS_FULL_BROWSER_UAT.md`](./SALES_REPORTS_FULL_BROWSER_UAT.md) | 商户 | 分账金额对照（MP-M-03） | **PARTIAL**（同 #3 钱包流水抽样；专页 L3 待补） |
| 11 | 线长钱包 `/line-managers` | [`LINE_MANAGERS_FULL_BROWSER_UAT.md`](./LINE_MANAGERS_FULL_BROWSER_UAT.md) | — / 线长端若有 | 本仓若无线长小程序则 **SKIP** 并注明；仅 Admin | 待确认 → 默认 **SKIP**（无线长 mp） |
| 12 | 开票申请 `/invoices` | FINANCE 册 | 消费者 | 若 C 端有开票入口则对照；否则 SKIP | **PENDING**（先探有无入口） |

---

## 2. 可 SKIP（纯后台 · 回测时勾「已确认无 C/B 动作」）

| Admin 模块 | 深测文档 | SKIP 原因 |
|------------|----------|-----------|
| 资金账单 | `FUND_BILLS_*` | 运营只读账本 |
| 进件工作台 | `MERCHANT_ONBOARDING_*` | 仅登记不推送渠道 |
| 对账 | `RECONCILIATION_*` | T+1 运营跑批 |
| 数据一致性 | `CONSISTENCY_*` | 巡检/修复运营侧 |
| OTA / SLA / 补货员效率 | `OTA_*` `SLA_*` `REPLENISHMENT_STAFF_*` | 运维报表 |
| 仓库视图 | `WAREHOUSE_VIEW_*` | 仓配后台 |
| 识别映射 / SKU 识别 / 选品 / 上传队列 | device-sku 各册 | 运营配置 |
| 固件/维修/地图/KPI/运维列表等 | device 各册 | 无 C 端必经（柜机码开门归 §1 #9） |
| 系统组织/角色/字典等 | SYSTEM 册 | 无 C/B |

---

## 3. 已做过（不必重复，除非回归）

| 范围 | 证据 | 备注 |
|------|------|------|
| 工作台三端口径 | `workbench/FINDINGS.md` · `wb-mp-c-*.png` · `wb-new-order-three-end.md` | 订单 ¥3.50 · 分账金额抽样 |
| 概览 Phase1 §7 | `OVERVIEW_*` Done 勾选 | 触发用例已跑或 SKIP |
| **双端基础烟测（2026-09-27）** | [`mp-smoke/FINDINGS.md`](../uat-screenshots/2026-09-26/mp-smoke/FINDINGS.md) · `BUTTONS.md` · `*-smoke.json` | 消费/商户各 19 页 L1；多页 L2；软写；**未写三端大脚本** |

商户钱包/补货页 automator 曾 **timeout**（`wb-x-mp-merchant-probe.json`）→ 本轮烟测已用 DevTools automator 重新打开钱包/补货（L2）；Admin L3 对照仍欠。

---

## 4. 回测执行顺序（建议一次开 DevTools）

```
1. 编译双端 dist/dev/mp-weixin（mtime 新）
2. 消费者：登录 → 订单对照（#7）→ 余额/充值（#1/#5）→ 开门（#9）
3. 商户：登录 → 钱包/分账（#2/#3/#10）→ 补货待办（#6）
4. 争议/开票按有无数据补（#8/#12）
5. 更新本表状态 + 各 FINDINGS「MP 回补」+ PROJECT_KNOWLEDGE Changelog
```

---

## 5. 与财务商户册的关系

本会话 Admin 已深测至 **充值管理**；财务剩余 Admin 页（余额退款 / 开票 / 用户余额）仍可先后台深测，但 **§1 P0 行不因 Admin PASS 关闭**。

索引：[`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md)

---

## 6. 三端场景清单与造数闸门（烟测之后）

| 产物 | 路径 |
|------|------|
| 场景真源（价值链 A + 页矩阵 B） | [`MP_THREE_END_SCENARIOS.md`](./MP_THREE_END_SCENARIOS.md)：**S0 清数 → S1 台子 → S2 主链 → S3 旁路** |
| 测前清数 | `cleanup-test-data.ps1` **默认 FullBusiness**（可清订单/分账/采购等；`-Light` 旧行为）；柜机动态解析 |
| 造数闸门 | `mp-seed-gate.ps1 -CleanupFirst`（`-SeedMinimal` 造单；`-DeviceId`/`E2E_DEVICE_ID` 可选，**不写死柜**） |
| 主链脊骨 | `powershell -File scripts/e2e-full-flow-milk.ps1`（内含 cleanup） |
| 闸门产物 | `.tmp/mp-seed-gate.json` |

自动化须 **S0+S1 绿** 再进 S2；**禁止**带着脏会话/争议宣称主链 PASS；**禁止**未列场景就扩「全业务大套」。
|
