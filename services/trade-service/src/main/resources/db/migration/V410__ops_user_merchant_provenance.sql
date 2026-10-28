-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: ops_user_merchant（运营账号↔商户可见范围绑定；行数=授权账号数×商户数，dev/UAT 均 <1k 行）
-- NOTES: PG11+ 的 ADD COLUMN ... NOT NULL DEFAULT NOW() 只改 catalog（既有行按 missing 值物化），
--        不重写表、不回填扫描；需 ACCESS EXCLUSIVE 但瞬时完成。created_by 可空无默认 ⇒ 纯元数据。
--        新列无 CHECK/索引依赖（V16 只有 PK + idx_ops_user_merchant_merchant），不必重建任何对象。
--        🔴 刻意不给 created_by 加外键：user_id 侧 V218 已 ON DELETE CASCADE（删号即解绑），而
--        created_by 是「当初谁授的权」的取证列——加 FK 无论 RESTRICT（删操作人被这行历史挡住）
--        还是 SET NULL（取证被抹掉）都破坏其用途。与 V220 那批工单/申请表的 created_by 外键语义
--        不同，属有意保留的不一致，后续会话不要顺手对齐。
-- G2-11 §7.17 ⑨（2026-10-24 UAT 实测，P3）：本表原本只有 (user_id, merchant_id) 两列，
-- 「谁在什么时候把这个账号授权到某商户」在库里没有任何答案；且授权审计只记 after
-- （OpsRbacService.doAssignMerchants 的 merchants=新列表），改完查不到改了什么。
-- 本迁移补「时间戳 + 操作人」两列；前后值由同批代码改动写进 admin_audit_log.detail
-- （形态抄本仓既有口径 LineManagerService.update 的 field=old→new，不新造格式）。
-- 🔴 语义边界：既有行由 DEFAULT NOW() 回填成**本迁移执行时刻** ⇒ created_at 只保证「不早于 V410」，
-- 不代表真实授权时刻；created_by 对既有行留 NULL（=迁移前既有 / 系统演示种子，见 DemoDataService）。
ALTER TABLE ops_user_merchant
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE ops_user_merchant
    ADD COLUMN IF NOT EXISTS created_by BIGINT;

COMMENT ON COLUMN ops_user_merchant.created_at IS
    '绑定建立时间（V410）；迁移前的既有行回填为本迁移执行时刻，不代表真实授权时间';
COMMENT ON COLUMN ops_user_merchant.created_by IS
    '建立该绑定的操作人 user_id（V410）；NULL=迁移前既有行或系统/演示种子；刻意不加外键，理由见 V410 头注释';
