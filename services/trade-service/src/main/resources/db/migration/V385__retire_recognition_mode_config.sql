-- MIGRATION_KIND: data
-- DC-27 批2（2026-10-17 用户拍板「现在删了」，源 docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md §3 批2）：
-- 重力/云端识别链的运行期代码已整体删除，配置面不留死旋钮。
--
-- 1) 删 `settlement.recognition_mode`（V199 建行，值域 VISION / VISION_GRAVITY）。
--    该键是「把已退役的重力融合链配活」的唯一旋钮：AU-134 已在写侧闸门只收 VISION，
--    批2 删掉 `usesGravityFusion()` 与其全部下游分支后，读侧无人再取它 ⇒ 留着就是
--    一个改了没人执行、却能误导运营的旋钮（本仓「no dead knobs」约定）。
-- 2) 把 `settlement.empty_auto_complete_no_gravity` 改名为 `settlement.empty_auto_complete`。
--    该键**仍在用**（识别为空时是否自动零结），只是「no_gravity」这个限定语随重力链退役
--    失去意义；UPDATE 保留运营已设的值，不做删除重建。
--
-- 历史留痕不清：`system_config_history` 里旧键名的记录（含 VISION_GRAVITY 那次误配）
-- 一律保留，作为「结算绿必须由将邑端侧链挣得」这条铁律的事故证据。
-- 无外键、无热表：system_config 以 config_key 为主键的单行 UPDATE/DELETE，行数量级为百。
-- LOCK_RISK: low
-- TABLES: system_config (~200 rows)
-- NOTES: 纯 DML（DELETE 1 键 + UPDATE 1 键名），不触发热表；执行后应用重启由 ensureDefaults 补种新键

DELETE FROM system_config
 WHERE config_key = 'settlement.recognition_mode';

UPDATE system_config
   SET config_key = 'settlement.empty_auto_complete',
       description = '识别为空是否自动零结（未取货/拿了又放回）；默认 false 进争议'
 WHERE config_key = 'settlement.empty_auto_complete_no_gravity';
