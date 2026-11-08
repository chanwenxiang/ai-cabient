# 轮次 R6 · 定时 / 日结 / 月结

> 归档 2026-11-08。UAT 库 `aicabinet_uat`；后台写路径 target = trade-service `127.0.0.1:18080`。
> **本文件是「归档重建」**：轮次定义取自 [`UAT_TEST_PLAN_V3.md §4`](../UAT_TEST_PLAN_V3.md) 的「R6 定时/日结/月结」表。
> 本轮（2026-11-08）**只做**两件事：**清单状态机在明确**（见 §1）、**核对已归档产物覆盖了哪些任务**（§3）、
> **用诚实度分级标注「哪一条真的被执行、哪一条只有计划或文档声明」**（§4）。**未重跑业务写路径。**

## 1 · 范围（逐任务 + 幂等断言）

| 任务 | 触发方式 | 断言 | 幂等 |
|------|----------|------|------|
| 日费用日扣 | `rerun-daily-fees?feeDate=` | `merchant_fee_ledger` + wallet 流水 | 二次 rerun 不双扣（唯一索引） |
| 日结收入通知 | scheduler `taskKey` run | `notification(DAILY_INCOME)` | 不重发 |
| 佣金日结 | 周期扫描（无手动端点） | `wallet_ledger COMMISSION = gmv×bps` | 重扫不双发 |
| 机器账单月出账 | `machine-payee-bills/generate?billMonth=` | `machine_payee_bill` 行；**再 generate 应 409** | 不重复出账 |
| 场地租金 / 数据费 | generate 端点 | 账单行 + appendLog | 再 generate 409 |
| 月份结差异处置 | close-sheets → approve → disposition | `inventory_write_off` 索赔行 | 并发双击不双挂 |
| 资金对账 | `reconciliation/run?date=` | recon 行 + MISMATCH | 重跑删旧重建不残留 |
| 欠款催缴 | 造 PENDING 观察阶梯 | 通知/风控事件 | 不重复催同档 |
| 有效性巡检 | consistency run / fix | `data_consistency_record` | FIXED 重放拒绝 |
| 过期批 / 补货建议 | expiry ensure / suggest | 告警 + 拉货任务 | 同批次不重开 |
| SMS 到达率巡检 | `sms-delivery-report` / scheduled 链 | 巡检记录 | 重复不重报 |

（原文见 [`UAT_TEST_PLAN_V3.md §4`](../UAT_TEST_PLAN_V3.md)）

## 2 · 本轮核实的 runner 结构（A：文件逐行读核）

**任务化 runner = [`.tmp/uat-r6.py`](../../.tmp/uat-r6.py)**（73 行，`python -u` 执行，`python.exe` 为 PATH 上的可用版本）。

它对下面 5 个任务各执行**两次** POST，且把返回码 `print` 到标准输出：

| 序 | 调用 | 备注（脚本实际所写） |
|----|------|----------------------|
| ① | `POST /api/v2/ops/admin/merchant-fee-ledgers/rerun-daily-fees?feeDate=2026-11-07` ×2 | 断言：`SELECT count(*) FROM merchant_fee_ledger WHERE fee_date='2026-11-07'` |
| ② | `POST /api/v2/ops/admin/machine-payee-bills/generate?billMonth=2026-11` ×2 | 第二次应 409/幂等 |
| ③ | `POST /api/v2/ops/admin/reconciliation/run`，body `{date:'2026-11-07', channel:'WECHAT'}` ×2 | 断言 `payment_reconciliation` 行数 |
| ④ | `POST /api/v2/ops/admin/consistency/run` ×2 | — |
| ⑤ | `POST /api/v2/ops/admin/scheduled-tasks/session-door-open-expire/run` ×1 | 调度任务手动触发 |
| — | `adminToken()` 登录（`127.0.0.1:18080` + Redis 取图形验证码） | 后端 token 后用于各 POST |

