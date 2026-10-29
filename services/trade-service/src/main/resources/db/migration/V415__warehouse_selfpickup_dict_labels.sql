-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- COMPETITOR_REF: CB-074
-- LOCK_RISK: low
-- TABLES: sys_dict_type / sys_dict_data（各 <200 行的小字典表，按唯一键条件更新 12 行）
-- NOTES: 只改「分仓→柜」这条链路的标签与类型名，不改任何 dict_value（状态机与代码判据不动）。
--
-- 为什么要改标签（铁律 80 / CB-074，2026-10-29 用户拍板）：
--   补货员在异地、自有仓库，每天从自己仓库备货、带着商品去补货——「分仓→柜」这一段是
--   **自提自送**，不存在物流意义上的「发运/在途/签收」。但字典里这条链路的标签是
--   待发运/在途/部分签收/已签收，运营与商户看到的是「货在路上、等着签收」，
--   而 `warehouse_in_transit` 的实际语义是「已领出分仓、尚未确认上柜」= **领而未上的差额**
--   （CB-074 已把差额落成出库行 `remainder_qty`）。三端口径差别的很大一部分就是这套词。
--   机制层（表、状态机、超时告警）保持不变，只把**用户面的词**改成「领货 → 上柜」。
--
-- 🔴 每条都带 `AND dict_label = '<旧默认标签>'`：运营在「字典管理」里自定义过的标签**不被覆盖**。
--   没带这个条件就会把别人的定制当成脏数据洗掉；带上之后重复执行是 no-op（幂等）。
--   新库由 `SysDictBootstrap` 直接种出新标签，本迁移只负责已存在的库。
--   与 `packages/shared-dict/src/index.ts` 的内置 baseline 必须逐字一致（运行时 DB 标签优先于内置）。

-- ① 类型名
UPDATE sys_dict_type
SET dict_name = '上柜交接状态', updated_at = NOW()
WHERE dict_type = 'handover_status' AND dict_name = '交接状态';

UPDATE sys_dict_type
SET dict_name = '领货未结状态', updated_at = NOW()
WHERE dict_type = 'in_transit_status' AND dict_name = '在途状态';

-- ② 出库单状态：SHIPPED 的动作是「补货员把拣好的货从分仓带走并当场扣减分仓库存」
UPDATE sys_dict_data
SET dict_label = '已领出', updated_at = NOW()
WHERE dict_type = 'warehouse_outbound_status'
  AND dict_value = 'SHIPPED'
  AND dict_label = '已发运';

-- ③ 交接状态（柜机侧看板读的就是这套标签）
UPDATE sys_dict_data
SET dict_label = '未交接', updated_at = NOW()
WHERE dict_type = 'handover_status'
  AND dict_value = 'PENDING'
  AND dict_label = '待备货';

UPDATE sys_dict_data
SET dict_label = '已拣待领', updated_at = NOW()
WHERE dict_type = 'handover_status'
  AND dict_value = 'READY'
  AND dict_label = '待发运';

UPDATE sys_dict_data
SET dict_label = '已领未上柜', updated_at = NOW()
WHERE dict_type = 'handover_status'
  AND dict_value = 'IN_TRANSIT'
  AND dict_label = '在途';

UPDATE sys_dict_data
SET dict_label = '部分上柜', updated_at = NOW()
WHERE dict_type = 'handover_status'
  AND dict_value = 'PARTIAL'
  AND dict_label = '部分签收';

UPDATE sys_dict_data
SET dict_label = '已上柜', updated_at = NOW()
WHERE dict_type = 'handover_status'
  AND dict_value = 'RECEIVED'
  AND dict_label = '已签收';

-- ④ 领货未结层（warehouse_in_transit 的行状态；LOST/DAMAGED 不属于本条链路，不动）
UPDATE sys_dict_data
SET dict_label = '未结清', updated_at = NOW()
WHERE dict_type = 'in_transit_status'
  AND dict_value = 'IN_TRANSIT'
  AND dict_label = '在途';

UPDATE sys_dict_data
SET dict_label = '已结清', updated_at = NOW()
WHERE dict_type = 'in_transit_status'
  AND dict_value = 'RECEIVED'
  AND dict_label = '已签收';

-- ⑤ 库存流水类型：OUTBOUND_SHIP 是「扣分仓库存」那一笔，标签说清是谁领走的
UPDATE sys_dict_data
SET dict_label = '领货出库', updated_at = NOW()
WHERE dict_type = 'warehouse_movement_type'
  AND dict_value = 'OUTBOUND_SHIP'
  AND dict_label = '发运';

-- ⑥ 工作台待办类型：这条告警判的是「领了货却没在柜确认上柜」，不是「货在路上没人签收」
UPDATE sys_dict_data
SET dict_label = '未上柜超时', updated_at = NOW()
WHERE dict_type = 'ops_alert_type'
  AND dict_value = 'IN_TRANSIT_OVERDUE'
  AND dict_label = '签收超时';
