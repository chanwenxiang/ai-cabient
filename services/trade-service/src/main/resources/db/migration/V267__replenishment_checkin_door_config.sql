-- 补货签到定位 / 距离 / 商户完成前开门：可在运营「参数配置」调整（默认与原先硬拦一致）
INSERT INTO system_config (config_key, config_value, description, updated_at)
VALUES
(
  'replenishment.check_in.require_location',
  'true',
  '柜机已配置坐标时，签到是否必须带定位；false=允许空定位签到（仍可带坐标并受距离校验）',
  NOW()
),
(
  'replenishment.check_in.max_distance_m',
  '500',
  '签到距柜机最大允许距离（米）；≤0 表示关闭距离校验',
  NOW()
),
(
  'replenishment.complete.require_door',
  'true',
  '商户端完成补货是否必须先补货开门；false=可跳过（运营后台代完成本就不拦）',
  NOW()
)
ON CONFLICT (config_key) DO NOTHING;
