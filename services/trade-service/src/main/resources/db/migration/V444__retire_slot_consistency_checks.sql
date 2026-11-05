-- MIGRATION_KIND: backfill
-- 批 3b 第十刀（A 段）：三个 SLOT_* 一致性检查停跑后，把它们的字典项从「可选筛选值」里撤下。
--
-- 为什么停跑：三个检查（checkSlotSkuMismatchConsistency / checkSlotCapacityConsistency /
--   checkSlotPhysicalConsistency）全部 `FROM device_slot` 取数。批 3b 第四刀断掉生产者后，
--   生产全新库 device_slot **恒 0 行**（本仓实测：seed_env=none 跑 V1→V443 全链后计数为 0），
--   ⇒ 检查恒「通过」却长期占着巡检项名额与前端筛选项，属空转 UI。
--
-- 🔴 为什么是 INACTIVE 而不是删行：dev 库 data_consistency_record 实测仍有 4 条 SLOT_* 历史行
--   （SLOT_PHYSICAL FAIL 1 / FIXED 2、SLOT_SKU_MISMATCH FAIL 1），后台 ConsistencyView 的类型标签
--   唯一来源就是这三行字典（V438 式「删字典行」的前提——业务表 0 行——在这儿不成立）。
--   INACTIVE 后：`packages/shared-dict` 的 dictOptions 只取 ACTIVE ⇒ 筛选下拉不再出现；
--   而 displayLabel→dictLabel 回落 baseline DICT 映射（baseline 不带状态）⇒ 历史行标签照读。
--   配套：SysDictBootstrap 的 consistency_check_type 目录已同批摘掉这三项，
--   否则全新库启动时会把它们当缺行**重新插成 ACTIVE**（= 又长出来）。
--   baseline 映射（shared-dict:745-747）**保留**——它是历史行的译名来源，删了才真变「未知类型」。
-- 幂等：带 `AND status <> 'INACTIVE'`。
-- ROLLBACK: UPDATE sys_dict_data SET status = 'ACTIVE'
--             WHERE dict_type = 'consistency_check_type'
--               AND dict_value IN ('SLOT_SKU_MISMATCH', 'SLOT_CAPACITY', 'SLOT_PHYSICAL');
-- LOCK_RISK: low
-- TABLES: sys_dict_data（≤3 行）
-- NOTES: 纯 DML 改状态；不动 data_consistency_record 历史行（FAIL 记录不再被复查，需人工结案）
UPDATE sys_dict_data
   SET status = 'INACTIVE'
 WHERE dict_type = 'consistency_check_type'
   AND dict_value IN ('SLOT_SKU_MISMATCH', 'SLOT_CAPACITY', 'SLOT_PHYSICAL')
   AND status <> 'INACTIVE';
