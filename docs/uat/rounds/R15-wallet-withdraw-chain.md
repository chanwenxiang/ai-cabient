# R15 — 钱包→提现金额全链对账（真 UAT 库 / 真 API）

> **轮次**：R15（A 类真库）。**日期**：2026-11-08。**活库**：`aicabinet_uat`；靶端 trade-service `127.0.0.1:18080`。
> **驱动**：`.tmp/r15-chain.mjs`（gitignore，不入库）。**结果**：**58 PASS / 0 FAIL，exit 0，0 残留**。
> **证据**：`.tmp/r15-run6.log`（stdout 全断言）+ `.tmp/r15-baseline6.txt`/`.tmp/r15-after6.txt`（轮首/轮末全 179 表精确计数）。

## 0. 范围调整（用户拍板，如实记录）

coverage-table §4 原 R15 设想「单据→钱包→提现全链」。实测 **commercial-flow 一键造单的识别腿恒转争议、orderId=null**（5 轮实证：补齐仓库负责人→补货缺口→柜机所属仓三个前置后，采购/补货/出库全 DONE，但 `AI_SETTLEMENT=WARN「识别结果未采纳（转争议/待人工复核）」`，不成单）。这是设计——demo 识别走 needReview，R1 当年亦靠「裁决 CONFIRM」才首结算。

用户拍板：**R15 聚焦「钱包→提现」链**，用现有钱包余额（基线 209900 分）测；「订单→分账→钱包入账」腿已由 **R1** 实证覆盖，不重复造数。本轮不碰 commercial-flow，残留极小且全部精确可清。

## 1. 被测模型（对着源码读死，非推断）

| 事实 | 源码锚点 |
|---|---|
| 阈值 `merchant.withdraw.review_threshold_cents=50000`（=¥500）；`amountCents>=阈值`→PENDING_REVIEW，否则自动 APPROVED | `MerchantWithdrawService.persistWithdrawApplication`；`SystemConfigService:897` 种子注释「默认 ¥500」 |
| 低于阈值：apply 即 APPROVED→`executePayout`→PAID（MOCK 通道申请即打款） | `createWithdraw`→`gate.shouldPayout()`→`executePayout`；`MerchantWithdrawPayoutService:99` `PayoutResult.success("MOCK",…)` |
| 高于阈值：双节点审批（node1 运营审核 DEPT=MANAGER，node2 财务复核 DEPT=FINANCE） | 实库 `approval_definition` def_id=3 / `approval_node` seq 1/2 |
| review approve 后 `isInstanceApproved` 未终审→保持 PENDING_REVIEW（初审）；终审→APPROVED→自动 executePayout→PAID | `MerchantWithdrawService.completeReview` |
| 100000001（13900000001）同属 MANAGER+FINANCE ⇒ 同账号审两次走完双节点 | 实库 `ops_user_department` |
| 钱包流水记**负数**：FREEZE=−amt、PAID=−(amt−fee)、FEE=−fee；`finalizePayout` 置 payChannel=result.payChannel()=MOCK | 实库 wallet_ledger 实测；`MerchantWithdrawService:679` |
| 费 `fee_bps=60`（0.6%）不封顶：A=10000→60 分；B=100000→600 分 | `merchant.withdraw.fee_bps=60, fee_cap_cents=0` |

## 2. 执行与断言（58 PASS / 0 FAIL）

基线：wallet `MERCHANT|MCH-DEFAULT|209900|0`，待结算 0，可用 209900；wd=2(max 2)、ledger max 26、audit max 395、approval inst 14/task 27。

- **提现 A（¥100<阈值）**：apply HTTP 200 → `PAID`、渠道 MOCK、掩码 `****0001`、户类型 `PAYEE_TYPE_COMPANY`、幂等键 `MW:<requestId>`；三腿 FREEZE/PAID/FEE 齐，|净额|=9940、|费|=60、净额+费=10000，终态 frozen=0、balance=基线−10000=199900。
- **提现 B（¥1000≥阈值）**：apply → `PENDING_REVIEW`、审批实例恰 1、冻结 frozen=100000；**初审（node1）后仍 PENDING_REVIEW**；**终审（node2）后 PAID**（自动打款）；|净额|=99400、|费|=600、净额+费=100000，终态 frozen=0。
- **终态对账**：balance=基线−(A+B)=99900、frozen=0、无在途提单；A/B 流水三恒等 `freeze−paid−fee=0` 各 0。
- **负控制**：低于最低（50 分）→4xx 含「最低提现」；超单笔上限（1000001）→4xx；超可用余额（250000）→**412** 含「可用余额不足」；同号同额幂等重放→200 回同一 requestId、未新增；同号异额→**409**、未新增。三条负控均不落库。

## 3. 收口与 0 残留（双重确认）

清理（精确 ID + FK 子先父后）：approval_task→approval_instance（biz_id IN 本轮 reqA/reqB）→wallet_ledger/merchant_fee_ledger/payment_platform_bill_line/notification_log/merchant_notify_log/admin_audit_log（`id>轮首max`，COALESCE 防空表 NaN）→merchant_withdraw_request（`request_no LIKE MARK-%`）→wallet_account 还原基线。

- **脚本内部断言**：wallet/提现单数/提现 max/ledger max/audit max/notification max/审批实例数/审批任务数 全部精确复原；MARK 提单残留=0。
- **外部独立核对**：轮首/轮末全 179 表 `count(*)` 逐表 diff，**仅 `device_temperature_reading` 随时间自然漂移**（设备背景遥测，非本轮产物），其余全部回基线。

## 4. 结论与遗留

- **R15 提现链 PASS**：自动通过/双节点人工审/MOCK 打款/负控制/幂等/金额三恒等全验，0 残留。
- **遗留（如实登记）**：「订单→分账→钱包入账」腿本轮未重复造数（R1 已证）；commercial-flow 识别恒转争议不成单，若后续要端到端演示单需走「裁决 CONFIRM」路径（侧表多、清理复杂，另立轮次）。
- **过程教训**：活共享 UAT 上盲跑造数脚本极易误伤基线（本会话前 5 轮曾误删出库明细/审计行，已重建/登记）；第 6 轮改聚焦提现链 + pg_dump 备份兜底 + 精确 ID 清理 + 外部全表计数双核，才稳定 0 残留。造数类轮次必须先备份、清理用返回的精确 ID 而非 `id>max` 孤儿跨度、审计删除带 target_type 过滤。