> **诚实提示**：脚本把每次响应码打在 stdout。**stdout 没有落盘到仓库**（本轮在 `.tmp/` 下找不到该次输出文件）。
> 因此 `①…⑤ 各次返回码属于「脚本已核实 / 返回未入库」= **B 级**，本轮**不据此重写 PASS/FAIL**。

## 3 · 界面登记（A：页面事实逐核）

任务化之外，R6 用 [`.tmp/r6-tabwalk.mjs`](../../.tmp/r6-tabwalk.mjs) 遍历后台各路由、逐个 click Tab、抓取
`总条数 / 行数 / 表头 / 空态 / 控制台错误 / apiCalls`，产物落 `.tmp/r6-<route>.json`。本轮读核了其中数份：

| 产物 | 本轮核实到的事实（原文摘要） |
|------|------------------------------|
| [`.tmp/r6-machine-payee-bills.json`](../../.tmp/r6-machine-payee-bills.json) | Tab「月度账单」（共 0 条）、「点位费用配置」（共 0 条）；toolbar 含「手动出账」；`consoleErrors: []`；apiCalls 只有 `GET .../machine-payee-bills?`（**读路径**） |
| [`.tmp/r6-reconciliation.json`](../../.tmp/r6-reconciliation.json) | 过滤条件「渠道」= 微信/支付宝/其他/余额/未知/微信支付分（六项）；「状态」= 已平账/存在差异/待处理/失败；toolbar 含「执行对账」；detail `found:1`，渠道合计 `¥0.00`｜本账合计 `¥0.00｜差额 ¥0.00｜未匹配笔数 0`；`consoleErrors: []` |
| [`.tmp/r6-consistency.json`](../../.tmp/r6-consistency.json) | 「类型」14 项：订单金额 / 支付净额 / 积分余额 / 发券数量 / 库存汇总 / 用户钱包 / 退款金额 / 订单行金额 / 券核销关联 / 孤儿批次 / 冻结毛额入账 / 无批次账本 / 积分恒等式 / 商户钱包；toolbar 含「立即巡检」；行操作：「需人工：该类仅巡检记录，需人工核对处理」；`consoleErrors: []` |
| [`.tmp/r6-6a1.json`](../../.tmp/r6-6a1.json) | 「账务明细」hint「支持跨月，单次不超过 **90 天**；留空＝只看近 7 天」；空态「近 7 天暂无流水」；platform-fund 共 1 条（rows=1）；device-rental 共 1 条 + 第二表 0 条；merchant-onboarding 共 3 条（rows=3）；merchant-fee-ledgers 共 0 条「暂无数据」；`consoleErrors: []` |

> 其余 `.tmp/r6-*.json`（fund-bills / recharges / invoices / users / merchants / line-* / balance-refunds / mw-tabs / 6b 等）本轮**未逐行读核** ⇒ **B 级**。

## 4 · 覆盖核对（哪个任务落到哪一级）

| 任务 | 有无真进程/脚本证据 | 级别 | 说明 |
|------|-------------------|------|------|
| 日费用日扣 rerun ×2 | [`uat-r6.py` ①](../../.tmp/uat-r6.py) | **B** | 脚本真；返回码未入库 |
| 机器账单 generate ×2 | `uat-r6.py` ②；页面轮换 = [`r6-machine-payee-bills.json`](../../.tmp/r6-machine-payee-bills.json) | **B** | 409 断言在脚本；实际 409 未留痕 |
| 资金对账 run ×2 | [`uat-r6.py` ③](../../.tmp/uat-r6.py) + [`r6-reconciliation.json`](../../.tmp/r6-reconciliation.json) 页面事实 | **B** | 脚本/页面真；行数未留痕 |
| 一致性巡检 run ×2 | [`uat-r6.py` ④](../../.tmp/uat-r6.py) + [`r6-consistency.json`](../../.tmp/r6-consistency.json) | **B** | — |
| 调度 taskKey run | [`uat-r6.py` ⑤](../../.tmp/uat-r6.py) | **B** | 路径 `session-door-open-expire` 未再核对控制器存在性 ⇒ **B** |
| 日结收入通知 | 未在 `uat-r6.py` 出现 | **C** | 仅计划表 |
| 佣金日结 | 计划称「周期扫描型（无手动端点）」 | **C** | 后续 R9/R11 补跑 |
| 场地租金 / 数据费 | 未在 `uat-r6.py` 出现 | **C** | 计划表 |
| 月份结差异处置 | 未在 `uat-r6.py` 出现 | **C** | 计划表 |
| 欠款催缴 / 过期批 / SMS 巡检 | 未在 `uat-r6.py` 出现 | **C** | 计划表 |

## 5 · 与 [`UAT-FINAL.md §16`](../UAT-FINAL.md) R6 行声明的对账

声明原文：`fee-rerun×2 / 机器账单×2 / recon×2 / 巡检×2 / 调度 run 幂等全验（零新缺陷）`。

- 前半句**与 `uat-r6.py` 逐条对齐**（①–⑤）⇒ 机制自洽。
- **§16 另写的「`feeDate` 未来值 400 边界 ✓」在本 runner 中找不到对应调用**（`uat-r6.py` 只传了 `2026-11-07` 当前值）⇒
  本轮**标为无法重核（C/未消歧）**，**不当成本轮实证**。

## 6 · 结论

- **可复现性**：R6 的核心可跑入口 = `python -u .tmp/uat-r6.py` + 页面遍历 `node .tmp/r6-tabwalk.mjs <route>`；本轮已核对脚本内容与页面事实。
- **忠实度结论**：脚本对 5 个任务各做了双触发的**结构完整**，但**逐次返回码未入库**。因此本文件的收口是
  「**结构已核验 + 语义幂等断言已落脚本，但「实证」这层欠账在 repo 内没有落点**」，**不待人再打勾**。
- **新增阻塞：0**（据 [`UAT_FINAL.md §三`](../UAT_FINAL.md)，上线结论无 R6 前置动作）。

## 7 · 产物清理
本轮未清理造数；「所有 `AUDIT-` 前缀造数已清理且归 0」的收口要求载于 [`UAT_TEST_PLAN_V3.md §6`](../UAT_TEST_PLAN_V3.md)。本轮未触计费写路径，不产生新残留。
