-- MIGRATION_KIND: backfill
-- DC-29（2026-10-16 用户拍板：**靠运营台手动开**）：把三条「无人开启就会自己动钱」的路径改成显式启用。
--
-- 三条路径的开箱即活事实（2026-10-16 在 dev 盘复核）：
--   1) V364 `refund-retry`：RefundRetryScheduler 是 @Scheduled(fixedRate=60_000)，
--      种子行 INSERT 未写 enabled ⇒ 继承 V152 的 `scheduled_task.enabled DEFAULT TRUE`
--      ⇒ 上线即每分钟向**真实渠道**重发退款。
--   2) V360 `deposit-monthly-refund`：同款 DEFAULT TRUE；开起来即每月向商户余额打钱。
--      （存量押金行 V360 已置 charge_mode='OFFLINE' 不参与，但新登记行默认 ONLINE 且带月退额即会跑。）
--   3) V365 `install_fee_clawback_enabled DEFAULT TRUE`：押金退完且未满合同年数时**倒扣商户余额**
--      （可挂负）。前置依赖第 2 条跑完 ⇒ 关掉 deposit-monthly-refund 后此支永不触发，
--      但仍按「资损开关默认关」口径一并收口。
--
-- 为什么改数据行就够（不用加代码开关）：ScheduledTaskService#tryBegin 在
--   `row != null && !TRUE.equals(row.getEnabled())` 时直接 recordSkip("task disabled") 返回 false，
--   调度入口当场退出；运营台 `PUT /api/v2/ops/admin/scheduled-tasks/{taskKey}/enabled`
--   （ScheduledTaskController:89 + ScheduledTaskView.vue）可一键启用 ⇒ 「手动开」有真实落点。
--
-- 全环境生效（不用 ${seed_env} 守卫）：dev/staging 的渠道凭据可能是**真的**（DC-29 §7 (iii) 的顾虑），
--   「测试环境就该自动跑」不成立；要在 dev 验证退款闭环，去运营台打开该任务即可，
--   跳过原因（task disabled）在运营台执行记录里可见，不会静默假绿。
--
-- 副作用与人工动作（上线必做，已写进 docs/DEPLOYMENT_CHECKLIST.md §3.1.2）：
--   refund-retry 不开 ⇒ 渠道退款失败/进程中断的 REFUND_INITIATED 痕迹无人闭环（30 分钟耗尽转人工也不会发生）；
--   deposit-monthly-refund 不开 ⇒ 押金按月退还（老系统口径）不执行，需运营手动退。
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: scheduled_task (~40 rows), device_rental_deposit (~0 rows in prod-at-rebuild)
-- NOTES: 仅 UPDATE 两个 task_key + 一列默认值/存量布尔，PG 元数据级 ALTER（SET DEFAULT 不重写表）；
--        两表均非热表（不在 check-migration-safety HOT_TABLES 名单），无长锁风险。

-- ① 两条资金类定时任务：默认停用，运营台按需开启
UPDATE scheduled_task
SET enabled = FALSE
WHERE task_key IN ('refund-retry', 'deposit-monthly-refund');

-- ② 撤机安装费倒扣：列默认改 FALSE（新登记行不再默认开）
ALTER TABLE device_rental_deposit
    ALTER COLUMN install_fee_clawback_enabled SET DEFAULT FALSE;

-- ③ 存量登记行同步关闭（TRUE→FALSE；NULL 不可能，列是 NOT NULL）
UPDATE device_rental_deposit
SET install_fee_clawback_enabled = FALSE
WHERE install_fee_clawback_enabled;

-- ④ 零残留断言：三条默认关必须真的关上（fail-loud，防「写了迁移却没生效」）
DO $defaults_off$
DECLARE
    n bigint;
    d text;
BEGIN
    SELECT count(*) INTO n FROM scheduled_task
    WHERE task_key IN ('refund-retry', 'deposit-monthly-refund') AND enabled;
    IF n > 0 THEN
        RAISE EXCEPTION 'V376 断言失败：仍有 % 条资金类定时任务处于 enabled=TRUE', n;
    END IF;

    SELECT count(*) INTO n FROM device_rental_deposit WHERE install_fee_clawback_enabled;
    IF n > 0 THEN
        RAISE EXCEPTION 'V376 断言失败：仍有 % 行押金登记 install_fee_clawback_enabled=TRUE', n;
    END IF;

    SELECT column_default::text INTO d FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name = 'device_rental_deposit'
      AND column_name = 'install_fee_clawback_enabled';
    IF d IS DISTINCT FROM 'false' THEN
        RAISE EXCEPTION 'V376 断言失败：install_fee_clawback_enabled 列默认值仍是 %（期望 false）', d;
    END IF;
END
$defaults_off$;
