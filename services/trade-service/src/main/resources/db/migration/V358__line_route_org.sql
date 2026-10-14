-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: N/A(线路组织为内部管理结构，非业务决策；无竞品对照——老系统仅 routeNo 字符串无实体)
-- 线路组织结构（2026-10-14 拍板）：线路可增减、柜子逻辑分组、线长负责、线路下多个补货员、
-- 一台柜可多人补货（佣金按实际补货留痕记到当天补货人）、线长与补货员可为同一人（双角色并行）。
-- 老系统无线路实体（仅 routeNo 字符串），本表为新增能力。
-- PostgreSQL 方言（2026-10-14 修正：初版误写 MySQL 语法，未在任何库应用过，原位改写）。

CREATE TABLE line_route (
    route_id    BIGSERIAL PRIMARY KEY,
    route_name  VARCHAR(64) NOT NULL, -- 线路名称
    manager_id  BIGINT NOT NULL,      -- 线长（线路负责人，line_manager.manager_id）
    remark      VARCHAR(200) NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/ARCHIVED（线路可增减归档）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE line_route_member (
    member_id   BIGSERIAL PRIMARY KEY,
    route_id    BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,      -- 成员（user_info.userId；线长与补货员可为同一人=两行角色）
    role        VARCHAR(16) NOT NULL, -- MANAGER=线长 / STAFF=补货员
    commission_basis VARCHAR(16) NOT NULL DEFAULT 'GMV', -- STAFF 提成基数：GMV/RESTOCK_QTY
    commission_rate_bps INT NOT NULL DEFAULT 0,             -- STAFF GMV 模式：万分比
    commission_per_unit_cents BIGINT NOT NULL DEFAULT 0,    -- STAFF RESTOCK_QTY 模式：每件佣金（分）
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_line_route_user_role
    ON line_route_member (route_id, user_id, role);
CREATE INDEX IF NOT EXISTS idx_line_route_member_user
    ON line_route_member (user_id);

CREATE TABLE staff_commission_daily (
    id               BIGSERIAL PRIMARY KEY,
    staff_user_id    BIGINT NOT NULL, -- 补货员（user_info.userId）
    route_id         BIGINT NOT NULL,
    biz_date         DATE NOT NULL,
    device_id        VARCHAR(32) NOT NULL,
    restock_qty      INT NOT NULL DEFAULT 0,     -- 当日该柜实际补货件数（按补货留痕）
    commission_cents BIGINT NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_staff_commission_date_device
    ON staff_commission_daily (staff_user_id, biz_date, device_id);
CREATE INDEX IF NOT EXISTS idx_staff_commission_date
    ON staff_commission_daily (staff_user_id, biz_date);
