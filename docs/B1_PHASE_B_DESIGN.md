# B1 Phase B 设计稿：补缴链路拆段（渠道 HTTP 移出 DB 事务）

> 状态：**待评审**（2026-11-16，B3/B4 专项已完成，本稿为最后一个动支付主链的专项）。
> 评审通过后再实施；实施批次含形态钉 + 三态负向 + 真栈弱网回归。

## 1. 现状与问题

`UnpaidOrderService.collect` / `collectByUser`（均为 `@Transactional`）→ `markPaid`(:413) → `chargeOrder(order, channel)`：

```
@Transactional collect/collectByUser          ← 外层事务
  └ findByIdForUpdate(orderId)                ← 订单行锁（PG row lock）
  └ markPaid
      └ 净额三分支判断（读）
      └ applyBestCouponForCollect（写：占券/核销）
      └ chargeOrder(order, channel)           ← ★ 渠道 HTTP（分钟级超时）
      └ setStatus(PAID) + freezePaidAmount    ← 写
      └ save
  └ recordSplit
```

问题（MA-274 Phase A 后的残留半边）：**订单行锁横跨整个渠道 HTTP**。弱网/渠道慢时：

- 该订单的所有并发入口（结算 finalize、余额冲抵、运营 collect）被阻塞至 HTTP 超时；
- 锁等待堆积 → 连接池被占满 → 拖垮同库其它写入；
- Redis 支付锁有心跳续租（Phase A），但 **DB 行锁没有续租机制**，事务多久锁就多久。

Phase A 已把 `chargeOrder` 自身去事务化（HTTP 零行锁），但调用方自带事务时 HTTP 仍包在**外层**事务里——去事务化对 join 进来的外层无解。

## 2. 目标形态：markPaid 拆三段

```
collect/collectByUser（去 @Transactional，改 Redis 锁内编排）
  │
  ├─ 段1 prepare（tx1，短事务）
  │    findByIdForUpdate → 状态/净额三分支判断 → 占券核销（applyBestCouponForCollect）
  │    → 置 CHARGE_PENDING 痕迹（recordChargePending，已有）→ 提交（锁短暂持有，毫秒级）
  │
  ├─ 段2 渠道 HTTP（无事务，零行锁）
  │    chargeChannel(...)（从 chargeOrder 抽出纯渠道调用段）
  │    超时/异常 → 段3 的 fail 分支
  │
  └─ 段3 finalize（tx2，短事务）
       ├─ 成功：markPaid 收口（setStatus PAID / freezePaidAmount / save / recordSplit）
       ├─ 失败-可重试：清 CHARGE_PENDING 痕迹（finalizeChargePending 已有幂等），
       │   返回业务 409/502，订单回 PENDING 可重新发起
       └─ 失败-结果未知（超时但渠道可能已扣）：**不动状态**，保留 CHARGE_PENDING 痕迹，
           交既有 STALE 清扫腿（alertStaleChargePendingOps → 渠道查询对账）收敛
```

## 3. 补偿语义（关键决策点）

### 3.1 状态收敛矩阵

| HTTP 结果 | 段3 动作 | 残留 | 收敛路径 |
|---|---|---|---|
| 明确成功 | 正常收口 | 无 | — |
| 明确失败（渠道返回失败码） | 清痕迹 + 释放占券（反向核销） | 无 | 订单回 PENDING |
| **超时/连接中断（结果未知）** | **不动**（保留 CHARGE_PENDING + 占券） | 券被占 + 痕迹 | 既有 stale 清扫：渠道查询→成功则补收口 / 失败则清痕迹+还券 |
| 段3 自身崩溃 | 痕迹在 | 同上 | 同上 |

### 3.2 幂等键矩阵（全部已有，零新增）

- 渠道幂等：`CHARGE:<orderId>:<amount>`（restoreCompletedCharge）——重放不双扣 ✓
- 痕迹：`recordChargePending` / `finalizeChargePending`（REQUIRES_NEW 自提交，Phase A 已就位）✓
- 券核销：applyBestCouponForCollect 的占券幂等（重放不重复占）——**需在实施时核实**占券是否有幂等键（Q1，见 §6）

