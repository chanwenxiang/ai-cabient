# P3 待定 5 条——产品拍板决策材料（2026-11-13）

> 来源：`docs/AUDIT_FOUR_END_R20_2026-11-12.md` §十七.4（6 条中 1 条已拍板落地，余 5 条）。
> 本文档逐条给出「现状（以当前 dev 源码为准，标注取证行号）/ 影响面 / 可选口径 / 建议 / 拍板问题」。
> 其中**第 5 条经代码+库实测复核为前提有误，建议直接关闭**（勘误，无需拍板）。
> 竞品/口径锚点：CB-045（券退款口径）、CB-053（退款失败先查单同号重试）、CB-056（回溯以已入账行为基数）。

---

## ① 争议改单跌破门槛时券不返还但仍 USED

> 🔄 **2026-11-13 已按推荐口径 B 落地**：`CouponService.restoreUnappliedCouponOnOrder(order)`（新公开方法）——改单后券未产生任何折扣（被活动覆盖或跌破门槛）→ 返还券（USED→UNUSED CAS + 清 usedAt/orderId/deviceId/抵扣额）并清订单券链接（`clearOrderCouponColumns` 单源清列，MA-147）；已产生折扣维持 USED（CB-045 已享受不退）。调用点=`SettlementConfirmDisputeService.finalizeConfirmDispute`（写路径；prepare 只读不动券）。金额基线不受影响（折扣本就为 0）。3 单测（零折扣返还 / 有折扣不返还 / 非 USED 幂等）。

**代码现状**（`SettlementOrderSupport.recalculatePayableAfterLineChange` :216-233；调用点 `SettlementConfirmDisputeService` :201/:244）：
- 争议改单按当前活动重定价；若命中活动价，**券被跳过**（`couponDisc=0`，CB-082「活动价与券不叠加、活动优先」防双折口径）。
- 若改单后小计跌破券门槛，`discountForOrderCoupon` 返回 0。
- ~~两种情况下 `user_coupon` 行都保持 USED~~ → **现实现：finalize 阶段返还**。

**拍板问题**：仅确认口径 B（默认维持）；如需改回「一律不返还」，删除 finalize 中的一行调用即可。

---

## ② 对账日跑删除重建，人工处置只存 audit

> 🔄 **2026-11-13 已按推荐口径 C 的可实现形态落地**：`ReconciliationService.runDaily` 重跑不再「删除重建主行」——**主行原地更新（reconId/createdAt 承接旧行），仅明细行按 reconId 清后重写**。旧实现每次重跑生成新 reconId，audit 里按 reconId 关联的人工处置痕迹全部脱链；现实现 audit 链接稳定。3 单测（新增重跑复用 reconId 用例；既有 `runDaily_recomputesExistingReconciliation` 语义更新为原地更新+从不 delete/flush）。
> 剩余拍板项：正式的**处置数据模型**（行上处置字段/端点/UI）尚不存在——若产品要「行上可见的处置状态」，需新增迁移+端点，属新功能排期而非本批范围。

**代码现状**（`ReconciliationService.runDaily` :120-137）：
- ~~对 (date, channel) 加分布式锁后物理删除重建~~ → **现实现：主行原地更新、明细行重建**（`rebuilt=true` 审计标记照旧）。

**影响面**：audit 处置痕迹脱链问题已消除。

**剩余拍板问题**：是否要行上可见的处置数据模型（新功能排期）？

---

## ③ 分账回退 WECHAT_FAILED 同号重提是否依赖微信侧幂等

> 🔄 **2026-11-13 已按 CB-053 官方口径落地「先查单再重提」**（`WeChatProfitSharingService.retrySingleFailedReturn`：查单 PROCESSING/SUCCESS 不再重提、FAILED/通道异常才同号重提；单测 `WeChatProfitSharingReturnRetryQueryFirstTest` 4 例）。拍板项降级为**口径确认**：若产品要求回到「直提靠幂等」，回退方式=去掉查单前置（一处方法体）。

**代码现状**（`WeChatProfitSharingService` :243-259、:277-281、:287）：
- 回退提交使用**稳定 out_return_no**（存于分账回退腿行，R20-9 按腿立账；`requireStableReturnNo` 拒空号，重试不换号）。
- ~~重试 = 直接带同号重提~~ → **现实现：重试前先 `queryReturnOutcome` 查单，已受理（PROCESSING/SUCCESS）直接落结果不再提交；查单 FAILED（真失败或通道异常）才同号重提**（同号幂等兜底）。

**对照**：CB-053 官方口径=「先查单、同号重试防重复退款」——**已对齐**。

**拍板问题**：仅确认口径（默认维持「先查单」）；如需改回直提，一处回退。

---

## ④ Ops「重试」可能落到补货会话

