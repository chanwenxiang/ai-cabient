-- MIGRATION_KIND: backfill
-- 🔴 生产守卫迁移：清除「版本迁移内嵌演示种子」（C-2026-10-13 结构性缺陷的补偿）。
--
-- 背景：V2/V4/V5/V8/V9/V11/V25/V26/V30/V34/V43/V44/V46/V54/V56/V58/V61/V62/V74/V86/V87/
--      V99/V139/V222/V302 等历史迁移在 seed_env 守卫（V202 引入）出现**之前**，无条件内嵌了
--      演示数据 ⇒ 版本迁移全环境必跑，任何全新环境（含未来重建的生产）都会再次长出演示数据。
--      2026-10-13 生产实证：商品管理/选品诊断出现 8 个演示 SKU 全家族（用户可见）。
--      铁律 50 禁改写已应用迁移（checksum）⇒ 只能追加本补偿迁移。
--
-- 机制：仅当 seed_env='none'（即 prod，见 application-prod.yml placeholders.seed_env）才执行；
--      local/dev/uat 的演示数据是合法测试夹具（e2e / 模拟器依赖），一律不动。
--      与 V202 同款占位符判定，不引入新配置项。
--
-- 锚点（演示实体清单，与 2026-10-13 生产人工清理口径一致）：
--   用户：100000001-100000011（V4/V8 演示运营号段）、10001/13800138000（V2 测试用户）、
--        10002（V74/V222 支付分演示用户）
--   SKU：SKU-DEMO/SODA/WATER/SNACK/MILK/NOODLE/APPLE-001（V2/V11/V25/V54/V56）
--   设备：CAB-001（V2 测试柜）、CAB-OTHER（V44 演示二号柜）
--   商户：MCH-DEFAULT（V15 默认直营商户，V139 演示余额）、MCH-OTHER（V44 演示商户B）
--   线长：13800138001（V133 演示线长，播种过演示钱包流水）
--   仓储：WH-DEMO-001（V26 演示中心仓）、SUP-DEMO-001（V34 演示供应商）
--   其它：cdn.example.com OTA 演示包（V9）、demo-rec-* 识别结果（V222）
--
-- 顺序：先子表后锚点；结尾动态断言（扫 pg_constraint 全部指向锚点表的外键），
--      仍有残留引用 ⇒ RAISE EXCEPTION（fail-loud，不静默残留，宁可迁移失败也不带病上线）。
-- 注意：payment_reconciliation / member_level_rule / points_redeem_item / coupon_definition
--      的演示行无业务锚点可循，但本迁移生效时（上线前/种子刚跑完的全新环境）这些表**只有**
--      演示行，按整表清空处理；此后产生的新行不受影响（版本迁移只执行一次）。

DO $demo_purge$
DECLARE
    n         bigint;
    child_tbl text;
    col_name  text;
