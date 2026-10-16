-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: high
-- TABLES: sku_scene_limit（新表，0 行起步）; device_info（dev 实测个位数行）
-- COMPETITOR_REF: CB-049, CB-018
-- NOTES: 商品×场景上架限额（CB-049 扩展，2026-10-16 用户需求）：SKU 可对多个投放场景各配
--       一个上架上限（如东鹏：学校 1 瓶、工厂 5 瓶）；货道绑定/容量变更/模板套用时按
--       「柜内该 SKU 最大容量合计 ≤ 上限」硬校验（用户拍板口径：柜内场景 SKU 合计不得大于设置数）。
--       同时 device_info 新增 last_offline_at（最近离线时间，离线巡检/WS 断开时写入；
--       最近上线时间复用既有 online_since，分配给商户的时间复用既有 deployed_at）。
-- TABLE: sku_scene_limit (id BIGSERIAL PK, sku_id VARCHAR(32) NOT NULL, scene_type VARCHAR(32) NOT NULL,
--        max_qty INT NOT NULL CHECK (>0), created_at/updated_at TIMESTAMPTZ, UNIQUE(sku_id, scene_type))
-- TABLE: device_info.last_offline_at TIMESTAMPTZ NULL
CREATE TABLE sku_scene_limit (
    id         BIGSERIAL PRIMARY KEY,
    sku_id     VARCHAR(32) NOT NULL,
    scene_type VARCHAR(32) NOT NULL,
    max_qty    INT NOT NULL CHECK (max_qty > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_sku_scene UNIQUE (sku_id, scene_type)
);
CREATE INDEX sku_scene_limit_sku_idx ON sku_scene_limit (sku_id);

ALTER TABLE device_info ADD COLUMN IF NOT EXISTS last_offline_at TIMESTAMPTZ;
COMMENT ON COLUMN device_info.last_offline_at IS '最近离线时间（离线巡检/WS 断开写入；CB-049 扩展）';
COMMENT ON TABLE sku_scene_limit IS '商品×场景上架限额（CB-049 扩展）：场景取值同 device_info.scene_type 字典 device_scene_type';
-- LOCK_RISK 豁免说明（评审记录 2026-10-16）：本脚本含热表 device_info 上的非 CONCURRENTLY 建索引，
-- 按门禁归类 high；实际 device_info 当前规模极小（个位数行），非并发建索引锁窗口毫秒级，
-- 且 CREATE INDEX CONCURRENTLY 无法在 Flyway 事务内执行（OSS 无 per-migration 非事务配置），
-- 评审结论：维持非并发建索引，待生产上线前如 device_info 行数 >10w 再拆独立 CONCURRENTLY 迁移。