### 3.3 券的补偿（唯一需要新写的补偿逻辑）

段1 已占券、段2 超时、段3 清痕迹时**必须还券**。占券核销不可逆（核销=已用），所以：

- 段1 占券后、段2 发起前，把券决策（couponId + 应用金额）写进 CHARGE_PENDING 痕迹的扩展字段；
- 段3 失败分支按痕迹反向操作：核销未发生→直接解占券；核销已发生（与渠道扣款同事务）→ 解核销还原可用态。

### 3.4 余额冲抵分支不动

净额三分支的「冲抵已覆盖全额」支（alreadyPaidCents >= total）**无渠道 HTTP**，保持现状（同事务收口），仅随外层去 @Transactional 后由编排层重组。

## 4. 外层去事务化后的隔离性代价（如实）

拆段后 prepare/finalize 之间（HTTP 窗口）订单行**无行锁保护**，并发入口可能读到中间态：

| 并发入口 | 读到中间态的后果 | 防护 |
|---|---|---|
| 结算 finalize | 状态仍 PENDING → 正常拒绝（结算只收已支付）| 无需 |
| 另一次 collect | CHARGE_PENDING 痕迹判据 → 409「支付处理中」（已有语义）✓ | 既有 |
| 运营强取消 | CHARGE_PENDING 判据 → 拒绝或排队 | 实施时核实 forceCancel 对 CHARGE_PENDING 的行为（Q2）|
| 余额冲抵（钱包） | 竞态双冲抵 | `netCompletedCents` 在 finalize 重读（段3 判据重算）✓ |

Redis 支付锁（`runWithOrderPaymentLock` + LockHeartbeat 续租）横跨三段全程持有——**这是拆段后的主并发防线**，DB 行锁只保护两个短事务自身。Redis 锁续租机制 Phase A 已验证（LockHeartbeatTest）。

## 5. 实施步骤（一次批次收口）

1. `chargeOrder` 内抽出 `chargeChannelOnce(order, requestedChannel)`（纯渠道段，返回三态结果枚举：SUCCESS / FAILED / UNKNOWN）——不动现有签名，旧 chargeOrder 保留给非补缴调用方（支付分 DONE 回调等无外层事务路径）。
2. `markPaid` 拆三段；collect/collectByUser 去 `@Transactional`，编排移入锁体。
3. 券补偿：痕迹扩展字段 + 反向核销。
4. 形态钉测试：collect/collectByUser 无 @Transactional；markPaid 内 HTTP 段方法无事务注解；痕迹两腿 REQUIRES_NEW（已有钉保活）。
5. 三态负向：外层复挂 @Transactional ⇒ 红；HTTP 段塞回事务 ⇒ 红。
6. 真栈弱网回归：mock 渠道延迟 > 行锁观测窗口，验证 `pg_locks` 无长持锁。
7. 台账 MA-274 彻底关闭 + 报告 §20。

## 6. 待评审确认的问题（实施前必须回答）

- **Q1**：`applyBestCouponForCollect` 的占券是否有幂等键（重放安全）？若无，段1 重试可能双占——需补。
- **Q2**：运营强取消（forceCancelForOperations）对 CHARGE_PENDING 订单的现状行为？拆段后 HTTP 窗口内强取消必须被 CHARGE_PENDING 判据拦截。
- **Q3**：`UNKNOWN` 残留的清扫频率与告警阈值沿用既有 STALE 配置（60min）是否足够？
- **Q4**：拆段后 `recordSplit` 从 finalize 事务里移到编排层尾部（锁内、finalize 提交后）——MA-276 REQUIRED join 外层的语义变化复核。

## 7. 验收标准

- 结算/补缴链全部单测绿（含既有 58 例支付套件）+ 新增拆段用例；
- 形态钉 + 三态负向全过；
- 真栈：`pg_locks` 在渠道 HTTP 期间（延迟 5s+）无订单行锁；
- CI build + integration 全绿；预检 150 门禁全绿。
