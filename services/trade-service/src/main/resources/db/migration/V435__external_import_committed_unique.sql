-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（CREATE UNIQUE INDEX 取共享锁并全表扫描；external_import_batch 是运营导入批次表，量级为「每渠道每月一批」）
-- TABLES: external_import_batch (2026-11-03 本机 dev 实测 0 行；其它库上线前按 docs/DEPLOYMENT_CHECKLIST.md §3.1.5 计数)
-- ROLLBACK: DROP INDEX IF EXISTS uk_external_import_batch_committed_period;（索引本身可逆，不动数据）
-- NOTES: MA-101。同渠道同账期出现第二份 COMMITTED 批次时，月结取数
--        （WarehouseMonthlyCloseService.flowBySku 对 (period_label, warehouse_id, op_type) 无条件 sum）
--        会把盘点/外调数量翻倍，而 COMMITTED 批次不可删 ⇒ 错账无补救入口。
--        服务层已在 commit 前拦兄弟批次（409），本索引兜的是**并发双提交**。
--        照 V431 的先例：先 count 重复组，>0 立即 RAISE EXCEPTION 阻断启动（fail-loud），
--        而不是让 PG 抛一个没有上下文的 constraint violation。
--        不要为了过迁移注释掉守卫，也不要删历史批次来凑数——删批次等于抹掉月结取数来源。

-- 防线：库里已有同渠道同账期的重复 COMMITTED 批次时拒绝建索引，先人工并账（保留一份、其余作废并留痕）。
DO $$
DECLARE
    dup_groups bigint;
BEGIN
    SELECT count(*)
      INTO dup_groups
      FROM (
        SELECT channel_code, period_label
          FROM external_import_batch
         WHERE status = 'COMMITTED'
         GROUP BY channel_code, period_label
        HAVING count(*) > 1
      ) d;

    IF dup_groups > 0 THEN
        RAISE EXCEPTION
            'MA-101 前置检查失败：external_import_batch 有 % 组「同渠道同账期多份 COMMITTED」批次。'
            '先按 docs/DEPLOYMENT_CHECKLIST.md §3.1.5 判据人工并账并留痕，再上线本迁移；'
            '勿注释守卫、勿直接删批次。',
            dup_groups;
    END IF;
END
$$;

-- 只约束 COMMITTED：同账期允许先传多份草稿/解析中批次，再挑一份提交。
CREATE UNIQUE INDEX IF NOT EXISTS uk_external_import_batch_committed_period
    ON external_import_batch (channel_code, period_label)
    WHERE status = 'COMMITTED';
