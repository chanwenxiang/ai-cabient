# POINTS_IDENTITY 差 32 分：取证结论（2026-10-18，只查**未改**）

> 起因：钱包统一轮的隔离栈实跑 8 条报警里剩这一条没查到底（登记为
> [PROJECT_KNOWLEDGE §10 G10](../../../PROJECT_KNOWLEDGE.md)）。用户本轮拍板「**只查不改**」⇒
> 本文只有取证与影响面，改法在 [POINTS_IDENTITY_DECISION_BRIEF_2026-10-18.md](../../../POINTS_IDENTITY_DECISION_BRIEF_2026-10-18.md) 等拍板。

## 1. 一句话结论

**不是假阳性，也不是数据被写坏**：`available_points` 与积分流水**逐分自洽**（`POINTS_BALANCE` 判据实测为绿）。
红的是**写侧少记一桶**——「退款扣回积分」只减 `available_points`，既不记 `used_points` 也不动 `total_points`，
于是 `total = available + used + expired` 这个三角恒等式必然破。差的 32 分 = 本栈 4 笔退款扣回 × 8 分。

⚠️ 更要紧的一条：**巡检的自动修复方向是错的**——`applyFix` 把 `total_points` **下调**去凑三桶之和，
等于把「累计发放」这个语义抹平，属§12 那两条同类的「判据与写侧口径漂移」，且修复动作本身会改用户可见数据。

## 2. 两条互补判据在同一行上的实测（隔离栈 `aicabinet-uat`，psql 只读）

| 判据 | expected | actual | 结果 |
|---|---|---|---|
| `POINTS_BALANCE`（可用 vs Σ 流水） | 149 | 149 | **t（绿）** ⇒ 流水与可用余额自洽 |
| `POINTS_IDENTITY`（total vs available+used+expired） | 181 | 149 | **f（红）** ⇒ 差的 32 全部是「扣回」，无处登记 |

被检行：`member_id=1`（`user_id=10001`，演示消费者）
`total_points=181 / available_points=149 / used_points=0 / expired_points=0`。
🔴 注意 `used_points` 与 `expired_points` **都是 0** —— 兑换与过期两条路径本栈没跑过，
所以「已用」桶全仓只有 `PointsRedeemService` 一个写方，扣回没地方去就暴露得这么干净。

## 3. 四条积分写路径的口径对照（源码实证）

