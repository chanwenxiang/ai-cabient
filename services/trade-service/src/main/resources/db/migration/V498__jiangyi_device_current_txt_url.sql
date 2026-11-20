-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: jiangyi_device（~十行）
-- NOTES: 设备开机回执 receiveCurrentTxtUrl 会报回「当前模型 classes.txt 地址」（相对路径
--   model/xxx.txt），此前只打日志、不落库。后果：后台「识别映射 → 复核（结算闸口）」的
--   classes 地址只能人工手抄，而厂商模型库 getModelFileByApi 对出厂预置模型返回空数组
--   ⇒ 运营在下拉里无候选、也无从得知地址，复核闸口实际走不通（2026-11-20 生产实查）。
--   本列只存设备自报的事实地址，不参与任何结算判据（闸口仍看 classes_version =
--   mapping_verified_version），故不影响 fail-closed 语义。
-- ROLLBACK: ALTER TABLE jiangyi_device DROP COLUMN IF EXISTS current_txt_url;

ALTER TABLE jiangyi_device ADD COLUMN IF NOT EXISTS current_txt_url VARCHAR(512);
COMMENT ON COLUMN jiangyi_device.current_txt_url IS
    '设备开机回执 receiveCurrentTxtUrl 报回的当前模型 classes.txt 地址（已按配置 base 拼为绝对 URL）；供后台复核默认填 classes 地址，不参与结算判据';
