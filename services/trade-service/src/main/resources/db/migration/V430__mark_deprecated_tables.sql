-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（仅 COMMENT ON，未改结构与数据）
-- NOTES: 给 11 张无活引用/已被替代的空表加【已废弃】标注，避免后续误用；不改结构/数据，幂等。
--        删除这些表不在本迁移内，须单独拍板 + 生产逐表 count + 备份 + 独立 DROP 迁移。
-- TABLES: 11 张既有表（只写 COMMENT ON，不建表、不改行数）
--
COMMENT ON TABLE data_discrepancy IS U&'\3010\5df2\5e9f\5f03\3011\6570\636e\5dee\5f02\660e\7ec6\ff08\4e00\81f4\6027\5de1\68c0\53d1\73b0\ff09\ff1b\5df2\7531 data_consistency_record \53d6\4ee3\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE data_quality_check IS U&'\3010\5df2\5e9f\5f03\3011\6570\636e\8d28\91cf\68c0\67e5\89c4\5219/\7ed3\679c\ff1b\65e0\4ee3\7801\5f15\7528\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE idempotency_key IS U&'\3010\5df2\5e9f\5f03\3011\5e42\7b49\6027\63a7\5236\8868\ff1b\5145\503c\5e42\7b49\5df2\6539\7528 recharge_order/payment_operation.idempotency_key + Redis \9501\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE payscore_contract IS U&'\3010\5df2\5e9f\5f03\3011\5fae\4fe1\652f\4ed8\5206\7b7e\7ea6\8bb0\5f55\ff1b\5408\540c\53f7\5b9e\9645\5b58 user_info.payscore_contract_id\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE promotion_device IS U&'\3010\5df2\5e9f\5f03\3011\6d3b\52a8\8bbe\5907\8303\56f4\ff1b\8bf7\7528 promotion_activity.device_scope\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE rate_limit_record IS U&'\3010\5df2\5e9f\5f03\3011\9650\6d41\547d\4e2d\8bb0\5f55\ff1b\65e0\4ee3\7801\5f15\7528\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE revenue_share_detail IS U&'\3010\5df2\5e9f\5f03\3011\5206\6da6\660e\7ec6\ff1b\5206\8d26\660e\7ec6\5df2\7528 order_revenue_split\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE revenue_share_rule IS U&'\3010\5df2\5e9f\5f03\3011\5206\6da6\89c4\5219\5b9a\4e49\ff1b\5206\8d26\5df2\8d70 order_revenue_split\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE sys_oper_log IS U&'\3010\5df2\5e9f\5f03\3011\7cfb\7edf\64cd\4f5c\65e5\5fd7\ff08\82e5\4f9d\98ce\683c\ff09\ff1b\771f\5b9e\5ba1\8ba1\89c1 admin_audit_log\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE user_login_log IS U&'\3010\5df2\5e9f\5f03\3011\7528\6237\767b\5f55\65e5\5fd7\ff1b\65e0\5199\5165\65b9\ff0c\52ff\65b0\5199\5165\3002';
COMMENT ON TABLE wechat_binding IS U&'\3010\5df2\5e9f\5f03\3011\5fae\4fe1\767b\5f55\7ed1\5b9a\ff1bopenid \5b9e\9645\5b58 user_info.wx_openid\ff0c\52ff\65b0\5199\5165\3002';