BEGIN
    -- 仅在种子被禁用的环境（= prod）执行；local/dev/uat 演示数据是合法测试夹具
    IF '${seed_env}' <> 'none' THEN
        RAISE NOTICE 'V344 demo purge skipped: seed_env=% keeps demo fixtures', '${seed_env}';
        RETURN;
    END IF;

    CREATE TEMP TABLE _demo_user (user_id bigint PRIMARY KEY) ON COMMIT DROP;
    INSERT INTO _demo_user SELECT gs FROM generate_series(100000001, 100000011) gs;
    INSERT INTO _demo_user VALUES (10001), (10002), (13800138000);

    -- 迁移自建的纯演示数据备份表（V157/V222 时代备份，2026-10-13 生产实证仍在）：
    -- 内容 100% 是演示 SKU 快照，无保留价值，直接删除（「留待仓内决定」的裁决：删）
    DROP TABLE IF EXISTS sku_catalog_vision_cols_bak_20261011;
    DROP TABLE IF EXISTS sku_vision_mapping_bak_20261011;

    -- ========== 1. 广告/素材（V222：上传者=演示运营号段，或演示屏保投放） ==========
    DELETE FROM recognition_result
    WHERE task_id LIKE 'demo-rec-%';
    DELETE FROM ad_campaign_item
    WHERE campaign_id IN (SELECT campaign_id FROM ad_campaign
                          WHERE name = '演示屏保投放'
                             OR created_by IN (SELECT user_id FROM _demo_user));
    DELETE FROM ad_campaign
    WHERE name = '演示屏保投放'
       OR created_by IN (SELECT user_id FROM _demo_user);
    DELETE FROM media_asset
    WHERE title = '演示屏保视频'
       OR uploaded_by IN (SELECT user_id FROM _demo_user);

    -- ========== 2. 用户域子表（V2/V74/V99/V222 + 运营号段挂靠） ==========
    DELETE FROM user_blacklist      WHERE user_id IN (SELECT user_id FROM _demo_user);
    DELETE FROM user_coupon         WHERE user_id IN (SELECT user_id FROM _demo_user);
    DELETE FROM member_points_log   WHERE member_id IN (SELECT member_id FROM member
                                                        WHERE user_id IN (SELECT user_id FROM _demo_user));
    DELETE FROM member              WHERE user_id IN (SELECT user_id FROM _demo_user);
    DELETE FROM phone_verify_log    WHERE user_id IN (SELECT user_id FROM _demo_user);
    DELETE FROM notification_log    WHERE user_id IN (SELECT user_id FROM _demo_user)
                                       OR merchant_id IN ('MCH-DEFAULT', 'MCH-OTHER');
    DELETE FROM balance_refund_request
    WHERE request_no = 'BR-DEMO-001'
       OR user_id IN (SELECT user_id FROM _demo_user);
    DELETE FROM user_account        WHERE user_id IN (SELECT user_id FROM _demo_user);
    DELETE FROM ops_user_role       WHERE user_id IN (SELECT user_id FROM _demo_user);
    DELETE FROM ops_user_merchant   WHERE user_id IN (SELECT user_id FROM _demo_user)
                                       OR merchant_id IN ('MCH-DEFAULT', 'MCH-OTHER');
    DELETE FROM ops_user_department WHERE user_id IN (SELECT user_id FROM _demo_user);

    -- ========== 3. 商户/线长域子表（V15/V44/V133/V139/V222/V307） ==========
    DELETE FROM merchant_payment_onboarding      WHERE merchant_id IN ('MCH-DEFAULT', 'MCH-OTHER');
    DELETE FROM merchant_wallet_ledger           WHERE merchant_id IN ('MCH-DEFAULT', 'MCH-OTHER');
    DELETE FROM merchant_wallet_account          WHERE merchant_id IN ('MCH-DEFAULT', 'MCH-OTHER');
    -- payout_account 以 owner_type+owner_id 弱引用主体（无外键），按演示商户 id 清理
    DELETE FROM payout_account
    WHERE owner_type = 'MERCHANT' AND owner_id IN ('MCH-DEFAULT', 'MCH-OTHER');
    -- 行表对请求头 ON DELETE CASCADE，删头即可；显式列 merchant_id 便于阅读
    DELETE FROM merchant_replenishment_request
    WHERE merchant_id IN ('MCH-DEFAULT', 'MCH-OTHER')
       OR device_id IN ('CAB-001', 'CAB-OTHER');
    -- 线长域：V133 给 13800138001 演示线长播种过钱包流水；生产实证 2026-10-13 该域为空，
    -- 这里按手机号锚点 + 演示柜/商户兜底，防全新环境长出演示余额
    DELETE FROM line_wallet_ledger    WHERE manager_id IN (SELECT manager_id FROM line_manager WHERE phone = '13800138001');
    DELETE FROM line_commission_daily WHERE manager_id IN (SELECT manager_id FROM line_manager WHERE phone = '13800138001')
                                         OR device_id IN ('CAB-001', 'CAB-OTHER');
    DELETE FROM line_withdraw_request WHERE manager_id IN (SELECT manager_id FROM line_manager WHERE phone = '13800138001');
    DELETE FROM line_device           WHERE manager_id IN (SELECT manager_id FROM line_manager WHERE phone = '13800138001')
                                         OR device_id IN ('CAB-001', 'CAB-OTHER');
    DELETE FROM line_manager          WHERE phone = '13800138001';

    -- ========== 4. 设备/仓储/SKU 域子表（V2/V25/V26/V30/V54/V56/V62/V160/V222） ==========
    DELETE FROM device_slot                WHERE device_id IN ('CAB-001', 'CAB-OTHER');
    DELETE FROM device_sku_inventory       WHERE device_id IN ('CAB-001', 'CAB-OTHER')
                                              OR sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001', 'SKU-WATER-001',
                                                            'SKU-SNACK-001', 'SKU-MILK-001', 'SKU-NOODLE-001',
                                                            'SKU-APPLE-001');
    DELETE FROM warehouse_inventory        WHERE warehouse_id = 'WH-DEMO-001'
                                              OR sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001', 'SKU-WATER-001',
                                                            'SKU-SNACK-001', 'SKU-MILK-001', 'SKU-NOODLE-001',
                                                            'SKU-APPLE-001');
    DELETE FROM warehouse_stocktake_line
    WHERE sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001', 'SKU-WATER-001', 'SKU-SNACK-001',
                     'SKU-MILK-001', 'SKU-NOODLE-001', 'SKU-APPLE-001')
       OR stocktake_id IN (SELECT stocktake_id FROM warehouse_stocktake
                           WHERE warehouse_id = 'WH-DEMO-001');
    DELETE FROM warehouse_stocktake        WHERE warehouse_id = 'WH-DEMO-001';
    DELETE FROM purchase_order_line
    WHERE purchase_order_id IN (SELECT purchase_order_id FROM purchase_order
                                WHERE supplier_id = 'SUP-DEMO-001'
                                   OR warehouse_id = 'WH-DEMO-001'
                                   OR ref_no = 'PO-DEMO-001')
       OR sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001', 'SKU-WATER-001', 'SKU-SNACK-001',
                     'SKU-MILK-001', 'SKU-NOODLE-001', 'SKU-APPLE-001');
    DELETE FROM purchase_order
    WHERE supplier_id = 'SUP-DEMO-001'
       OR warehouse_id = 'WH-DEMO-001'
       OR ref_no = 'PO-DEMO-001';
    DELETE FROM sku_delist_review          WHERE sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001', 'SKU-WATER-001',
                                                           'SKU-SNACK-001', 'SKU-MILK-001', 'SKU-NOODLE-001',
                                                           'SKU-APPLE-001');
    -- sku_vision_mapping 已随 V340（云端识别下线）删除，无需清理
    DELETE FROM aliyun_category_mapping    WHERE sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001', 'SKU-WATER-001',
                                                           'SKU-SNACK-001', 'SKU-MILK-001', 'SKU-NOODLE-001',
                                                           'SKU-APPLE-001');
    DELETE FROM ota_release                WHERE download_url LIKE 'https://cdn.example.com/%'
                                              OR checksum_sha256 = 'demo-sha256';

    -- ========== 5. 无业务锚点的演示目录表（V99/V222；生效时点这些表只有演示行） ==========
    DELETE FROM points_redeem_item;   -- V99 演示兑换目录（3 条，引用下方演示券）
    DELETE FROM coupon_definition     WHERE coupon_name IN ('新人立减 ¥2', '满减券 ¥5', '满减券 ¥10');
    DELETE FROM member_level_rule;    -- V99 演示等级规则（NORMAL/SILVER/...，上线前整表清空）
    DELETE FROM promotion_activity    WHERE activity_name IN ('夏日冰饮满减周', '新客开门礼', '积分兑好礼');
    DELETE FROM payment_reconciliation; -- V222 演示对账行（生效时点表内仅有演示行；对账行为派生数据可重建）

    -- ========== 6. 锚点行本体 ==========
    DELETE FROM user_info   WHERE user_id IN (SELECT user_id FROM _demo_user);
    DELETE FROM sku_catalog WHERE sku_id IN ('SKU-DEMO-001', 'SKU-SODA-001', 'SKU-WATER-001',
                                             'SKU-SNACK-001', 'SKU-MILK-001', 'SKU-NOODLE-001',
                                             'SKU-APPLE-001');
    DELETE FROM device_info WHERE device_id IN ('CAB-001', 'CAB-OTHER');
    DELETE FROM warehouse   WHERE warehouse_id = 'WH-DEMO-001';
    DELETE FROM supplier    WHERE supplier_id = 'SUP-DEMO-001';
    DELETE FROM merchant    WHERE merchant_id IN ('MCH-DEFAULT', 'MCH-OTHER');

    -- ========== 7. 弱引用断言（fail-loud） ==========
    -- 有外键的引用由 PG 强制（锚点 DELETE 当场报错，天然 fail-loud）；能**静默残留**的只有
    -- 「无外键的弱引用列」（实证：payout_account.owner_id，V307 刻意不做外键）。
    -- 这里全库扫描同名列 + 已知弱引用对：任何表仍存有演示锚点值 ⇒ 迁移失败，点名人工排查。
    CREATE TEMP TABLE _anchor_text (col_name text, val text) ON COMMIT DROP;
    INSERT INTO _anchor_text VALUES
        ('merchant_id', 'MCH-DEFAULT'), ('merchant_id', 'MCH-OTHER'),
        ('sku_id', 'SKU-DEMO-001'), ('sku_id', 'SKU-SODA-001'), ('sku_id', 'SKU-WATER-001'),
        ('sku_id', 'SKU-SNACK-001'), ('sku_id', 'SKU-MILK-001'), ('sku_id', 'SKU-NOODLE-001'),
        ('sku_id', 'SKU-APPLE-001'),
        ('device_id', 'CAB-001'), ('device_id', 'CAB-OTHER'),
        ('warehouse_id', 'WH-DEMO-001'), ('supplier_id', 'SUP-DEMO-001'),
        ('owner_id', 'MCH-DEFAULT'), ('owner_id', 'MCH-OTHER');

    FOR child_tbl, col_name IN
        SELECT c.table_name, c.column_name
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
        WHERE c.table_schema = 'public'
          AND (
            (c.column_name IN ('merchant_id', 'sku_id', 'device_id', 'warehouse_id', 'supplier_id', 'owner_id')
             AND c.data_type IN ('character varying', 'character', 'text'))
            OR
            (c.column_name IN ('user_id', 'created_by', 'operator_id', 'uploaded_by')
             AND c.data_type IN ('bigint', 'integer', 'smallint', 'numeric'))
          )
          -- 锚点表自身已在前一步清空并排除，避免把自己扫出来
          AND c.table_name NOT IN ('user_info', 'sku_catalog', 'device_info',
                                   'warehouse', 'supplier', 'merchant')
        ORDER BY 1, 2
    LOOP
        IF col_name IN ('user_id', 'created_by', 'operator_id', 'uploaded_by') THEN
            EXECUTE format('SELECT count(*) FROM %I t WHERE t.%I IN (SELECT user_id FROM _demo_user)',
                           child_tbl, col_name)
            INTO n;
        ELSE
            EXECUTE format('SELECT count(*) FROM %I t WHERE t.%I IN (SELECT val FROM _anchor_text WHERE col_name = %L)',
                           child_tbl, col_name, col_name)
            INTO n;
        END IF;
        IF n > 0 THEN
            RAISE EXCEPTION 'V344 生产守卫：表 %.% 仍有 % 行存有演示锚点值——拒绝带演示残留上线，请人工排查该表',
                child_tbl, col_name, n;
        END IF;
    END LOOP;

    RAISE NOTICE 'V344 demo purge done: demo seeds removed (seed_env=none)';
END
$demo_purge$;
