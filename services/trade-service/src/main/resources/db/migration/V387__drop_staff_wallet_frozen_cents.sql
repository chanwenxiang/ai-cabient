-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: staff_wallet_account (~几十行，每个补货员一行)
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = ALTER TABLE staff_wallet_account ADD COLUMN frozen_cents BIGINT NOT NULL DEFAULT 0;
-- NOTES: §12 决策 #2（2026-10-18 用户拍板「staff_wallet_account.frozen_cents 没用就删」）。
--   补货员钱包没有冻结/解冻业务（提现走「线下打款后运营标记出账」，不经冻结），
--   staff_wallet_account.frozen_cents 自 V359 建表以来恒为 0，StaffWalletAccountMapper.xml
--   亦未映射该列（MyBatis SELECT a.* 不读、insert 不写）⇒ 死列，删除。DROP COLUMN 只重写
--   表元数据、小表锁瞬时；无消费者（domain/service/openapi.ts 同批改）。
--   仅删账户侧死列：staff_wallet_ledger.frozen_after 是跨商户/线长/补货员三域台账的
--   通用「入账后冻结余额快照」列（商户/线长确有冻结流水），保留以维持三域台账 schema 对齐，
--   补货员侧由 StaffWalletService 恒写 0。

ALTER TABLE staff_wallet_account DROP COLUMN frozen_cents;
