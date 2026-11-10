-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-066（提现状态机域：列注释与真实状态词汇对齐，R6-D-新6 注释漂移收口）
-- V139 的 status 列 COMMENT 缺后加的 CANCELLED（打款失败取消态，MerchantWithdrawService:453 /
-- LineWithdrawService:255 写入）。COMMENT 是元数据，无行为影响；已应用迁移禁重写（铁律 50）⇒ 新迁移覆盖。
-- LOCK_RISK: none（纯 COMMENT 语句，不锁业务数据）
-- TABLES: merchant_withdraw_request, line_withdraw_request

COMMENT ON COLUMN merchant_withdraw_request.status IS
    'PENDING_REVIEW待审核/APPROVED已通过/PAYING打款中/PAID已打款/REJECTED已驳回/FAILED失败/CANCELLED已取消';
COMMENT ON COLUMN line_withdraw_request.status IS
    'PENDING_REVIEW待审核/APPROVED已通过/PAYING打款中/PAID已打款/REJECTED已驳回/FAILED失败/CANCELLED已取消';
