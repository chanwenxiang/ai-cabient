-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-022
-- MIGRATION_REVIEWED: yes（解锁 ACK 与物理拉门必须分开，未拉门不能卡成 10 分钟争议）
-- LOCK_RISK: low（加布尔列；存量活跃会话按已开门保护，避免部署瞬间误判）
-- TABLES: shopping_session
-- NOTES: 新会话默认 false；存量已离开 OPENING/CREATED 的会话按已物理开门保护。

ALTER TABLE shopping_session
    ADD COLUMN physical_open_confirmed boolean NOT NULL DEFAULT false;

UPDATE shopping_session
SET physical_open_confirmed = true
WHERE state IN ('SHOPPING', 'RECOGNIZING', 'WAITING_UPLOAD', 'SETTLING', 'COMPLETED', 'DISPUTED');

COMMENT ON COLUMN shopping_session.physical_open_confirmed IS
    'true=已收到设备物理拉门 OPEN；false=仅收到解锁指令 ACK 或尚未开门';
