-- V270: 去掉与 V69 完全重复的订单用户索引（保留更早的 idx_cabinet_order_user_created）
DROP INDEX IF EXISTS idx_order_user_created;
