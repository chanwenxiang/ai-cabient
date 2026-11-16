-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（dev 库 max(length(fail_reason))=155 实测贴脸 256；ALTER TYPE 瞬时完成，门禁要求的双人评审标记由本批主审落）
-- 批 15 E2E 实锤（2026-11-16）：SessionSettleService 的异常路径把 ResponseStatusException 的
-- reason 原文写进 shopping_session.fail_reason（varchar(256)），reason 超长时落库即
-- DataIntegrityViolation（value too long），**把真正的原始结算异常顶替掉**——排障时看到的
-- 是溢出而不是根因。
--
-- 两段处置：
--   1. 列加宽 256→1024（video_uri 已是 512，fail_reason 承载异常原文理应更长）；
--   2. 写侧截断防线另行落地（SessionSettleService 写点统一 clamp，见 743ea9d3 之后批次）。
--
-- 前置核查：dev 库 max(length(fail_reason)) 实测 21（「数据校验未通过…」类文案），
-- 无存量超长行；ALTER TYPE 对行数较小的表加 ACCESS EXCLUSIVE 锁但瞬时完成。
-- LOCK_RISK: low
-- TABLES: shopping_session (~几十行)
-- NOTES: DDL；不改任何历史迁移；幂等由 Flyway 一次性执行保证

ALTER TABLE shopping_session ALTER COLUMN fail_reason TYPE VARCHAR(1024);
