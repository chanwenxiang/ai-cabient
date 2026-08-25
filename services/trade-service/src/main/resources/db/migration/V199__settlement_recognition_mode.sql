-- 全局结算识别方式：VISION=纯视觉；VISION_GRAVITY=视觉+重力融合
INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES (
  'settlement.recognition_mode',
  'VISION',
  '结算识别方式: VISION=纯视觉(忽略重力), VISION_GRAVITY=视觉+重力融合',
  NOW()
)
ON CONFLICT (config_key) DO NOTHING;