> 🔄 **2026-11-13 已落地入口 fail-closed 过滤（口径 A）**：`SessionService.resetSessionForRetry` 在状态白名单之后增加 `DeviceValidationService.isRestockSession` 守卫，补货会话重试一律 409「补货会话不支持运营重试识别/结算」（单测：`RestockDoorCloseReportTest.opsRetry_onRestockSession_rejected409` + 消费者正向对照）。拍板项收窄为两点：① 扫描源头是否同步排除补货会话（口径 B，减少噪音异常行）；② OPS/STOCKTAKE 会话是否也纳入守卫（本批未动，无实证风险）。
>
> ⚖️ **2026-11-13 二次裁决（代码证据，OpsExceptionScannerService 三扫描面核对）**：
> - **口径 B 否决**：扫描面=SHOPPING 超时（DOOR_OPEN_TOO_LONG）/RECOGNIZING 滞留/SETTLING 滞留。补货会话可达的只有 SHOPPING——「柜门久开」对补货柜是**真实告警**（安全/温度风险），源头排除会消灭信号；RECOGNIZING/SETTLING 补货根本进不去，排除也省不了行。收益为零、代价是盲区 ⇒ 不做。
> - **守卫扩展 OPS/STOCKTAKE 暂缓**：它们可达的同样只有 SHOPPING 门久开——告警有效，仅「重试」按钮 409；误结算实质风险所在的两态进不去，且无误重试实证。等真实案例或产品明确「运营重试仅限消费者会话」再扩展（一处 if）。

**代码现状**（`OpsExceptionScannerService` 全文件无 sessionKind 过滤 + `SessionService.retryForOperations` :437-468）：
- 异常扫描按**会话状态**（RECOGNIZING 等）捞取，不区分会话类型 ⇒ 补货（restocking）会话若处于被扫状态也会生成异常行。
- ~~运营「重试」入口仅校验状态~~ → **现实现：状态白名单后增加补货会话守卫，409 拒绝**；消费者会话行为不变（正向对照单测）。
- 结论（代码事实）：**补货会话已被入口拦截**，不会再进入 consumer 结算链；消费者重试不受影响。

**影响面**：补货会话被误结算（订单/资金/支付分副作用错挂）——已由入口守卫消除。

**可选口径（剩余）**：
- B. 扫描源头排除补货会话（异常行不再为 restocking 会话生成，减少噪音）。
- C. 维持并补语义：为补货会话定义专用重试行为（仅当补货确实需要运营重试时）。

**拍板问题**：补货异常的运营处置路径是什么（不重试的话）？扫描源头是否同步排除？

---

## ⑤ device_sku_price 行锁粒度——**前提有误，建议关闭（勘误）**

**复核结论**（推翻待定项前提）：
- 待定项原文假设「`FOR UPDATE` 只按 `device_id` 索引 ⇒ 锁该柜全部 SKU 行」。
- 实测（当前 dev 源码 + dev 库）：
  - 锁 SQL 为 `SELECT p.* FROM device_sku_price p WHERE p.device_id=? AND p.sku_id=? FOR UPDATE`（`resources/mapper/DeviceSkuPriceMapper.xml:8-10`）——**两列谓词齐全**；
  - 复合主键 `device_sku_price_pkey (device_id, sku_id)` 在 dev 库实测存在（`to_regclass`/`pg_indexes`），谓词可走主键 ⇒ **锁粒度=单行**；
  - `idx_device_sku_price_device(device_id)` 只是备选路径，优化器对本查询应优先主键。
- 结论：锁该柜全部 SKU 行的前提**不成立**，无需数据判定、无需改动。

**建议**：直接关闭该项（勘误记录入台账）；产品无需拍板。

---

## 汇总

| # | 事项 | 状态（2026-11-13 二次更新） | 需拍板 |
|---|---|---|---|
| ① | 改单未生效券仍 USED | **已落地口径 B**（restoreUnappliedCouponOnOrder，3 单测） | 仅口径确认 |
| ② | 对账重跑覆盖处置 | **已落地原地更新**（reconId 稳定，audit 不脱链，3 单测） | 仅新功能排期（处置数据模型） |
| ③ | 分账回退重试 | **已落地「先查单再重提」**（CB-053 口径，4 单测） | 仅口径确认 |
| ④ | Ops 重试落补货会话 | **已落地入口 fail-closed 守卫**（2 单测）；余两项二次裁决：口径 B 否决（消灭真实门久开告警）、守卫扩展暂缓（无实证） | 否（已闭环，观察即可） |
| ⑤ | device_sku_price 锁粒度 | **勘误关闭**（前提有误，锁为单行） | 否 |

**P3 待定 5 条至此全部收口**：2 条按推荐口径落地（①②）、1 条按官方口径落地（③）、1 条防御性落地（④入口）、1 条勘误关闭（⑤）；无任何一项仍处于「等拍板才能动」的状态。