| 路径 | 位置 | `total` | `available` | `used` | `expired` | 流水行 | 恒等式 |
|---|---|---|---|---|---|---|---|
| 购物返积分 / 演示补分 | [MemberService.java:262-263](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/services/trade-service/src/main/java/com/aicabinet/trade/service/MemberService.java#L262) | +p | +p | — | — | `EARN` | ✅ |
| 兑换商品 | [PointsRedeemService.java:144-145](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/services/trade-service/src/main/java/com/aicabinet/trade/service/PointsRedeemService.java#L144) | — | −cost | +cost | — | `USE` | ✅ |
| 到期作废 | [PointsExpiryScheduler.java:149-150](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/services/trade-service/src/main/java/com/aicabinet/trade/service/PointsExpiryScheduler.java#L149) | — | −x | — | +x | `EXPIRE` | ✅ |
| **退款扣回** | [MemberService.java:316-323](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/services/trade-service/src/main/java/com/aicabinet/trade/service/MemberService.java#L316) | — | **−a** | — | — | `USE`(`ORDER_REFUND`) | ❌ **少记** |

前三条都成对动两列（一侧减、一侧归集），恒等式守恒；第四条只减 `available`，
差额就从 `total` 那一侧漏出来。`clawback` 的额度算法与 `EARN` 对称（按等级倍率 × 退款额取整、
`min(clawback, available)` 截断、`existsByMemberAndSource` 幂等去重），所以「扣了多少」本身是对的，
**只是没人给它记账**。

## 4. 32 分的逐笔对齐

`member_points_log`（member_id=1）共 13 行：`EARN` 9 笔合计 **+181**（演示补分 120 + 8 笔购物返分），
`USE / ORDER_REFUND` 4 笔合计 **−32**（每笔 −8，`description=退款扣回积分`，
`source_id=<orderId>:REFUND:<orderId>:686`）。⇒ `181 − 32 = 149 = available_points`，
而 `total_points` 仍停在发放侧累计 181。四笔扣回的时间戳与 F2/F3 驱动器那四轮退款一一对上
（03:11:29 / 03:14:02 / 04:41:10 / 04:49:39）⇒ 是真实业务写入，不是脏数据。

## 5. `applyFix` 方向的实验（事务内改、随即回滚，库未变）

[DataConsistencyService.java:1367](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java#L1367)
把 `POINTS_IDENTITY` 接到 [fixPointsIdentity(:1692-1708)](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java#L1692)，
SQL 是 `UPDATE member SET total_points = available + used + expired`（**以三桶为准回写累计**）。

在隔离栈里用 `BEGIN; … ROLLBACK;` 真跑了一遍这条 UPDATE：

| 阶段 | total | available | used | expired | 消费者端「距金卡还差」 |
|---|---|---|---|---|---|
| 现状 | 181 | 149 | 0 | 0 | 319 |
| 应用 fix 后（同事务内） | **149** | 149 | 0 | 0 | **351** |
| `ROLLBACK` 后 | 181 | 149 | 0 | 0 | 319 |

⇒ 运营点一次「修复」= 把「累计获得积分」永久少报 32 分，同时把升级进度条往后推 32 分。
**报警不变绿是靠改数据口径、不是靠补记**，这正是钱包族本轮已被禁止的「自动回写」形态
（本类 `applyFix` 现在对钱包判据一律 `FixOutcome.fail("该类仅巡检记录，请人工核对处理")`，积分族没有同等待遇）。

## 6. 顺带查出的等级/积分口径缺口（与恒等式无关，同样只登记）

- **等级判定完全不看积分**：[MemberService.java:150-174](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/services/trade-service/src/main/java/com/aicabinet/trade/service/MemberService.java#L150)
  的 `calculateMemberLevel(totalSpent, netRecharge)` 只匹配 `min_spent`/`max_spent` 与 `min_recharge`。
- 但 `member_level_rule.min_points` **是**消费者端「距下一等级还差 N 分」的唯一分母
  （[PointsRedeemService.java:69-70](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/services/trade-service/src/main/java/com/aicabinet/trade/service/PointsRedeemService.java#L69)）。
  ⇒ 同一个 `min_points` 列被两处当两种东西用：运营台当**升级门槛**（不生效），H5 当**进度门槛**（生效）。
- `member_level_rule.max_points` 全仓**零读取**（只有实体与后台写路径）⇒ 死列，
  后台「累计积分区间」列（[MemberLevelsView.vue:71](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/clients/admin-vue/src/views/growth/MemberLevelsView.vue#L71)）
  展示的是一个改了不影响任何行为的旋钮。

## 7. 读侧影响面（决定「该不该改」时的权重）

| 读方 | 读哪列 | 现状后果 |
|---|---|---|
| 消费者 H5 积分页 [points.vue:14](file:///D:/ai-generated%20code/ai-cabinet-wt/cadmin-uat/clients/consumer-mp/src/pages/points/points.vue#L14) | `total`（累计）、`used`（已用） | 「累计」含已扣回的 32（口径=发放累计）；「已用」永远 0，扣回看不见 |
| `ConsumerMemberFacade:67` | `total` | 同上 |
| `PointsRedeemService.summary()` | `total` + `min_points` | 进度 gap 用发放累计（对用户更有利的一侧） |
| 运营台会员/巡检页 | `total/used/expired` + 本判据 | 恒常一条红；点修复会静默下调累计 |

## 8. 诚实边界

- 只在隔离栈 `aicabinet-uat`（演示级数据）取证，**共享 dev 库未查**（本轮不需要、且避免再动 peer 环境）。
- 本栈没有兑换与过期流水 ⇒ 「扣回归入 `used` 会不会污染已用报表」这一点只有代码面依据，没有真实数据样本。
- 未改任何代码、未改任何库行（§5 的 UPDATE 已在同一事务内回滚，`AFTER_ROLLBACK` 核对 total 仍是 181）。

## 9. 复现命令

```bash
docker exec -i aicabinet-uat-postgres-1 psql -U aicabinet -d aicabinet <<'SQL'
SELECT total_points, available_points, used_points, expired_points FROM member WHERE member_id=1;
SELECT points_type, COUNT(*), SUM(points) FROM member_points_log WHERE member_id=1 GROUP BY points_type;
SQL
# 巡检实跑（需临时 CORS_ORIGIN=http://localhost:28080，见 §10 G9）：
#   POST /api/v2/ops/admin/consistency/run
```
