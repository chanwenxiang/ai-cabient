-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: N/A(线路组织为内部管理结构，非业务决策；无竞品对照——老系统仅 routeNo 字符串无实体)
-- 线路组织结构（2026-10-14 拍板）：线路可增减、柜子逻辑分组、线长负责、线路下多个补货员、
-- 一台柜可多人补货（佣金按实际补货留痕记到当天补货人）、线长与补货员可为同一人（双角色并行）。
-- 老系统无线路实体（仅 routeNo 字符串），本表为新增能力。

CREATE TABLE line_route (
    route_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_name  VARCHAR(64) NOT NULL COMMENT '线路名称',
    manager_id  BIGINT NOT NULL COMMENT '线长（线路负责人，line_manager.manager_id）',
    remark      VARCHAR(200) NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED（线路可增减归档）',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) COMMENT='补货线路（逻辑分组，可增减）';

CREATE TABLE line_route_member (
    member_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id    BIGINT NOT NULL,
    user_id     BIGINT NOT NULL COMMENT '成员（user_info.userId；线长与补货员可为同一人=两行角色）',
    role        VARCHAR(16) NOT NULL COMMENT 'MANAGER=线长 / STAFF=补货员',
    commission_basis VARCHAR(16) NOT NULL DEFAULT 'GMV' COMMENT 'STAFF 提成基数：GMV/RESTOCK_QTY',
    commission_rate_bps INT NOT NULL DEFAULT 0 COMMENT 'STAFF GMV 模式：万分比',
    commission_per_unit_cents BIGINT NOT NULL DEFAULT 0 COMMENT 'STAFF RESTOCK_QTY 模式：每件佣金（分）',
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_route_user_role (route_id, user_id, role),
    KEY idx_user (user_id)
) COMMENT='线路成员（线长/补货员，同人可双角色）';

CREATE TABLE staff_commission_daily (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_user_id BIGINT NOT NULL COMMENT '补货员（user_info.userId）',
    route_id    BIGINT NOT NULL,
    biz_date    DATE NOT NULL,
    device_id   VARCHAR(32) NOT NULL,
    restock_qty INT NOT NULL DEFAULT 0 COMMENT '当日该柜实际补货件数（按补货留痕）',
    commission_cents BIGINT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_staff_date_device (staff_user_id, biz_date, device_id),
    KEY idx_staff_date (staff_user_id, biz_date)
) COMMENT='补货员日佣金台账（按实际补货留痕，线下结算汇总）';

-- 总线路负责人（全局一人，可随时改指）：system_config 键在 SystemConfigService seed，
-- 这里不写数据行（upsertIfAbsent 启动时自动补）。
