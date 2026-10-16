-- MIGRATION_KIND: backfill
-- DC-26（2026-10-16 用户拍板：当前全为测试环境、未上真机 ⇒ 试点柜内并没有在售「东方树叶茉莉花茶335ml」）。
--
-- 问题：V62 在**版本迁移**里无条件内嵌了一个真实在售商品种子（sku_catalog 'SKU-TEA-5839'
--       + aliyun_category_mapping category_id '5839'），而它**不在 V344 生产演示清理守卫的锚点清单**里
--       （实证：`grep -c TEA-5839 V344__production_demo_seed_guard.sql` = 0，V344 锚点只对上 V2/V11/V25/V54/V56 家族）
--       ⇒ 全新生产库从零重建后，它会作为**唯一内嵌在售商品**长出来，出现在商品管理/选品诊断里，
--          被当成一个真实 SKU（还带一条阿里云类目映射）。
-- 铁律 50 禁改写已应用迁移（checksum）⇒ 只能追加本补偿迁移。
--
-- 与 V344 的两点不同（刻意更保守）：
--   1) **不做「先删子表再删锚点」的批量清理**。本 SKU 只有一个 id，若它已被真单引用（订单行/库存/货道
--      绑定/采购行），删引用=删资金与履约痕迹。⇒ 除「目录/评审附属」两表（aliyun_category_mapping、
--      sku_delist_review，随本体同删）外，只要在任何 sku_id/assigned_sku_id 列发现引用，
--      当场 RAISE EXCEPTION 交人工判断，绝不静默级联。
--   2) 全新生产库（本次重建的真实场景）里除 V62 那两行外不可能有引用 ⇒ 正常路径就是「映射 + 本体」两行删掉。
--
-- 环境守卫：仅 '${seed_env}' = 'none'（prod，见 application-prod.yml placeholders.seed_env）执行；
--   local/dev/uat 保留该 SKU 作为测试夹具（与 V344 同口径，别在测试环境删东西制造假象）。

DO $tea5839_purge$
DECLARE
    rec       record;
    n         bigint;
    scanned   bigint := 0;
    anchor    text := 'SKU-TEA-5839';
BEGIN
    IF '${seed_env}' <> 'none' THEN
        RAISE NOTICE 'V375 skipped: seed_env=% keeps the V62 SKU fixture', '${seed_env}';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM sku_catalog WHERE sku_id = anchor) THEN
        RAISE NOTICE 'V375 nothing to do: % already absent from sku_catalog', anchor;
        RETURN;
    END IF;

    -- 引用普查：全库 BASE TABLE 上的 sku_id / assigned_sku_id 列（排除锚点表自身与 V344 已删的备份表）。
    -- 备份表 *_bak_20261011 里是演示 SKU 快照，V344 在生产已 DROP；万一仍在也不作为阻断项（只提示）。
    -- 「目录附属/派生记录」白名单随本体一起删（V344 对演示 SKU 同款口径：aliyun_category_mapping、
    --  sku_delist_review 都是围绕 SKU 的目录/评审数据，不是资金或履约痕迹）：
    --   · 实证：dev 库里 SKU-TEA-5839 就有一条 2026-10-07 由选品诊断生成的 sku_delist_review 行。
    FOR rec IN
        SELECT c.table_name, c.column_name
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
        WHERE c.table_schema = 'public'
          AND c.column_name IN ('sku_id', 'assigned_sku_id')
          AND c.table_name NOT IN ('sku_catalog', 'aliyun_category_mapping', 'sku_delist_review')
          AND c.table_name NOT LIKE '%_bak_%'
        ORDER BY 1, 2
    LOOP
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE %I = %L',
                       'public', rec.table_name, rec.column_name, anchor)
        INTO n;
        IF n > 0 THEN
            RAISE EXCEPTION
                'V375 生产守卫：%.% 仍有 % 行引用 SKU-TEA-5839（真实在售商品？）——拒绝级联删除资金/履约痕迹。确认在售 ⇒ 把本迁移改为跳过并留痕；确认不在售 ⇒ 先人工处置引用行再重跑',
                rec.table_name, rec.column_name, n;
        END IF;
        scanned := scanned + 1;
    END LOOP;

    DELETE FROM aliyun_category_mapping WHERE sku_id = anchor;
    DELETE FROM sku_delist_review WHERE sku_id = anchor;
    DELETE FROM sku_catalog WHERE sku_id = anchor;

    IF EXISTS (SELECT 1 FROM sku_catalog WHERE sku_id = anchor)
       OR EXISTS (SELECT 1 FROM aliyun_category_mapping WHERE sku_id = anchor)
       OR EXISTS (SELECT 1 FROM sku_delist_review WHERE sku_id = anchor) THEN
        RAISE EXCEPTION 'V375 零残留断言失败：锚点 % 未被清除', anchor;
    END IF;

    RAISE NOTICE 'V375 done: V62 内嵌商品种子已清除（seed_env=none，零引用普查列数=%）', scanned;
END
$tea5839_purge$;
