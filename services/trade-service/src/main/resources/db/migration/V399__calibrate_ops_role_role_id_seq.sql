-- V399：校准 ops_role_role_id_seq —— 修「新建角色 500（duplicate key violates ops_role_pkey）」
--
-- 成因：V4 建 ops_role 时 role_id 走 serial/nextval（1..8 由序列发号），而 V140/V162 补
-- merchant_store_manager=10、merchant_replenisher=11 用的是**显式 role_id**，Postgres 不因
-- 显式主键推序列 ⇒ 全新空库跑完迁移后序列停在 8、MAX(role_id) 已是 11。
-- OpsRbacService.createRole 走 MyBatis-Plus insert（role_id 由库默认 nextval 发）：
--   第 1 次发 9 —— 恰好落在种子留的空洞里，侥幸成功；
--   第 2 次发 10、第 3 次发 11 —— 撞已存在的种子角色，DuplicateKeyException 被
--   GlobalExceptionHandler 兜成 500「系统繁忙」，运营在界面建角色必失败（非超管也建不了）。
-- UAT 2026-10-23 实测（库 aicabinet_fulltest_1023b，从零跑完 395 迁移）：
--   traceId 2b0f4d24f563dc718f23b987e9693533 / 4390e0eda55f0aec6d2c24be5e77c012 两次 500，
--   日志原文 `Key (role_id)=(11) already exists`，插入语句列里没有 role_id（纯靠库默认）。
-- 与 V398 的区别：这里没有静默覆盖风险（走的是库默认自增，不是 save()/updateById），
--   所以只需要校准序列；不改 Java。
--
-- ⚠️ 取 GREATEST(当前 last_value, MAX(role_id)) 而不是只取 MAX：两种写法都能修掉本次的撞主键
--   （setval 到 MAX 后 nextval=MAX+1，必然没人占），但只取 MAX 会把已经发出去过的号**重发**
--   （发过 100、行删到只剩 5 时拉回 5 ⇒ 下次建角色又拿到 6、7…）。号一旦在
--   approval_task.assignee_user_id、admin_audit_log.detail 这类**不受 FK 约束**的引用里出现过，
--   重发就等于串号。GREATEST 是保守写法。seq 名用 pg_get_serial_sequence 取，不写死字面量。

SELECT setval(
    pg_get_serial_sequence('ops_role', 'role_id'),
    GREATEST(
        (SELECT last_value FROM ops_role_role_id_seq),
        COALESCE((SELECT MAX(role_id) FROM ops_role), 1)
    ),
    TRUE
);
