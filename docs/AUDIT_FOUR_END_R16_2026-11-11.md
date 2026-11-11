# 四端审计 R16 报告（2026-11-11）

> **基线**：HEAD `eccccb20`（R15 报告入库时点；R15 的 8 条 P2 修复在制工作树未提交，一律不采信、不纳入）。
> **方法**：会话内手动只读审计，聚焦 R1-R15 从未系统审计的机制层：结算对账链 / 通知投递链 / 提现审批链 / CSV 导出 / 设备生命周期 / 定时任务执行 / 预授权 / 积分过期 / 会员等级。
> **总账**：新发现 **1 条 = P3×1**；P0/P1/P2 为零。
> **修复状态**：待用户拍板。

---

## 一、发现总览

| 区域 | P2 | P3 | 说明 |
|---|---|---|---|
| 结算对账链 | 0 | 0 | SettlementService 幂等正确（findBySessionId 检查） |
| 通知投递链 | 0 | 0 | NotificationService 异步/同步回退正确 |
| 提现审批链 | 0 | 0 | MerchantWithdrawService 幂等+冻结+审批完善 |
| CSV 导出 | 0 | 0 | CsvCells.escape() 覆盖全面（RFC 4180 + 公式注入中和） |
| 设备生命周期 | 0 | 0 | DeviceSlot/DeviceRental/SessionService 锁保护完善 |
| 定时任务执行 | 0 | 0 | ScheduledTaskRegistry + tryBegin/finish 模式完善 |
| 预授权 | 0 | 0 | ConsumerPreauthService @Transactional + 锁 |
| 积分过期 | 0 | 0 | PointsExpiryScheduler @Transactional |
| 会员等级 | 0 | 0 | MemberLevelAdminService 校验+锁 |
| 机具点位账单 | 0 | 1 | MachinePayeeBillService 缺 @Transactional |
| **合计** | **0** | **1** | 本轮审计面大幅收窄 |

---

## 二、P3（1 条）

### R16-MA-175 [P3] MachinePayeeBillService.settle/saveConfig 缺 @Transactional + 无行锁

- **位置**：`MachinePayeeBillService.java:146-156`（`settle`）/ `:175-210`（`saveConfig`）
- **缺陷**：
  - `settle()`: `selectById` → `setStatus("SETTLED")` → `updateById`，无 `@Transactional`、无 `findByIdForUpdate`、无分布式锁
  - `saveConfig()`: `selectById` → 校验 → `insert`/`updateById`，无 `@Transactional`、无 `findByIdForUpdate`
- **影响**：
  - `settle()`: 并发结算同一账单 → 两次 `updateById` 都是 `SETTLED`（幂等，但审计日志双写）
  - `saveConfig()`: 并发保存同一设备配置 → 第二次 `updateById` 覆盖第一次（丢失更新）
- **触发**：运营台并发操作同一机具账单/配置（低概率，但非零）
- **修法**：`settle()` 加 `@Transactional` + `findByIdForUpdate`；`saveConfig()` 加 `@Transactional` + `findByIdForUpdate`（模板同 OpsExceptionService）
- **置信度**：中（代码路径实锤，触发依赖并发）

---

## 三、已核对无问题（划新审计边界）

| 区域 | 要点 |
|---|---|
| SettlementService | 幂等检查（findBySessionId）在 settle 前；sessionLifeLockKey 分布式锁 |
| NotificationService | 异步 producer 不可用时回退同步；persistInAppLog 在 @Transactional 内 |
| MerchantWithdrawService | requestNo 幂等回放 + 归属+金额校验；freezeForWithdraw 冻结；审批工作流 |
| DataConsistencyService | 23 项一致性检查；checkType+checkKey 锁；stale failure 自动关闭 |
| CSV 导出 | CsvCells.escape() 统一转义（RFC 4180 + 公式注入中和 = + - @ TAB CR 前缀加 '） |
| DeviceSlotService | deviceSlotLockKey 分布式锁；recordRestock 用 findById + save |
| DeviceRentalService | settle 用 adjustBalance CAS + creditIfAbsent 幂等 |
| SessionService | transition 用 Java 侧 canTransitionTo 检查 + save；调用方均在 sessionLifeLockKey 锁内 |
| ScheduledTaskRegistry | 38 个任务注册；tryBegin/finish 模式；stale monitor 覆盖 |
| ConsumerPreauthService | @Transactional + preauthLockKey 分布式锁；freeze/release/capture 均有事务 |
| PointsExpiryScheduler | @Transactional + self 代理调用；expire 按 memberId 聚合 |
| MemberLevelAdminService | requireLevelParams 校验 + levelIdLock/levelCodeLock |
| ReconciliationScheduler | tryBegin/finish 模式；tryBegin 失败即返回 |
| OpsExceptionService | @Transactional + exceptionLockKey + requireForUpdate |

---

## 四、R15 修复在制确认

R15 的 8 条 P2 修复全部在工作树中（未提交）：
- H1: ci.yml 加 timeout-minutes ×5
- H2: audit-frontend-deps.mjs 改 fail-closed
- K1/K2: shared-api refreshSilently 失败分类
- K3: merchant endpoints 加 logout + mine.vue onLogout 调服务端
- L1: SkuCatalogMapper 加 existsByImageUrlAndSkuIdNot + FileAttachmentService release 前检查
- M1: docker-compose 加 restart: unless-stopped ×2
- M2: admin-uat.mjs 结构化断言

---

## 五、建议批次修法

**P3（1 条）**：
- R16-MA-175：MachinePayeeBillService.settle/saveConfig 加 @Transactional + findByIdForUpdate

---

*报告落盘：2026-11-11。基线 HEAD=eccccb20；R15 修复在制不采信。本轮审计面大幅收窄——R1-R15 已覆盖业务逻辑/seq 守卫/M01 家族/R4-E5 家族/XFF 限流/CI/auth/媒体链/infra，R16 聚焦机制层深层并发安全与事务完整性，仅发现 1 条 P3。*
