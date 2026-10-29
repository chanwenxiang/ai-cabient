-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: warehouse_movement（纯 INSERT，不碰 DDL；行数 = 仓×SKU×批次缺期初的组数，dev/UAT 实测 9 行）
-- NOTES: 只读 warehouse_inventory + 聚合 warehouse_movement 后插补行，无 ALTER/DROP、无回填扫描锁。
--        幂等由 NOT EXISTS(ref_type='OPENING_BACKFILL') 保证；Flyway 本身也只跑一次。
--
-- 2026-10-28 用户实测仓库「月结」页「应有」列全负（-8/-8/-6/-6/-8/-6），与本仓自带巡检
-- data_consistency_record 的 6 条 WAREHOUSE_LEDGER FAIL 逐格相等 ⇒ 不是月结公式算错，是仓账缺期初。
--
-- 根因（V26 的账本洞，非演示库专属）：
--   V26__warehouse_phase_b.sql:76 直接 INSERT warehouse_inventory 建了 6 个批次（80/60/100/40/30/50），
--   当时没有配套写 warehouse_movement。后来 DemoDataService.seedWarehouseLotsIfEmpty 学到要补记
--   MANUAL_INBOUND 期初流水（:557-566），但它的门是「lot 行不存在才建 + 才记流水」⇒ V26 那批 lot
--   早已存在，补记逻辑在**任何**跑过 V26 的库里都永远不触发。V26 是无条件 INSERT，
--   全新生产库同样带这个洞，所以必须在迁移侧一次性闭合，而不是只修本机。
--
-- 后果：warehouse_movement 全库只有 6 行 OUTBOUND_SHIP（合计 -42），账面 Σ流水 = -8，
--   而 warehouse_inventory 余额 72 ⇒ 月结「上期」取不到结转、「应有」= 0+0-8 = -8。
--   真实采购收货路径（WarehouseService.java:297 写 PURCHASE_RECEIVE）本身是对的，缺的是建账那笔。
--
-- 🔴 created_at 锚 warehouse.created_at 的取舍：期初建账行的落点决定它进「上期」还是进「本期采购入库」
--   （WarehouseMonthlyCloseMath.aggregate 以 createdAt < 账期起点 判期初）。选建仓时刻的语义是
--   「仓开立当天收货建账」，因此它自然落在该仓**第一个账期内**=采购入库，之后的账期自动变「上期」，
--   两个方向都对。刻意不用 CURRENT_DATE（会把建账量记进执行迁移这个月，凭空造一笔本月采购），
--   也不用 1970/固定远期（库存流水页会显示一条荒年日期，运营读不出含义）。
--   注：warehouse_inventory 只有 updated_at（可变、非入仓时间），无建账时间列可用，故取仓创建时刻。
--
-- delta_qty = quantity - Σ既有流水（不是照抄 quantity）：这样补完恒满足巡检公式
--   「期初 + Σ流水 = 余额」，且对已经被出过货的批次同样成立（V26 那 6 个批次即此情形）。

INSERT INTO warehouse_movement (warehouse_id, sku_id, batch_no, movement_type, delta_qty,
                               ref_type, ref_id, operator_id, created_at)
SELECT i.warehouse_id,
       i.sku_id,
       i.batch_no,
       'MANUAL_INBOUND',
       i.quantity - COALESCE(m.flow_sum, 0),
       'OPENING_BACKFILL',
       i.warehouse_id,
       NULL,
       w.created_at
FROM warehouse_inventory i
         JOIN warehouse w ON w.warehouse_id = i.warehouse_id
         LEFT JOIN (SELECT warehouse_id,
                           sku_id,
                           COALESCE(batch_no, '') AS batch_no,
                           SUM(delta_qty)        AS flow_sum
                    FROM warehouse_movement
                    GROUP BY warehouse_id, sku_id, COALESCE(batch_no, '')) m
                   ON m.warehouse_id = i.warehouse_id
                       AND m.sku_id = i.sku_id
                       AND m.batch_no = COALESCE(i.batch_no, '')
WHERE COALESCE(m.flow_sum, 0) <> i.quantity
  AND NOT EXISTS (SELECT 1
                  FROM warehouse_movement x
                  WHERE x.ref_type = 'OPENING_BACKFILL'
                    AND x.warehouse_id = i.warehouse_id
                    AND x.sku_id = i.sku_id
                    AND COALESCE(x.batch_no, '') = COALESCE(i.batch_no, ''));
