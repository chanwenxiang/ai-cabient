-- MIGRATION_KIND: backfill
-- MA-031（R9 商户端 2026-11-02）：待办类型标签「未知」的第二批补齐（V298 是第一批）。
--
-- 为什么会渲染成「未知」：商户端待办把四个来源合并（`merchant/exceptions?status=OPEN`、
--   `workbench.actionItems`、`expiry-alerts`、货道差异），行首标签走 `alertTypeLabel`
--   ⇒ `displayLabel('merchant_alert_type', code)`；字典缺该 key 时 shared-dict 对
--   「纯大写码」回退「未知」（`packages/shared-dict/src/index.ts:857/862`）。
--
-- 本轮在跑实测到的缺项（`docs/module-audit/rounds/R09-miniprograms.md` M 批 1）：
--   RESTOCK_TASK_TIMEOUT（补货任务超时）、OPEN_TIMEOUT（开门超时）——两行的类型标签都是「未知」。
--   另两个同族缺项 SLOT_DISCREPANCY（货道账实差异）、INVENTORY_MISMATCH（库存差异）
--   由同一批补齐，避免下次换柜型/换数据又冒出来。
--
-- 🔴 必须同时改 DOOR_OPEN_TOO_LONG 的标签：V298 把它写成「开门超时」，而语义上它是
--   「长时间未关门」（`exception_type` 字典里就是这么分的：DOOR_OPEN_TOO_LONG=长时间未关门、
--   OPEN_TIMEOUT=开门超时）。若直接加 OPEN_TIMEOUT='开门超时'，同一 dict_type 下会出现
--   两个同名 ACTIVE 项 ⇒ 撞 `DictLabelUniquenessIT`（棘轮要求重名集合与 KNOWN_DUPLICATES
--   完全相等，而该清单在 MA-018 后已清空）。故这里把旧行让位改名。
--
-- 守卫：插入按 (dict_type, dict_value) NOT EXISTS ⇒ 幂等；改名只命中仍等于旧标签的行 ⇒ 重放为 0 行。
-- LOCK_RISK: low（sys_dict_data 小表；1 条 UPDATE + 4 条条件 INSERT，均为字典元数据，不触业务表）
-- TABLES: sys_dict_data
-- NOTES: 与 `packages/shared-dict`（mp 侧静态基线）+ `SysDictBootstrap`（启动补种）同批改，
--   三处必须同值：mp 读基线、admin 读库、bootstrap 补新库 ⇒ 任一漏改就会三端不同名。

UPDATE sys_dict_data
   SET dict_label = '长时间未关门'
 WHERE dict_type = 'merchant_alert_type'
   AND dict_value = 'DOOR_OPEN_TOO_LONG'
   AND dict_label = '开门超时';

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status)
SELECT v.dict_type, v.dict_value, v.dict_label, v.sort_order, 'ACTIVE'
FROM (VALUES
    ('merchant_alert_type', 'DOOR_OPEN_TOO_LONG', '长时间未关门', 14),
    ('merchant_alert_type', 'OPEN_TIMEOUT', '开门超时', 15),
    ('merchant_alert_type', 'RESTOCK_TASK_TIMEOUT', '补货任务超时', 16),
    ('merchant_alert_type', 'SLOT_DISCREPANCY', '货道账实差异', 17),
    ('merchant_alert_type', 'INVENTORY_MISMATCH', '库存差异', 18)
) AS v(dict_type, dict_value, dict_label, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = v.dict_type AND d.dict_value = v.dict_value
);
