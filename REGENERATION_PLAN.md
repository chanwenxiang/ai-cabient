# AI Cabinet 优化文件清单（需要重新生成）

## 确认状态
- ✅ 原始文件完整（V1-V70）
- ❌ 新增优化文件被清除
- ✅ Java 服务类完整
- ✅ 原有功能正常

## 需要重新生成的文件

### 一、数据库迁移文件（12个新增）
1. V71__concurrency_control.sql ✅ 已生成
2. V72__distributed_transaction.sql
3. V73__data_consistency.sql  
4. V74__payscore_payment.sql
5. V75__user_auth.sql
6. V76__database_optimization.sql
7. V77__database_maintenance.sql
8. V78__revenue_share.sql
9. V79__franchise_management.sql
10. V80__line_leader_management.sql
11. V81__member_management.sql
12. V82__marketing_campaign.sql

### 二、Java 服务类（约20个新增）
1. DistributedLockService.java
2. IdempotencyService.java
3. TransactionCoordinator.java
4. CompensationScheduler.java
5. DataChangeLogService.java
6. DataConsistencyService.java
7. DataRepairService.java
8. PayScoreService.java
9. PaymentRiskService.java
10. WeChatLoginService.java
11. RealnameAuthService.java
12. RevenueShareService.java
13. RevenueShareSettlementService.java
14. RevenueShareWithdrawService.java
（以及其他服务类...）

### 三、实体类（约15个新增）
1. IdempotencyKey.java
2. DistributedTransaction.java
3. CompensationTask.java
4. DataChangeLog.java
5. UserRealnameAuth.java
6. RevenueShareRule.java
7. RevenueShareDetail.java
（以及其他实体类...）

### 四、Repository（约15个新增）

## 重新生成策略
由于文件较多，建议：
1. 核心迁移文件优先（数据库表）
2. 核心 Java 服务类其次
3. 实体类和 Repository 最后

## 估算工作量
- 数据库迁移：约 10 分钟
- Java 服务类：约 30 分钟
- 总计：约 40 分钟

---
状态：准备重新生成
时间：2026-07-14
