-- seed consumer self-refund policy keys (defaults also in SystemConfigService.ensureDefaults)
INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES
  ('refund.self.max_hours', '24', '消费者自助退款时限（下单后小时数）', NOW()),
  ('refund.self.max_cents', '5000', '消费者自助单笔退款上限（分），0=不限制', NOW()),
  ('refund.self.max_daily', '3', '消费者每日自助退款次数上限，0=不限制', NOW()),
  ('refund.self.partial_enabled', 'true', '是否允许消费者按行自助部分退', NOW())
ON CONFLICT (config_key) DO NOTHING;
