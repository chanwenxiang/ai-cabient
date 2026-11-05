-- MIGRATION_KIND: backfill
-- 批 3b 第十一刀：SLOT_DISCREPANCY（货道账实差异）告警面退场后，把它的两行字典撤下可选项。
--
-- 为什么退场：这条告警的唯一算式是「货道账面 vs 货道实盘（last_physical_qty）」——两个入参都是
--   货道概念。批 3b 第四刀断掉 device_slot 生产者后，生产全新库恒 0 行（第十刀 A 段已实测：
--   seed_env=none 跑 V1→V443 全链后 device_slot 计数为 0），⇒ 差异恒空，但界面仍挂着
--   「货道差异」订阅项、「货道账实差异」筛选项与运营 KPI，属空转 UI。
--   同批删除：merchant `GET /slot-discrepancies` 端点、workbench `slotDiscrepancies` 字段、
--   AdminStatsDto.slotDiscrepancyCount、MerchantNotifyService 两处分支、DeviceSlotService
--   listDiscrepancyAlerts/countDiscrepancies/buildDiscrepancy* 机件、SlotDiscrepancyAlertDto。
--
-- 🔴 两行都来自哪儿（先实测再决定动谁）：dev 库 `sys_dict_data` 里 dict_value='SLOT_DISCREPANCY'
--   实测 2 行 = `merchant_alert_type`（V426 插）+ `exception_type`（**只有 SysDictBootstrap 插**，
--   迁移目录里搜不到）。⇒ 光靠本迁移不够：全新库的 exception_type 那行是启动期 bootstrap 补的，
--   已同批从 SysDictBootstrap 的 exception_type / merchant_alert_type 目录里摘掉，否则「又长出来」。
-- 🔴 为什么是 INACTIVE 而不是删行：dev 全库 853 个文本列扫描，除这两行字典外**没有任何业务行**
--   携带该值（待办/告警/订阅偏好/推送日志均 0），本机确实可删；但 UAT/别的环境可能有历史行，
--   且「字典行不删、只置非 ACTIVE」是本仓退役惯例（V386/V421/V427/V444）。
--   标签层（packages/shared-dict 的 baseline 两处）刻意**保留**——它是历史行的译名来源。
-- 幂等：带 `AND status <> 'INACTIVE'`。
-- ROLLBACK: UPDATE sys_dict_data SET status = 'ACTIVE'
--             WHERE dict_value = 'SLOT_DISCREPANCY'
--               AND dict_type IN ('exception_type', 'merchant_alert_type');
-- LOCK_RISK: low
-- TABLES: sys_dict_data（≤2 行）
-- NOTES: 纯 DML 改状态；商户端订阅页少一个勾选项，历史 merchant_subscribe_pref 无该值行（实测 0）
UPDATE sys_dict_data
   SET status = 'INACTIVE'
 WHERE dict_type IN ('exception_type', 'merchant_alert_type')
   AND dict_value = 'SLOT_DISCREPANCY'
   AND status <> 'INACTIVE';
