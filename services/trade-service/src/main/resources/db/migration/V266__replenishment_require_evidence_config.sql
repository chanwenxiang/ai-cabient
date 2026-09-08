-- 商户端完成补货任务：现场凭证是否必填（默认 true，可在运营「参数配置」关闭）
INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES (
  'replenishment.complete.require_evidence',
  'true',
  '商户端完成补货是否必须上传现场凭证照片；false=可跳过（仍可上传便于抽检）',
  NOW()
)
ON CONFLICT (config_key) DO NOTHING;
